package com.queryhub.adapter.dbprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.queryhub.domain.connection.ConnectionFailureKind;
import com.queryhub.domain.connection.ConnectionId;
import com.queryhub.domain.connection.ConnectionPassword;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.ConnectionTestResult;
import com.queryhub.domain.connection.DatabaseType;
import com.queryhub.domain.connection.InvalidConnectionSettingsException;
import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** AC12: Driver.connect 경로, 옵션 허용 목록, 고정 속성, 비밀번호 없는 결과·예외·로그, 하드 제한 시간. */
class JdbcDatabaseProviderTest {

  static final String SECRET = "s3cr3t-P4ssw0rd";

  static ConnectionSettings settings(String host, Duration timeout, Map<String, String> options) {
    return new ConnectionSettings(
        DatabaseType.POSTGRESQL, host, 5432, "app", "app_user", timeout, 3, options);
  }

  static ConnectionSettings settings(Map<String, String> options) {
    return settings("db.example.com", Duration.ofSeconds(5), options);
  }

  /** provider-postgresql 의 sslmode 허용 값 (이 모듈은 provider 를 보지 않으므로 같은 값을 둔다). */
  static final Set<String> PG_SSL_MODES =
      Set.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full");

  /** provider-mariadb 의 sslMode 허용 값. */
  static final Set<String> MARIADB_SSL_MODES =
      Set.of("disable", "trust", "verify-ca", "verify-full");

  static ConnectionPassword password() {
    return ConnectionPassword.of(SECRET.toCharArray());
  }

  @Test
  void connectsThroughTheProviderDriverWithTheUrlBuiltFromFields() {
    FakeDriver driver = new FakeDriver();
    JdbcDatabaseProvider provider = new JdbcDatabaseProvider(new FakeProviderSpi(driver));
    ConnectionPassword password = password();

    ConnectionTestResult result =
        provider.testConnection(settings(Map.of("sslMode", "require")), password);

    assertThat(result.succeeded()).as(result.message()).isTrue();
    assertThat(result.productName()).isEqualTo("FakeDB");
    assertThat(result.productVersion()).isEqualTo("1.2.3");
    assertThat(provider.type()).isEqualTo(DatabaseType.POSTGRESQL);
    // FakeDriver 는 DriverManager 에 없다: DriverManager 를 썼다면 "No suitable driver" 로 실패했다.
    assertThat(DriverManager.drivers()).noneMatch(FakeDriver.class::isInstance);
    assertThat(driver.connects).hasValue(1);
    assertThat(driver.seenUrls).containsExactly("jdbc:fake://db.example.com:5432/app");
    Properties seen = driver.seenProperties.get(0);
    assertThat(seen)
        .containsEntry("user", "app_user")
        .containsEntry("password", SECRET)
        .containsEntry("sslMode", "require")
        .containsEntry("connectTimeout", "5000")
        .containsEntry("allowLocalInfile", "false")
        .hasSize(5);
    FakeDriver.FakeConnection connection = driver.connections.get(0);
    assertThat(connection.executed).containsExactly("SELECT 1");
    assertThat(connection.closed).isTrue();
    // 호출한 쪽의 비밀번호는 그대로다 (Provider 는 사본을 쓰고 지운다).
    assertThat(new String(password.copyChars())).isEqualTo(SECRET);
  }

  @Test
  void ipv6HostsAreBracketedInTheUrl() {
    FakeDriver driver = new FakeDriver();
    new JdbcDatabaseProvider(new FakeProviderSpi(driver))
        .testConnection(settings("2001:db8::7", Duration.ofSeconds(2), Map.of()), password());
    assertThat(driver.seenUrls).containsExactly("jdbc:fake://[2001:db8::7]:5432/app");
  }

