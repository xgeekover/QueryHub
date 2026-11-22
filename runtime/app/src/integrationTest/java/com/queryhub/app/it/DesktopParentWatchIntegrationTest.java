package com.queryhub.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.app.it.support.IntegrationHttp;
import com.queryhub.app.it.support.MetadataMariaDb;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AC-16: 실행 JAR 을 Tauri 셸처럼 띄운다(desktop 프로파일, 포트 명시, 접속 정보는 환경변수로만). 부모 자리에는 도우미 프로세스를 두고 그 PID 를
 * QUERYHUB_DESKTOP_PARENT_PID 로 넘긴다.
 *
 * <ul>
 *   <li>도우미가 죽으면 JAR 프로세스가 20 초 안에 끝나고 포트가 다시 열린다(셸이 SIGKILL 로 죽은 경우와 같다).
 *   <li>JAR 안의 application-desktop.yml 이 CORS 출처를 담는다: Origin tauri://localhost 에 ACAO 로 답한다.
 *   <li>값이 잘못되면(abc) 포트를 열지 않고 0 이 아닌 코드로 끝나며 출력이 변수 이름을 말한다.
 * </ul>
 *
 * <p>JAR 은 이 테스트 JVM 의 자식이지만 도우미의 자식은 아니다. 그래서 JAR 의 감시는 운영과 같이 자식이 아닌 프로세스를 폴링하는 경로를 탄다.
 */
class DesktopParentWatchIntegrationTest {

  private static final String BOOT_JAR_PROPERTY = "queryhub.it.boot-jar";

  private static final String PARENT_PID_VARIABLE = "QUERYHUB_DESKTOP_PARENT_PID";

  private static final String DATABASE = "queryhub_parent_watch";

  private static final String TAURI_ORIGIN = "tauri://localhost";

  private static final Duration HEALTHY_WITHIN = Duration.ofSeconds(60);

  private static final Duration EXITS_AFTER_PARENT_WITHIN = Duration.ofSeconds(20);

  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .build();

  private static Path bootJar;

  private static boolean databaseCreated;

  @TempDir private Path workDir;

  private final List<Process> processes = new ArrayList<>();

  @BeforeAll
  static void findBootJar() {
    String path = System.getProperty(BOOT_JAR_PROPERTY);
    assertThat(path).as("Gradle 이 %s 를 넘겨야 한다", BOOT_JAR_PROPERTY).isNotBlank();
    bootJar = Path.of(path);
    assertThat(bootJar).as("bootJar 산출물").isRegularFile();
  }

  @AfterEach
  void stopProcesses() throws InterruptedException {
    for (Process process : processes) {
      if (process.isAlive()) {
        process.destroy();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
          process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
      }
    }
  }

  @Test
  void runtimeExitsAfterTheParentDiesAndAnswersTheTauriOrigin() throws Exception {
    Process parent = startHelper();
    int port = freeLoopbackPort();
    Path log = workDir.resolve("runtime.log");

    Process runtime = startRuntime(log, port, metadataEnvironment(), String.valueOf(parent.pid()));
    awaitHealthy(runtime, port, log);

    HttpResponse<String> cors =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin/api/v1/runtime"))
                .timeout(Duration.ofSeconds(10))
                .header("Origin", TAURI_ORIGIN)
                .header("Authorization", "Bearer " + IntegrationHttp.ADMIN_TOKEN)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(cors.statusCode()).as("body=%s", cors.body()).isEqualTo(200);
    assertThat(cors.headers().firstValue("Access-Control-Allow-Origin")).hasValue(TAURI_ORIGIN);
    assertThat(IntegrationHttp.json(cors).path("data").path("mode").stringValue())
        .isEqualTo("desktop");

    assertThat(runtime.isAlive()).as("부모가 살아 있으면 런타임도 살아 있다").isTrue();
    Instant killed = Instant.now();
    parent.toHandle().destroyForcibly();

    assertThat(runtime.waitFor(EXITS_AFTER_PARENT_WITHIN.toSeconds(), TimeUnit.SECONDS))
        .as("부모가 죽으면 %d 초 안에 끝나야 한다:\n%s", EXITS_AFTER_PARENT_WITHIN.toSeconds(), read(log))
        .isTrue();
    Duration took = Duration.between(killed, Instant.now());
    assertThat(runtime.exitValue()).as("정상 종료(halt 가 아님), %s 걸림", took).isZero();
    try (ServerSocket rebound = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
      assertThat(rebound.getLocalPort()).as("포트가 다시 열린다").isEqualTo(port);
    }
    String output = read(log);
    assertThat(output).contains(PARENT_PID_VARIABLE);
    assertThat(output).as("출력에 비밀번호가 없어야 한다").doesNotContain(MetadataMariaDb.PASSWORD);
  }

