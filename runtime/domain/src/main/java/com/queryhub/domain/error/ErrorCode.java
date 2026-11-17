package com.queryhub.domain.error;

/**
 * 클라이언트와 공유하는 오류 코드 어휘.
 *
 * <p>코드 이름은 API 계약의 일부라서 바꾸지 않는다. 각 코드는 클라이언트에 그대로 보여도 되는 고정 한국어 기본 메시지를 가진다. HTTP 상태와의 대응은 웹
 * 어댑터(adapter-web)가 정한다. domain 은 HTTP 를 모른다.
 */
public enum ErrorCode {
  // 데이터 API 계약 코드 (design-brief 5.2)
  API_NOT_FOUND("요청한 API 를 찾을 수 없습니다."),
  UNAUTHORIZED("인증이 필요합니다."),
  FORBIDDEN("요청한 작업에 대한 권한이 없습니다."),
  INVALID_PARAMETER("요청 파라미터가 올바르지 않습니다."),
  QUERY_FAILED("쿼리 실행에 실패했습니다."),
  QUERY_TIMEOUT("쿼리 실행 시간이 초과되었습니다."),
  RATE_LIMITED("요청 한도를 초과했습니다. 잠시 후 다시 시도하세요."),
  ROW_LIMIT_EXCEEDED("결과 행 수가 허용 한도를 초과했습니다."),
  SERVICE_STOPPED("API 서비스가 중지되어 있습니다."),
  INTERNAL_ERROR("서버 내부 오류가 발생했습니다."),

  // 프로토콜 수준 코드 (없는 경로, 지원하지 않는 메서드, 깨진 요청)
  NOT_FOUND("요청한 리소스를 찾을 수 없습니다."),
  METHOD_NOT_ALLOWED("지원하지 않는 HTTP 메서드입니다."),
  INVALID_REQUEST("요청 형식이 올바르지 않습니다."),
  UNSUPPORTED_MEDIA_TYPE("지원하지 않는 Content-Type 입니다.");

  private final String defaultMessage;

  ErrorCode(String defaultMessage) {
    this.defaultMessage = defaultMessage;
  }

  /** 클라이언트에 보여도 안전한 고정 기본 메시지. */
  public String defaultMessage() {
    return defaultMessage;
  }
}
