package com.queryhub.app.it.dbprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.queryhub.adapter.dbprovider.HikariConnectionPools;
import com.queryhub.adapter.dbprovider.JdbcDatabaseProvider;
import com.queryhub.adapter.dbprovider.JdbcProviderSpi;
import com.queryhub.domain.connection.ConnectionFailureKind;
import com.queryhub.domain.connection.ConnectionId;
import com.queryhub.domain.connection.ConnectionPassword;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.ConnectionTestResult;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * P1-07 AC18, AC19: 한 Provider 를 실제 서버(로컬 이미지 컨테이너)로 시험한다. 하위 클래스가 컨테이너 하나를 클래스 동안 띄운다 (한 번에 한 컨테이너
 * 클래스).
 *
 * <p>풀과 접속 테스트는 읽기 사용자({@value #READER}, 시험 표 {@value #TABLE} 에 SELECT 와 INSERT 권한)로 접속한다. 세션 수와 표
 * 내용은 관리자 연결로 본다. 비밀번호는 실행마다 새로 만드는 버리는 값이다.
 */
abstract class DatabaseProviderContainerTest {

  static final String DATABASE = "qh_it";
  static final String READER = "qh_reader";
  static final String TABLE = "probe";
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  static final Duration SHORT_TIMEOUT = Duration.ofSeconds(2);

  /** AC18: 응답 없는 서버의 TIMEOUT 은 접속 제한 시간 + 5 초 안에 온다. */
  static final Duration TIMEOUT_ALLOWANCE = Duration.ofSeconds(5);

  static String throwawayPassword() {
    return "it-" + UUID.randomUUID();
  }

  /** 클래스의 컨테이너 (127.0.0.1 에만 포트를 연다). */
  abstract JdbcDatabaseContainer<?> container();

  abstract int containerPort();

  abstract JdbcProviderSpi spi();

  /** 컨테이너 관리자(슈퍼유저)의 연결. 세션 수와 표 내용을 본다. */
  abstract Connection adminConnection() throws SQLException;

  /** 읽기 사용자와 시험 표를 만드는 SQL (관리자로 한 번 실행). */
  abstract List<String> readerSetupSql(String readerPassword);

  /** 사용자의 서버 세션 수 (매개변수 1 개: 사용자 이름). */
  abstract String sessionCountSql();

  abstract String readerPassword();

  abstract String productNameFragment();

  /** 읽기 사용자를 만든 시험 클래스 (JUnit 은 시험 메서드마다 인스턴스를 새로 만든다). */
  private static final Set<Class<?>> READER_CREATED = ConcurrentHashMap.newKeySet();

  @BeforeEach
  void createReaderOnce() throws SQLException {
    if (READER_CREATED.contains(getClass())) {
      return;
    }
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement()) {
      for (String sql : readerSetupSql(readerPassword())) {
        statement.execute(sql);
      }
    }
    READER_CREATED.add(getClass());
  }

  int mappedPort() {
    return container().getMappedPort(containerPort());
  }

  ConnectionSettings readerSettings(String database, Duration timeout, int maxPoolSize) {
    return new ConnectionSettings(
        spi().type(),
        LoopbackPorts.LOOPBACK,
        mappedPort(),
        database,
        READER,
        timeout,
        maxPoolSize,
        Map.of());
  }

  ConnectionSettings localSettings(int port, Duration timeout) {
    return new ConnectionSettings(
        spi().type(), LoopbackPorts.LOOPBACK, port, DATABASE, READER, timeout, 1, Map.of());
  }

  static ConnectionPassword password(String value) {
    return ConnectionPassword.of(value.toCharArray());
  }

  JdbcDatabaseProvider provider() {
    return new JdbcDatabaseProvider(spi());
  }

  @Test
  void containerPortsArePublishedOnLoopbackOnly() {
    assertThat(LoopbackPorts.publishedHostIps(container()))
        .as("docker inspect NetworkSettings.Ports 의 HostIp")
        .isNotEmpty()
        .containsOnly(LoopbackPorts.LOOPBACK);
    assertThat(container().getContainerInfo().getHostConfig().getPublishAllPorts()).isFalse();
  }

  @Test
  void successReportsProductNameAndVersionWithoutLeakingThePassword() {
    ConnectionTestResult result;
    String logs;
    try (SecretCapture capture = new SecretCapture()) {
      result =
          provider()
              .testConnection(
                  readerSettings(DATABASE, CONNECT_TIMEOUT, 1), password(readerPassword()));
      logs = capture.text();
    }
    assertThat(result.succeeded()).as("%s", result).isTrue();
    assertThat(result.productName()).containsIgnoringCase(productNameFragment());
    assertThat(result.productVersion()).isNotBlank();
    assertThat(result.toString() + result.message() + logs).doesNotContain(readerPassword());
  }

  @Test
  void wrongPasswordIsAuthenticationFailedAndThePasswordAppearsNowhere() throws Exception {
    String wrong = throwawayPassword();
    ConnectionTestResult result;
    String poolFailure;
    String logs;
    try (SecretCapture capture = new SecretCapture();
        HikariConnectionPools pools = new HikariConnectionPools(List.of(spi()))) {
      result =
          provider().testConnection(readerSettings(DATABASE, CONNECT_TIMEOUT, 1), password(wrong));

      ConnectionId id = new ConnectionId("wrong-password");
      pools.open(id, readerSettings(DATABASE, SHORT_TIMEOUT, 1), password(wrong));
      try (Connection unexpected = pools.getConnection(id)) {
        fail("틀린 비밀번호로 풀 연결이 생겼다: " + unexpected);
        return;
      } catch (SQLException expected) {
        poolFailure = SecretCapture.describe(expected);
      }
      logs = capture.text();
      assertThat(capture.eventCount()).as("붙잡은 로그가 있어야 검사가 뜻이 있다").isPositive();
    }

    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.AUTHENTICATION_FAILED);
    assertThat(result.message())
        .isEqualTo(ConnectionFailureKind.AUTHENTICATION_FAILED.message(null));
    for (String secret : List.of(wrong, readerPassword())) {
      assertThat(result.toString()).doesNotContain(secret);
      assertThat(result.message()).doesNotContain(secret);
      assertThat(poolFailure).doesNotContain(secret);
      assertThat(logs).doesNotContain(secret);
    }
  }

  @Test
  void unknownDatabaseIsDatabaseNotFound() {
    ConnectionTestResult result =
        provider()
            .testConnection(
                readerSettings("qh_no_such_db", CONNECT_TIMEOUT, 1), password(readerPassword()));

    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.DATABASE_NOT_FOUND);
  }

  @Test
  void closedLocalPortIsUnreachable() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    ConnectionTestResult result =
        provider()
            .testConnection(localSettings(closedPort, CONNECT_TIMEOUT), password(readerPassword()));

    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.UNREACHABLE);
  }

  @Test
  void silentServerTimesOutWithinTheDeadlineAndLeavesNoTestThread() throws Exception {
    try (SilentServer silent = new SilentServer()) {
      long start = System.nanoTime();
      ConnectionTestResult result =
          provider()
              .testConnection(
                  localSettings(silent.port(), SHORT_TIMEOUT), password(readerPassword()));
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

      assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.TIMEOUT);
      assertThat(elapsed).isLessThan(SHORT_TIMEOUT.plus(TIMEOUT_ALLOWANCE));
      assertThat(silent.acceptedCount()).as("드라이버가 실제로 접속했다").isPositive();
      // 드라이버가 포기하고 소켓을 닫는다 (자기 읽기 제한 시간 안에).
      assertThat(
              silent.awaitPeerClose(
                  SHORT_TIMEOUT.plus(TIMEOUT_ALLOWANCE).toMillis(), TimeUnit.MILLISECONDS))
          .as("드라이버가 응답 없는 연결을 닫았다")
          .isTrue();
      assertThat(awaitNoThread("queryhub-conn-test-", Duration.ofSeconds(3)))
          .as("남은 접속 테스트 스레드")
          .isEmpty();
      System.out.printf(
          "[P1-07] %s silent server: TIMEOUT after %d ms%n", spi().type(), elapsed.toMillis());
    }
  }

  @Test
  void poolServesSelectAndRefusesWrites() throws SQLException {
    try (HikariConnectionPools pools = new HikariConnectionPools(List.of(spi()))) {
      ConnectionId id = new ConnectionId("read-only");
      pools.open(id, readerSettings(DATABASE, CONNECT_TIMEOUT, 2), password(readerPassword()));
      try (Connection connection = pools.getConnection(id);
          Statement statement = connection.createStatement()) {
        assertThat(connection.isReadOnly()).isTrue();
        try (ResultSet rows = statement.executeQuery("SELECT 1")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isEqualTo(1);
        }
        assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO " + TABLE + " VALUES (1)"))
            .as("읽기 전용 풀 연결의 INSERT")
            .isInstanceOf(SQLException.class)
            .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("25006"));
      }
    }
    assertThat(rowCount()).as("거부된 INSERT 뒤 표").isZero();

    // 거부는 권한이 아니라 읽기 전용 세션 때문이다: 같은 사용자가 풀 밖의 일반 연결로는 쓸 수 있다.
    try (Connection plain = readerPlainConnection();
        Statement statement = plain.createStatement()) {
      assertThat(statement.executeUpdate("INSERT INTO " + TABLE + " VALUES (2)")).isEqualTo(1);
    }
    assertThat(rowCount()).isEqualTo(1);
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement()) {
      statement.executeUpdate("DELETE FROM " + TABLE);
    }
  }

  @Test
  void closingAPoolEndsItsServerSessions() throws Exception {
    try (HikariConnectionPools pools = new HikariConnectionPools(List.of(spi()))) {
      ConnectionId id = new ConnectionId("close-check");
      pools.open(id, readerSettings(DATABASE, CONNECT_TIMEOUT, 2), password(readerPassword()));
      try (Connection first = pools.getConnection(id);
          Connection second = pools.getConnection(id)) {
        for (Connection connection : List.of(first, second)) {
          try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
          }
        }
      }
      assertThat(awaitSessions(2, Duration.ofSeconds(10))).as("풀의 세션").isEqualTo(2);

      pools.close(id);

      assertThat(pools.isOpen(id)).isFalse();
      assertThat(awaitSessions(0, Duration.ofSeconds(10))).as("close(id) 뒤 세션").isZero();
    }
  }

  /** 읽기 사용자의 일반 연결 (풀 아님, 읽기 전용 아님). 컨테이너의 드라이버로 직접 연다. */
  Connection readerPlainConnection() throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", READER);
    properties.setProperty("password", readerPassword());
    return container()
        .getJdbcDriverInstance()
        .connect(spi().jdbcUrl(readerSettings(DATABASE, CONNECT_TIMEOUT, 1)), properties);
  }

  int rowCount() throws SQLException {
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement();
        ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
      rows.next();
      return rows.getInt(1);
    }
  }

  int sessions() throws SQLException {
    try (Connection admin = adminConnection();
        PreparedStatement statement = admin.prepareStatement(sessionCountSql())) {
      statement.setString(1, READER);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private int awaitSessions(int expected, Duration limit) throws Exception {
    long deadline = System.nanoTime() + limit.toNanos();
    int current = sessions();
    while (current != expected && System.nanoTime() < deadline) {
      Thread.sleep(100);
      current = sessions();
    }
    return current;
  }

  static List<String> awaitNoThread(String prefix, Duration limit) throws InterruptedException {
    long deadline = System.nanoTime() + limit.toNanos();
    List<String> alive = threads(prefix);
    while (!alive.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(50);
      alive = threads(prefix);
    }
    return alive;
  }

  private static List<String> threads(String prefix) {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(Thread::isAlive)
        .map(Thread::getName)
        .filter(name -> name.startsWith(prefix))
        .toList();
  }
}