  @Test
  void invalidParentPidExitsNonZeroWithoutOpeningThePort() throws Exception {
    int port = freeLoopbackPort();
    Path log = workDir.resolve("invalid.log");

    Process runtime = startRuntime(log, port, metadataEnvironment(), "abc");
    boolean connected = false;
    Instant deadline = Instant.now().plus(HEALTHY_WITHIN);
    while (runtime.isAlive() && Instant.now().isBefore(deadline)) {
      connected |= canConnect(port);
      Thread.sleep(50);
    }

    assertThat(runtime.isAlive()).as("스스로 끝나야 한다").isFalse();
    assertThat(runtime.exitValue()).isNotZero();
    assertThat(connected).as("포트를 열지 않는다").isFalse();
    String output = read(log);
    assertThat(output).contains(PARENT_PID_VARIABLE).doesNotContain("Tomcat started");
  }

  private static Map<String, String> metadataEnvironment() throws Exception {
    createDatabaseOnce();
    return Map.of(
        "QUERYHUB_METADATA_DB_HOST",
        MetadataMariaDb.host(),
        "QUERYHUB_METADATA_DB_PORT",
        String.valueOf(MetadataMariaDb.port()),
        "QUERYHUB_METADATA_DB_NAME",
        DATABASE,
        "QUERYHUB_METADATA_DB_USERNAME",
        MetadataMariaDb.USERNAME,
        "QUERYHUB_METADATA_DB_PASSWORD",
        MetadataMariaDb.PASSWORD);
  }

  private static synchronized void createDatabaseOnce() throws Exception {
    if (!databaseCreated) {
      MetadataMariaDb.createEmptyDatabase(DATABASE);
      databaseCreated = true;
    }
  }

  /**
   * 셸과 같은 명령행: -jar JAR --spring.profiles.active=desktop --server.port=PORT. 물려받은 QUERYHUB_*,
   * SPRING_* 는 지운다.
   */
  private Process startRuntime(
      Path log, int port, Map<String, String> metadataEnvironment, String parentPid)
      throws IOException {
    ProcessBuilder builder =
        new ProcessBuilder(
                List.of(
                    javaExecutable(),
                    "-jar",
                    bootJar.toString(),
                    "--spring.profiles.active=desktop",
                    "--server.port=" + port))
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    Map<String, String> env = builder.environment();
    env.keySet().removeIf(name -> name.startsWith("QUERYHUB_") || name.startsWith("SPRING_"));
    env.putAll(metadataEnvironment);
    // 셸이 하는 것처럼 관리자 토큰을 환경변수로 넘긴다 (argv 에는 넣지 않는다).
    env.put("QUERYHUB_ADMIN_TOKEN", IntegrationHttp.ADMIN_TOKEN);
    env.put(PARENT_PID_VARIABLE, parentPid);
    Process process = builder.start();
    processes.add(process);
    return process;
  }

  /** 부모 자리의 도우미: 잠만 자는 Java 소스 프로그램. 테스트가 끝나면 지운다. */
  private Process startHelper() throws IOException {
    Path source = workDir.resolve("ParentHelper.java");
    Files.writeString(
        source,
        "class ParentHelper { public static void main(String[] args) throws Exception {"
            + " Thread.sleep(600_000L); } }",
        StandardCharsets.UTF_8);
    Process helper =
        new ProcessBuilder(javaExecutable(), source.toString())
            .redirectErrorStream(true)
            .redirectOutput(workDir.resolve("helper.log").toFile())
            .start();
    processes.add(helper);
    return helper;
  }

  /** /actuator/health 가 200 이 될 때까지 기다린다. 프로세스가 먼저 끝나거나 시간이 지나면 출력과 함께 실패한다. */
  private static void awaitHealthy(Process process, int port, Path log) throws Exception {
    Instant deadline = Instant.now().plus(HEALTHY_WITHIN);
    while (Instant.now().isBefore(deadline)) {
      if (!process.isAlive()) {
        throw new AssertionError("실행 JAR 이 끝났다 (exit " + process.exitValue() + "):\n" + read(log));
      }
      try {
        if (IntegrationHttp.get(port, "/actuator/health").statusCode() == 200) {
          return;
        }
      } catch (IOException notYetListening) {
        // 아직 포트를 열지 않았다.
      }
      Thread.sleep(250);
    }
    throw new AssertionError(HEALTHY_WITHIN.toSeconds() + " 초 안에 healthy 가 되지 않았다:\n" + read(log));
  }

  private static boolean canConnect(int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 200);
      return true;
    } catch (IOException refused) {
      return false;
    }
  }

  private static String read(Path log) throws IOException {
    return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
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
