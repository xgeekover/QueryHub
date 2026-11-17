package com.queryhub.adapter.web.common.error;

import com.queryhub.adapter.web.common.requestid.RequestIds;
import com.queryhub.adapter.web.common.response.ErrorEnvelope;
import com.queryhub.domain.error.ErrorCode;
import com.queryhub.domain.error.QueryHubException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Spring MVC 안에서 난 모든 예외를 공통 오류 형식({@link ErrorEnvelope})으로 바꾼다.
 *
 * <ul>
 *   <li>Spring MVC 표준 예외: {@link #handleExceptionInternal} 이 상태로 코드를 정하고, 넘겨받은 헤더(Allow 등)를 유지한다.
 *   <li>{@link QueryHubException}: {@link ErrorHttpStatus} 의 상태와 예외의 클라이언트 메시지를 쓴다.
 *   <li>그 밖의 예외: 500 INTERNAL_ERROR 와 고정 메시지. 예외 메시지는 응답에 넣지 않고 로그에만 남긴다.
 * </ul>
 *
 * <p>5xx 는 requestId 와 스택 트레이스를 ERROR 로 남긴다. 4xx 는 스택 트레이스 없이 남긴다. 응답의 Content-Type 은 요청의 Accept 와
 * 관계없이 application/json 으로 고정한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(QueryHubException.class)
  public ResponseEntity<ErrorEnvelope> handleQueryHubException(
      QueryHubException exception, WebRequest request) {
    HttpStatus status = ErrorHttpStatus.of(exception.errorCode());
    String requestId = RequestIds.currentOrGenerate(request);
    if (status.is5xxServerError()) {
      log.error("요청 처리 실패 (requestId={}, code={})", requestId, exception.errorCode(), exception);
    } else {
      log.info(
          "요청 거부 (requestId={}, status={}, code={})",
          requestId,
          status.value(),
          exception.errorCode());
    }
    return errorResponse(
        status,
        new HttpHeaders(),
        ErrorEnvelope.of(exception.errorCode(), exception.clientMessage(), requestId));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorEnvelope> handleUnexpectedException(
      Exception exception, WebRequest request) {
    String requestId = RequestIds.currentOrGenerate(request);
    log.error("처리하지 못한 예외 (requestId={})", requestId, exception);
    return errorResponse(
        HttpStatus.INTERNAL_SERVER_ERROR,
        new HttpHeaders(),
        ErrorEnvelope.of(
            ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), requestId));
  }

  @Override
  protected @Nullable ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    if (request instanceof ServletWebRequest servletRequest
        && servletRequest.getResponse() != null
        && servletRequest.getResponse().isCommitted()) {
      // 이미 응답이 나갔으면 상태와 본문을 바꿀 수 없다.
      log.warn("응답이 이미 커밋되어 오류 응답을 쓸 수 없다", exception);
      return null;
    }
    HttpStatusCode status = ErrorHttpStatus.responseStatus(statusCode);
    ErrorCode code = ErrorHttpStatus.protocolCode(status);
    String requestId = RequestIds.currentOrGenerate(request);
    if (status.is5xxServerError()) {
      log.error("요청 처리 실패 (requestId={}, status={})", requestId, status.value(), exception);
    } else {
      log.info(
          "요청 오류 (requestId={}, status={}, code={}, exception={})",
          requestId,
          status.value(),
          code,
          exception.getClass().getName());
    }
    return errorResponse(status, headers, ErrorEnvelope.of(code, code.defaultMessage(), requestId));
  }

  private static <T> ResponseEntity<T> errorResponse(
      HttpStatusCode status, HttpHeaders headers, T body) {
    return ResponseEntity.status(status)
        .headers(headers)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body);
  }
}
