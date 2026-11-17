package com.queryhub.adapter.web.common.error;

import com.queryhub.domain.error.ErrorCode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * {@link ErrorCode} 와 HTTP 상태의 대응 (design-brief 5.2).
 *
 * <p>대응표는 default 가 없는 switch 식으로 만든다. 그래서 ErrorCode 에 코드를 더하고 여기에 상태를 정하지 않으면 컴파일이 실패한다. 결과는 모든
 * 코드를 담은 EnumMap 이다.
 */
public final class ErrorHttpStatus {

  private static final Map<ErrorCode, HttpStatus> STATUS_BY_CODE = buildTable();

  private ErrorHttpStatus() {}

  /** 오류 코드의 HTTP 상태. */
  public static HttpStatus of(ErrorCode code) {
    return STATUS_BY_CODE.get(Objects.requireNonNull(code, "code"));
  }

  /**
   * Spring MVC 나 서블릿 컨테이너가 정한 프로토콜 오류 상태에 쓸 코드.
   *
   * <p>404 는 NOT_FOUND, 405 는 METHOD_NOT_ALLOWED, 415 는 UNSUPPORTED_MEDIA_TYPE, 그 밖의 4xx 는
   * INVALID_REQUEST, 나머지는 INTERNAL_ERROR 다. 응답 상태는 원래 상태를 유지한다({@link
   * #responseStatus(HttpStatusCode)}).
   */
  public static ErrorCode protocolCode(HttpStatusCode status) {
    return switch (status.value()) {
      case 404 -> ErrorCode.NOT_FOUND;
      case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
      case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
      default -> status.is4xxClientError() ? ErrorCode.INVALID_REQUEST : ErrorCode.INTERNAL_ERROR;
    };
  }

  /** 프로토콜 오류에 실제로 보낼 상태. 4xx·5xx 는 그대로 두고, 오류 상태가 아니면 500 으로 바꾼다. */
  public static HttpStatusCode responseStatus(HttpStatusCode status) {
    return status.isError() ? status : HttpStatus.INTERNAL_SERVER_ERROR;
  }

  private static Map<ErrorCode, HttpStatus> buildTable() {
    EnumMap<ErrorCode, HttpStatus> table = new EnumMap<>(ErrorCode.class);
    for (ErrorCode code : ErrorCode.values()) {
      table.put(code, statusFor(code));
    }
    return Collections.unmodifiableMap(table);
  }

  private static HttpStatus statusFor(ErrorCode code) {
    return switch (code) {
      case API_NOT_FOUND, NOT_FOUND -> HttpStatus.NOT_FOUND;
      case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
      case FORBIDDEN -> HttpStatus.FORBIDDEN;
      case INVALID_PARAMETER, INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
      case QUERY_FAILED, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
      case QUERY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
      case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
      case ROW_LIMIT_EXCEEDED -> HttpStatus.UNPROCESSABLE_CONTENT;
      case SERVICE_STOPPED -> HttpStatus.SERVICE_UNAVAILABLE;
      case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
      case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
    };
  }
}
