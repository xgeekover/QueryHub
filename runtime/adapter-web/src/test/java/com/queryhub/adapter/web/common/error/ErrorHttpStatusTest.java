package com.queryhub.adapter.web.common.error;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryhub.domain.error.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;

class ErrorHttpStatusTest {

  /** 계약표 (design-brief 5.2 와 프로토콜 코드). 코드를 더하면 여기에도 더해야 테스트가 통과한다. */
  private static final Map<ErrorCode, Integer> CONTRACT =
      Map.ofEntries(
          entry(ErrorCode.API_NOT_FOUND, 404),
          entry(ErrorCode.UNAUTHORIZED, 401),
          entry(ErrorCode.FORBIDDEN, 403),
          entry(ErrorCode.INVALID_PARAMETER, 400),
          entry(ErrorCode.QUERY_FAILED, 500),
          entry(ErrorCode.QUERY_TIMEOUT, 504),
          entry(ErrorCode.RATE_LIMITED, 429),
          entry(ErrorCode.ROW_LIMIT_EXCEEDED, 422),
          entry(ErrorCode.SERVICE_STOPPED, 503),
          entry(ErrorCode.INTERNAL_ERROR, 500),
          entry(ErrorCode.NOT_FOUND, 404),
          entry(ErrorCode.METHOD_NOT_ALLOWED, 405),
          entry(ErrorCode.INVALID_REQUEST, 400),
          entry(ErrorCode.UNSUPPORTED_MEDIA_TYPE, 415));

  @Test
  void mapsEveryErrorCodeToItsContractStatus() {
    for (ErrorCode code : ErrorCode.values()) {
      assertThat(CONTRACT).as("계약표에 없는 오류 코드: %s", code).containsKey(code);
      assertThat(ErrorHttpStatus.of(code)).as("상태가 정해지지 않은 오류 코드: %s", code).isNotNull();
      assertThat(ErrorHttpStatus.of(code).value()).as(code.name()).isEqualTo(CONTRACT.get(code));
    }
  }

  @Test
  void contractCoversExactlyTheDefinedCodes() {
    assertThat(CONTRACT.keySet()).containsExactlyInAnyOrder(ErrorCode.values());
  }

  @Test
  void rejectsANullCode() {
    assertThatThrownBy(() -> ErrorHttpStatus.of(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void mapsProtocolStatusesToProtocolCodes() {
    assertThat(protocolCode(404)).isEqualTo(ErrorCode.NOT_FOUND);
    assertThat(protocolCode(405)).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED);
    assertThat(protocolCode(415)).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    assertThat(protocolCode(400)).isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(protocolCode(406)).isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(protocolCode(413)).isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(protocolCode(500)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    assertThat(protocolCode(503)).isEqualTo(ErrorCode.INTERNAL_ERROR);
  }

  @Test
  void keepsErrorStatusesAndTurnsNonErrorStatusesInto500() {
    assertThat(ErrorHttpStatus.responseStatus(HttpStatusCode.valueOf(406)).value()).isEqualTo(406);
    assertThat(ErrorHttpStatus.responseStatus(HttpStatusCode.valueOf(503)).value()).isEqualTo(503);
    assertThat(ErrorHttpStatus.responseStatus(HttpStatusCode.valueOf(200)).value()).isEqualTo(500);
    assertThat(ErrorHttpStatus.responseStatus(HttpStatusCode.valueOf(302)).value()).isEqualTo(500);
  }

  private static ErrorCode protocolCode(int status) {
    return ErrorHttpStatus.protocolCode(HttpStatusCode.valueOf(status));
  }
}
