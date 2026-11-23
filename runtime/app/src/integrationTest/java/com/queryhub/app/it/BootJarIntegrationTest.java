package com.queryhub.app.it;

import static com.queryhub.app.it.support.IntegrationSqlite.fileKey;
import static com.queryhub.app.it.support.IntegrationSqlite.mode;
import static com.queryhub.app.it.support.IntegrationSqlite.nativeLibraryFiles;
import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.app.it.support.IntegrationHttp;
import com.queryhub.app.it.support.IntegrationSqlite;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AC-17, P1-06 AC5, AC6, AC7, AC10, AC15, AC17: 실행
 * JAR(runtime/app/build/libs/queryhub-runtime.jar)을 자식 프로세스로 띄운다.
 *
 * <ul>
 *   <li>데이터 디렉터리는 실제 환경변수 이름(QUERYHUB_DATA_DIR)이나 외부 설정 파일로만 넘긴다. 물려받은 QUERYHUB_*, SPRING_* 는 지운다.
 *   <li>마이그레이션과 SQLite 네이티브 라이브러리가 JAR 안(BOOT-INF/lib)에서 발견되어야 한다. 네이티브 라이브러리는 sqlite-jdbc 가 JVM 마다
 *       한 번 풀므로 새 JVM 인 자식 프로세스로만 추출 위치를 확인할 수 있다. 자식의 java.io.tmpdir 는 빈 임시 디렉터리로 둔다.
 *   <li>모든 자식의 출력에는 관리자 토큰과 마스터 키가 없다.
 * </ul>
 *
 * <p>프로세스는 테스트가 끝나면 반드시 종료한다. 작업 디렉터리, 데이터 디렉터리, 출력 파일은 JUnit 임시 디렉터리에 둔다.
 */
class BootJarIntegrationTest {

  private static final String BOOT_JAR_PROPERTY = "queryhub.it.boot-jar";

  private static final Duration HEALTHY_WITHIN = Duration.ofSeconds(60);

  /**
   * R2 (P1-05 리뷰): Boot StartupInfoLogger 의 'Starting … using Java &lt;java.version&gt; with PID …'
   * 줄. GA JDK 17 은 java.version 이 "17" 이고 업데이트 빌드는 "17.0.x" 다. 둘 다 받는다.
   */
  static final Pattern USING_JAVA_17 = Pattern.compile("using Java 17(\\.[0-9][0-9.]*)? with ");

  private static Path bootJar;

  @TempDir private Path workDir;

  @BeforeAll
  static void findBootJar() throws Exception {
    String path = System.getProperty(BOOT_JAR_PROPERTY);
    assertThat(path).as("Gradle 이 %s 를 넘겨야 한다", BOOT_JAR_PROPERTY).isNotBlank();
    bootJar = Path.of(path);
    assertThat(bootJar).as("bootJar 산출물").isRegularFile();
    IntegrationSqlite.loadNativeOnce();
  }

  @Test
  void javaVersionPatternAcceptsGaAndUpdateReleasesOfJava17Only() {
    assertThat(USING_JAVA_17.matcher("Starting X using Java 17 with PID 1").find()).isTrue();
    assertThat(USING_JAVA_17.matcher("Starting X using Java 17.0.17 with PID 1").find()).isTrue();
    assertThat(USING_JAVA_17.matcher("Starting X using Java 21.0.2 with PID 1").find()).isFalse();
    assertThat(USING_JAVA_17.matcher("Starting X using Java 171 with PID 1").find()).isFalse();
  }

