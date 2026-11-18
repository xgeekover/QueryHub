package com.queryhub.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.app.it.support.IntegrationHttp;
import com.queryhub.app.it.support.MetadataMariaDb;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AC-17: 실행 JAR(runtime/app/build/libs/queryhub-runtime.jar)을 자식 프로세스로 띄운다.
 *
 * <ul>
 *   <li>접속 정보는 실제 환경변수 이름(QUERYHUB_METADATA_DB_*)으로만 넘긴다. 물려받은 QUERYHUB_*, SPRING_* 는 지운다. 명령행에는
 *       포트만 있다.
 *   <li>이 테스트만의 빈 데이터베이스를 쓴다. 마이그레이션이 JAR 안(BOOT-INF/lib 의 adapter-metadata)에서 발견되어 V1 이 적용되어야 한다.
 *   <li>비밀번호 변수가 없으면 프로세스는 0 이 아닌 코드로 끝나고 출력이 그 변수 이름을 말한다.
 * </ul>
 *
 * <p>프로세스는 테스트가 끝나면 반드시 종료한다. 작업 디렉터리와 출력 파일은 JUnit 임시 디렉터리에 둔다.
 */
class BootJarIntegrationTest {

  private static final String BOOT_JAR_PROPERTY = "queryhub.it.boot-jar";

  private static final String FRESH_DATABASE = "queryhub_bootjar";

  private static final Duration HEALTHY_WITHIN = Duration.ofSeconds(60);

  private static Path bootJar;

  @TempDir private Path workDir;

  @BeforeAll
  static void findBootJar() {
    String path = System.getProperty(BOOT_JAR_PROPERTY);
    assertThat(path).as("Gradle 이 %s 를 넘겨야 한다", BOOT_JAR_PROPERTY).isNotBlank();
    bootJar = Path.of(path);
    assertThat(bootJar).as("bootJar 산출물").isRegularFile();
  }

  @Test
  void bootJarStartsWithEnvironmentVariablesAndMigratesAFreshDatabase() throws Exception {
    MetadataMariaDb.createEmptyDatabase(FRESH_DATABASE);
    int port = freeLoopbackPort();
    Path log = workDir.resolve("healthy.log");

    Process process = start(log, port, metadataEnvironment(true));
    try {
      HttpResponse<String> health = awaitHealthy(process, port, log);

      assertThat(health.statusCode()).as("body=%s", health.body()).isEqualTo(200);
      assertThat(IntegrationHttp.json(health).path("status").stringValue()).isEqualTo("UP");
    } finally {
      stop(process);
    }

    try (Connection connection = MetadataMariaDb.userConnection(FRESH_DATABASE);
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT version, CAST(success AS UNSIGNED) FROM flyway_schema_history"
                    + " ORDER BY installed_rank")) {
      assertThat(rs.next()).as("JAR 안의 V1 이 적용되어야 한다").isTrue();
      assertThat(rs.getString(1)).isEqualTo("1");
      assertThat(rs.getLong(2)).isEqualTo(1L);
      assertThat(rs.next()).isFalse();
    }
    assertThat(Files.readString(log, StandardCharsets.UTF_8))
        .as("출력에 비밀번호가 없어야 한다")
        .doesNotContain(MetadataMariaDb.PASSWORD);
  }

  @Test
  void bootJarWithoutThePasswordVariableExitsNonZeroNamingIt() throws Exception {
    Path log = workDir.resolve("no-password.log");

    Process process = start(log, freeLoopbackPort(), metadataEnvironment(false));
    try {
      assertThat(process.waitFor(HEALTHY_WITHIN.toSeconds(), TimeUnit.SECONDS))
          .as("기동 실패로 스스로 끝나야 한다")
          .isTrue();
      assertThat(process.exitValue()).isNotZero();
    } finally {
      stop(process);
    }
    assertThat(Files.readString(log, StandardCharsets.UTF_8))
        .contains("QUERYHUB_METADATA_DB_PASSWORD");
  }

  private static Map<String, String> metadataEnvironment(boolean withPassword) {
    Map<String, String> env =
        new HashMap<>(
            Map.of(
                "QUERYHUB_METADATA_DB_HOST",
                MetadataMariaDb.host(),
                "QUERYHUB_METADATA_DB_PORT",
                String.valueOf(MetadataMariaDb.port()),
                "QUERYHUB_METADATA_DB_NAME",
                FRESH_DATABASE,
                "QUERYHUB_METADATA_DB_USERNAME",
                MetadataMariaDb.USERNAME));
    if (withPassword) {
      env.put("QUERYHUB_METADATA_DB_PASSWORD", MetadataMariaDb.PASSWORD);
    }
    return env;
  }

  private Process start(Path log, int port, Map<String, String> metadataEnvironment)
      throws IOException {
    ProcessBuilder builder =
        new ProcessBuilder(
                List.of(javaExecutable(), "-jar", bootJar.toString(), "--server.port=" + port))
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    Map<String, String> env = builder.environment();
    env.keySet().removeIf(name -> name.startsWith("QUERYHUB_") || name.startsWith("SPRING_"));
    env.putAll(metadataEnvironment);
    return builder.start();
  }

  /** /actuator/health 가 200 이 될 때까지 기다린다. 프로세스가 먼저 끝나거나 시간이 지나면 출력과 함께 실패한다. */
  private static HttpResponse<String> awaitHealthy(Process process, int port, Path log)
      throws Exception {
    Instant deadline = Instant.now().plus(HEALTHY_WITHIN);
    while (Instant.now().isBefore(deadline)) {
      if (!process.isAlive()) {
        throw new AssertionError(
            "실행 JAR 이 끝났다 (exit "
                + process.exitValue()
                + "):\n"
                + Files.readString(log, StandardCharsets.UTF_8));
      }
      try {
        HttpResponse<String> response = IntegrationHttp.get(port, "/actuator/health");
        if (response.statusCode() == 200) {
          return response;
        }
      } catch (IOException notYetListening) {
        // 아직 포트를 열지 않았다.
      }
      Thread.sleep(250);
    }
    throw new AssertionError(
        HEALTHY_WITHIN.toSeconds()
            + " 초 안에 healthy 가 되지 않았다:\n"
            + Files.readString(log, StandardCharsets.UTF_8));
  }

  private static void stop(Process process) throws InterruptedException {
    if (process.isAlive()) {
      process.destroy();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
      }
    }
  }

  /** 지금 JVM 의 java 실행 파일(Gradle toolchain 의 Java 21). */
  private static String javaExecutable() {
    return ProcessHandle.current()
        .info()
        .command()
        .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
  }

  private static int freeLoopbackPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