  @Test
  void unknownOptionIsRefusedByNameWithoutItsValueAndBeforeAnyDriverCall() {
    FakeDriver driver = new FakeDriver();
    JdbcDatabaseProvider provider = new JdbcDatabaseProvider(new FakeProviderSpi(driver));

    ConnectionTestResult result;
    String logs;
    try (LogCapture capture = new LogCapture()) {
      result =
          provider.testConnection(
              settings(Map.of("sslMode", "require", "socketFactory", "evil.Factory-VALUE123")),
              password());
      logs = capture.text();
    }

    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.OPTION_REJECTED);
    assertThat(result.rejectedOption()).isEqualTo("socketFactory");
    assertThat(result.message())
        .isEqualTo("허용되지 않은 접속 옵션입니다: socketFactory")
        .doesNotContain("VALUE123");
    assertThat(result.toString()).doesNotContain("VALUE123");
    assertThat(logs).doesNotContain("VALUE123").doesNotContain(SECRET);
    assertThat(driver.connects).hasValue(0);
  }

  /**
   * P1-08a A-AC3: 허용 값이 아닌 sslmode(오타 requir)는 OPTION_REJECTED 이고 드라이버를 부르지 않는다. 값은 결과, 로그, 풀의 예외
   * 어디에도 없다.
   */
  @Test
  void optionValueOutsideTheAllowedSetIsRefusedByNameBeforeAnyDriverCall() {
    FakeDriver driver = new FakeDriver();
    FakeProviderSpi spi =
        new FakeProviderSpi(driver, DatabaseType.POSTGRESQL, Map.of("sslmode", PG_SSL_MODES));
    JdbcDatabaseProvider provider = new JdbcDatabaseProvider(spi);

    ConnectionTestResult result;
    String logs;
    try (LogCapture capture = new LogCapture()) {
      result = provider.testConnection(settings(Map.of("sslmode", "requir")), password());
      logs = capture.text();
      assertThat(capture.events()).as("붙잡은 로그가 있어야 검사가 뜻이 있다").isNotEmpty();
    }

    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.OPTION_REJECTED);
    assertThat(result.rejectedOption()).isEqualTo("sslmode");
    assertThat(result.message()).isEqualTo("허용되지 않은 접속 옵션입니다: sslmode");
    assertThat(result.toString()).doesNotContain("requir");
    assertThat(logs).doesNotContain("requir").doesNotContain(SECRET);
    assertThat(driver.connects).hasValue(0);

    try (HikariConnectionPools pools = new HikariConnectionPools(List.of(spi))) {
      ConnectionId id = new ConnectionId("value-check");
      assertThatThrownBy(() -> pools.open(id, settings(Map.of("sslmode", "requir")), password()))
          .isInstanceOf(InvalidConnectionSettingsException.class)
          .hasMessage("허용되지 않은 접속 옵션입니다: sslmode");
      assertThat(pools.isOpen(id)).isFalse();
    }
    assertThat(driver.connects).hasValue(0);
  }

  /**
   * P1-08a A-AC3: PostgreSQL 과 MariaDB 의 값 집합에서 받지 않는 값 (오타, 대소문자 변형, 드라이버 별칭). 거부는 옵션 이름만 밝히고 값은
   * 메시지, 결과, 로그에 없다. 값마다 둘레에 표식을 붙여 짧은 값(0, 1)도 검사가 뜻이 있게 한다.
   */
  @ParameterizedTest
  @CsvSource({
    "sslmode, requir",
    "sslmode, REQUIRE",
    "sslmode, Prefer",
    "sslMode, allow",
    "sslMode, REQUIRED",
    "sslMode, VERIFY_IDENTITY",
    "sslMode, 0",
    "sslMode, true",
    "sslMode, Trust"
  })
  void refusedValuesNeverAppearInMessagesResultsOrLogs(String option, String value) {
    FakeDriver driver = new FakeDriver();
    FakeProviderSpi spi =
        new FakeProviderSpi(
            driver,
            DatabaseType.POSTGRESQL,
            Map.of("sslmode", PG_SSL_MODES, "sslMode", MARIADB_SSL_MODES));
    // 거부된 값과 같은 글자가 고정 문구나 로그에 우연히 있어도 걸리지 않도록, 값 자체 대신 표식을 붙인 값도 따로 시험한다.
    for (String given : List.of(value, "VALMARK-" + value + "-VALMARK")) {
      ConnectionTestResult result;
      String logs;
      try (LogCapture capture = new LogCapture()) {
        result =
            new JdbcDatabaseProvider(spi)
                .testConnection(settings(Map.of(option, given)), password());
        logs = capture.text();
      }
      assertThat(result.failureKind()).as(given).isEqualTo(ConnectionFailureKind.OPTION_REJECTED);
      assertThat(result.rejectedOption()).isEqualTo(option);
      assertThat(result.message()).isEqualTo(ConnectionFailureKind.OPTION_REJECTED.message(option));
      assertThat(result.toString() + logs).doesNotContain("VALMARK");
      assertThatThrownBy(() -> ConnectionProperties.build(spi, settings(Map.of(option, given))))
          .isInstanceOf(InvalidConnectionSettingsException.class)
          .hasMessage(ConnectionFailureKind.OPTION_REJECTED.message(option));
    }
    assertThat(driver.connects).hasValue(0);
  }

  @Test
  void fixedPropertiesWinAndCannotBeSetThroughOptions() {
    FakeDriver driver = new FakeDriver();
    // 잘못 만든 Provider: 허용 목록에 고정 속성과 user/password 이름이 들어 있다. 그래도 옵션으로 바꿀 수 없다.
    // 값 999999 는 허용 값에 넣어 둔다: 거부는 값이 아니라 겹치는 이름 때문이다.
    Set<String> anyValue = Set.of("999999");
    FakeProviderSpi careless =
        new FakeProviderSpi(
            driver,
            DatabaseType.POSTGRESQL,
            Map.of(
                "sslMode",
                FakeProviderSpi.SSL_MODES,
                "connectTimeout",
                anyValue,
                "ALLOWLOCALINFILE",
                anyValue,
                "password",
                anyValue,
                "User",
                anyValue));
    JdbcDatabaseProvider provider = new JdbcDatabaseProvider(careless);

    for (String name : List.of("connectTimeout", "ALLOWLOCALINFILE", "password", "User")) {
      ConnectionTestResult result =
          provider.testConnection(settings(Map.of(name, "999999")), password());
      assertThat(result.failureKind()).as(name).isEqualTo(ConnectionFailureKind.OPTION_REJECTED);
      assertThat(result.rejectedOption()).isEqualTo(name);
    }
    assertThat(driver.connects).hasValue(0);

    Properties properties =
        ConnectionProperties.build(careless, settings(Map.of("sslMode", "verify-full")));
    assertThat(properties)
        .containsEntry("connectTimeout", "5000")
        .containsEntry("allowLocalInfile", "false")
        .containsEntry("sslMode", "verify-full")
        .doesNotContainKeys("user", "password");
  }

  @Test
  void driverFailureWithThePasswordInItsMessageLeavesNoTraceOfIt() throws Exception {
    FakeDriver driver = new FakeDriver();
    driver.mode = FakeDriver.Mode.THROW;
    driver.failure =
        () -> {
          SQLException failure =
              new SQLException(
                  "FATAL: password authentication failed (password=" + SECRET + ")",
                  "28P01",
                  1045,
                  new IOException("socket said " + SECRET));
          failure.setNextException(new SQLException("next " + SECRET));
          failure.addSuppressed(new IllegalStateException("suppressed " + SECRET));
          return failure;
        };
    FakeProviderSpi spi = new FakeProviderSpi(driver);

    ConnectionTestResult result;
    String logs;
    SQLException thrown;
    try (LogCapture capture = new LogCapture()) {
      result = new JdbcDatabaseProvider(spi).testConnection(settings(Map.of()), password());
      try (DriverConnectDataSource source =
          DriverConnectDataSource.create(spi, settings(Map.of()), password())) {
        thrown =
            org.junit.jupiter.api.Assertions.assertThrows(
                SQLException.class, source::getConnection);
      }
      logs = capture.text();
    }

    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.AUTHENTICATION_FAILED);
    assertThat(result.toString()).doesNotContain(SECRET);
    assertThat(result.message()).isEqualTo("사용자 이름 또는 비밀번호가 올바르지 않습니다.");

    assertThat((Throwable) thrown).isInstanceOf(ConnectionFailedException.class);
    assertThat(thrown.getMessage()).isEqualTo(result.message());
    assertThat(thrown.getCause()).isNull();
    assertThat((Throwable) thrown.getNextException()).isNull();
    assertThat(thrown.getSuppressed()).isEmpty();
    assertThat(thrown.getSQLState()).isEqualTo("28P01");
    assertThat(thrown.getErrorCode()).isEqualTo(1045);
    assertThat(thrown.toString()).doesNotContain(SECRET);

    assertThat(logs)
        .contains("kind=AUTHENTICATION_FAILED", "sqlState=28P01", "vendorCode=1045")
        .doesNotContain(SECRET)
        .doesNotContain("FATAL");
  }

  @Test
  void runtimeExceptionsAndNullConnectionsFromTheDriverAreSanitizedToo() {
    FakeDriver driver = new FakeDriver();
    JdbcDatabaseProvider provider = new JdbcDatabaseProvider(new FakeProviderSpi(driver));
    String logs;
    try (LogCapture capture = new LogCapture()) {
      driver.mode = FakeDriver.Mode.THROW_RUNTIME;
      ConnectionTestResult runtime = provider.testConnection(settings(Map.of()), password());
      assertThat(runtime.failureKind()).isEqualTo(ConnectionFailureKind.CONNECTION_FAILED);
      driver.mode = FakeDriver.Mode.RETURN_NULL;
      ConnectionTestResult nothing = provider.testConnection(settings(Map.of()), password());
      assertThat(nothing.failureKind()).isEqualTo(ConnectionFailureKind.CONNECTION_FAILED);
      logs = capture.text();
    }
    assertThat(logs).doesNotContain(SECRET).contains("java.lang.IllegalStateException");
  }

  @Test
  void validationQueryFailureIsReportedWithoutDriverTextAndTheConnectionIsClosed() {
    FakeDriver driver = new FakeDriver();
    driver.validationFailure = new SQLException("syntax error near " + SECRET, "42601");
    ConnectionTestResult result;
    String logs;
    try (LogCapture capture = new LogCapture()) {
      result =
          new JdbcDatabaseProvider(new FakeProviderSpi(driver))
              .testConnection(settings(Map.of()), password());
      logs = capture.text();
    }
    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.VALIDATION_FAILED);
    assertThat(driver.connections.get(0).closed).isTrue();
    assertThat(logs).doesNotContain(SECRET);
    // 로그의 분류는 결과와 같다 (접속 실패로 적지 않는다).
    assertThat(logs)
        .contains("업무 DB 확인 쿼리 실패: type=POSTGRESQL, kind=VALIDATION_FAILED, sqlState=42601")
        .doesNotContain("kind=CONNECTION_FAILED")
        .doesNotContain("업무 DB 접속 실패");
  }

  @Test
  void aDriverThatBlocksForeverReturnsTimeoutWithinTheDeadlineAndLeavesNoThread() {
    FakeDriver driver = new FakeDriver();
    driver.mode = FakeDriver.Mode.BLOCK_INTERRUPTIBLY;
    Duration connectTimeout = Duration.ofSeconds(1);
    JdbcDatabaseProvider provider = new JdbcDatabaseProvider(new FakeProviderSpi(driver));

    long start = System.nanoTime();
    ConnectionTestResult result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(20),
            () ->
                provider.testConnection(
                    settings("db.example.com", connectTimeout, Map.of()), password()));
    Duration wall = Duration.ofNanos(System.nanoTime() - start);

    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.TIMEOUT);
    assertThat(result.message()).isEqualTo("DB 서버가 제한 시간 안에 응답하지 않았습니다.");
    assertThat(wall)
        .isGreaterThanOrEqualTo(connectTimeout)
        .isLessThan(connectTimeout.plusSeconds(5));
    assertThat(driver.connects).hasValue(1);
    assertThat(awaitNoTestThread(Duration.ofSeconds(5))).as("제한 시간 뒤 남은 테스트 스레드").isEmpty();
  }

  @Test
  void aDriverThatIgnoresInterruptsStillTimesOutAndItsLateConnectionIsClosed() throws Exception {
    FakeDriver driver = new FakeDriver();
    driver.mode = FakeDriver.Mode.BLOCK_IGNORING_INTERRUPTS;
    JdbcDatabaseProvider provider = new JdbcDatabaseProvider(new FakeProviderSpi(driver));

    ConnectionTestResult result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(20),
            () ->
                provider.testConnection(
                    settings("db.example.com", Duration.ofSeconds(1), Map.of()), password()));

    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.TIMEOUT);
    assertThat(result.elapsed()).isLessThan(Duration.ofSeconds(6));
    List<Thread> stuck = liveTestThreads();
    assertThat(stuck).isNotEmpty().allMatch(Thread::isDaemon);
    assertThat(driver.interruptIgnored).isTrue();

    driver.release.countDown();
    assertThat(awaitNoTestThread(Duration.ofSeconds(5))).isEmpty();
    assertThat(driver.connections).hasSize(1);
    FakeDriver.FakeConnection late = driver.connections.get(0);
    assertThat(late.closed).as("늦게 생긴 연결은 닫힌다").isTrue();
    assertThat(late.executed).as("늦은 연결로 확인 쿼리를 돌리지 않는다").isEmpty();
  }

  @Test
  void settingsForAnotherTypeAreRefused() {
    JdbcDatabaseProvider provider = new JdbcDatabaseProvider(new FakeProviderSpi(new FakeDriver()));
    ConnectionSettings mariadb =
        new ConnectionSettings(
            DatabaseType.MARIADB, "db", 3306, "app", "u", Duration.ofSeconds(2), 1, Map.of());
    assertThatThrownBy(() -> provider.testConnection(mariadb, password()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static List<Thread> liveTestThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(Thread::isAlive)
        .filter(thread -> thread.getName().startsWith(JdbcDatabaseProvider.THREAD_NAME_PREFIX))
        .toList();
  }

  static List<Thread> awaitNoTestThread(Duration limit) {
    long deadline = System.nanoTime() + limit.toNanos();
    List<Thread> live = liveTestThreads();
    while (!live.isEmpty() && System.nanoTime() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        break;
      }
      live = liveTestThreads();
    }
    return live;
  }
}
