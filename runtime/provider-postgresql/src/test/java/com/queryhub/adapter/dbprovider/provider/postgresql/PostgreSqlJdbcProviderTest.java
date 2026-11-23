package com.queryhub.adapter.dbprovider.provider.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.queryhub.adapter.dbprovider.HikariConnectionPools;
import com.queryhub.adapter.dbprovider.JdbcDatabaseProvider;
import com.queryhub.domain.connection.ConnectionFailureKind;
import com.queryhub.domain.connection.ConnectionId;
import com.queryhub.domain.connection.ConnectionPassword;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.ConnectionTestResult;
import com.queryhub.domain.connection.DatabaseType;
import com.queryhub.domain.connection.InvalidConnectionSettingsException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** P1-07 AC17: PostgreSQL Provider 의 URL, 고정 속성, 옵션 허용 목록, 오류 분류. 네트워크는 닫힌 로컬 포트만 쓴다. */
class PostgreSqlJdbcProviderTest {

  private static final String PASSWORD = "unit-throwaway-3b8e";

  private final PostgreSqlJdbcProvider provider = new PostgreSqlJdbcProvider();

  private static ConnectionSettings settings(
      String host, Duration timeout, Map<String, String> options) {
    return new ConnectionSettings(
        DatabaseType.POSTGRESQL, host, 5432, "app_db", "reader", timeout, 2, options);
  }

  private static ConnectionSettings settings(String host, Map<String, String> options) {
    return settings(host, Duration.ofSeconds(5), options);
  }

  private static int closedLocalPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static ConnectionSettings onPort(int port, Map<String, String> options) {
    ConnectionSettings base = settings("127.0.0.1", options);
    return new ConnectionSettings(
        base.type(),
        base.host(),
        port,
        base.databaseName(),
        base.username(),
        base.connectTimeout(),
        base.maxPoolSize(),
        base.options());
  }

  private static ConnectionPassword password() {
    return ConnectionPassword.of(PASSWORD.toCharArray());
  }

  @Test
  void urlsAreBuiltFromTheFieldsOnly() {
    assertThat(provider.jdbcUrl(settings("db.example.org", Map.of())))
        .isEqualTo("jdbc:postgresql://db.example.org:5432/app_db");
    assertThat(provider.jdbcUrl(settings("198.51.100.7", Map.of())))
        .isEqualTo("jdbc:postgresql://198.51.100.7:5432/app_db");
    assertThat(provider.jdbcUrl(settings("fe80::1", Map.of())))
        .isEqualTo("jdbc:postgresql://[fe80::1]:5432/app_db");
    assertThat(provider.jdbcUrl(settings("db", Map.of("sslmode", "require"))))
        .isEqualTo("jdbc:postgresql://db:5432/app_db");
    assertThat(provider.driver()).isInstanceOf(org.postgresql.Driver.class);
    assertThat(provider.type()).isEqualTo(DatabaseType.POSTGRESQL);
    assertThat(provider.validationQuery()).isEqualTo("SELECT 1");
  }

  @Test
  void fixedPropertiesSetTimeoutsInSecondsAndReadOnlyModeAlways() {
    assertThat(provider.fixedProperties(settings("db", Map.of())))
        .containsExactly(
            entry("connectTimeout", "5"),
            entry("socketTimeout", "5"),
            entry("loginTimeout", "8"),
            entry("readOnlyMode", "always"));
    // 초 단위 올림: 1.2 초는 2 초다.
    assertThat(provider.fixedProperties(settings("db", Duration.ofMillis(1200), Map.of())))
        .containsEntry("connectTimeout", "2")
        .containsEntry("socketTimeout", "2")
        .containsEntry("loginTimeout", "5");
    // loginTimeout 은 공통 제한 시간(접속 제한 시간 + 여유)보다 늦다.
    assertThat(Duration.ofSeconds(8))
        .isGreaterThan(Duration.ofSeconds(5).plus(JdbcDatabaseProvider.DEADLINE_MARGIN));
    assertThat(provider.allowedOptions()).containsExactly("sslmode");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "socketFactory",
        "socketFactoryArg",
        "sslfactory",
        "sslFactory",
        "loggerFile",
        "loggerLevel",
        "authenticationPluginClassName",
        "service",
        "allowLocalInfile",
        "credentialType",
        "sessionVariables",
        "sslpassword",
        "sslkey",
        "options",
        "readOnlyMode",
        "READONLYMODE",
        "loginTimeout",
        "socketTimeout",
        "connectTimeout",
        "password",
        "USER",
        "sslMode"
      })
  void optionsOutsideTheAllowListAreRefusedByNameBeforeAnyConnection(String option)
      throws IOException {
    // 닫힌 로컬 포트: 허용 목록 검사가 망가져도 시험이 실제 서비스에 접속하지 않는다.
    int closedPort = closedLocalPort();
    String value = "option-value-marker-31";
    ConnectionTestResult result =
        new JdbcDatabaseProvider(provider)
            .testConnection(onPort(closedPort, Map.of(option, value)), password());

    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.OPTION_REJECTED);
    assertThat(result.message()).contains(option).doesNotContain(value);

    try (HikariConnectionPools pools = new HikariConnectionPools(List.of(provider))) {
      ConnectionId id = new ConnectionId("p1");
      assertThatThrownBy(
              () -> pools.open(id, onPort(closedPort, Map.of(option, value)), password()))
          .isInstanceOf(InvalidConnectionSettingsException.class)
          .hasMessageContaining(option)
          .hasMessageNotContaining(value);
      assertThat(pools.isOpen(id)).isFalse();
    }
  }

  @Test
  void theTlsModeOptionIsAcceptedAndAClosedPortIsUnreachable() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    ConnectionSettings closed =
        new ConnectionSettings(
            DatabaseType.POSTGRESQL,
            "127.0.0.1",
            closedPort,
            "app_db",
            "reader",
            Duration.ofSeconds(3),
            1,
            Map.of("sslmode", "disable"));

    ConnectionTestResult result =
        new JdbcDatabaseProvider(provider).testConnection(closed, password());

    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.UNREACHABLE);
    assertThat(result.toString()).doesNotContain(PASSWORD);
  }

  @Test
  void classificationUsesTheStandardSqlStates() {
    assertThat(provider.classify(new SQLException("database does not exist", "3D000")))
        .isEqualTo(ConnectionFailureKind.DATABASE_NOT_FOUND);
    assertThat(provider.classify(new SQLException("password authentication failed", "28P01")))
        .isEqualTo(ConnectionFailureKind.AUTHENTICATION_FAILED);
    assertThat(
            provider.classify(
                new SQLException("refused", "08001", new java.net.ConnectException())))
        .isEqualTo(ConnectionFailureKind.UNREACHABLE);
    assertThat(
            provider.classify(
                new SQLException("timed out", "08001", new java.net.SocketTimeoutException())))
        .isEqualTo(ConnectionFailureKind.TIMEOUT);
  }
}
