package com.queryhub.app.config.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.queryhub.app.QueryHubApplication;
import com.queryhub.app.support.TestAdminToken;
import com.queryhub.app.support.TestComponentScanExclusion;
import com.queryhub.app.support.TestMasterKey;
import com.queryhub.app.support.WithoutMetadataDatabase;
import com.queryhub.application.secret.SecretCipher;
import com.queryhub.application.secret.SecretContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * P1-04 AC12: 마스터 키가 없거나 틀리면 두 프로파일 모두 웹 서버가 포트를 열기 전에 기동이 멈춘다(지연 초기화를 켜도). 실제 QueryHubApplication
 * 을 Metadata DB 없이 루프백의 포트 0 으로 띄운다(AdminSecurityStartupTest 와 같은 방식). 관리자 토큰은 언제나 맞게 주므로 실패 원인은
 * 키뿐이다.
 */
class MasterKeyStartupTest {

  private static final String KEY = TestMasterKey.VALUE;

  @TempDir Path dir;

  @ParameterizedTest
  @ValueSource(strings = {"desktop", "server"})
  void startupWithoutAKeyFailsBeforeThePortOpens(String profile) {
    Attempt attempt = start(profile, false, Map.of());

    assertRefused(attempt, "QUERYHUB_MASTER_KEY");
    assertThat(attempt.message()).contains("QUERYHUB_MASTER_KEY_FILE");
  }

  @ParameterizedTest
  @ValueSource(strings = {"desktop", "server"})
  void startupWithoutAKeyFailsAlsoWithLazyInitialization(String profile) {
    assertRefused(start(profile, true, Map.of()), "QUERYHUB_MASTER_KEY");
  }

  @ParameterizedTest
  @ValueSource(strings = {"desktop", "server"})
  void aValidKeyFromTheEnvironmentStartsAndProvidesTheCipher(String profile) throws IOException {
    Path file = write(KEY.toUpperCase(Locale.ROOT) + "\r\n");
    for (Map<String, Object> variables :
        List.<Map<String, Object>>of(
            Map.of("QUERYHUB_MASTER_KEY", KEY),
            Map.of("QUERYHUB_MASTER_KEY_FILE", file.toString()))) {
      Attempt attempt = start(profile, true, variables);
      assertThat(attempt.failure()).isNull();
      assertThat(attempt.webServerStarted()).isTrue();
      assertThat(attempt.roundTrip()).isEqualTo("round-trip");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"desktop", "server"})
  void bothSourcesAreRefused(String profile) throws IOException {
    Path file = write(KEY);
    Attempt attempt =
        start(
            profile,
            false,
            Map.of("QUERYHUB_MASTER_KEY", KEY, "QUERYHUB_MASTER_KEY_FILE", file.toString()));

    assertRefused(attempt, "QUERYHUB_MASTER_KEY_FILE", file.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"desktop", "server"})
  void keyOnTheCommandLineIsRefused(String profile) {
    Attempt attempt =
        start(profile, false, Map.of("QUERYHUB_MASTER_KEY", KEY), "--queryhub.master-key=" + KEY);

    assertRefused(attempt, "queryhub.master-key");
    assertThat(attempt.message()).contains("명령행 인자");
  }

  @ParameterizedTest
  @ValueSource(strings = {"desktop", "server"})
  void keyInSpringApplicationJsonOnTheCommandLineIsRefused(String profile) {
    Attempt attempt =
        start(
            profile,
            false,
            Map.of(),
            "--spring.application.json={\"queryhub\":{\"master-key\":\"" + KEY + "\"}}");

    assertRefused(attempt, "queryhub.master-key");
    assertThat(attempt.message()).contains("spring.application.json");
  }

  @ParameterizedTest
  @ValueSource(strings = {"desktop", "server"})
  void keyAsASystemPropertyIsRefused(String profile) {
    assertSystemPropertyRefused(profile, "queryhub.master-key", KEY, "JVM 시스템 속성");
  }

  @ParameterizedTest
  @ValueSource(strings = {"desktop", "server"})
  void keyInSpringApplicationJsonAsASystemPropertyIsRefused(String profile) {
    assertSystemPropertyRefused(
        profile,
        "spring.application.json",
        "{\"queryhub\":{\"master-key\":\"" + KEY + "\"}}",
        "spring.application.json");
  }

  @ParameterizedTest
  @ValueSource(strings = {"desktop", "server"})
  void badKeyFilesAreRefused(String profile) throws IOException {
    Path missing = dir.resolve("missing.key");
    Path directory = Files.createDirectories(dir.resolve("a-directory"));
    Path empty = write("");
    Path tooLarge = write(KEY + "\n" + "#".repeat(4096));
    for (Path file : List.of(missing, directory, empty, tooLarge)) {
      Attempt attempt = start(profile, false, Map.of("QUERYHUB_MASTER_KEY_FILE", file.toString()));
      assertRefused(attempt, "QUERYHUB_MASTER_KEY_FILE", file.toString());
    }
  }

