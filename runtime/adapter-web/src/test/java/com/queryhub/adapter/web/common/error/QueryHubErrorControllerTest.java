package com.queryhub.adapter.web.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.adapter.web.common.requestid.RequestIds;
import com.queryhub.adapter.web.common.response.ErrorEnvelope;
import jakarta.servlet.RequestDispatcher;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 컨테이너 오류 디스패치를 흉내 낸 단위 테스트. 실제 필터 예외 경로는 app 의 ErrorFormatTest 가 검증한다. */
class QueryHubErrorControllerTest {

  private final QueryHubErrorController controller = new QueryHubErrorController();
  private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @Test
  void containerErrorBecomesInternalErrorWithoutTheExceptionMessage() {
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
    request.setAttribute(
        RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("secret-filter-marker"));
    request.setAttribute(RequestIds.ATTRIBUTE, "filter-req");

    ResponseEntity<ErrorEnvelope> result = controller.handleError(request, response);

    assertThat(result.getStatusCode().value()).isEqualTo(500);
    assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(result.getBody()).isNotNull();
    assertThat(result.getBody().success()).isFalse();
    assertThat(result.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
    assertThat(result.getBody().error().requestId()).isEqualTo("filter-req");
    assertThat(result.getBody().toString())
        .doesNotContain("secret-filter-marker")
        .doesNotContain("IllegalStateException");
    assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo("filter-req");
  }

  @Test
  void keepsAClientErrorStatus() {
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);

    ResponseEntity<ErrorEnvelope> result = controller.handleError(request, response);

    assertThat(result.getStatusCode().value()).isEqualTo(400);
    assertThat(result.getBody()).isNotNull();
    assertThat(result.getBody().error().code()).isEqualTo("INVALID_REQUEST");
  }

  @Test
  void directCallWithoutStatusIsNotFoundAndGetsAGeneratedRequestId() {
    ResponseEntity<ErrorEnvelope> result = controller.handleError(request, response);

    assertThat(result.getStatusCode().value()).isEqualTo(404);
    assertThat(result.getBody()).isNotNull();
    assertThat(result.getBody().error().code()).isEqualTo("NOT_FOUND");
    String id = result.getBody().error().requestId();
    assertThat(UUID.fromString(id).toString()).isEqualTo(id);
    assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo(id);
  }
}
