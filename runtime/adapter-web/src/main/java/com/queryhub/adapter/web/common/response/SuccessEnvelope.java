package com.queryhub.adapter.web.common.response;

/**
 * 공통 성공 응답 형식: {@code {"success": true, "data": ..., "meta": {"requestId": ...}}}.
 *
 * <p>ProblemDetail 이나 Spring 의 ErrorResponse 를 쓰지 않고 전용 레코드를 둔다. 필드가 항상 존재해야 하는 계약이기 때문이다. 데이터 API
 * 의 meta 필드(rowCount, executionTimeMs, truncated)는 Phase 4 에서 더한다.
 */
public record SuccessEnvelope<T>(boolean success, T data, Meta meta) {

  public SuccessEnvelope {
    if (!success) {
      throw new IllegalArgumentException("SuccessEnvelope.success must be true");
    }
    if (meta == null) {
      throw new IllegalArgumentException("SuccessEnvelope.meta must not be null");
    }
  }

  public static <T> SuccessEnvelope<T> of(T data, String requestId) {
    return new SuccessEnvelope<>(true, data, new Meta(requestId));
  }

  /** 응답 메타데이터. requestId 는 X-Request-Id 응답 헤더와 같은 값이다. */
  public record Meta(String requestId) {}
}