  /** AC6, AC7, AC10: 새 데이터 디렉터리로 뜨고, 같은 디렉터리로 다시 뜨면 같은 파일을 마이그레이션 없이 쓴다. */
  @Test
  void bootJarCreatesAPrivateStoreExtractsTheNativeLibraryThereAndReusesItOnRestart()
      throws Exception {
    // P1-05 (ADR-025): 자식은 이 JVM 의 java 로 뜬다. 이 JVM 이 Java 17 이어야 아래 'using Java 17' 이 실행 JAR 이
    // Java 17 에서 돈다는 증거가 된다. 기대값을 일부러 고정한다: 결정 없이 toolchain 을 바꾸면 이 테스트가 실패한다.
    assertThat(Runtime.version().feature()).as("통합 테스트 JVM 의 Java 주 버전").isEqualTo(17);
    Path dataDir = workDir.resolve("data");
    Path javaTmp = Files.createDirectory(workDir.resolve("java-tmp"));
    Path db = dataDir.resolve("queryhub.db");
    int port = freeLoopbackPort();
    Path log = workDir.resolve("first.log");

    Process process =
        start(log, port, dataDirEnvironment(dataDir), true, true, List.of(tmpdir(javaTmp)));
    Object firstFile;
    try {
      HttpResponse<String> health = awaitHealthy(process, port, log);
      assertThat(health.statusCode()).as("body=%s", health.body()).isEqualTo(200);
      assertThat(IntegrationHttp.json(health).path("status").stringValue()).isEqualTo("UP");

      // AC6: 런타임이 만든 디렉터리와 파일의 권한. WAL·공유 메모리 파일은 Flyway 가 V1 을 쓴 뒤 풀이 연결을 쥐고 있어 있다.
      assertThat(mode(dataDir)).isEqualTo("rwx------");
      assertThat(mode(db)).isEqualTo("rw-------");
      assertThat(mode(dataDir.resolve("queryhub.db-wal"))).isEqualTo("rw-------");
      assertThat(mode(dataDir.resolve("queryhub.db-shm"))).isEqualTo("rw-------");
      // AC10: 네이티브 라이브러리는 <data-dir>/native 에 풀리고 java.io.tmpdir 에는 없다.
      Path nativeDir = dataDir.resolve("native");
      assertThat(mode(nativeDir)).isEqualTo("rwx------");
      assertThat(nativeLibraryFiles(nativeDir))
          .as("sqlite-<버전>-<uuid>-libsqlitejdbc.* 와 .lck")
          .anySatisfy(
              file ->
                  assertThat(file.getFileName().toString())
                      .startsWith("sqlite-")
                      .contains("sqlitejdbc")
                      .doesNotEndWith(".lck"));
      assertThat(nativeLibraryFiles(javaTmp)).as("java.io.tmpdir 에는 풀지 않는다").isEmpty();

      // P1-03 AC14: 관리 API 는 환경변수로 받은 토큰이 있어야 열리고, health 는 토큰 없이 열린다.
      HttpResponse<String> tokenless = IntegrationHttp.get(port, "/admin/api/v1/runtime");
      assertThat(tokenless.statusCode()).as("body=%s", tokenless.body()).isEqualTo(401);
      assertThat(tokenless.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
      HttpResponse<String> admin = IntegrationHttp.adminGet(port, "/admin/api/v1/runtime");
      assertThat(admin.statusCode()).as("body=%s", admin.body()).isEqualTo(200);
      assertThat(IntegrationHttp.json(admin).path("data").path("name").stringValue())
          .isEqualTo("queryhub-runtime");
      // 토큰과 마스터 키는 argv 에 없다 (ps 로 보이지 않는다).
      String commandLine = process.info().commandLine().orElse("");
      assertThat(commandLine)
          .doesNotContain(IntegrationHttp.ADMIN_TOKEN)
          .doesNotContainIgnoringCase(IntegrationHttp.MASTER_KEY);
      assertThat(process.info().arguments().map(List::of).orElse(List.of()))
          .noneMatch(
              argument ->
                  argument.contains(IntegrationHttp.ADMIN_TOKEN)
                      || argument.toLowerCase(Locale.ROOT).contains(IntegrationHttp.MASTER_KEY));
      firstFile = fileKey(db);
    } finally {
      stop(process);
    }

    assertThat(IntegrationSqlite.flywayHistory(db))
        .as("JAR 안의 V1 이 한 번 적용되어야 한다")
        .containsExactly(Map.of("version", "1", "success", 1L));
    String firstOutput = read(log);
    assertNoSecrets(firstOutput);
    assertThat(firstOutput).contains("마스터 키를 읽었다").contains("Metadata 저장소를 열었다: " + db);
    assertThat(USING_JAVA_17.matcher(firstOutput).find())
        .as("StartupInfoLogger 의 Java 17 줄 (GA 와 업데이트 판 모두)")
        .isTrue();

    // 두 실행 사이에 값을 하나 넣는다(아직 값을 쓰는 API 가 없다). 두 번째 실행이 저장소를 새로 만들면 사라진다.
    try (Connection connection = IntegrationSqlite.open(db);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO app_setting VALUES ('it.restart', 'kept', '2026-11-23T00:00:00.000000Z',"
              + " '2026-11-23T00:00:00.000000Z')");
    }

    Path secondLog = workDir.resolve("second.log");
    int secondPort = freeLoopbackPort();
    Process restarted =
        start(secondLog, secondPort, dataDirEnvironment(dataDir), true, true, List.of());
    try {
      awaitHealthy(restarted, secondPort, secondLog);
      assertThat(fileKey(db)).as("같은 DB 파일(inode)을 다시 쓴다").isEqualTo(firstFile);
    } finally {
      stop(restarted);
    }
    assertThat(IntegrationSqlite.flywayHistory(db))
        .as("다시 뜰 때 마이그레이션을 적용하지 않는다")
        .containsExactly(Map.of("version", "1", "success", 1L));
    try (Connection connection = IntegrationSqlite.open(db);
        Statement statement = connection.createStatement();
        var rs =
            statement.executeQuery(
                "SELECT setting_value FROM app_setting WHERE setting_key = 'it.restart'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("kept");
    }
    assertNoSecrets(read(secondLog));
  }

  /** AC10: QUERYHUB_SQLITE_NATIVE_DIR 로 지정한 디렉터리에 풀리고 데이터 디렉터리 아래에는 풀리지 않는다. */
  @Test
  void nativeDirectorySettingMovesTheExtractedLibrary() throws Exception {
    Path dataDir = workDir.resolve("data");
    Path nativeDir = workDir.resolve("exec-allowed/native");
    Path javaTmp = Files.createDirectory(workDir.resolve("java-tmp"));
    Map<String, String> env = dataDirEnvironment(dataDir);
    env.put("QUERYHUB_SQLITE_NATIVE_DIR", nativeDir.toString());
    int port = freeLoopbackPort();
    Path log = workDir.resolve("native-dir.log");

    Process process = start(log, port, env, true, true, List.of(tmpdir(javaTmp)));
    try {
      awaitHealthy(process, port, log);
      assertThat(mode(nativeDir)).isEqualTo("rwx------");
      assertThat(nativeLibraryFiles(nativeDir)).isNotEmpty();
      assertThat(dataDir.resolve("native")).doesNotExist();
      assertThat(nativeLibraryFiles(dataDir)).isEmpty();
      assertThat(nativeLibraryFiles(javaTmp)).isEmpty();
    } finally {
      stop(process);
    }
    assertNoSecrets(read(log));
  }

  /**
   * AC5: server 프로파일이 환경변수 없이 외부 설정 파일(spring.config.additional-location)의 queryhub.data-dir 로 뜬다.
   */
  @Test
  void serverProfileTakesTheDataDirectoryFromAnExternalConfigFile() throws Exception {
    Path dataDir = workDir.resolve("server-data");
    Path conf = Files.createDirectory(workDir.resolve("conf"));
    Files.writeString(
        conf.resolve("application.yml"),
        "queryhub:\n  data-dir: \"" + dataDir + "\"\n",
        StandardCharsets.UTF_8);
    int port = freeLoopbackPort();
    Path log = workDir.resolve("config-file.log");

    Process process =
        start(
            log,
            port,
            new HashMap<>(),
            true,
            true,
            List.of(),
            "--spring.profiles.active=server",
            "--server.address=127.0.0.1",
            "--spring.config.additional-location=file:" + conf + "/");
    try {
      awaitHealthy(process, port, log);
      HttpResponse<String> admin = IntegrationHttp.adminGet(port, "/admin/api/v1/runtime");
      assertThat(IntegrationHttp.json(admin).path("data").path("mode").stringValue())
          .isEqualTo("server");
      assertThat(dataDir.resolve("queryhub.db")).isRegularFile();
      assertThat(mode(dataDir.resolve("queryhub.db"))).isEqualTo("rw-------");
    } finally {
      stop(process);
    }
    assertNoSecrets(read(log));
  }

  /** AC5: 데이터 디렉터리가 없으면 포트를 열지 않고 1 로 끝나며 출력이 설정 이름을 말한다. */
  @Test
  void bootJarWithoutTheDataDirectoryExitsWithOneNamingTheSetting() throws Exception {
    Path log = workDir.resolve("no-data-dir.log");

    Process process = start(log, freeLoopbackPort(), new HashMap<>(), true, true, List.of());
    assertExitsNonZeroWithoutOpeningThePort(process, log);
    assertThat(process.exitValue()).isEqualTo(1);
    String output = read(log);
    assertThat(output).contains("QUERYHUB_DATA_DIR").contains("queryhub.data-dir");
    assertNoSecrets(output);
  }

  @Test
  void bootJarWithoutTheMasterKeyExitsNonZeroWithoutOpeningThePort() throws Exception {
    // P1-04 AC14: 데이터 디렉터리와 토큰은 맞게 주고 마스터 키만 뺀다. 그래서 실패 원인은 키뿐이다.
    Path log = workDir.resolve("no-key.log");

    Process process =
        start(
            log,
            freeLoopbackPort(),
            dataDirEnvironment(workDir.resolve("data")),
            true,
            false,
            List.of());
    assertExitsNonZeroWithoutOpeningThePort(process, log);
    String output = read(log);
    assertThat(output).contains("QUERYHUB_MASTER_KEY").contains("QUERYHUB_MASTER_KEY_FILE");
    assertThat(output).doesNotContainIgnoringCase(IntegrationHttp.MASTER_KEY);
  }

  @Test
  void bootJarRefusesTheMasterKeyOnTheCommandLine() throws Exception {
    // P1-04 AC14: argv 는 ps 로 보이므로 키를 받지 않는다. 출력에도 값이 나오지 않는다.
    Path log = workDir.resolve("argv-key.log");

    Process process =
        start(
            log,
            freeLoopbackPort(),
            dataDirEnvironment(workDir.resolve("data")),
            true,
            false,
            List.of(),
            "--queryhub.master-key=" + IntegrationHttp.MASTER_KEY);
    assertExitsNonZeroWithoutOpeningThePort(process, log);
    String output = read(log);
    assertThat(output).contains("queryhub.master-key").contains("명령행 인자");
    assertThat(output).doesNotContainIgnoringCase(IntegrationHttp.MASTER_KEY);
  }

  @Test
  void bootJarWithoutTheAdminTokenExitsNonZeroWithoutOpeningThePort() throws Exception {
    // 데이터 디렉터리와 마스터 키는 맞게 주고 토큰만 뺀다. 그래서 실패 원인은 토큰뿐이다.
    Path log = workDir.resolve("no-token.log");

    Process process =
        start(
            log,
            freeLoopbackPort(),
            dataDirEnvironment(workDir.resolve("data")),
            false,
            true,
            List.of());
    assertExitsNonZeroWithoutOpeningThePort(process, log);
    String output = read(log);
    assertThat(output).contains("QUERYHUB_ADMIN_TOKEN");
    assertThat(output).doesNotContainIgnoringCase(IntegrationHttp.MASTER_KEY);
  }

  private static void assertExitsNonZeroWithoutOpeningThePort(Process process, Path log)
      throws Exception {
    try {
      assertThat(process.waitFor(HEALTHY_WITHIN.toSeconds(), TimeUnit.SECONDS))
          .as("기동 실패로 스스로 끝나야 한다")
          .isTrue();
      assertThat(process.exitValue()).isNotZero();
    } finally {
      stop(process);
    }
    assertThat(read(log)).as("웹 서버가 포트를 열지 않았다").doesNotContain("Tomcat started on port");
  }

  private static void assertNoSecrets(String output) {
    assertThat(output)
        .as("출력에 관리자 토큰과 마스터 키가 없어야 한다")
        .doesNotContain(IntegrationHttp.ADMIN_TOKEN)
        .doesNotContainIgnoringCase(IntegrationHttp.MASTER_KEY);
  }

  private static Map<String, String> dataDirEnvironment(Path dataDir) {
    Map<String, String> env = new HashMap<>();
    env.put("QUERYHUB_DATA_DIR", dataDir.toString());
    return env;
  }

  private static String tmpdir(Path directory) {
    return "-Djava.io.tmpdir=" + directory;
  }

  private Process start(
      Path log,
      int port,
      Map<String, String> storeEnvironment,
      boolean withAdminToken,
      boolean withMasterKey,
      List<String> jvmArgs,
      String... extraArgs)
      throws IOException {
    List<String> command = new ArrayList<>(List.of(javaExecutable()));
    command.addAll(jvmArgs);
    command.addAll(List.of("-jar", bootJar.toString(), "--server.port=" + port));
    command.addAll(List.of(extraArgs));
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    Map<String, String> env = builder.environment();
    env.keySet().removeIf(name -> name.startsWith("QUERYHUB_") || name.startsWith("SPRING_"));
    env.putAll(storeEnvironment);
    if (withAdminToken) {
      // Tauri 셸과 같은 방식: 토큰은 자식 프로세스의 환경변수로만 넘긴다.
      env.put("QUERYHUB_ADMIN_TOKEN", IntegrationHttp.ADMIN_TOKEN);
    }
    if (withMasterKey) {
      // 마스터 키도 같은 방식이다 (P1-04, ADR-013).
      env.put("QUERYHUB_MASTER_KEY", IntegrationHttp.MASTER_KEY);
    }
    assertThat(builder.command())
        .noneMatch(argument -> argument.contains(IntegrationHttp.ADMIN_TOKEN));
    return builder.start();
  }

  /** /actuator/health 가 200 이 될 때까지 기다린다. 프로세스가 먼저 끝나거나 시간이 지나면 출력과 함께 실패한다. */
  private static HttpResponse<String> awaitHealthy(Process process, int port, Path log)
      throws Exception {
    Instant deadline = Instant.now().plus(HEALTHY_WITHIN);
    while (Instant.now().isBefore(deadline)) {
      if (!process.isAlive()) {
        throw new AssertionError("실행 JAR 이 끝났다 (exit " + process.exitValue() + "):\n" + read(log));
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
    throw new AssertionError(HEALTHY_WITHIN.toSeconds() + " 초 안에 healthy 가 되지 않았다:\n" + read(log));
  }

  private static String read(Path log) throws IOException {
    String output = Files.readString(log, StandardCharsets.UTF_8);
    assertThat(output).as("웹 서버 기동 여부와 관계없이 읽을 수 있어야 한다").isNotNull();
    return output;
  }

  private static void stop(Process process) throws InterruptedException {
    if (process.isAlive()) {
      process.destroy();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
      }
    }
  }

  /** 지금 JVM 의 java 실행 파일(Gradle toolchain 의 Java 17, ADR-025). */
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
