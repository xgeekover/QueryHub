package com.queryhub.app.config.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * P1-03 AC8: 관리자 토큰의 출처와 형식. 실제 OS 환경과 시스템 속성 대신 같은 이름의 속성 소스를 넣어 느슨한 바인딩(QUERYHUB_ADMIN_TOKEN →
 * queryhub.admin.token)과 프로세스 목록 검사를 그대로 거친다.
 */
class AdminTokenSourceTest {

  /** 테스트 전용 값(실제 비밀이 아니다). */
  private static final String VALID = "unit-test-admin-token-ABCDEFGHIJKLMNOPQRSTUV";

  @TempDir Path dir;

  @Test
  void validEnvironmentVariableLoads() {
    assertThat(AdminTokenSource.load(env(Map.of("QUERYHUB_ADMIN_TOKEN", VALID)))).isEqualTo(VALID);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "\n", "\r\n"})
  void validFileLoadsWithOneTrailingLineEndStripped(String lineEnd) throws IOException {
    Path file = write(VALID + lineEnd);

    assertThat(AdminTokenSource.load(env(Map.of("QUERYHUB_ADMIN_TOKEN_FILE", file.toString()))))
        .isEqualTo(VALID);
  }

  @Test
  void boundaryLengthsAndB64TokenPaddingAreAccepted() {
    for (String token :
        new String[] {
          "a".repeat(32), "Z".repeat(512), "abc-._~+/" + "x".repeat(21) + "==", "0".repeat(64)
        }) {
      assertThat(AdminTokenSource.load(env(Map.of("QUERYHUB_ADMIN_TOKEN", token))))
          .isEqualTo(token);
    }
  }

  @Test
  void bothSourcesAreRefused() throws IOException {
    Path file = write(VALID);
    assertRefused(
        env(Map.of("QUERYHUB_ADMIN_TOKEN", VALID, "QUERYHUB_ADMIN_TOKEN_FILE", file.toString())),
        "QUERYHUB_ADMIN_TOKEN_FILE",
        VALID,
        file.toString());
  }

  @Test
  void neitherSourceIsRefused() {
    assertRefused(env(Map.of()), "QUERYHUB_ADMIN_TOKEN");
    assertRefused(env(Map.of()), "QUERYHUB_ADMIN_TOKEN_FILE");
  }

  @Test
  void missingEmptyTooLargeDirectoryAndDoubleLineEndFilesAreRefused() throws IOException {
    Path missing = dir.resolve("missing");
    assertRefused(fileEnv(missing), "QUERYHUB_ADMIN_TOKEN_FILE", missing.toString());
    Path empty = write("");
    assertRefused(fileEnv(empty), "QUERYHUB_ADMIN_TOKEN_FILE", empty.toString());
    Path large = write("a".repeat(AdminTokenSource.MAX_FILE_BYTES + 1));
    assertRefused(fileEnv(large), "QUERYHUB_ADMIN_TOKEN_FILE", "a".repeat(32));
    assertRefused(fileEnv(dir), "QUERYHUB_ADMIN_TOKEN_FILE", dir.toString());
    Path twoLines = write(VALID + "\n\n");
    assertRefused(fileEnv(twoLines), "QUERYHUB_ADMIN_TOKEN_FILE", VALID);
    assertRefused(env(Map.of("QUERYHUB_ADMIN_TOKEN_FILE", "")), "QUERYHUB_ADMIN_TOKEN_FILE");
  }

  @Test
  void unreadableFileIsRefused() throws IOException {
    Path file = write(VALID);
    Files.setPosixFilePermissions(file, EnumSet.noneOf(PosixFilePermission.class));
    try {
      assumeThat(Files.isReadable(file)).as("root 는 권한과 관계없이 읽는다").isFalse();
      assertRefused(fileEnv(file), "QUERYHUB_ADMIN_TOKEN_FILE", VALID, file.toString());
    } finally {
      Files.setPosixFilePermissions(
          file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "short-token-31-chars-xxxxxxxxxx",
        "with space in the middle of the token value",
        "with\"quote\"inside-the-admin-token-value-xx",
        "non-ascii-é-inside-the-admin-token-value-xx",
        "padding=in-the-middle-of-the-admin-token-xx",
        "tab\tinside-the-admin-token-value-xxxxxxxxxx",
        "comma,inside-the-admin-token-value-xxxxxxxx",
        ""
      })
  void invalidFormatsAreRefusedWithoutEchoingTheValue(String token) {
    assertRefused(env(Map.of("QUERYHUB_ADMIN_TOKEN", token)), "QUERYHUB_ADMIN_TOKEN", token);
  }

  @Test
  void tooLongTokenIsRefused() {
    String token = "b".repeat(AdminTokenSource.MAX_LENGTH + 1);
    assertRefused(
        env(Map.of("QUERYHUB_ADMIN_TOKEN", token)), "QUERYHUB_ADMIN_TOKEN", "b".repeat(32));
  }

  @Test
  void invalidTokenInAFileIsRefused() throws IOException {
    Path file = write("too-short");
    assertRefused(fileEnv(file), "QUERYHUB_ADMIN_TOKEN_FILE", "too-short");
  }

  @Test
  void commandLineArgumentIsRefused() {
    StandardEnvironment environment = env(Map.of());
    environment
        .getPropertySources()
        .addFirst(new SimpleCommandLinePropertySource("--queryhub.admin.token=" + VALID));
    assertRefused(environment, "queryhub.admin.token", VALID);
    assertThat(message(environment)).contains("명령행 인자");
  }

  @Test
  void systemPropertyIsRefusedAlsoInARelaxedSpelling() {
    for (String key : new String[] {"queryhub.admin.token", "queryhub.admin.TOKEN"}) {
      StandardEnvironment environment = env(Map.of());
      Map<String, Object> systemProperties = new HashMap<>();
      systemProperties.put(key, VALID);
      environment
          .getPropertySources()
          .replace(
              StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
              new MapPropertySource(
                  StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, systemProperties));
      assertRefused(environment, "queryhub.admin.token", VALID);
      assertThat(message(environment)).contains("JVM 시스템 속성");
    }
  }

  @Test
  void springApplicationJsonFromTheCommandLineIsRefused() {
    StandardEnvironment environment = env(Map.of());
    environment
        .getPropertySources()
        .addFirst(
            new SimpleCommandLinePropertySource(
                "--spring.application.json={\"queryhub\":{\"admin\":{\"token\":\""
                    + VALID
                    + "\"}}}"));
    new SpringApplicationJsonEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());

    assertRefused(environment, "queryhub.admin.token", VALID);
    assertThat(message(environment)).contains("spring.application.json");
  }

  @Test
  void springApplicationJsonFromTheOsEnvironmentIsAllowed() {
    StandardEnvironment environment =
        env(
            Map.of(
                "SPRING_APPLICATION_JSON",
                "{\"queryhub\":{\"admin\":{\"token\":\"" + VALID + "\"}}}"));
    new SpringApplicationJsonEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());

    assertThat(AdminTokenSource.load(environment)).isEqualTo(VALID);
  }

  private void assertRefused(StandardEnvironment environment, String named, String... secrets) {
    String message = message(environment);
    assertThat(message).contains(named);
    for (String secret : secrets) {
      if (!secret.isEmpty()) {
        assertThat(message).as("메시지에 값이 없어야 한다").doesNotContain(secret);
      }
    }
    assertThat(message).doesNotContain(VALID);
  }

  private static String message(StandardEnvironment environment) {
    Throwable failure = catchThrowable(() -> AdminTokenSource.load(environment));
    assertThat(failure).isInstanceOf(IllegalStateException.class);
    return failure.getMessage();
  }

  private StandardEnvironment fileEnv(Path file) {
    return env(Map.of("QUERYHUB_ADMIN_TOKEN_FILE", file.toString()));
  }

  private Path write(String content) throws IOException {
    Path file = Files.createTempFile(dir, "token", ".txt");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  /** OS 환경과 시스템 속성을 주어진 값으로 바꾼 환경(실제 OS 환경의 영향을 받지 않는다). */
  static StandardEnvironment env(Map<String, @Nullable Object> variables) {
    StandardEnvironment environment = new StandardEnvironment();
    MutablePropertySources sources = environment.getPropertySources();
    sources.replace(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        new SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new LinkedHashMap<>(variables)));
    sources.replace(
        StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
        new MapPropertySource(
            StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, new HashMap<>()));
    return environment;
  }
}
