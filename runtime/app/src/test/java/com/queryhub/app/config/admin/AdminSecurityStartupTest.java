package com.queryhub.app.config.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.queryhub.app.QueryHubApplication;
import com.queryhub.app.support.TestAdminToken;
import com.queryhub.app.support.TestComponentScanExclusion;
import com.queryhub.app.support.WithoutMetadataDatabase;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.ValveBase;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;

/**
 * P1-03 AC6, AC7, AC8 (cycle 2 의 R1, R4, cycle 3 의 C2-R1, C2-R2, C2-R5 포함): 관리자 보안 설정이 잘못되면 웹 서버가
 * 포트를 열기 전에 기동이 멈추고, 그래도 관리 체인이 답하지 않으면 기동 직후의 자기 점검이 런타임을 멈춘다. 실제 QueryHubApplication 을 Metadata
 * DB 없이 루프백의 포트 0 으로 띄운다.
 */
class AdminSecurityStartupTest {

  private static final List<String> PROFILES = List.of("desktop", "server");

  /** 자기 점검이 확인하는 경로 전체: 체인이 401/403 으로 답해야 하는 세 경로와 열린 health 세 경로. */
  private static final List<String> ALL_PROBED_PATHS =
      Stream.concat(
              AdminChainSelfProbe.PROBE_PATHS.stream(),
              AdminChainSelfProbe.OPEN_HEALTH_PATHS.stream())
          .toList();

  @ParameterizedTest
  @ValueSource(strings = {"desktop", "server"})
  void startupWithoutATokenFailsBeforeThePortOpens(String profile) {
    StartupAttempt attempt = start(profile, false, false);

    assertRefused(attempt, "QUERYHUB_ADMIN_TOKEN");
    assertThat(attempt.message()).contains("QUERYHUB_ADMIN_TOKEN_FILE");
  }

  @Test
  void startupWithoutATokenFailsAlsoWithLazyInitialization() {
    assertRefused(start("desktop", true, false), "QUERYHUB_ADMIN_TOKEN");
  }

  @Test
  void tokenOnTheCommandLineIsRefused() {
    StartupAttempt attempt =
        start("desktop", false, false, "--queryhub.admin.token=" + TestAdminToken.VALUE);

    assertRefused(attempt, "queryhub.admin.token");
    assertThat(attempt.message()).contains("명령행 인자");
  }

  @Test
  void tokenInSpringApplicationJsonOnTheCommandLineIsRefused() {
    StartupAttempt attempt =
        start(
            "server",
            false,
            false,
            "--spring.application.json={\"queryhub\":{\"admin\":{\"token\":\""
                + TestAdminToken.VALUE
                + "\"}}}");

    assertRefused(attempt, "queryhub.admin.token");
    assertThat(attempt.message()).contains("spring.application.json");
  }

  @Test
  void tokenAsASystemPropertyIsRefused() {
    String key = "queryhub.admin.token";
    System.setProperty(key, TestAdminToken.VALUE);
    try {
      StartupAttempt attempt = start("desktop", false, false);
      assertRefused(attempt, key);
      assertThat(attempt.message()).contains("JVM 시스템 속성");
    } finally {
      System.clearProperty(key);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"native", "framework", "NATIVE"})
  void forwardHeadersWithoutAllowRemoteAreRefused(String strategy) {
    StartupAttempt attempt =
        start("desktop", false, true, "--server.forward-headers-strategy=" + strategy);

    assertRefused(attempt, "server.forward-headers-strategy");
    assertThat(attempt.message()).contains("queryhub.admin.allow-remote");
  }

  @Test
  void forwardHeadersAreAllowedWhenRemoteIsAllowed() {
    StartupAttempt attempt =
        start("server", false, true, "--server.forward-headers-strategy=framework");

    assertThat(attempt.failure()).as("allow-remote=true(server)이면 forward 헤더 전략을 막지 않는다").isNull();
  }

