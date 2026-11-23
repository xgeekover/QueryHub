package com.queryhub.app.config.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.queryhub.adapter.crypto.MasterKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
 * P1-04 F4, AC11, AC12: 마스터 키의 출처와 형식. 실제 OS 환경과 시스템 속성 대신 같은 이름의 속성 소스를 넣어 느슨한
 * 바인딩(QUERYHUB_MASTER_KEY → queryhub.master-key)과 프로세스 목록 검사를 그대로 거친다(AdminTokenSourceTest 와 같은
 * 방식).
 */
class MasterKeySourceTest {

  /** 테스트 전용 키. NOT A SECRET. */
  private static final String VALID =
      "8f14e45fceea167a5a36dedd4bea2543c4ca4238a0b923820dcc509a6f75849b";

  /** 64 자리 16진수이면서 관리자 토큰 형식(b64token, 32-512 자)에도 맞는 값. NOT A SECRET. */
  private static final String SHARED =
      "d3b07384d113edec49eaa6238ad5ff00c157c4a1b7d2e9f6a8b5c4d3e2f1a0b9";

  private static final String ADMIN_TOKEN = "unit-test-admin-token-for-master-key-tests-0001";

  @TempDir Path dir;

  @Test
  void validEnvironmentVariableLoads() {
    MasterKey key = MasterKeySource.load(env(Map.of("QUERYHUB_MASTER_KEY", VALID)));

    assertThat(key.kid()).matches("[0-9a-f]{16}");
    assertThat(VALID).doesNotStartWith(key.kid());
  }

