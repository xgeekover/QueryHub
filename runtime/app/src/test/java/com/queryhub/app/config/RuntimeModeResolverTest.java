package com.queryhub.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.queryhub.application.runtime.GetRuntimeInfoUseCase;
import com.queryhub.domain.runtime.RuntimeMode;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;

/** AC-12: 프로파일 -> 실행 모드. desktop 과 server 가 둘 다 있거나 둘 다 없으면 기동이 실패한다. lazy 초기화에서도 마찬가지다(L4). */
class RuntimeModeResolverTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(RuntimeInfoConfiguration.class)
          .withPropertyValues("spring.application.name=queryhub-runtime");

  @Test
  void activeDesktopProfileGivesDesktop() {
    assertThat(RuntimeModeResolver.resolve(environment(new String[] {"desktop"}, "server")))
        .isEqualTo(RuntimeMode.DESKTOP);
  }

  @Test
  void activeServerProfileGivesServerAndOtherProfilesAreIgnored() {
    assertThat(RuntimeModeResolver.resolve(environment(new String[] {"metrics", "server"})))
        .isEqualTo(RuntimeMode.SERVER);
  }

  @Test
  void defaultProfilesAreUsedWhenNoProfileIsActive() {
    assertThat(RuntimeModeResolver.resolve(environment(new String[0], "desktop")))
        .isEqualTo(RuntimeMode.DESKTOP);
  }

  @Test
  void bothDesktopAndServerFail() {
    assertThatThrownBy(
            () -> RuntimeModeResolver.resolve(environment(new String[] {"desktop", "server"})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("desktop")
        .hasMessageContaining("server");
  }

  @Test
  void neitherDesktopNorServerFails() {
    // 활성 프로파일이 있으면 기본 프로파일(desktop)은 쓰지 않는다.
    assertThatThrownBy(
            () -> RuntimeModeResolver.resolve(environment(new String[] {"dev"}, "desktop")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dev");
    assertThatThrownBy(() -> RuntimeModeResolver.resolve(environment(new String[0], "default")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> RuntimeModeResolver.resolve(List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void profileNamesAreCaseSensitive() {
    assertThatThrownBy(() -> RuntimeModeResolver.resolve(List.of("Desktop")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void startupFailsWhenBothModesAreActive() {
    contextRunner
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("desktop", "server"))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("desktop")
                  .hasMessageContaining("server");
            });
  }

  @Test
  void startupFailsWhenNoModeProfileIsPresent() {
    // ApplicationContextRunner 의 기본 프로파일은 "default" 다 (application.yml 을 읽지 않는다).
    contextRunner.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .rootCause()
              .isInstanceOf(IllegalStateException.class);
        });
  }

  @Test
  void startupSucceedsWithExactlyOneModeAndReportsUnknownVersionWithoutBuildInfo() {
    contextRunner
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("server"))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var info = context.getBean(GetRuntimeInfoUseCase.class).getRuntimeInfo();
              assertThat(info.mode()).isEqualTo(RuntimeMode.SERVER);
              assertThat(info.name()).isEqualTo("queryhub-runtime");
              // 이 컨텍스트에는 BuildProperties 빈이 없다.
              assertThat(info.version()).isEqualTo(RuntimeInfoConfiguration.UNKNOWN_VERSION);
            });
  }

  /**
   * L4: spring.main.lazy-initialization=true 이고 desktop/server 가 아닌 프로파일만 켜도 기동이 실패해야 한다. 실패는 웹 서버가
   * 포트를 열기 전(WebServerInitializedEvent 전)에 일어나야 한다.
   *
   * <p>회귀로 기동이 성공해도 루프백의 임의 포트에만 열고 컨텍스트를 바로 닫는다.
   */
  @Test
  void lazyInitializationWithoutModeProfileFailsBeforeTheWebServerStarts() {
    AtomicBoolean webServerStarted = new AtomicBoolean();
    ApplicationListener<ApplicationEvent> webServerListener =
        event -> {
          if (event instanceof WebServerInitializedEvent) {
            webServerStarted.set(true);
          }
        };
    SpringApplication application =
        new SpringApplicationBuilder(LazyStartupApplication.class)
            .web(WebApplicationType.SERVLET)
            .lazyInitialization(true)
            .profiles("dev")
            .listeners(webServerListener)
            .build();

    Throwable failure =
        catchThrowable(
            () -> {
              try (ConfigurableApplicationContext context =
                  application.run("--server.address=127.0.0.1", "--server.port=0")) {
                assertThat(context.isActive()).isTrue();
              }
            });

    assertThat(failure).as("lazy 초기화에서도 모드 검사가 기동을 실패시켜야 한다").isNotNull();
    assertThat(failure)
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dev");
    assertThat(webServerStarted).as("웹 서버가 시작(포트 바인딩)되기 전에 실패해야 한다").isFalse();
  }

  /**
   * 실제 자동 구성(서블릿 웹 서버 포함)과 application.yml 에 RuntimeInfoConfiguration 만 더한 최소 애플리케이션. 컴포넌트 스캔은 하지
   * 않는다.
   */
  @TestConfiguration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import(RuntimeInfoConfiguration.class)
  static class LazyStartupApplication {}

  private static MockEnvironment environment(String[] active, String... defaults) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(active);
    if (defaults.length > 0) {
      environment.setDefaultProfiles(defaults);
    }
    return environment;
  }
}
