package com.queryhub.adapter.dbprovider.provider.mariadb;

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
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** P1-07 AC17: MariaDB Provider 의 URL, 고정 속성, 옵션 허용 목록, 오류 분류. 네트워크는 닫힌 로컬 포트만 쓴다. */
class MariaDbJdbcProviderTest {

  private static final String PASSWORD = "unit-throwaway-9c41";

  private final MariaDbJdbcProvider provider = new MariaDbJdbcProvider();

  private static ConnectionSettings settings(String host, Map<String, String> options) {
    return new ConnectionSettings(
        DatabaseType.MARIADB, host, 3306, "app_db", "reader", Duration.ofSeconds(7), 3, options);
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
        .isEqualTo("jdbc:mariadb://db.example.org:3306/app_db");
    assertThat(provider.jdbcUrl(settings("192.0.2.10", Map.of())))
        .isEqualTo("jdbc:mariadb://192.0.2.10:3306/app_db");
    assertThat(provider.jdbcUrl(settings("2001:db8::1", Map.of())))
        .isEqualTo("jdbc:mariadb://[2001:db8::1]:3306/app_db");
    assertThat(provider.jdbcUrl(settings("[::1]", Map.of())))
        .isEqualTo("jdbc:mariadb://[::1]:3306/app_db");
    // 옵션은 URL 에 붙지 않는다 (속성으로 간다).
    assertThat(provider.jdbcUrl(settings("db", Map.of("sslMode", "verify-full"))))
        .isEqualTo("jdbc:mariadb://db:3306/app_db");
    assertThat(provider.driver()).isInstanceOf(org.mariadb.jdbc.Driver.class);
    assertThat(provider.type()).isEqualTo(DatabaseType.MARIADB);
    assertThat(provider.validationQuery()).isEqualTo("SELECT 1");
  }

  @Test
  void fixedPropertiesSetTimeoutsLocalInfileOffAndServerSideReadOnly() {
    assertThat(provider.fixedProperties(settings("db", Map.of())))
        .containsExactly(
            entry("connectTimeout", "7000"),
            entry("socketTimeout", "7000"),
            entry("allowLocalInfile", "false"),
            entry("readOnlyPropagatesToServer", "true"),
            entry("permitRedirect", "false"));
    assertThat(provider.allowedOptions()).containsExactly("sslMode");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "allowLocalInfile",
        "credentialType",
        "sessionVariables",
        "socketFactory",
        "sslfactory",
        "loggerFile",
        "authenticationPluginClassName",
        "service",
        "ALLOWLOCALINFILE",
        "connectTimeout",
        "socketTimeout",
        "readOnlyPropagatesToServer",
        "permitRedirect",
        "user",
        "Password",
        "sslmode"
      })
  void optionsOutsideTheAllowListAreRefusedByNameBeforeAnyConnection(String option)
      throws IOException {
    // 닫힌 로컬 포트: 허용 목록 검사가 망가져도 시험이 실제 서비스에 접속하지 않는다.
    int closedPort = closedLocalPort();
    String value = "option-value-marker-77";
    ConnectionTestResult result =
        new JdbcDatabaseProvider(provider)
            .testConnection(onPort(closedPort, Map.of(option, value)), password());

    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.OPTION_REJECTED);
    assertThat(result.message()).contains(option).doesNotContain(value);

    try (HikariConnectionPools pools = new HikariConnectionPools(List.of(provider))) {
      ConnectionId id = new ConnectionId("m1");
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
            DatabaseType.MARIADB,
            "127.0.0.1",
            closedPort,
            "app_db",
            "reader",
            Duration.ofSeconds(3),
            1,
            Map.of("sslMode", "disable"));

    ConnectionTestResult result =
        new JdbcDatabaseProvider(provider).testConnection(closed, password());

    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.UNREACHABLE);
    assertThat(result.toString()).doesNotContain(PASSWORD);
  }

  @Test
  void unknownDatabaseErrorIsDatabaseNotFoundAlsoWhenWrapped() {
    SQLException unknown = new SQLSyntaxErrorException("Unknown database 'x'", "42000", 1049);
    SQLException wrapped =
        new SQLNonTransientConnectionException("Could not connect", "08000", 0, unknown);

    assertThat(provider.classify(unknown)).isEqualTo(ConnectionFailureKind.DATABASE_NOT_FOUND);
    assertThat(provider.classify(wrapped)).isEqualTo(ConnectionFailureKind.DATABASE_NOT_FOUND);
    // 권한 없는 사용자: 없는 데이터베이스도 1044 (Access denied ... to database).
    assertThat(provider.classify(new SQLSyntaxErrorException("Access denied", "42000", 1044)))
        .isEqualTo(ConnectionFailureKind.DATABASE_NOT_FOUND);
    // 같은 SQLState 의 다른 오류는 데이터베이스 없음이 아니다.
    assertThat(provider.classify(new SQLSyntaxErrorException("syntax", "42000", 1064)))
        .isEqualTo(ConnectionFailureKind.CONNECTION_FAILED);
    assertThat(
            provider.classify(
                new SQLInvalidAuthorizationSpecException("Access denied", "28000", 1045)))
        .isEqualTo(ConnectionFailureKind.AUTHENTICATION_FAILED);
    assertThat(provider.classify(null)).isEqualTo(ConnectionFailureKind.CONNECTION_FAILED);
  }
}