  /** R1: remoteip 속성 하나만 있어도 Boot 가 RemoteIpValve 를 넣는다. 값은 메시지에 옮기지 않는다. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "remote-ip-header=x-qh-probe-ip",
        "protocol-header=x-qh-probe-proto",
        "internal-proxies=qh-probe-proxies",
        "trusted-proxies=qh-probe-trusted"
      })
  void remoteIpPropertiesWithoutAllowRemoteAreRefused(String assignment) {
    String name = assignment.substring(0, assignment.indexOf('='));
    String value = assignment.substring(assignment.indexOf('=') + 1);
    StartupAttempt attempt =
        start("desktop", false, true, "--server.tomcat.remoteip." + assignment);

    assertRefused(attempt, "server.tomcat.remoteip." + name);
    assertThat(attempt.message()).contains("queryhub.admin.allow-remote").doesNotContain(value);
  }

  @Test
  void remoteIpPropertiesAreRefusedAlsoOnTheServerProfileWithoutAllowRemote() {
    StartupAttempt attempt =
        start(
            "server",
            false,
            true,
            "--queryhub.admin.allow-remote=false",
            "--server.tomcat.remoteip.remote-ip-header=x-qh-probe-ip");

    assertRefused(attempt, "server.tomcat.remoteip.remote-ip-header");
  }

  @Test
  void remoteIpPropertiesAreAllowedWhenRemoteIsAllowed() {
    StartupAttempt attempt =
        start("server", false, true, "--server.tomcat.remoteip.remote-ip-header=x-forwarded-for");

    assertThat(attempt.failure())
        .as("allow-remote=true(server)이면 remoteip 속성과 RemoteIpValve 를 막지 않는다")
        .isNull();
  }

  /** R1 이중 안전장치: 속성 검사를 지나도 만들어진 파이프라인에 허용 목록 밖의 valve 가 있으면 포트를 열기 전에 멈춘다. */
  @ParameterizedTest
  @EnumSource(PipelineLevel.class)
  void remoteAddressValveInTheTomcatPipelineIsRefused(PipelineLevel level) {
    StartupAttempt attempt = start("desktop", false, true, level::register);

    assertRefused(attempt, level.expectedValve);
    assertThat(attempt.message()).contains(level.container).contains("queryhub.admin.allow-remote");
  }

  @Test
  void pipelineValveIsAllowedWhenRemoteIsAllowed() {
    StartupAttempt attempt = start("server", false, true, PipelineLevel.ENGINE::register);

    assertThat(attempt.failure()).as("allow-remote=true 이면 파이프라인 검사를 하지 않는다").isNull();
  }

  /** R4: 관리 체인은 /actuator 와 기본 서블릿 경로만 덮는다. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "management.endpoints.web.base-path=/manage",
        "management.endpoints.web.base-path=/",
        "management.endpoints.web.base-path=/actuator/",
        "management.server.port=0",
        "spring.mvc.servlet.path=/qh"
      })
  void actuatorOrServletPathsOutsideTheAdminChainAreRefused(String assignment) {
    String name = assignment.substring(0, assignment.indexOf('='));
    for (String profile : new String[] {"desktop", "server"}) {
      StartupAttempt attempt = start(profile, false, true, "--" + assignment);

      assertRefused(attempt, name);
    }
  }

  @Test
  void explicitDefaultActuatorAndServletPathsAreAccepted() {
    StartupAttempt attempt =
        start(
            "desktop",
            false,
            true,
            "--management.endpoints.web.base-path=/actuator",
            "--spring.mvc.servlet.path=/");

    assertThat(attempt.failure()).isNull();
  }

  /** 시험용 valve 를 넣는 위치. RemoteIpValve 와, 하는 일을 알 수 없는 valve(허용 목록 밖)를 함께 본다. */
  enum PipelineLevel {
    ENGINE("StandardEngine", "org.apache.catalina.valves.RemoteIpValve") {
      @Override
      void customize(TomcatServletWebServerFactory factory) {
        factory.addEngineValves(new RemoteIpValve());
      }
    },
    HOST("StandardHost", "org.apache.catalina.valves.RemoteIpValve") {
      @Override
      void customize(TomcatServletWebServerFactory factory) {
        factory.addContextCustomizers(
            context -> context.getParent().getPipeline().addValve(new RemoteIpValve()));
      }
    },
    CONTEXT("TomcatEmbeddedContext", UnknownValve.class.getName()) {
      @Override
      void customize(TomcatServletWebServerFactory factory) {
        factory.addContextValves(new UnknownValve());
      }
    };

