package com.queryhub.adapter.web.common.error;

import com.queryhub.adapter.web.common.requestid.RequestIds;
import com.queryhub.adapter.web.common.response.ErrorEnvelope;
import com.queryhub.domain.error.ErrorCode;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서블릿 컨테이너 수준의 오류(필터 예외, sendError 등)를 공통 오류 형식으로 바꾼다.
 *
 * <p>Boot 의 BasicErrorController 를 대신한다(ErrorController 빈이 있으면 Boot 는 그것을 만들지 않는다). 경로는 Boot 와 같은
 * {@code spring.web.error.path} (기본 /error) 다. 상태는 {@code jakarta.servlet.error.status_code} 에서 읽고,
 * 없으면(직접 /error 를 호출) 404 로 본다. 예외 메시지는 읽지 않으며 응답에는 코드의 고정 메시지만 넣는다. 오류 디스패치는 RequestIdFilter 를 다시
 * 거치지 않으므로 요청 속성의 요청 ID 로 X-Request-Id 헤더를 다시 넣는다.
 */
@RestController
public class QueryHubErrorController implements ErrorController {

  private static final Logger log = LoggerFactory.getLogger(QueryHubErrorController.class);

  @RequestMapping("${spring.web.error.path:${error.path:/error}}")
  public ResponseEntity<ErrorEnvelope> handleError(
      HttpServletRequest request, HttpServletResponse response) {
    HttpStatusCode status = ErrorHttpStatus.responseStatus(originalStatus(request));
    ErrorCode code = ErrorHttpStatus.protocolCode(status);
    String requestId = RequestIds.currentOrGenerate(request);
    response.setHeader(RequestIds.HEADER, requestId);
    if (status.is5xxServerError()) {
      // 원인 예외는 로그에만 남긴다(스택 트레이스 포함). 응답에는 고정 메시지만 쓴다.
      Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
      log.error(
          "컨테이너 오류 (requestId={}, status={}, uri={})",
          requestId,
          status.value(),
          request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),
          exception instanceof Throwable throwable ? throwable : null);
    }
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(ErrorEnvelope.of(code, code.defaultMessage(), requestId));
  }

  private static HttpStatusCode originalStatus(HttpServletRequest request) {
    Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    if (value instanceof Integer statusCode && statusCode >= 100 && statusCode <= 999) {
      return HttpStatusCode.valueOf(statusCode);
    }
    return HttpStatus.NOT_FOUND;
  }
}
