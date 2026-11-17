package com.queryhub.domain.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class QueryHubExceptionTest {

  private static final String SECRET = "ORA-00942: table or view SECRET_TABLE does not exist";

  @Test
  void keepsCodeAndClientMessage() {
    QueryHubException exception =
        new QueryHubException(ErrorCode.INVALID_PARAMETER, "limit 은 1 이상이어야 합니다.");

    assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER);
    assertThat(exception.clientMessage()).isEqualTo("limit 은 1 이상이어야 합니다.");
    assertThat(exception.getMessage()).isEqualTo("limit 은 1 이상이어야 합니다.");
  }

  @Test
  void clientMessageNeverContainsTheCauseMessage() {
    IllegalStateException cause = new IllegalStateException(SECRET);

    QueryHubException exception =
        new QueryHubException(ErrorCode.QUERY_FAILED, "쿼리를 실행하지 못했습니다.", cause);

    assertThat(exception.getCause()).isSameAs(cause);
    assertThat(exception.clientMessage()).doesNotContain(SECRET).doesNotContain("SECRET_TABLE");
    assertThat(exception.getMessage()).doesNotContain(SECRET);
  }

  @Test
  void causeWithoutClientMessageFallsBackToTheDefaultMessage() {
    QueryHubException exception =
        new QueryHubException(ErrorCode.QUERY_FAILED, null, new IllegalStateException(SECRET));

    assertThat(exception.clientMessage()).isEqualTo(ErrorCode.QUERY_FAILED.defaultMessage());
    assertThat(exception.clientMessage()).doesNotContain(SECRET);
  }

  @Test
  void usesTheDefaultMessageWhenOnlyTheCodeIsGiven() {
    QueryHubException exception = new QueryHubException(ErrorCode.NOT_FOUND);

    assertThat(exception.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    assertThat(exception.clientMessage()).isEqualTo(ErrorCode.NOT_FOUND.defaultMessage());
    assertThat(exception.getCause()).isNull();
  }

  @Test
  void blankClientMessageFallsBackToTheDefaultMessage() {
    QueryHubException exception = new QueryHubException(ErrorCode.FORBIDDEN, "   ");

    assertThat(exception.clientMessage()).isEqualTo(ErrorCode.FORBIDDEN.defaultMessage());
  }

  @Test
  void rejectsMissingErrorCode() {
    assertThatThrownBy(() -> new QueryHubException(null, "메시지"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("errorCode");
  }

  @Test
  void everyErrorCodeHasANonBlankDefaultMessage() {
    assertThat(ErrorCode.values()).hasSize(14);
    assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::defaultMessage))
        .allSatisfy(message -> assertThat(message).isNotBlank());
  }
}
