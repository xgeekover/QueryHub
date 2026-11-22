package com.queryhub.app.config.admin;

import com.queryhub.adapter.web.admin.security.AdminAccessPolicy;
import com.queryhub.adapter.web.admin.security.AdminRequestMatcher;
import com.queryhub.adapter.web.admin.security.AdminTokenVerifier;
import com.queryhub.adapter.web.admin.security.HostHeaderPolicy;
import com.queryhub.app.config.RuntimeModeResolver;
import com.queryhub.domain.runtime.RuntimeMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.server.Ssl;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * 관리 체인의 설정({@link AdminAccessPolicy})을 만든다. 검사가 실패하면 웹 서버가 포트를 열기 전에 기동이 멈춘다.
 *
 * <ol>
 *   <li>토큰: {@link AdminTokenSource} (없거나 틀리면 두 프로파일 모두 기동 거부).
 *   <li>원격 주소 재작성: {@value AdminSecurityProperties#ALLOW_REMOTE}=false 인데 {@value
 *       #FORWARD_HEADERS_STRATEGY} 가 none 이 아니거나 {@value #REMOTEIP_PREFIX}.* 속성에 값이 있으면 기동을 거부한다.
 *       Boot 는 이때 RemoteIpValve 를 넣고, 그 valve 가 소켓 원격 주소를 X-Forwarded-For 값으로 바꾸면 루프백 검사가 의미를 잃는다.
 *       이중 안전장치로 {@link TomcatRemoteAddressGuard} 가 만들어진 Tomcat 파이프라인을 포트를 열기 전에 검사한다.
 *   <li>actuator 경로: {@link AdminRequestMatcher} 는 {@value AdminRequestMatcher#ACTUATOR} 접두사와 기본
 *       서블릿 경로를 전제로 한다. 그래서 {@value #MANAGEMENT_BASE_PATH} 가 그 값이 아니거나, actuator 가 다른 포트({@value
 *       #MANAGEMENT_SERVER_PORT})로 열리거나, {@value #MVC_SERVLET_PATH} 가 {@code /} 가 아니면 (두 프로파일 모두)
 *       기동을 거부한다.
 *   <li>체인을 끄거나 핸들러를 체인 밖·열린 health 경로로 옮기는 속성 (두 프로파일 모두, P1-03 3회차): {@value
 *       #SECURITY_FILTER_DISPATCHER_TYPES} 가 Boot 기본 다섯 가지를 다 갖지 않을 때, {@value
 *       #PATH_MAPPING_PREFIX}.* 에 값이 있을 때, {@value #HEALTH_ADDITIONAL_PATHS} 가 true 이거나 {@value
 *       #HEALTH_GROUP_PREFIX}.*.additional-path 에 값이 있을 때, {@value #ACCESS_LOG_PATTERN} 이
 *       Authorization 헤더를 적을 때 기동을 거부하고, {@value #MVC_LOG_REQUEST_DETAILS}=true 도 거부한다(헤더 값이 로그에
 *       남는다). 값은 Boot 가 쓰는 속성 클래스로 바인딩해 읽는다(이름 변형과 환경변수 형태를 Boot 와 똑같이 푼다).
 *   <li>내장 TLS (두 프로파일 모두, P1-03 4회차 C3-R4): {@value #SERVER_SSL}.* 가 TLS 를 켜면(Boot 의 {@link Ssl} 로
 *       바인딩해 {@link Ssl#isEnabled(Ssl)} 로 판단, SSL 번들 포함) 기동을 거부한다. 자기 점검은 평문 HTTP 로만 묻고, TLS 는 앞단의
 *       리버스 프록시에서 끝낸다(사람 결정 2026-11-22 「받아들임 (권장)」, ADR-022).
 *   <li>속성으로 잡지 못한 경우의 이중 안전장치: {@link TomcatSecurityFilterGuard}(포트를 열기 전 필터 매핑 검사)와 {@link
 *       AdminChainSelfProbe}(포트를 연 뒤 readiness 전에 토큰 없는 관리 요청이 체인의 401/403 인지 확인, 아니면 기동 실패).
 *   <li>Host 검사: desktop 은 늘 켜고, server 는 {@value AdminSecurityProperties#ALLOWED_HOSTS} 가 비어 있지 않을
 *       때만 켠다.
 * </ol>
 *
 * <p>거부 메시지는 속성 이름만 말하고 값은 옮기지 않는다. lazy 초기화에서도 검사가 기동 중에 일어나도록 두 빈은 늘 즉시
 * 만든다({@code @Lazy(false)}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminSecurityProperties.class)
public class AdminAccessConfiguration {

  /** Boot 의 forward 헤더 전략 속성. application.yml 이 none 으로 고정한다. */
  public static final String FORWARD_HEADERS_STRATEGY = "server.forward-headers-strategy";

  /** Boot 가 RemoteIpValve 를 구성하는 속성들의 접두사. */
  public static final String REMOTEIP_PREFIX = "server.tomcat.remoteip";

  /** actuator 웹 엔드포인트의 기본 경로 속성. {@value AdminRequestMatcher#ACTUATOR} 여야 한다. */
  public static final String MANAGEMENT_BASE_PATH = "management.endpoints.web.base-path";

  /** actuator 를 별도 포트로 여는 속성. 관리 체인이 그 포트를 덮지 않으므로 다른 포트는 거부한다. */
  public static final String MANAGEMENT_SERVER_PORT = "management.server.port";

  /** DispatcherServlet 경로 속성. 관리 경로 판단이 서블릿 경로 {@code /} 를 전제로 한다. */
  public static final String MVC_SERVLET_PATH = "spring.mvc.servlet.path";

  /**
   * Spring Security 필터를 등록할 dispatcher 종류. Boot 기본(request, error, async, forward, include)만 받는다.
   */
  public static final String SECURITY_FILTER_DISPATCHER_TYPES =
      "spring.security.filter.dispatcher-types";

  /** actuator 엔드포인트 경로를 바꾸는 속성들의 접두사. 값이 하나라도 있으면 거부한다. */
  public static final String PATH_MAPPING_PREFIX = "management.endpoints.web.path-mapping";

  /** health 그룹(liveness, readiness)을 주 포트의 /livez, /readyz 로도 여는 속성. */
  public static final String HEALTH_ADDITIONAL_PATHS =
      "management.endpoint.health.probes.add-additional-paths";

  /** health 그룹 설정의 접두사. 그룹마다 additional-path 를 줄 수 있다. */
  public static final String HEALTH_GROUP_PREFIX = "management.endpoint.health.group";

  /** Tomcat 접근 로그 패턴. Authorization 헤더를 적으면 토큰이 로그 파일에 남는다. */
  public static final String ACCESS_LOG_PATTERN = "server.tomcat.accesslog.pattern";

  /** DispatcherServlet 이 요청 헤더 값까지 로그에 적게 하는 속성 (TRACE 에서 Authorization 이 나온다). */
  public static final String MVC_LOG_REQUEST_DETAILS = "spring.mvc.log-request-details";

  /** 내장 TLS 속성의 접두사 (Boot 의 ServerProperties.ssl). */
  public static final String SERVER_SSL = "server.ssl";

  private static final String NONE = "none";

  /** Boot 4.1 이 아는 remoteip 속성. 이름을 열거할 수 없는 속성 소스를 위해 이름으로도 확인한다. */
  private static final List<String> KNOWN_REMOTEIP_PROPERTIES =
      List.of(
          "internal-proxies",
          "trusted-proxies",
          "remote-ip-header",
          "protocol-header",
          "protocol-header-https-value",
          "host-header",
          "port-header");

  @Bean
  @Lazy(false)
  public AdminAccessPolicy adminAccessPolicy(
      Environment environment, AdminSecurityProperties properties) {
    RuntimeMode mode = RuntimeModeResolver.resolve(environment);
    AdminTokenVerifier verifier = new AdminTokenVerifier(AdminTokenSource.load(environment));
    rejectForwardHeadersWithoutRemote(environment, properties.allowRemote());
    rejectRemoteIpPropertiesWithoutRemote(environment, properties.allowRemote());
    rejectUncoveredActuatorPaths(environment);
    rejectReducedSecurityFilterDispatch(environment);
    rejectEndpointPathMapping(environment);
    rejectAdditionalHealthPaths(environment);
    rejectAuthorizationInAccessLog(environment);
    rejectRequestDetailsLogging(environment);
    rejectEmbeddedTls(environment);
    boolean hostCheck = mode == RuntimeMode.DESKTOP || !properties.allowedHosts().isEmpty();
    return new AdminAccessPolicy(
        verifier,
        properties.allowRemote(),
        hostCheck,
        HostHeaderPolicy.of(properties.allowedHosts()));
  }

  /** 포트를 열기 전에 Tomcat 파이프라인을 검사한다 (allow-remote=false 일 때). */
  @Bean
  @Lazy(false)
  public TomcatRemoteAddressGuard tomcatRemoteAddressGuard(
      ApplicationContext applicationContext, AdminSecurityProperties properties) {
    return new TomcatRemoteAddressGuard(applicationContext, properties.allowRemote());
  }

  /** 포트를 열기 전에 Spring Security 필터의 Tomcat 매핑과 순서를 검사한다 (두 프로파일 모두). */
  @Bean
  @Lazy(false)
  public TomcatSecurityFilterGuard tomcatSecurityFilterGuard(
      ApplicationContext applicationContext, AdminSecurityProperties properties) {
    return new TomcatSecurityFilterGuard(applicationContext, properties.allowRemote());
  }

  /** 포트를 연 뒤, readiness 가 ACCEPTING_TRAFFIC 이 되기 전에 관리 체인이 실제로 답하는지 확인한다 (두 프로파일 모두). */
  @Bean
  @Lazy(false)
  public AdminChainSelfProbe adminChainSelfProbe(
      ApplicationContext applicationContext, Environment environment) {
    return new AdminChainSelfProbe(applicationContext, environment);
  }

  /**
   * allow-remote=false 이면 forward 헤더 전략이 none 이어야 한다. 값이 없어도(Boot 가 클라우드에서 native 로 고를 수 있다) 거부한다.
   */
  static void rejectForwardHeadersWithoutRemote(Environment environment, boolean allowRemote) {
    if (allowRemote) {
      return;
    }
    String strategy =
        Binder.get(environment).bind(FORWARD_HEADERS_STRATEGY, String.class).orElse(null);
    if (strategy == null || !NONE.equals(strategy.trim().toLowerCase(Locale.ROOT))) {
      throw new IllegalStateException(
          AdminSecurityProperties.ALLOW_REMOTE
              + "=false 이면 "
              + FORWARD_HEADERS_STRATEGY
              + " 는 none 이어야 한다. 그렇지 않으면 X-Forwarded-For 가 소켓 원격 주소를 바꿔 루프백 검사를 우회할 수 있어 런타임을 시작하지 않는다.");
    }
  }

  /**
   * allow-remote=false 이면 {@value #REMOTEIP_PREFIX}.* 속성은 모두 비어 있어야 한다. 특정 속성 이름이 아니라 접두사 전체로
   * 판단한다(remote-ip-header 나 protocol-header 중 하나만 있어도 Boot 는 RemoteIpValve 를 넣고, 그 valve 의 기본값은
   * X-Forwarded-For 와 사설 대역 프록시다).
   */
  static void rejectRemoteIpPropertiesWithoutRemote(Environment environment, boolean allowRemote) {
    if (allowRemote) {
      return;
    }
    Set<String> present = remoteIpPropertiesWithText(environment);
    if (!present.isEmpty()) {
      throw new IllegalStateException(
          AdminSecurityProperties.ALLOW_REMOTE
              + "=false 이면 "
              + REMOTEIP_PREFIX
              + ".* 속성은 비어 있어야 한다 (값이 있는 속성: "
              + String.join(", ", present)
              + "). 이 속성은 RemoteIpValve 를 켜 X-Forwarded-For 로 소켓 원격 주소를 바꾸므로 런타임을 시작하지 않는다.");
    }
  }

  private static Set<String> remoteIpPropertiesWithText(Environment environment) {
    ConfigurationPropertyName prefix = ConfigurationPropertyName.of(REMOTEIP_PREFIX);
    Set<String> present = new TreeSet<>();
    for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
      if (source instanceof IterableConfigurationPropertySource iterable) {
        iterable.stream()
            .filter(prefix::isAncestorOf)
            .filter(name -> hasText(source.getConfigurationProperty(name)))
            .forEach(name -> present.add(name.toString()));
      }
    }
    if (present.isEmpty()) {
      Binder binder = Binder.get(environment);
      for (String name : KNOWN_REMOTEIP_PROPERTIES) {
        String property = REMOTEIP_PREFIX + "." + name;
        if (StringUtils.hasText(binder.bind(property, String.class).orElse(null))) {
          present.add(property);
        }
      }
    }
    return present;
  }

  private static boolean hasText(@Nullable ConfigurationProperty property) {
    return property != null
        && property.getValue() != null
        && StringUtils.hasText(String.valueOf(property.getValue()));
  }

  /**
   * 관리 체인이 덮는 actuator 경로는 {@value AdminRequestMatcher#ACTUATOR} 하나뿐이다. 다른 기본 경로, 다른 관리 포트, 기본이 아닌
   * DispatcherServlet 경로에서는 actuator 나 관리 API 가 토큰 없이 열릴 수 있으므로 기동을 거부한다. management.server.port 가
   * 음수(HTTP actuator 끔)이거나 server.port 와 같으면 받는다.
   */
  static void rejectUncoveredActuatorPaths(Environment environment) {
    Binder binder = Binder.get(environment);
    String basePath =
        binder.bind(MANAGEMENT_BASE_PATH, String.class).orElse(AdminRequestMatcher.ACTUATOR);
    if (!AdminRequestMatcher.ACTUATOR.equals(basePath)) {
      throw new IllegalStateException(
          MANAGEMENT_BASE_PATH
              + " 는 "
              + AdminRequestMatcher.ACTUATOR
              + " 여야 한다. 관리 체인은 그 경로만 보호하므로 다른 경로에서는 actuator 가 토큰 없이 열릴 수 있어 런타임을 시작하지 않는다.");
    }
    if (ManagementPortType.get(environment) == ManagementPortType.DIFFERENT) {
      throw new IllegalStateException(
          MANAGEMENT_SERVER_PORT
              + " 로 actuator 를 다른 포트에 열 수 없다. 관리 체인은 그 포트를 보호하지 않으므로 런타임을 시작하지 않는다 (같은 포트로 두거나 지운다).");
    }
    String servletPath = binder.bind(MVC_SERVLET_PATH, String.class).orElse("/");
    if (!"/".equals(servletPath)) {
      throw new IllegalStateException(
          MVC_SERVLET_PATH
              + " 는 / 여야 한다. 관리 경로 판단이 기본 서블릿 경로를 전제로 하므로 다른 값에서는 관리 API 가 토큰 없이 열릴 수 있어 런타임을 시작하지 않는다.");
    }
  }

  /**
   * Spring Security 필터의 dispatcher 종류가 Boot 기본 다섯 가지를 모두 가져야 한다. REQUEST 가 없으면 관리 체인이 일반 요청에 돌지 않고,
   * FORWARD·INCLUDE·ASYNC·ERROR 가 없으면 내부 전달 요청이 체인을 건너뛴다. 값은 Boot 의 {@link
   * SecurityFilterProperties} 로 바인딩해 읽는다.
   */
  static void rejectReducedSecurityFilterDispatch(Environment environment) {
    Set<?> required = new SecurityFilterProperties().getDispatcherTypes();
    Set<?> configured =
        Binder.get(environment)
            .bindOrCreate("spring.security.filter", SecurityFilterProperties.class)
            .getDispatcherTypes();
    if (!configured.containsAll(required)) {
      throw new IllegalStateException(
          SECURITY_FILTER_DISPATCHER_TYPES
              + " 는 Boot 기본값(request, error, async, forward, include)을 모두 가져야 한다. 빠진 종류의 요청은 관리 체인을 거치지 않아"
              + " 관리 API 가 토큰 없이 열릴 수 있으므로 런타임을 시작하지 않는다 (속성을 지운다).");
    }
  }

  /**
   * actuator 엔드포인트 경로 바꾸기({@value #PATH_MAPPING_PREFIX}.*)는 받지 않는다. 열린 health 경로는 경로로 정하므로, 다른
   * 엔드포인트(env 등)를 그 경로로 옮기면 토큰 없이 열린다. 메시지는 속성 이름만 말한다.
   */
  static void rejectEndpointPathMapping(Environment environment) {
    Map<String, String> mapping =
        Binder.get(environment)
            .bindOrCreate("management.endpoints.web", WebEndpointProperties.class)
            .getPathMapping();
    Set<String> present = new TreeSet<>();
    mapping.forEach(
        (endpoint, path) -> {
          if (StringUtils.hasText(path)) {
            present.add(PATH_MAPPING_PREFIX + "." + endpoint);
          }
        });
    if (!present.isEmpty()) {
      throw new IllegalStateException(
          PATH_MAPPING_PREFIX
              + ".* 로 actuator 엔드포인트 경로를 바꿀 수 없다 (값이 있는 속성: "
              + String.join(", ", present)
              + "). 열린 health 경로로 다른 엔드포인트가 옮겨 가면 토큰 없이 열리므로 런타임을 시작하지 않는다.");
    }
  }

  /**
   * health 를 /actuator 밖의 주 포트 경로로 여는 설정({@value #HEALTH_ADDITIONAL_PATHS}=true, {@value
   * #HEALTH_GROUP_PREFIX}.*.additional-path)은 받지 않는다. 토큰 없이 여는 경로를 정확히 health 세 경로로 둔다.
   */
  static void rejectAdditionalHealthPaths(Environment environment) {
    Binder binder = Binder.get(environment);
    if (binder.bind(HEALTH_ADDITIONAL_PATHS, Boolean.class).orElse(false)) {
      throw new IllegalStateException(
          HEALTH_ADDITIONAL_PATHS
              + " 는 false 여야 한다. 관리 체인 밖의 주 포트 경로(/livez, /readyz)에 health 를 열게 되므로 런타임을 시작하지 않는다.");
    }
    Set<String> present = new TreeSet<>();
    binder
        .bindOrCreate("management.endpoint.health", HealthEndpointProperties.class)
        .getGroup()
        .forEach(
            (group, properties) -> {
              if (StringUtils.hasText(properties.getAdditionalPath())) {
                present.add(HEALTH_GROUP_PREFIX + "." + group + ".additional-path");
              }
            });
    if (!present.isEmpty()) {
      throw new IllegalStateException(
          HEALTH_GROUP_PREFIX
              + ".*.additional-path 는 쓸 수 없다 (값이 있는 속성: "
              + String.join(", ", present)
              + "). 관리 체인 밖의 경로에 health 그룹을 열게 되므로 런타임을 시작하지 않는다.");
    }
  }

  /** 접근 로그 패턴이 Authorization 헤더(대소문자 무관)를 적으면 토큰이 로그 파일에 남는다. 패턴 값은 메시지에 옮기지 않는다. */
  static void rejectAuthorizationInAccessLog(Environment environment) {
    String pattern = Binder.get(environment).bind(ACCESS_LOG_PATTERN, String.class).orElse("");
    if (pattern.toLowerCase(Locale.ROOT).contains("authorization")) {
      throw new IllegalStateException(
          ACCESS_LOG_PATTERN + " 에 Authorization 헤더를 넣을 수 없다. 관리자 토큰이 접근 로그에 남으므로 런타임을 시작하지 않는다.");
    }
  }

  /**
   * {@value #MVC_LOG_REQUEST_DETAILS}=true 이면 DispatcherServlet 이 TRACE 로그에 요청 헤더 값을 적는다. 관리 요청은
   * 토큰을 확인한 뒤에야 DispatcherServlet 에 닿으므로 그 값은 유효한 토큰이다. 기동을 거부한다.
   */
  static void rejectRequestDetailsLogging(Environment environment) {
    if (Binder.get(environment).bind(MVC_LOG_REQUEST_DETAILS, Boolean.class).orElse(false)) {
      throw new IllegalStateException(
          MVC_LOG_REQUEST_DETAILS
              + " 는 false 여야 한다. 요청 헤더 값(관리자 토큰이 든 Authorization)이 로그에 남을 수 있으므로 런타임을 시작하지 않는다.");
    }
  }

  /**
   * 내장 TLS 는 받지 않는다. {@value #SERVER_SSL}.* 를 Boot 와 같은 {@link Ssl} 로 바인딩해, Boot 가 TLS 커넥터를 만들
   * 조건({@link Ssl#isEnabled(Ssl)}: 속성이 하나라도 있고 enabled 가 false 가 아님, 키 저장소·인증서·SSL 번들 모두)이면 포트를 열기
   * 전에 기동을 거부한다. 값(경로, 비밀번호, 번들 이름)은 메시지에 옮기지 않는다.
   */
  static void rejectEmbeddedTls(Environment environment) {
    Ssl ssl = Binder.get(environment).bind(SERVER_SSL, Ssl.class).orElse(null);
    if (Ssl.isEnabled(ssl)) {
      throw new IllegalStateException(
          SERVER_SSL
              + ".* 로 내장 TLS 를 켤 수 없다. 관리 체인 자기 점검은 평문 HTTP 로만 하므로 런타임을 시작하지 않는다. TLS 는 앞단의 리버스 프록시에서 끝낸다"
              + " ("
              + SERVER_SSL
              + ".enabled=false 로 두거나 "
              + SERVER_SSL
              + ".* 를 지운다).");
    }
  }
}
