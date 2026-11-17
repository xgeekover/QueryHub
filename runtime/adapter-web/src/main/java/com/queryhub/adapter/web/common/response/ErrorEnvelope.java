package com.queryhub.adapter.web.common.response;

import com.queryhub.domain.error.ErrorCode;
import java.util.Objects;

/**
 * 공통 오류 응답 형식: {@code {"success": false, "error": {"code", "message", "requestId"}}}.
 *
 * <p>message 에는 {@link ErrorCode} 의 고정 메시지나 QueryHubException 의 클라이언트 메시지만 넣는다. 예외 메시지, 클래스 이름, 스택
 * 트레이스, SQL, DB 정보는 절대 넣지 않는다. 상세 내용은 requestId 로 서버 로그에서 찾는다.
 */
public record ErrorEnvelope(boolean success, Detail error) {

  public ErrorEnvelope {
    if (success) {
      throw new IllegalArgumentException("ErrorEnvelope.success must be false");
    }
    Objects.requireNonNull(error, "error");
  }

  public static ErrorEnvelope of(ErrorCode code, String message, String requestId) {
    return new ErrorEnvelope(false, new Detail(code.name(), message, requestId));
  }

  /** 오류 내용. code 는 ErrorCode 이름이다. */
  public record Detail(String code, String message, String requestId) {}
}
