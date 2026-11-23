package com.queryhub.adapter.dbprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryhub.domain.connection.ConnectionFailureKind;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.DatabaseType;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class StandardFailureClassifierTest {

  @Test
  void classifiesBySqlStateExceptionTypeAndCauseNeverByMessage() {
    assertThat(classify(new SQLException("x", "28P01")))
        .isEqualTo(ConnectionFailureKind.AUTHENTICATION_FAILED);
    assertThat(classify(new SQLInvalidAuthorizationSpecException("x")))
        .isEqualTo(ConnectionFailureKind.AUTHENTICATION_FAILED);
    assertThat(classify(new SQLException("x", "3D000")))
        .isEqualTo(ConnectionFailureKind.DATABASE_NOT_FOUND);
    assertThat(classify(new SQLException("x", "08001")))
        .isEqualTo(ConnectionFailureKind.UNREACHABLE);
    assertThat(classify(new SQLException("x", null, new ConnectException())))
        .isEqualTo(ConnectionFailureKind.UNREACHABLE);
    assertThat(classify(new SQLException("x", null, new UnknownHostException())))
        .isEqualTo(ConnectionFailureKind.UNREACHABLE);
    assertThat(classify(new SQLTimeoutException("x"))).isEqualTo(ConnectionFailureKind.TIMEOUT);
    assertThat(classify(new SQLException("x", "08001", new SocketTimeoutException())))
        .isEqualTo(ConnectionFailureKind.TIMEOUT);
    assertThat(classify(new SQLException("x", "08006", new SSLHandshakeException("h"))))
        .isEqualTo(ConnectionFailureKind.TLS_FAILED);
    assertThat(classify(new SQLException("password authentication failed", "XX000")))
        .as("메시지는 보지 않는다")
        .isEqualTo(ConnectionFailureKind.CONNECTION_FAILED);
    assertThat(classify(new SQLException("x", "28")))
        .as("5 자가 아닌 SQLState")
        .isEqualTo(ConnectionFailureKind.CONNECTION_FAILED);
    assertThat(classify(null)).isEqualTo(ConnectionFailureKind.CONNECTION_FAILED);
  }

  @Test
  void causeChainWalkStopsOnCycles() {
    SQLException outer = new SQLException("outer");
    RuntimeException inner = new RuntimeException("inner", outer);
    outer.initCause(inner);
    assertThat(StandardFailureClassifier.hasCause(outer, ConnectException.class)).isFalse();
  }

  @Test
  void sqlStateIsOnlyCopiedWhenItHasTheStandardShape() {
    assertThat(ConnectionFailedException.safeSqlState("28P01")).isEqualTo("28P01");
    assertThat(ConnectionFailedException.safeSqlState("pw=secret")).isNull();
    assertThat(ConnectionFailedException.safeSqlState(null)).isNull();
  }

  @Test
  void urlHelperBracketsIpv6AndChecksThePrefix() {
    ConnectionSettings v6 =
        new ConnectionSettings(
            DatabaseType.MARIADB, "[::1]", 3306, "app", "u", Duration.ofSeconds(1), 1, Map.of());
    ConnectionSettings v4 =
        new ConnectionSettings(
            DatabaseType.MARIADB, "10.0.0.1", 3306, "app", "u", Duration.ofSeconds(1), 1, Map.of());
    assertThat(JdbcUrls.host(v6)).isEqualTo("[::1]");
    assertThat(JdbcUrls.hostPortDatabase("jdbc:mariadb:", v6))
        .isEqualTo("jdbc:mariadb://[::1]:3306/app");
    assertThat(JdbcUrls.hostPortDatabase("jdbc:postgresql:", v4))
        .isEqualTo("jdbc:postgresql://10.0.0.1:3306/app");
    assertThatThrownBy(() -> JdbcUrls.hostPortDatabase("jdbc:x?y=1:", v4))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JdbcUrls.hostPortDatabase("mysql:", v4))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ConnectionFailureKind classify(SQLException failure) {
    return StandardFailureClassifier.classify(failure);
  }
}
