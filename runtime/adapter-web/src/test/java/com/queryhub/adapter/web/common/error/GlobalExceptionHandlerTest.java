package com.queryhub.adapter.web.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.adapter.web.common.requestid.RequestIds;
import com.queryhub.adapter.web.common.response.ErrorEnvelope;
import com.queryhub.domain.error.ErrorCode;
import com.queryhub.domain.error.QueryHubException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;

/** 애플리케이션 컨텍스트 없이 처리기 메서드를 직접 부르는 단위 테스트. 실제 HTTP 경로는 app 의 ErrorFormatTest 가 검증한다. */
class GlobalExceptionHandlerTest {

  private static final String SECRET = "secret-marker-7f3a";

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
  private final MockHttpServletRequest servletRequest = new MockHttpServletRequest();
  private final ServletWebRequest request =
      new ServletWebRequest(servletRequest, new MockHttpServletResponse());

  GlobalExceptionHandlerTest() {
    servletRequest.setAttribute(RequestIds.ATTRIBUTE, "req-1");
  }

  @Test
  void methodNotAllowedKeepsTheAllowHeader() throws Exception {
    ResponseEntity<Object> response =
        handler.handleException(
            new HttpRequestMethodNotSupportedException("DELETE", List.of("GET")), request);

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(405);
    assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW)).contains("GET");
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertError(response.getBody(), ErrorCode.METHOD_NOT_ALLOWED);
  }

  @Test
  void unsupportedMediaTypeBecomes415() throws Exception {
    ResponseEntity<Object> response =
        handler.handleException(
            new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
            request);

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode().value()).isEqualTo(415);
    assertError(response.getBody(), ErrorCode.UNSUPPORTED_MEDIA_TYPE);
  }

  @Test
  void queryHubExceptionUsesTheMappedStatusAndTheClientMessage() {
    ResponseEntity<ErrorEnvelope> response =
        handler.handleQueryHubException(
            new QueryHubException(
                ErrorCode.INVALID_PARAMETER,
                "limit 은 숫자여야 합니다.",
                new IllegalStateException(SECRET)),
            request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error().code()).isEqualTo("INVALID_PARAMETER");
    assertThat(response.getBody().error().message()).isEqualTo("limit 은 숫자여야 합니다.");
    assertThat(response.getBody().toString()).doesNotContain(SECRET);
  }

  @Test
  void unexpectedExceptionBecomesInternalErrorWithTheFixedMessage() {
    ResponseEntity<ErrorEnvelope> response =
        handler.handleUnexpectedException(new IllegalStateException(SECRET), request);

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertError(response.getBody(), ErrorCode.INTERNAL_ERROR);
    assertThat(response.getBody().toString())
        .doesNotContain(SECRET)
        .doesNotContain("IllegalStateException");
  }

  @Test
  void generatesARequestIdWhenNoneWasStored() {
    ServletWebRequest bare =
        new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse());

    ResponseEntity<ErrorEnvelope> response =
        handler.handleUnexpectedException(new IllegalStateException(SECRET), bare);

    assertThat(response.getBody()).isNotNull();
    assertThat(RequestIds.isValid(response.getBody().error().requestId())).isTrue();
  }

  private static void assertError(Object body, ErrorCode code) {
    assertThat(body).isInstanceOf(ErrorEnvelope.class);
    ErrorEnvelope envelope = (ErrorEnvelope) body;
    assertThat(envelope.success()).isFalse();
    assertThat(envelope.error().code()).isEqualTo(code.name());
    assertThat(envelope.error().message()).isEqualTo(code.defaultMessage());
    assertThat(envelope.error().requestId()).isEqualTo("req-1");
  }
}
