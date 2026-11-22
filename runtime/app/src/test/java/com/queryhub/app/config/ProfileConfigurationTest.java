package com.queryhub.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.adapter.web.admin.security.AdminAccessPolicy;
import com.queryhub.app.support.TestAdminToken;
import com.queryhub.app.support.WithoutMetadataDatabase;
import com.queryhub.application.runtime.GetRuntimeInfoUseCase;
import com.queryhub.domain.runtime.RuntimeMode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC-12: 프로파일별 바인딩 주소와 포트. 웹 서버를 띄우지 않는다(WebEnvironment.NONE), 그래서 포트를 열지 않는다.
 *
 * <p>각 중첩 클래스가 설정이 다른 컨텍스트 하나씩을 만든다.
 */
class ProfileConfigurationTest {

  @Nested
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.NONE,
      properties = TestAdminToken.PROPERTY)
  @ActiveProfiles("desktop")
  @WithoutMetadataDatabase
  class DesktopProfile {

    @Autowired private Environment environment;
    @Autowired private GetRuntimeInfoUseCase runtimeInfo;
    @Autowired private AdminAccessPolicy policy;

    @Test
    void bindsLoopbackOnDesktopPort() {
      assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
      assertThat(environment.getProperty("server.port")).isEqualTo("18580");
      assertThat(runtimeInfo.getRuntimeInfo().mode()).isEqualTo(RuntimeMode.DESKTOP);
    }

    @Test
    void adminAcceptsLoopbackOnlyAndChecksTheHost() {
      // P1-03 AC6: desktop 의 기본값.
      assertThat(environment.getProperty("queryhub.admin.allow-remote")).isEqualTo("false");
      assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("none");
      assertThat(policy.allowRemote()).isFalse();
      assertThat(policy.hostCheck()).isTrue();
      assertThat(policy.hosts().allowedNames()).containsExactly("localhost", "127.0.0.1", "[::1]");
    }
  }

  @Nested
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.NONE,
      properties = TestAdminToken.PROPERTY)
  @ActiveProfiles("server")
  @WithoutMetadataDatabase
  class ServerProfile {

    @Autowired private Environment environment;
    @Autowired private GetRuntimeInfoUseCase runtimeInfo;
    @Autowired private AdminAccessPolicy policy;

    @Test
    void bindsAllInterfacesOnServerPort() {
      assertThat(environment.getProperty("server.address")).isEqualTo("0.0.0.0");
      assertThat(environment.getProperty("server.port")).isEqualTo("8080");
      assertThat(runtimeInfo.getRuntimeInfo().mode()).isEqualTo(RuntimeMode.SERVER);
    }

    @Test
    void adminAllowsRemoteAndSkipsTheHostCheckByDefault() {
      // P1-03 AC6, AC7: server 의 기본값. 토큰은 그대로 필요하다.
      assertThat(environment.getProperty("queryhub.admin.allow-remote")).isEqualTo("true");
      assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("none");
      assertThat(policy.allowRemote()).isTrue();
      assertThat(policy.hostCheck()).isFalse();
    }
  }

  @Nested
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.NONE,
      properties = TestAdminToken.PROPERTY)
  @WithoutMetadataDatabase
  class NoActiveProfile {

    @Autowired private Environment environment;
    @Autowired private GetRuntimeInfoUseCase runtimeInfo;
    @Autowired private AdminAccessPolicy policy;

    @Test
    void fallsBackToDesktopValues() {
      assertThat(environment.getActiveProfiles()).isEmpty();
      assertThat(environment.getDefaultProfiles()).containsExactly("desktop");
      assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
      assertThat(environment.getProperty("server.port")).isEqualTo("18580");
      assertThat(runtimeInfo.getRuntimeInfo().mode()).isEqualTo(RuntimeMode.DESKTOP);
    }

    @Test
    void sharedSettingsExposeOnlyHealthAndHideErrorInternals() {
      assertThat(environment.getProperty("spring.application.name")).isEqualTo("queryhub-runtime");
      assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
          .isEqualTo("health");
      assertThat(environment.getProperty("management.endpoints.web.discovery.enabled"))
          .isEqualTo("false");
      assertThat(environment.getProperty("management.endpoint.health.show-details"))
          .isEqualTo("never");
      assertThat(environment.getProperty("management.endpoint.health.probes.enabled"))
          .isEqualTo("true");
      assertThat(environment.getProperty("spring.web.error.include-stacktrace")).isEqualTo("never");
      assertThat(environment.getProperty("spring.web.error.include-message")).isEqualTo("never");
      assertThat(environment.getProperty("spring.web.error.include-binding-errors"))
          .isEqualTo("never");
      assertThat(environment.getProperty("spring.web.error.include-exception")).isEqualTo("false");
      assertThat(environment.getProperty("spring.web.resources.add-mappings")).isEqualTo("false");
    }
  }

  /**
   * L4: 바인딩 주소의 공통 기본값은 application.yml 의 루프백이다. desktop 프로파일 파일이 적용되지 않아도(다른 프로파일만 켠 경우) 루프백이고, 모든
   * 인터페이스로 넓히는 것은 server 프로파일뿐이다. 설정 파일만 읽는다(빈을 만들지 않으므로 모드 검사는 일어나지 않는다).
   */
  @Nested
  class SharedDefaults {

    private final ApplicationContextRunner configOnly =
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void unrelatedProfileStillBindsLoopback() {
      configOnly
          .withPropertyValues("spring.profiles.active=dev")
          .run(
              context -> {
                assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("dev");
                assertThat(context.getEnvironment().getProperty("server.address"))
                    .isEqualTo("127.0.0.1");
              });
    }

    @Test
    void onlyTheServerProfileWidensTheBindAddress() {
      configOnly
          .withPropertyValues("spring.profiles.active=dev,server")
          .run(
              context ->
                  assertThat(context.getEnvironment().getProperty("server.address"))
                      .isEqualTo("0.0.0.0"));
    }
  }
}
