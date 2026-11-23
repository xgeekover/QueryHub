package com.queryhub.domain.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ConnectionTestResultTest {

  @Test
  void successKeepsSanitizedProductTextAndAFixedMessage() {
    ConnectionTestResult result =
        ConnectionTestResult.success(
            "PostgreSQL\n\u0000", " 16.15 " + "x".repeat(200), Duration.ofMillis(12));
    assertThat(result.succeeded()).isTrue();
    assertThat(result.outcome()).isEqualTo(ConnectionTestOutcome.SUCCESS);
    assertThat(result.failureKind()).isNull();
    assertThat(result.productName()).isEqualTo("PostgreSQL");
    assertThat(result.productVersion()).startsWith("16.15").hasSizeLessThanOrEqualTo(128);
    assertThat(result.message()).isEqualTo("연결에 성공했습니다.");
    assertThat(ConnectionTestResult.success(null, null, Duration.ZERO).productName()).isEmpty();
  }

  @Test
  void everyFailureKindHasAFixedKoreanMessage() {
    for (ConnectionFailureKind kind : EnumSet.allOf(ConnectionFailureKind.class)) {
      ConnectionTestResult result = ConnectionTestResult.failure(kind, Duration.ofSeconds(1));
      assertThat(result.succeeded()).isFalse();
      assertThat(result.message()).isNotBlank().isEqualTo(kind.message(null));
      assertThat(result.productName()).isNull();
    }
    assertThat(ConnectionFailureKind.OPTION_REJECTED.message(null)).isEqualTo("허용되지 않은 접속 옵션입니다.");
  }

  /**
   * P1-07: MariaDB 는 일반 사용자에게 없는 DB 와 권한 없는 DB 를 같은 1044 로 알린다. 그래서 DATABASE_NOT_FOUND 의 고정 메시지는 두
   * 원인을 모두 말하고 이름과 권한을 함께 확인하라고 한다(ADR-027).
   */
  @Test
  void databaseNotFoundMessageNamesBothTheMissingDatabaseAndMissingPrivileges() {
    assertThat(ConnectionFailureKind.DATABASE_NOT_FOUND.message(null))
        .isEqualTo("데이터베이스를 찾을 수 없거나 이 사용자에게 접근 권한이 없습니다. 데이터베이스 이름과 사용자 권한을 확인하세요.")
        .contains("이름")
        .contains("권한")
        .isEqualTo(ConnectionFailureKind.DATABASE_NOT_FOUND.message("ignored"));
  }

  @Test
  void optionRejectionNamesTheOptionOnly() {
    ConnectionTestResult result =
        ConnectionTestResult.optionRejected("socketFactory", Duration.ZERO);
    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.OPTION_REJECTED);
    assertThat(result.message()).isEqualTo("허용되지 않은 접속 옵션입니다: socketFactory");
    assertThatThrownBy(() -> ConnectionTestResult.optionRejected("bad name=x", Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    InvalidConnectionSettingsException refusal =
        InvalidConnectionSettingsException.optionNotAllowed("socketFactory");
    assertThat(refusal.rejectedOption()).isEqualTo("socketFactory");
    assertThat(refusal.field()).isEqualTo("options");
    assertThat(refusal.getMessage()).isEqualTo(result.message());
    assertThat(InvalidConnectionSettingsException.unsupportedType(DatabaseType.ORACLE).field())
        .isEqualTo("type");
  }

  @Test
  void inconsistentResultsAreRefused() {
    assertThatThrownBy(
            () ->
                new ConnectionTestResult(
                    ConnectionTestOutcome.SUCCESS,
                    ConnectionFailureKind.TIMEOUT,
                    "x",
                    "y",
                    Duration.ZERO,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ConnectionTestResult(
                    ConnectionTestOutcome.FAILURE,
                    ConnectionFailureKind.TIMEOUT,
                    "driver text",
                    null,
                    Duration.ZERO,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ConnectionTestResult(
                    ConnectionTestOutcome.FAILURE,
                    ConnectionFailureKind.TIMEOUT,
                    null,
                    null,
                    Duration.ZERO,
                    "sslmode"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ConnectionTestResult.failure(ConnectionFailureKind.TIMEOUT, Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void connectionIdIsRestrictedToPoolNameSafeCharacters() {
    assertThat(new ConnectionId("conn-1_A").value()).isEqualTo("conn-1_A");
    assertThat(new ConnectionId("42").toString()).isEqualTo("42");
    for (String bad : new String[] {"", "-x", "a b", "a:b", "a/b", "a".repeat(65), "한글"}) {
      assertThatThrownBy(() -> new ConnectionId(bad)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new ConnectionId(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
