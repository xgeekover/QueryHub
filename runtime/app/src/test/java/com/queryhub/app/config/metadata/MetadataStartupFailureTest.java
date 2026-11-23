package com.queryhub.app.config.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.queryhub.adapter.metadata.store.MetadataStoreException;
import com.queryhub.adapter.metadata.store.StoreLayout;
import com.queryhub.adapter.metadata.store.StorePragmas;
import com.queryhub.app.QueryHubApplication;
import com.queryhub.app.support.TestAdminToken;
import com.queryhub.app.support.TestComponentScanExclusion;
import com.queryhub.app.support.TestMasterKey;
import com.queryhub.app.support.TestSqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * P1-06 AC5, AC9, AC11, AC14 (단위, Docker 없이): Metadata 저장소(SQLite) 문제는 기동을 웹 서버가 포트를 열기 전에 멈춘다.
 *
 * <ul>
 *   <li>데이터 디렉터리가 없거나(빈 값, 상대 경로, '?') 잘못되면 desktop 과 server 프로파일 모두 실패하고 메시지가 QUERYHUB_DATA_DIR 와
 *       queryhub.data-dir 를 말한다. 일반 초기화와 lazy 초기화 모두 확인한다.
 *   <li>같은 데이터 디렉터리의 두 번째 런타임은 queryhub.lock 때문에 거부된다. 첫 런타임이 끝나면 다시 뜬다.
 *   <li>SQLite 설정을 data-source-properties 로 바꾸면 거부하고, connection-init-sql 로 바꾸면 풀 연결 확인이 거부한다.
 *   <li>spring.datasource.hikari.jdbc-url 로 다른 파일을 가리켜도 풀은 검사한 파일에 남는다. Flyway 를 꺼도 나쁜 저장소는 거부된다.
 *   <li>V1 의 체크섬이 다른 저장소는 Flyway 검증이 거부한다.
 * </ul>
 *
 * <p>실제 QueryHubApplication 을 Metadata 저장소 제외 없이 띄운다(servlet, 127.0.0.1, 포트 0). 데이터 디렉터리는 테스트마다 새
 * 임시 디렉터리다(잠금 때문에 컨텍스트마다 따로 둔다). 데이터 디렉터리는 비밀이 아니므로 명령행 인자로 넘긴다. 출력에는 관리자 토큰과 마스터 키가 없어야 한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class MetadataStartupFailureTest {

  @TempDir private Path tmp;

  @BeforeAll
  static void loadNative() throws Exception {
    TestSqlite.loadNativeOnce();
  }

  @ParameterizedTest(name = "lazy={0} profile={1}")
  @CsvSource({"false, desktop", "true, desktop", "false, server", "true, server"})
  void missingDataDirectoryFailsBeforeTheWebServerStarts(
      boolean lazy, String profile, CapturedOutput output) {
    StartupAttempt attempt = start(lazy, profile, List.of());

    assertThat(attempt.failure()).as("데이터 디렉터리 없이 기동하면 실패해야 한다").isNotNull();
    assertThat(attempt.failure())
        .rootCause()
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining("QUERYHUB_DATA_DIR")
        .hasMessageContaining("queryhub.data-dir");
    assertThat(attempt.webServerStarted()).as("포트를 열기 전에 실패해야 한다").isFalse();
    assertNoSecretInOutput(output);
  }

  @ParameterizedTest(name = "{0} profile={1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "' '                       | desktop",
        "relative/data             | desktop",
        "relative/data             | server",
        "/tmp/queryhub?mode=ro     | desktop",
        "/tmp/queryhub?mode=ro     | server"
      })
  void invalidDataDirectoryValuesFailBeforeTheWebServerStarts(String value, String profile) {
    StartupAttempt attempt = start(false, profile, List.of("--queryhub.data-dir=" + value));

    assertThat(attempt.failure())
        .rootCause()
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining("QUERYHUB_DATA_DIR")
        .hasMessageContaining("queryhub.data-dir");
    assertThat(attempt.webServerStarted()).isFalse();
  }

  @Test
  void secondRuntimeOnTheSameDataDirectoryIsRefusedUntilTheFirstStops(CapturedOutput output) {
    Path dataDir = tmp.resolve("data");

    try (ConfigurableApplicationContext first = run(dataDir)) {
      assertThat(first.isActive()).isTrue();

      StartupAttempt second = start(false, "desktop", dataDirArgs(dataDir));

      assertThat(second.failure())
          .rootCause()
          .isInstanceOf(MetadataStoreException.class)
          .hasMessageContaining(dataDir.toString())
          .hasMessageContaining("queryhub.lock");
      assertThat(second.webServerStarted()).isFalse();
      assertThat(first.isActive()).as("거부된 두 번째 런타임이 첫 런타임을 건드리지 않는다").isTrue();
    }

    try (ConfigurableApplicationContext third = run(dataDir)) {
      assertThat(third.isActive()).as("첫 런타임이 끝나면 같은 디렉터리로 다시 뜬다").isTrue();
    }
    assertNoSecretInOutput(output);
  }

  /** P1-07: 거부 메시지는 런타임이 정하는 설정을 모두(open_mode 포함) 이름으로만 든다. 넘긴 값은 메시지에 없다. */
  private static final String MANAGED_KEY_LIST =
      "(런타임이 정하는 설정: journal_mode, synchronous, foreign_keys, busy_timeout, transaction_mode, open_mode)";

  @Test
  void managedKeyListInTheRefusalMessageIsTheManagedKeySet() {
    assertThat(MANAGED_KEY_LIST)
        .isEqualTo("(런타임이 정하는 설정: " + String.join(", ", StorePragmas.MANAGED_KEYS) + ")");
  }

  @ParameterizedTest
  @ValueSource(strings = {"journal_mode=DELETE", "synchronous=OFF", "busy_timeout=0"})
  void dataSourcePropertiesThatChangeTheStoreSettingsAreRefused(String setting) {
    String key = setting.substring(0, setting.indexOf('='));
    Path dataDir = tmp.resolve("data");

    StartupAttempt attempt =
        start(
            false,
            "desktop",
            withDataDir(dataDir, "--spring.datasource.hikari.data-source-properties." + setting));

    assertThat(attempt.failure())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.datasource.hikari.data-source-properties." + key)
        .hasMessageContaining("런타임이 정하는 값")
        .hasMessageContaining(MANAGED_KEY_LIST)
        .hasMessageNotContaining(setting);
    assertThat(attempt.webServerStarted()).isFalse();
  }

  /**
   * P1-07 AC6 (리뷰 N5): 런타임 URL 의 open_mode=2 는 DB 파일을 만들지 않는다. sqlite-jdbc 는 연결 속성을 URL 의 같은 이름보다
   * 앞세우므로 data-source-properties 의 open_mode(예: 6 = 읽기·쓰기·만들기)는 이름만으로 거부한다.
   */
  @Test
  void dataSourcePropertyOpenModeIsRefusedBecauseTheRuntimeOpensWithoutCreate() {
    Path dataDir = tmp.resolve("data");

    StartupAttempt attempt =
        start(
            false,
            "desktop",
            withDataDir(dataDir, "--spring.datasource.hikari.data-source-properties.open_mode=6"));

    assertThat(attempt.failure()).as("open_mode 를 바꾸면 기동할 수 없다").isNotNull();
    assertThat(attempt.failure())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.datasource.hikari.data-source-properties.open_mode")
        .hasMessageContaining("런타임이 정하는 값")
        .hasMessageContaining(MANAGED_KEY_LIST)
        .hasMessageNotContaining("open_mode=6");
    assertThat(attempt.webServerStarted()).isFalse();
  }

  @ParameterizedTest(name = "flyway.enabled={0}")
  @ValueSource(booleans = {true, false})
  void connectionInitSqlThatWeakensThePragmasIsCaughtOnPoolConnections(boolean flywayEnabled)
      throws Exception {
    Path dataDir = tmp.resolve("data");

    StartupAttempt attempt =
        start(
            false,
            "desktop",
            withDataDir(
                dataDir,
                "--spring.datasource.hikari.connection-init-sql=PRAGMA synchronous=OFF",
                "--spring.flyway.enabled=" + flywayEnabled));

    assertThat(attempt.failure())
        .rootCause()
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining("풀 연결 1/4 (Flyway 전)")
        .hasMessageContaining("synchronous=0");
    assertThat(attempt.webServerStarted()).isFalse();
    assertThat(tables(dataDir)).as("약해진 연결로는 마이그레이션하지 않는다").doesNotContain("app_setting");
  }

  /**
   * R3(a): spring.datasource.hikari.jdbc-url 로 다른 SQLite 파일(같은 PRAGMA)을 가리켜도 풀은 검사한 파일에 남고 그 파일은
   * 생기지 않는다. 풀 연결 검사가 PRAGMA database_list 로 파일을 확인하므로, 이 설정이 풀을 옮기게 되면 기동이 멈춘다
   * (MetadataPoolConnectionCheckTest).
   */
  @Test
  void hikariJdbcUrlPointingAtAnotherSqliteFileNeitherMovesThePoolNorCreatesThatFile()
      throws Exception {
    Path dataDir = tmp.resolve("data");
    Path other = tmp.resolve("other.db");
    String otherUrl = "jdbc:sqlite:" + other + "?" + StoreLayout.URL_OPTIONS;

    try (ConfigurableApplicationContext context =
        application(false, "desktop", new AtomicBoolean())
            .run(
                arguments(
                    withDataDir(dataDir, "--spring.datasource.hikari.jdbc-url=" + otherUrl)))) {
      try (Connection connection = context.getBean(DataSource.class).getConnection();
          Statement statement = connection.createStatement();
          var rs = statement.executeQuery("PRAGMA database_list")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("name")).isEqualTo("main");
        assertThat(Files.isSameFile(Path.of(rs.getString("file")), dataDir.resolve("queryhub.db")))
            .as("풀은 검사한 DB 파일을 연다")
            .isTrue();
      }
      assertThat(tables(dataDir)).contains("app_setting");
    }
    assertThat(other).as("다른 파일을 만들지 않는다").doesNotExist();
  }

  /** R3(b): Flyway 를 꺼도 저장소 검사는 그대로 돌아 나쁜 저장소는 포트를 열기 전에 거부된다(DATABASE_SUPPORT). */
  @ParameterizedTest(name = "lazy={0}")
  @ValueSource(booleans = {false, true})
  void withFlywayDisabledABadStoreIsStillRefusedBeforeTheWebServerStarts(boolean lazy)
      throws Exception {
    Path dataDir = Files.createDirectory(tmp.resolve("data"));
    Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("rwx------"));
    Path db = dataDir.resolve("queryhub.db");
    Files.writeString(db, "this is a plain text file, not a database\n");
    Files.setPosixFilePermissions(db, PosixFilePermissions.fromString("rw-------"));
    byte[] before = Files.readAllBytes(db);

    StartupAttempt attempt =
        start(lazy, "desktop", withDataDir(dataDir, "--spring.flyway.enabled=false"));

    assertThat(attempt.failure())
        .rootCause()
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining("SQLite DB 파일이 아니다")
        .hasMessageContaining(db.toString());
    assertThat(attempt.webServerStarted()).as("포트를 열기 전에 실패해야 한다").isFalse();
    assertThat(Files.readAllBytes(db)).as("거부한 파일은 그대로다").isEqualTo(before);
  }

  @Test
  void storeWhoseV1ChecksumDiffersIsRefusedByFlywayValidation() throws Exception {
    Path dataDir = tmp.resolve("data");
    try (ConfigurableApplicationContext first = run(dataDir)) {
      assertThat(first.isActive()).isTrue();
    }
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("queryhub.db"));
        Statement statement = connection.createStatement()) {
      assertThat(
              statement.executeUpdate(
                  "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'"))
          .isEqualTo(1);
    }

    StartupAttempt attempt = start(false, "desktop", dataDirArgs(dataDir));

    assertThat(attempt.failure()).as("체크섬이 다른 V1 로는 기동할 수 없다").isNotNull();
    assertThat(causes(attempt.failure()))
        .anySatisfy(
            cause ->
                assertThat(cause.getClass().getName())
                    .isEqualTo("org.flywaydb.core.api.exception.FlywayValidateException"));
    assertThat(String.valueOf(attempt.failure()))
        .as("Flyway 검증 실패")
        .containsIgnoringCase("checksum");
    assertThat(attempt.webServerStarted()).isFalse();
  }

  private static List<String> tables(Path dataDir) throws Exception {
    List<String> tables = new ArrayList<>();
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("queryhub.db"));
        Statement statement = connection.createStatement();
        var rs = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
      while (rs.next()) {
        tables.add(rs.getString(1));
      }
    }
    return tables;
  }

  private static void assertNoSecretInOutput(CapturedOutput output) {
    assertThat(output.getAll())
        .doesNotContain(TestAdminToken.VALUE)
        .doesNotContainIgnoringCase(TestMasterKey.VALUE);
  }

  private static List<String> dataDirArgs(Path dataDir) {
    return List.of("--queryhub.data-dir=" + dataDir);
  }

  private static List<String> withDataDir(Path dataDir, String... extra) {
    List<String> args = new ArrayList<>(dataDirArgs(dataDir));
    args.addAll(List.of(extra));
    return args;
  }

  private static ConfigurableApplicationContext run(Path dataDir) {
    assertThat(Files.exists(dataDir.getParent())).isTrue();
    return application(false, "desktop", new AtomicBoolean()).run(arguments(dataDirArgs(dataDir)));
  }

  private static List<Throwable> causes(@Nullable Throwable failure) {
    List<Throwable> causes = new ArrayList<>();
    for (Throwable cause = failure;
        cause != null && !causes.contains(cause);
        cause = cause.getCause()) {
      causes.add(cause);
    }
    return causes;
  }

  private static SpringApplication application(
      boolean lazy, String profile, AtomicBoolean webServerStarted) {
    ApplicationListener<ApplicationEvent> webServerListener =
        event -> {
          if (event instanceof WebServerInitializedEvent) {
            webServerStarted.set(true);
          }
        };
    return new SpringApplicationBuilder(QueryHubApplication.class)
        .web(WebApplicationType.SERVLET)
        .lazyInitialization(lazy)
        .profiles(profile)
        // 관리자 토큰 검사가 아니라 Metadata 저장소 검사가 기동을 멈추는지 본다.
        .properties(TestAdminToken.PROPERTY, TestMasterKey.PROPERTY)
        .initializers(TestComponentScanExclusion::register)
        .listeners(webServerListener)
        .build();
  }

  private static String[] arguments(List<String> extraArgs) {
    List<String> args = new ArrayList<>(List.of("--server.address=127.0.0.1", "--server.port=0"));
    args.addAll(extraArgs);
    return args.toArray(String[]::new);
  }

  private static StartupAttempt start(boolean lazy, String profile, List<String> extraArgs) {
    AtomicBoolean webServerStarted = new AtomicBoolean();
    SpringApplication application = application(lazy, profile, webServerStarted);
    Throwable failure =
        catchThrowable(
            () -> {
              try (ConfigurableApplicationContext context = application.run(arguments(extraArgs))) {
                assertThat(context.isActive()).isTrue();
              }
            });
    return new StartupAttempt(failure, webServerStarted.get());
  }

  private record StartupAttempt(@Nullable Throwable failure, boolean webServerStarted) {}
}