  @Test
  void unreadableKeyFileIsRefused() throws IOException {
    Path file = write(KEY);
    assertThat(file.toFile().setReadable(false, false)).isTrue();
    try {
      assertThat(Files.isReadable(file)).as("테스트는 root 가 아닌 사용자로 돈다").isFalse();
      Attempt attempt = start("server", false, Map.of("QUERYHUB_MASTER_KEY_FILE", file.toString()));
      assertRefused(attempt, "QUERYHUB_MASTER_KEY_FILE", file.toString());
      assertThat(attempt.message()).contains("읽을 수 없다");
    } finally {
      assertThat(file.toFile().setReadable(true, true)).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"desktop", "server"})
  void malformedWeakAndAdminTokenKeysAreRefused(String profile) throws IOException {
    String shortKey = KEY.substring(1);
    String weak = "00".repeat(32);
    for (String value : List.of(shortKey, KEY + "0", KEY.replace('a', 'g'), weak)) {
      assertRefused(
          start(profile, false, Map.of("QUERYHUB_MASTER_KEY", value)),
          "QUERYHUB_MASTER_KEY",
          value);
      Path file = write(value + "\n");
      assertRefused(
          start(profile, false, Map.of("QUERYHUB_MASTER_KEY_FILE", file.toString())),
          "QUERYHUB_MASTER_KEY_FILE",
          value,
          file.toString());
    }
    // 관리자 토큰과 같은 값: 토큰을 키 값으로 덮어쓴다(기본 속성보다 앞선 환경변수 소스).
    Attempt sameAsToken =
        start(
            profile,
            false,
            Map.of(
                "QUERYHUB_MASTER_KEY", KEY, "QUERYHUB_ADMIN_TOKEN", KEY.toUpperCase(Locale.ROOT)));
    assertRefused(sameAsToken, "QUERYHUB_ADMIN_TOKEN");
    assertThat(sameAsToken.message()).contains("관리자 토큰").contains("QUERYHUB_MASTER_KEY");
  }

  private void assertSystemPropertyRefused(
      String profile, String property, String value, String expected) {
    String previous = System.getProperty(property);
    System.setProperty(property, value);
    try {
      Attempt attempt = start(profile, false, Map.of());
      assertRefused(attempt, "queryhub.master-key");
      assertThat(attempt.message()).contains(expected);
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  private void assertRefused(Attempt attempt, String named, String... values) {
    assertThat(attempt.failure()).as("기동이 실패해야 한다").isNotNull();
    assertThat(attempt.failure()).rootCause().isInstanceOf(IllegalStateException.class);
    assertThat(attempt.message())
        .startsWith("마스터 키 설정이 잘못되어 런타임을 시작하지 않는다.")
        .contains(named)
        .doesNotContainIgnoringCase(KEY)
        .doesNotContain(dir.toString());
    for (String value : values) {
      assertThat(attempt.message()).doesNotContainIgnoringCase(value);
    }
    assertThat(attempt.webServerStarted()).as("포트를 열기 전에 실패해야 한다").isFalse();
  }

  private Path write(String content) throws IOException {
    Path file = Files.createTempFile(dir, "master", ".key");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  /**
   * 앱을 띄운다. 키 변수는 OS 환경변수와 같은 종류의 속성 소스(SystemEnvironmentPropertySource)로 넣어 느슨한 바인딩을 그대로 거친다. 이
   * 소스는 프로세스 목록에 보이는 소스가 아니다.
   */
  private static Attempt start(
      String profile, boolean lazy, Map<String, Object> variables, String... extraArgs) {
    AtomicBoolean webServerStarted = new AtomicBoolean();
    List<String> roundTrip = new ArrayList<>();
    ApplicationListener<ApplicationEvent> listener =
        event -> {
          if (event instanceof WebServerInitializedEvent) {
            webServerStarted.set(true);
          }
        };
    SpringApplication application =
        new SpringApplicationBuilder(QueryHubApplication.class)
            .web(WebApplicationType.SERVLET)
            .lazyInitialization(lazy)
            .profiles(profile)
            .properties(WithoutMetadataDatabase.PROPERTY, TestAdminToken.PROPERTY)
            .initializers(
                TestComponentScanExclusion::register,
                context ->
                    context
                        .getEnvironment()
                        .getPropertySources()
                        .addFirst(
                            new SystemEnvironmentPropertySource(
                                "qhTest-"
                                    + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                new HashMap<>(variables))))
            .listeners(listener)
            .build();
    List<String> args = new ArrayList<>(List.of("--server.address=127.0.0.1", "--server.port=0"));
    args.addAll(List.of(extraArgs));
    Throwable failure =
        catchThrowable(
            () -> {
              try (ConfigurableApplicationContext context =
                  application.run(args.toArray(String[]::new))) {
                SecretCipher cipher = context.getBean(SecretCipher.class);
                SecretContext secretContext = SecretContext.databaseConnectionPassword("startup");
                roundTrip.add(
                    cipher.decrypt(cipher.encrypt("round-trip", secretContext), secretContext));
              }
            });
    return new Attempt(
        failure, webServerStarted.get(), roundTrip.isEmpty() ? null : roundTrip.get(0));
  }

  private record Attempt(
      @Nullable Throwable failure, boolean webServerStarted, @Nullable String roundTrip) {

    String message() {
      Throwable root = failure;
      while (root != null && root.getCause() != null) {
        root = root.getCause();
      }
      return root == null ? "" : String.valueOf(root.getMessage());
    }
  }
}