  @Test
  void hexIsCaseInsensitive() {
    String lower = MasterKeySource.load(env(Map.of("QUERYHUB_MASTER_KEY", VALID))).kid();
    String upper =
        MasterKeySource.load(env(Map.of("QUERYHUB_MASTER_KEY", VALID.toUpperCase(Locale.ROOT))))
            .kid();
    assertThat(upper).isEqualTo(lower);
    assertThat(lower)
        .isEqualTo(new MasterKey(HexFormat.of().parseHex(VALID)).kid())
        .as("같은 키는 같은 키 ID");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "\n", "\r\n"})
  void validFileLoadsWithOneTrailingLineEndStripped(String lineEnd) throws IOException {
    Path file = write(VALID + lineEnd);

    MasterKey key = MasterKeySource.load(fileEnv(file));

    assertThat(key.kid()).isEqualTo(new MasterKey(HexFormat.of().parseHex(VALID)).kid());
  }

  @Test
  void bothSourcesAreRefused() throws IOException {
    Path file = write(VALID);
    assertRefused(
        env(Map.of("QUERYHUB_MASTER_KEY", VALID, "QUERYHUB_MASTER_KEY_FILE", file.toString())),
        "QUERYHUB_MASTER_KEY_FILE",
        file.toString());
  }

  @Test
  void neitherSourceIsRefused() {
    assertRefused(env(Map.of()), "QUERYHUB_MASTER_KEY");
    assertRefused(env(Map.of()), "QUERYHUB_MASTER_KEY_FILE");
    assertRefused(env(Map.of()), "queryhub.master-key");
  }

  @Test
  void missingEmptyTooLargeDirectoryAndDoubleLineEndFilesAreRefused() throws IOException {
    Path missing = dir.resolve("missing.key");
    assertRefused(fileEnv(missing), "QUERYHUB_MASTER_KEY_FILE", missing.toString());

    Path empty = write("");
    assertRefused(fileEnv(empty), "QUERYHUB_MASTER_KEY_FILE", empty.toString());

    Path tooLarge = write(VALID + "\n" + "#".repeat(4096));
    assertThat(Files.size(tooLarge)).isGreaterThan(4096);
    assertRefused(fileEnv(tooLarge), "QUERYHUB_MASTER_KEY_FILE", tooLarge.toString());

    Path directory = Files.createDirectory(dir.resolve("key-directory"));
    assertRefused(fileEnv(directory), "QUERYHUB_MASTER_KEY_FILE", directory.toString());

    Path doubleLineEnd = write(VALID + "\n\n");
    assertRefused(fileEnv(doubleLineEnd), "QUERYHUB_MASTER_KEY_FILE", doubleLineEnd.toString());

    Path withSpace = write(" " + VALID);
    assertRefused(fileEnv(withSpace), "QUERYHUB_MASTER_KEY_FILE", withSpace.toString());

    assertRefused(env(Map.of("QUERYHUB_MASTER_KEY_FILE", "")), "QUERYHUB_MASTER_KEY_FILE");
    assertRefused(
        env(Map.of("QUERYHUB_MASTER_KEY_FILE", "bad\u0000path")), "QUERYHUB_MASTER_KEY_FILE");
  }

  @Test
  void aFileOfExactly4096BytesIsReadButItsFormatIsChecked() throws IOException {
    Path exactly = write("a".repeat(4096));
    String message = message(fileEnv(exactly));
    assertThat(message).contains("64 자리 16진수").doesNotContain("4096 바이트보다 크다");
  }

  @Test
  void unreadableFileIsRefused() throws IOException {
    Path file = write(VALID);
    Files.setPosixFilePermissions(file, EnumSet.noneOf(PosixFilePermission.class));
    try {
      // root 로 돌리면 권한과 관계없이 읽으므로 이 전제가 실패한다(건너뛰지 않는다).
      assertThat(Files.isReadable(file)).as("테스트는 root 가 아닌 사용자로 돈다").isFalse();
      assertRefused(fileEnv(file), "QUERYHUB_MASTER_KEY_FILE", file.toString());
      assertThat(message(fileEnv(file))).contains("읽을 수 없다");
    } finally {
      Files.setPosixFilePermissions(
          file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "8f14e45fceea167a5a36dedd4bea2543c4ca4238a0b923820dcc509a6f75849",
        "8f14e45fceea167a5a36dedd4bea2543c4ca4238a0b923820dcc509a6f75849b0",
        "8f14e45fceea167a5a36dedd4bea2543c4ca4238a0b923820dcc509a6f75849g",
        "8f14e45fceea167a5a36dedd4bea2543 4ca4238a0b923820dcc509a6f75849b",
        " 8f14e45fceea167a5a36dedd4bea2543c4ca4238a0b923820dcc509a6f75849b",
        "0x8f14e45fceea167a5a36dedd4bea2543c4ca4238a0b923820dcc509a6f7584",
        "j3RP5DjqFnpaNt7dS+olQ8TKQjigs5I4Dcxu4m91hJs=",
        "8f14e45fceea167a5a36dedd4bea2543c4ca4238a0b923820dcc509a6f75849b\n"
      })
  void valuesThatAreNotExactly64HexDigitsAreRefusedWithoutEchoingThem(String value) {
    assertRefused(env(Map.of("QUERYHUB_MASTER_KEY", value)), "QUERYHUB_MASTER_KEY", value);
    assertThat(message(env(Map.of("QUERYHUB_MASTER_KEY", value)))).contains("64 자리 16진수");
  }

  @ParameterizedTest
  @ValueSource(strings = {"00", "ff", "FF", "aa", "11"})
  void keysWhoseBytesAreAllIdenticalAreRefused(String pair) throws IOException {
    String weak = pair.repeat(32);
    assertRefused(env(Map.of("QUERYHUB_MASTER_KEY", weak)), "QUERYHUB_MASTER_KEY", weak);
    assertThat(message(env(Map.of("QUERYHUB_MASTER_KEY", weak)))).contains("모든 바이트가 같다");
    Path file = write(weak + "\n");
    assertRefused(fileEnv(file), "QUERYHUB_MASTER_KEY_FILE", weak, file.toString());
  }

  @Test
  void aKeyEqualToTheAdminTokenIsRefusedInAnyCase() throws IOException {
    for (String key : new String[] {SHARED, SHARED.toUpperCase(Locale.ROOT)}) {
      StandardEnvironment environment =
          env(Map.of("QUERYHUB_MASTER_KEY", key, "QUERYHUB_ADMIN_TOKEN", SHARED));
      assertRefused(environment, "QUERYHUB_ADMIN_TOKEN", SHARED);
      assertThat(message(environment)).contains("관리자 토큰");
    }
    Path tokenFile = write(SHARED + "\n");
    StandardEnvironment fromFiles =
        env(
            Map.of(
                "QUERYHUB_MASTER_KEY_FILE", write(SHARED).toString(),
                "QUERYHUB_ADMIN_TOKEN_FILE", tokenFile.toString()));
    assertRefused(fromFiles, "QUERYHUB_ADMIN_TOKEN", SHARED, tokenFile.toString());
  }

  @Test
  void aDifferentAdminTokenIsAccepted() {
    StandardEnvironment environment =
        env(Map.of("QUERYHUB_MASTER_KEY", VALID, "QUERYHUB_ADMIN_TOKEN", ADMIN_TOKEN));
    assertThat(MasterKeySource.load(environment).kid()).hasSize(16);
  }

  @Test
  void commandLineArgumentIsRefused() {
    StandardEnvironment environment = env(Map.of());
    environment
        .getPropertySources()
        .addFirst(new SimpleCommandLinePropertySource("--queryhub.master-key=" + VALID));

    assertRefused(environment, "queryhub.master-key", VALID);
    assertThat(message(environment)).contains("명령행 인자");
  }

  @Test
  void commandLineArgumentIsRefusedEvenWhenTheEnvironmentAlsoHasAKey() {
    StandardEnvironment environment = env(Map.of("QUERYHUB_MASTER_KEY", VALID));
    environment
        .getPropertySources()
        .addFirst(new SimpleCommandLinePropertySource("--queryhub.masterKey=" + VALID));

    assertRefused(environment, "queryhub.master-key", VALID);
  }

  @Test
  void systemPropertyIsRefusedAlsoInARelaxedSpelling() {
    for (String name : new String[] {"queryhub.master-key", "queryhub.masterKey"}) {
      Map<String, Object> systemProperties = new HashMap<>();
      systemProperties.put(name, VALID);
      StandardEnvironment environment = env(Map.of());
      environment
          .getPropertySources()
          .replace(
              StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
              new MapPropertySource(
                  StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, systemProperties));

      assertRefused(environment, "queryhub.master-key", VALID);
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
                "--spring.application.json={\"queryhub\":{\"master-key\":\"" + VALID + "\"}}"));
    new SpringApplicationJsonEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());

    assertRefused(environment, "queryhub.master-key", VALID);
    assertThat(message(environment)).contains("spring.application.json");
  }

  @Test
  void springApplicationJsonFromASystemPropertyIsRefused() {
    Map<String, Object> systemProperties = new HashMap<>();
    systemProperties.put(
        "spring.application.json", "{\"queryhub\":{\"master-key\":\"" + VALID + "\"}}");
    StandardEnvironment environment = env(Map.of());
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
            new MapPropertySource(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, systemProperties));
    new SpringApplicationJsonEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());

    assertRefused(environment, "queryhub.master-key", VALID);
    assertThat(message(environment)).contains("spring.application.json");
  }

  @Test
  void springApplicationJsonFromTheOsEnvironmentIsAllowed() {
    StandardEnvironment environment =
        env(Map.of("SPRING_APPLICATION_JSON", "{\"queryhub\":{\"master-key\":\"" + VALID + "\"}}"));
    new SpringApplicationJsonEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());

    assertThat(MasterKeySource.load(environment).kid()).hasSize(16);
  }

  @Test
  void everyRefusalExplainsHowToSetTheKey() {
    assertThat(message(env(Map.of())))
        .startsWith("마스터 키 설정이 잘못되어 런타임을 시작하지 않는다.")
        .contains("openssl rand -hex 32");
  }

  private void assertRefused(StandardEnvironment environment, String named, String... secrets) {
    String message = message(environment);
    assertThat(message).contains(named);
    for (String secret : secrets) {
      if (!secret.isBlank()) {
        assertThat(message)
            .as("메시지에 값이 없어야 한다")
            .doesNotContain(secret.strip())
            .doesNotContain(secret.strip().toLowerCase(Locale.ROOT))
            .doesNotContain(secret.strip().toUpperCase(Locale.ROOT));
      }
    }
    assertThat(message)
        .doesNotContain(VALID)
        .doesNotContain(VALID.toUpperCase(Locale.ROOT))
        .doesNotContain(dir.toString());
  }

  private static String message(StandardEnvironment environment) {
    Throwable failure = catchThrowable(() -> MasterKeySource.load(environment));
    assertThat(failure).isInstanceOf(IllegalStateException.class);
    assertThat(failure.getCause()).isNull();
    return failure.getMessage();
  }

  private StandardEnvironment fileEnv(Path file) {
    return env(Map.of("QUERYHUB_MASTER_KEY_FILE", file.toString()));
  }

  private Path write(String content) throws IOException {
    Path file = Files.createTempFile(dir, "master", ".key");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  /** OS 환경과 시스템 속성을 주어진 값으로 바꾼 환경(실제 OS 환경의 영향을 받지 않는다). */
  private static StandardEnvironment env(Map<String, Object> variables) {
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
