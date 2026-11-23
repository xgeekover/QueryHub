package com.queryhub.adapter.dbprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryhub.domain.connection.ConnectionId;
import com.queryhub.domain.connection.ConnectionPassword;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.DatabaseType;
import com.queryhub.domain.connection.InvalidConnectionSettingsException;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.management.ObjectName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** AC13: 접속 정보마다 읽기 전용 풀 하나, 이름·크기·MBean·닫기·다시 열기. */
class HikariConnectionPoolsTest {

  private static final String SECRET = JdbcDatabaseProviderTest.SECRET;
  private static final ConnectionId A = new ConnectionId("a1");
  private static final ConnectionId B = new ConnectionId("b2");

  private final FakeDriver driver = new FakeDriver();
  private final HikariConnectionPools pools =
      new HikariConnectionPools(List.of(new FakeProviderSpi(driver)), Duration.ZERO);

  @AfterEach
  void closeAll() {
    pools.closeAll();
  }

  private static ConnectionSettings settings(int maxPoolSize, Map<String, String> options) {
    return new ConnectionSettings(
        DatabaseType.POSTGRESQL,
        "db.example.com",
        5432,
        "app",
        "app_user",
        Duration.ofSeconds(1),
        maxPoolSize,
        options);
  }

  private static ConnectionPassword password() {
    return ConnectionPassword.of(SECRET.toCharArray());
  }

  @Test
  void opensOneReadOnlyPoolPerIdWithoutMbeansOrAStartupConnection() throws Exception {
    pools.open(A, settings(3, Map.of()), password());
    pools.open(B, settings(1, Map.of()), password());

    HikariDataSource a = pools.dataSource(A);
    assertThat(a.getPoolName()).isEqualTo("queryhub-conn-a1");
    assertThat(pools.dataSource(B).getPoolName()).isEqualTo("queryhub-conn-b2");
    assertThat(a.getMaximumPoolSize()).isEqualTo(3);
    assertThat(pools.dataSource(B).getMaximumPoolSize()).isEqualTo(1);
    assertThat(a.isReadOnly()).isTrue();
    assertThat(a.isRegisterMbeans()).isFalse();
    assertThat(a.getMinimumIdle()).isZero();
    assertThat(pools.isOpen(A)).isTrue();
    assertThat(driver.connects).as("여는 순간에는 접속하지 않는다").hasValue(0);

    try (Connection connection = pools.getConnection(A)) {
      assertThat(connection.isValid(1)).isTrue();
      assertThat(connection.isReadOnly()).isTrue();
    }
    assertThat(driver.connects).hasValue(1);
    assertThat(driver.connections.get(0).readOnlyCalls).contains(true);
    assertThat(driver.seenProperties.get(0)).containsEntry("password", SECRET);
    assertThat(
            ManagementFactory.getPlatformMBeanServer()
                .queryNames(new ObjectName("com.zaxxer.hikari:*"), null))
        .as("HikariCP MBean")
        .isEmpty();
  }

  @Test
  void closeAndCloseAllClosePoolsAndDestroyThePasswordCopies() {
    ConnectionPassword callerPassword = password();
    pools.open(A, settings(2, Map.of()), callerPassword);
    pools.open(B, settings(2, Map.of()), password());
    HikariDataSource a = pools.dataSource(A);
    HikariDataSource b = pools.dataSource(B);
    DriverConnectDataSource sourceA = pools.source(A);
    DriverConnectDataSource sourceB = pools.source(B);

    pools.close(A);
    assertThat(a.isClosed()).isTrue();
    assertThat(sourceA.isClosed()).isTrue();
    assertThat(pools.isOpen(A)).isFalse();
    assertThat(pools.isOpen(B)).isTrue();
    assertThatThrownBy(() -> pools.getConnection(A)).isInstanceOf(IllegalStateException.class);
    assertThat(callerPassword.isDestroyed()).as("호출한 쪽의 비밀번호는 지우지 않는다").isFalse();
    pools.close(A);

    pools.closeAll();
    assertThat(b.isClosed()).isTrue();
    assertThat(sourceB.isClosed()).isTrue();
    assertThat(pools.isOpen(B)).isFalse();
  }

  @Test
  void reopeningAnIdClosesTheOldPoolFirst() {
    pools.open(A, settings(2, Map.of()), password());
    HikariDataSource old = pools.dataSource(A);

    pools.open(A, settings(4, Map.of()), password());

    assertThat(old.isClosed()).isTrue();
    HikariDataSource current = pools.dataSource(A);
    assertThat(current).isNotSameAs(old);
    assertThat(current.isClosed()).isFalse();
    assertThat(current.getMaximumPoolSize()).isEqualTo(4);
    assertThat(current.getPoolName()).isEqualTo("queryhub-conn-a1");
  }

  @Test
  void aRefusedOptionOrTypeLeavesTheExistingPoolOpen() {
    pools.open(A, settings(2, Map.of()), password());
    HikariDataSource old = pools.dataSource(A);

    assertThatThrownBy(() -> pools.open(A, settings(2, Map.of("loggerFile", "/tmp/x")), password()))
        .isInstanceOfSatisfying(
            InvalidConnectionSettingsException.class,
            refused -> {
              assertThat(refused.rejectedOption()).isEqualTo("loggerFile");
              assertThat(refused.getMessage()).doesNotContain("/tmp/x");
            });
    ConnectionSettings mariadb =
        new ConnectionSettings(
            DatabaseType.MARIADB, "db", 3306, "app", "u", Duration.ofSeconds(1), 1, Map.of());
    assertThatThrownBy(() -> pools.open(A, mariadb, password()))
        .isInstanceOfSatisfying(
            InvalidConnectionSettingsException.class,
            refused -> assertThat(refused.field()).isEqualTo("type"));

    assertThat(pools.dataSource(A)).isSameAs(old);
    assertThat(old.isClosed()).isFalse();
  }

  @Test
  void maximumPoolSizeIsEnforced() throws Exception {
    pools.open(A, settings(1, Map.of()), password());
    try (Connection first = pools.getConnection(A)) {
      assertThat(first).isNotNull();
      assertThatThrownBy(() -> pools.getConnection(A)).isInstanceOf(SQLException.class);
    }
    assertThat(driver.connects).hasValue(1);
  }

  @Test
  void aFailingDriverYieldsAPoolExceptionAndLogsWithoutThePassword() {
    driver.mode = FakeDriver.Mode.THROW;
    driver.failure = () -> new SQLException("login failed for " + SECRET, "28000", 1045);
    pools.open(A, settings(1, Map.of()), password());

    SQLException thrown;
    String logs;
    try (LogCapture capture = new LogCapture()) {
      thrown =
          org.junit.jupiter.api.Assertions.assertThrows(
              SQLException.class, () -> pools.getConnection(A));
      logs = capture.text();
    }

    List<String> chain = new ArrayList<>();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      chain.add(t.getClass().getName() + ": " + t.getMessage() + " " + t);
      for (Throwable suppressed : t.getSuppressed()) {
        chain.add(suppressed.toString());
      }
    }
    assertThat(chain).isNotEmpty().allSatisfy(text -> assertThat(text).doesNotContain(SECRET));
    assertThat(chain).anySatisfy(text -> assertThat(text).contains("ConnectionFailedException"));
    assertThat(logs).contains("queryhub-conn-a1").doesNotContain(SECRET);
  }
}