    final String container;
    final String expectedValve;

    PipelineLevel(String container, String expectedValve) {
      this.container = container;
      this.expectedValve = expectedValve;
    }

    abstract void customize(TomcatServletWebServerFactory factory);

    void register(ConfigurableApplicationContext context) {
      WebServerFactoryCustomizer<TomcatServletWebServerFactory> customizer = this::customize;
      context.getBeanFactory().registerSingleton("qhTestValve" + name(), customizer);
    }
  }

  /** 아무것도 하지 않지만 검사 쪽에서는 하는 일을 알 수 없는 valve. */
  static final class UnknownValve extends ValveBase {
    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
      getNext().invoke(request, response);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "*.example",
        "host:80",
        "admin.example/path",
        "admin.example.",
        "user@admin.example"
      })
  void invalidAllowedHostEntriesAreRefused(String entry) {
    StartupAttempt attempt =
        start("desktop", false, true, "--queryhub.admin.allowed-hosts=" + entry);

    assertThat(attempt.failure()).as("'%s'", entry).isNotNull();
    assertThat(attempt.failure()).rootCause().isInstanceOf(IllegalArgumentException.class);
    assertThat(attempt.failure().getCause() == null ? attempt.message() : rootMessage(attempt))
        .contains("queryhub.admin.allowed-hosts")
        .contains("'" + entry + "'");
    assertThat(attempt.webServerStarted()).isFalse();
  }

  /**
   * C2-R1 1층: spring.security.filter.dispatcher-types 가 Boot 기본(request, error, async, forward,
   * include)을 다 갖지 않으면 관리 체인이 일반 요청(REQUEST)이나 내부 전달 요청에 돌지 않을 수 있다. 2회차 JAR 은 error 에서 토큰 없이 200
   * 이었다.
   */
  @ParameterizedTest
  @ValueSource(strings = {"error", "async,error,forward,include", "request", "request,error"})
  void securityFilterDispatcherTypesWithoutTheDefaultSetAreRefused(String types) {
    for (String profile : PROFILES) {
      StartupAttempt attempt =
          start(profile, false, true, "--spring.security.filter.dispatcher-types=" + types);

      assertRefused(attempt, "spring.security.filter.dispatcher-types");
    }
  }

  @Test
  void securityFilterDispatcherTypesFromTheEnvironmentAreRefused() {
    StartupAttempt attempt =
        start(
            "desktop",
            false,
            true,
            environment(Map.of("SPRING_SECURITY_FILTER_DISPATCHER_TYPES", "error")));

    assertRefused(attempt, "spring.security.filter.dispatcher-types");
  }

  @Test
  void explicitDefaultDispatcherTypesAreAccepted() {
    StartupAttempt attempt =
        start(
            "desktop",
            false,
            true,
            "--spring.security.filter.dispatcher-types=request,error,async,forward,include");

    assertThat(attempt.failure()).isNull();
  }

  /** C2-R2: 다른 엔드포인트를 열린 health 경로로 옮기거나 health 를 옮기면 토큰 없이 열린다. 값이 있으면 모두 거부한다. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "management.endpoints.web.path-mapping.env=health",
        "management.endpoints.web.path-mapping.health=hc",
        "management.endpoints.web.path-mapping.info=info"
      })
  void endpointPathMappingIsRefused(String assignment) {
    for (String profile : PROFILES) {
      StartupAttempt attempt = start(profile, false, true, "--" + assignment);

      assertRefused(attempt, "management.endpoints.web.path-mapping");
    }
  }

  @Test
  void endpointPathMappingFromTheEnvironmentIsRefused() {
    StartupAttempt attempt =
        start(
            "desktop",
            false,
            true,
            environment(
                Map.of(
                    "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE", "health,env",
                    "MANAGEMENT_ENDPOINTS_WEB_PATHMAPPING_HEALTH", "hc",
                    "MANAGEMENT_ENDPOINTS_WEB_PATHMAPPING_ENV", "health")));

    assertRefused(attempt, "management.endpoints.web.path-mapping");
  }

  /** C2-R2 (C2-R4): health 그룹을 /actuator 밖의 주 포트 경로로 여는 설정. 열린 경로는 health 세 경로로만 둔다. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "management.endpoint.health.probes.add-additional-paths=true",
        "management.endpoint.health.group.liveness.additional-path=server:/livez",
        "management.endpoint.health.group.qh.additional-path=server:/qh-health"
      })
  void additionalHealthPathsAreRefused(String assignment) {
    String name = assignment.substring(0, assignment.indexOf('='));
    for (String profile : PROFILES) {
      StartupAttempt attempt = start(profile, false, true, "--" + assignment);

      assertRefused(attempt, name);
    }
  }

  @Test
  void additionalHealthPathsFromTheEnvironmentAreRefused() {
    StartupAttempt attempt =
        start(
            "desktop",
            false,
            true,
            environment(Map.of("MANAGEMENT_ENDPOINT_HEALTH_PROBES_ADD_ADDITIONAL_PATHS", "true")));

    assertRefused(attempt, "management.endpoint.health.probes.add-additional-paths");
  }

  @Test
  void explicitlyDisabledAdditionalHealthPathsAreAccepted() {
    StartupAttempt attempt =
        start(
            "desktop",
            false,
            true,
            "--management.endpoint.health.probes.add-additional-paths=false");

    assertThat(attempt.failure()).isNull();
  }

  /** C2-R5: 접근 로그 패턴이 Authorization 헤더를 적으면 토큰이 로그 파일에 남는다. 패턴 값은 메시지에 옮기지 않는다. */
  @ParameterizedTest
  @ValueSource(strings = {"%h %{Authorization}i", "%{authorization}i", "%r %{AUTHORIZATION}i"})
  void accessLogPatternWithTheAuthorizationHeaderIsRefused(String pattern) {
    for (String profile : PROFILES) {
      StartupAttempt attempt =
          start(profile, false, true, "--server.tomcat.accesslog.pattern=" + pattern);

      assertRefused(attempt, "server.tomcat.accesslog.pattern");
      assertThat(attempt.message()).doesNotContain("%{");
    }
  }

  /** 요청 헤더 값을 로그에 적는 DispatcherServlet 설정. 관리 요청이 DispatcherServlet 에 닿을 때는 유효한 토큰이 들어 있다. */
  @Test
  void requestDetailsLoggingIsRefused() {
    for (String profile : PROFILES) {
      StartupAttempt attempt = start(profile, false, true, "--spring.mvc.log-request-details=true");

      assertRefused(attempt, "spring.mvc.log-request-details");
    }
    assertThat(start("desktop", false, true, "--spring.mvc.log-request-details=false").failure())
        .isNull();
  }

  @Test
  void accessLogPatternWithoutTheAuthorizationHeaderIsAccepted() {
    StartupAttempt attempt =
        start("desktop", false, true, "--server.tomcat.accesslog.pattern=%h %r %s %{User-Agent}i");

    assertThat(attempt.failure()).isNull();
  }

  /** C2-R1 2층: 보안 필터 등록이 꺼지면(속성이 아닌 다른 길) Tomcat 필터 매핑 검사가 포트를 열기 전에 멈춘다. */
  @Test
  void disabledSecurityFilterRegistrationIsRefusedBeforeThePortOpens() {
    StartupAttempt attempt =
        start(
            "desktop",
            false,
            true,
            context ->
                context
                    .getBeanFactory()
                    .addBeanPostProcessor(
                        new BeanPostProcessor() {
                          @Override
                          public Object postProcessBeforeInitialization(
                              Object bean, String beanName) {
                            if (bean
                                instanceof DelegatingFilterProxyRegistrationBean registration) {
                              registration.setEnabled(false);
                            }
                            return bean;
                          }
                        }));

    assertRefused(attempt, "springSecurityFilterChain");
  }

  /**
   * C2-R1 3층: 속성과 필터 매핑 검사가 보지 못하는 방법으로 관리 체인이 꺼져도(여기서는 시험용 WebSecurityCustomizer 의 ignoring), 기동
   * 직후의 자기 점검이 토큰 없는 관리 요청이 401 이 아님을 보고 런타임을 멈춘다. readiness 는 ACCEPTING_TRAFFIC 이 되지 않는다.
   */
  @ParameterizedTest
  @ValueSource(strings = {"desktop", "server"})
  void adminChainThatDoesNotAnswerStopsTheRuntimeThroughTheSelfProbe(String profile) {
    StartupAttempt attempt =
        start(
            profile,
            false,
            true,
            context ->
                context
                    .getBeanFactory()
                    .registerSingleton(
                        "qhTestIgnoreAdminPaths",
                        (WebSecurityCustomizer)
                            web -> web.ignoring().requestMatchers("/admin/**", "/actuator/**")));

    assertThat(attempt.failure()).as("기동이 실패해야 한다").isNotNull();
    assertThat(attempt.failure()).rootCause().isInstanceOf(IllegalStateException.class);
    assertThat(attempt.message())
        .contains("/admin/api/v1/runtime")
        .doesNotContain(TestAdminToken.VALUE);
    assertThat(attempt.webServerStarted()).as("자기 점검은 포트를 연 뒤에 돈다").isTrue();
    assertThat(attempt.acceptedTraffic()).as("readiness 가 ACCEPTING_TRAFFIC 이 되면 안 된다").isFalse();
    assertPortClosed(attempt.port());
  }

  /**
   * 3층: 보안 필터 뒤의 시험용 필터가 열린 health 경로에서 health 가 아닌 본문(env 모양)을 답하면, 속성 검사와 필터 매핑 검사는 통과해도 자기 점검이
   * 런타임을 멈춘다 (path-mapping 으로 엔드포인트를 health 로 옮기는 것과 같은 모양).
   */
  @Test
  void nonHealthBodyOnAnOpenHealthPathStopsTheRuntimeThroughTheSelfProbe() {
    StartupAttempt attempt =
        start(
            "desktop",
            false,
            true,
            context -> {
              FilterRegistrationBean<Filter> registration =
                  new FilterRegistrationBean<>(
                      (request, response, chain) -> {
                        response.setContentType("application/json");
                        response
                            .getOutputStream()
                            .write(
                                "{\"activeProfiles\":[],\"propertySources\":[]}"
                                    .getBytes(StandardCharsets.UTF_8));
                      });
              registration.addUrlPatterns("/actuator/health");
              registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
              context.getBeanFactory().registerSingleton("qhTestEnvOnHealth", registration);
            });

    assertThat(attempt.failure()).as("기동이 실패해야 한다").isNotNull();
    assertThat(attempt.failure()).rootCause().isInstanceOf(IllegalStateException.class);
    assertThat(attempt.message()).contains("/actuator/health").contains("health 문서");
    assertThat(attempt.webServerStarted()).isTrue();
    assertThat(attempt.acceptedTraffic()).isFalse();
    assertPortClosed(attempt.port());
  }

  /**
   * server 프로파일, allow-remote=true, localhost 가 없는 allowed-hosts: 자기 점검은 Host 검사의 403 도 체인의 응답으로
   * 받는다.
   */
  @Test
  void selfProbeAcceptsTheHostCheckRefusalOnTheServerProfile() {
    StartupAttempt attempt =
        start("server", false, true, "--queryhub.admin.allowed-hosts=admin.example");

    assertThat(attempt.failure()).isNull();
    assertThat(attempt.acceptedTraffic()).isTrue();
    assertThat(attempt.probed()).isEqualTo(ALL_PROBED_PATHS);
  }

  /** 정상 기동에서도 자기 점검이 세 경로를 모두 확인하고, 그 뒤에 readiness 가 ACCEPTING_TRAFFIC 이 된다. */
  @ParameterizedTest
  @ValueSource(strings = {"desktop", "server"})
  void selfProbeVerifiesEveryProbePathOnANormalStartup(String profile) {
    StartupAttempt attempt = start(profile, false, true);

    assertThat(attempt.failure()).isNull();
    assertThat(attempt.probed()).isEqualTo(ALL_PROBED_PATHS);
    assertThat(attempt.acceptedTraffic()).isTrue();
  }

  /**
   * C3-R1: health 기여자가 {@link AdminChainSelfProbe#TIMEOUT} 보다 오래 멈추면(메타데이터 DB 가 멈춘 것과 같은 모양), 자기
   * 점검은 시간 초과를 따로 말하고, 그 이유를 컨텍스트 정리를 시작하기 전에 ERROR 로그에 남기며, 기동 실패는 정해진 시간 안에 끝난다.
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void blockingHealthContributorStopsTheRuntimeWithALoggedTimeoutReason(CapturedOutput output) {
    CountDownLatch release = new CountDownLatch(1);
    long started = System.nanoTime();
    StartupAttempt attempt;
    try {
      attempt =
          start(
              "desktop",
              false,
              true,
              context ->
                  context
                      .getBeanFactory()
                      .registerSingleton("qhTestBlockingHealthIndicator", new Blocking(release)));
    } finally {
      release.countDown();
    }
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

    assertThat(attempt.failure()).as("기동이 실패해야 한다").isNotNull();
    assertThat(attempt.failure()).rootCause().isInstanceOf(IllegalStateException.class);
    assertThat(attempt.message())
        .contains("/actuator/health")
        .contains("시간 초과")
        .contains("메타데이터 DB")
        .doesNotContain("옮겨져");
    assertThat(attempt.webServerStarted()).isTrue();
    assertThat(attempt.acceptedTraffic()).isFalse();
    assertThat(elapsed).as("시간 초과 뒤 정리가 바로 끝나야 한다").isLessThan(Duration.ofSeconds(25));
    String log = output.getOut();
    List<String> lines = log.lines().toList();
    int reason = -1;
    for (int i = 0; i < lines.size() && reason < 0; i++) {
      String line = lines.get(i);
      if (line.contains("ERROR")
          && line.contains("AdminChainSelfProbe")
          && line.contains("/actuator/health")
          && line.contains("시간 초과")) {
        reason = i;
      }
    }
    assertThat(reason).as("이유가 자기 점검의 ERROR 로그 한 줄에 있어야 한다").isNotNegative();
    int cancelled = -1;
    for (int i = 0; i < lines.size() && cancelled < 0; i++) {
      if (lines.get(i).contains("cancelling refresh attempt")) {
        cancelled = i;
      }
    }
    assertThat(cancelled).as("이유는 컨텍스트 정리를 시작하기 전에 남아야 한다").isGreaterThan(reason);
    assertThat(log).doesNotContain(TestAdminToken.VALUE);
    assertPortClosed(attempt.port());
  }

  /** 기다리는 동안 멈춰 있는 health 기여자. 웹 서버가 멈추며 요청 스레드를 interrupt 하거나 시험이 풀면 돌아온다. */
  private record Blocking(CountDownLatch release) implements HealthIndicator {
    @Override
    public Health health() {
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return Health.up().build();
    }
  }

  /**
   * C3-R2: 들여쓴 JSON 출력(spring.jackson.serialization.indent-output=true)에서도 자기 점검이 통과하고 런타임이 시작한다.
   */
  @ParameterizedTest
  @ValueSource(strings = {"desktop", "server"})
  void prettyPrintedJsonOutputStillStarts(String profile) {
    StartupAttempt attempt =
        start(profile, false, true, "--spring.jackson.serialization.indent-output=true");

    assertThat(attempt.failure()).isNull();
    assertThat(attempt.probed()).isEqualTo(ALL_PROBED_PATHS);
    assertThat(attempt.acceptedTraffic()).isTrue();
  }

  /**
   * C3-R4: 내장 TLS(유효한 일회용 키 저장소)는 포트를 열기 전에 속성 검사가 거부한다. 메시지는 키 저장소 경로와 비밀번호를 옮기지 않는다. 같은 키 저장소라도
   * server.ssl.enabled=false 이면 시작한다.
   */
  @Test
  void embeddedTlsIsRefusedBeforeThePortOpens(@TempDir Path directory) throws Exception {
    String password = UUID.randomUUID().toString();
    Path keyStore = throwawayKeyStore(directory, password);
    String[] ssl = {
      "--server.ssl.key-store=file:" + keyStore,
      "--server.ssl.key-store-type=PKCS12",
      "--server.ssl.key-store-password=" + password
    };
    for (String profile : PROFILES) {
      StartupAttempt attempt = start(profile, false, true, ssl);

      assertRefused(attempt, "server.ssl");
      assertThat(attempt.message())
          .contains("리버스 프록시")
          .doesNotContain(password)
          .doesNotContain(keyStore.toString());
    }
    List<String> disabled = new ArrayList<>(List.of(ssl));
    disabled.add("--server.ssl.enabled=false");
    StartupAttempt plain = start("desktop", false, true, disabled.toArray(String[]::new));
    assertThat(plain.failure()).as("server.ssl.enabled=false 이면 TLS 가 아니다").isNull();
    assertThat(plain.acceptedTraffic()).isTrue();
  }

  /** C3-R4: 속성 검사는 Boot 와 같은 조건으로 TLS 를 본다 (SSL 번들, enabled 만, 끈 경우). */
  @Test
  void embeddedTlsCheckFollowsBootsSslCondition() {
    for (String[] refused :
        List.of(
            new String[] {"server.ssl.bundle", "qh-test-bundle"},
            new String[] {"server.ssl.enabled", "true"},
            new String[] {"server.ssl.certificate", "classpath:qh-test.crt"})) {
      MockEnvironment environment = new MockEnvironment().withProperty(refused[0], refused[1]);
      assertThat(catchThrowable(() -> AdminAccessConfiguration.rejectEmbeddedTls(environment)))
          .as(refused[0])
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("server.ssl")
          .hasMessageNotContaining(refused[1]);
    }
    assertThat(
            catchThrowable(
                () ->
                    AdminAccessConfiguration.rejectEmbeddedTls(
                        new MockEnvironment()
                            .withProperty("server.ssl.enabled", "false")
                            .withProperty("server.ssl.bundle", "qh-test-bundle"))))
        .isNull();
    assertThat(
            catchThrowable(() -> AdminAccessConfiguration.rejectEmbeddedTls(new MockEnvironment())))
        .isNull();
  }

  /** JDK 의 keytool 로 하루짜리 자체 서명 키 저장소를 만든다 (시험 전용, 임시 디렉터리). */
  private static Path throwawayKeyStore(Path directory, String password) throws Exception {
    Path keyStore = directory.resolve("qh-test.p12");
    Process keytool =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-noprompt",
                "-alias",
                "qh-test",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=localhost",
                "-validity",
                "1",
                "-storetype",
                "PKCS12",
                "-keystore",
                keyStore.toString(),
                "-storepass",
                password,
                "-keypass",
                password)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertThat(keytool.waitFor(60, TimeUnit.SECONDS)).isTrue();
    assertThat(keytool.exitValue()).isZero();
    assertThat(Files.size(keyStore)).isPositive();
    return keyStore;
  }

  private static void assertPortClosed(int port) {
    assertThat(port).isPositive();
    Throwable connect =
        catchThrowable(
            () -> {
              try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
              }
            });
    assertThat(connect).as("멈춘 런타임의 포트는 닫혀 있어야 한다").isInstanceOf(IOException.class);
  }

  private static ApplicationContextInitializer<ConfigurableApplicationContext> environment(
      Map<String, Object> variables) {
    return context ->
        context
            .getEnvironment()
            .getPropertySources()
            .addFirst(
                new SystemEnvironmentPropertySource(
                    "qhTest-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    variables));
  }

  private static void assertRefused(StartupAttempt attempt, String named) {
    assertThat(attempt.failure()).as("기동이 실패해야 한다").isNotNull();
    assertThat(attempt.failure()).rootCause().isInstanceOf(IllegalStateException.class);
    assertThat(attempt.message()).contains(named).doesNotContain(TestAdminToken.VALUE);
    assertThat(attempt.webServerStarted()).as("포트를 열기 전에 실패해야 한다").isFalse();
  }

  private static String rootMessage(StartupAttempt attempt) {
    Throwable root = attempt.failure();
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return root.getMessage();
  }

  private static StartupAttempt start(
      String profile, boolean lazy, boolean withToken, String... extraArgs) {
    return start(profile, lazy, withToken, context -> {}, extraArgs);
  }

  private static StartupAttempt start(
      String profile,
      boolean lazy,
      boolean withToken,
      ApplicationContextInitializer<ConfigurableApplicationContext> initializer,
      String... extraArgs) {
    AtomicBoolean webServerStarted = new AtomicBoolean();
    AtomicInteger port = new AtomicInteger();
    AtomicBoolean acceptedTraffic = new AtomicBoolean();
    List<String> probed = new ArrayList<>();
    ApplicationListener<ApplicationEvent> webServerListener =
        event -> {
          if (event instanceof WebServerInitializedEvent initialized) {
            webServerStarted.set(true);
            port.set(initialized.getWebServer().getPort());
          }
          if (event instanceof AvailabilityChangeEvent<?> change
              && change.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
            acceptedTraffic.set(true);
          }
        };
    SpringApplicationBuilder builder =
        new SpringApplicationBuilder(QueryHubApplication.class)
            .web(WebApplicationType.SERVLET)
            .lazyInitialization(lazy)
            .profiles(profile)
            .properties(WithoutMetadataDatabase.PROPERTY)
            .initializers(TestComponentScanExclusion::register, initializer)
            .listeners(webServerListener);
    if (withToken) {
      builder.properties(TestAdminToken.PROPERTY);
    }
    SpringApplication application = builder.build();
    List<String> args = new ArrayList<>(List.of("--server.address=127.0.0.1", "--server.port=0"));
    args.addAll(List.of(extraArgs));
    Throwable failure =
        catchThrowable(
            () -> {
              try (ConfigurableApplicationContext context =
                  application.run(args.toArray(String[]::new))) {
                assertThat(context.isActive()).isTrue();
                probed.addAll(context.getBean(AdminChainSelfProbe.class).verifiedPaths());
              }
            });
    return new StartupAttempt(
        failure, webServerStarted.get(), port.get(), acceptedTraffic.get(), List.copyOf(probed));
  }

  private record StartupAttempt(
      @Nullable Throwable failure,
      boolean webServerStarted,
      int port,
      boolean acceptedTraffic,
      List<String> probed) {

    String message() {
      Throwable root = failure;
      while (root != null && root.getCause() != null) {
        root = root.getCause();
      }
      return root == null ? "" : String.valueOf(root.getMessage());
    }
  }
}
