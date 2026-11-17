package com.queryhub.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.adapter.web.common.requestid.RequestIds;
import com.queryhub.app.support.ErrorProbeTestConfiguration;
import com.queryhub.app.support.LocalHttp;
import com.queryhub.app.support.QueryHubWebTest;
import com.queryhub.domain.error.ErrorCode;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * AC-10: 실제 HTTP 로 확인하는 공통 오류 형식.
 *
 * <p>Spring MVC 안의 오류(GlobalExceptionHandler)와 서블릿 컨테이너 수준의 오류(필터 예외 -> /error ->
 * QueryHubErrorController)가 같은 형식을 쓰는지, 그리고 내부 정보가 응답에 새지 않는지 본다.
 */
@QueryHubWebTest
@ExtendWith(OutputCaptureExtension.class)
class ErrorFormatTest {

  @LocalServerPort private int port;

  private LocalHttp http;

  @BeforeEach
  void setUp() {
    http = new LocalHttp(port);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/does-not-exist", "/admin/api/v1/does-not-exist", "/api/v1/does-not-exist"})
  void unknownPathsReturnNotFound(String path) {
    JsonNode error = LocalHttp.assertErrorEnvelope(http.get(path), 404, ErrorCode.NOT_FOUND);

    assertThat(error.path("message").stringValue()).isEqualTo(ErrorCode.NOT_FOUND.defaultMessage());
  }

  @Test
  void unsupportedMethodReturnsMethodNotAllowedWithAllowHeader() {
    HttpResponse<String> response =
        http.send(http.request("/admin/api/v1/runtime").DELETE().build());

    LocalHttp.assertErrorEnvelope(response, 405, ErrorCode.METHOD_NOT_ALLOWED);
    assertThat(response.headers().allValues("Allow"))
        .anySatisfy(v -> assertThat(v).contains("GET"));
  }

  @Test
  void validJsonReachesTheProbe() {
    // 대조군: 프로브 경로와 본문 처리가 동작한다. 아래 400/415 가 404 의 다른 모습이 아님을 보여 준다.
    HttpResponse<String> response =
        postJson(ErrorProbeTestConfiguration.ECHO_PATH, "{\"text\":\"안녕\"}");

    assertThat(response.statusCode()).as("body=%s", response.body()).isEqualTo(200);
    assertThat(LocalHttp.json(response).path("data").path("text").stringValue()).isEqualTo("안녕");
  }

  @Test
  void malformedJsonReturnsInvalidRequest() {
    HttpResponse<String> response = postJson(ErrorProbeTestConfiguration.ECHO_PATH, "{\"text\": ");

    JsonNode error = LocalHttp.assertErrorEnvelope(response, 400, ErrorCode.INVALID_REQUEST);
    assertThat(error.path("message").stringValue())
        .isEqualTo(ErrorCode.INVALID_REQUEST.defaultMessage());
    assertNoInternals(response.body());
  }

  @Test
  void unsupportedContentTypeReturnsUnsupportedMediaType() {
    HttpRequest request =
        http.request(ErrorProbeTestConfiguration.ECHO_PATH)
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString("text"))
            .build();

    LocalHttp.assertErrorEnvelope(http.send(request), 415, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
  }

  @Test
  void queryHubExceptionUsesMappedStatusAndClientMessage() {
    HttpResponse<String> response = http.get(ErrorProbeTestConfiguration.DOMAIN_ERROR_PATH);

    JsonNode error = LocalHttp.assertErrorEnvelope(response, 400, ErrorCode.INVALID_PARAMETER);
    assertThat(error.path("message").stringValue())
        .isEqualTo(ErrorProbeTestConfiguration.DOMAIN_ERROR_MESSAGE);
  }

  @Test
  void unexpectedExceptionReturnsFixedMessageAndLogsDetails(CapturedOutput output) {
    HttpResponse<String> response = http.get(ErrorProbeTestConfiguration.UNEXPECTED_PATH);

    JsonNode error = LocalHttp.assertErrorEnvelope(response, 500, ErrorCode.INTERNAL_ERROR);
    assertThat(error.path("message").stringValue())
        .isEqualTo(ErrorCode.INTERNAL_ERROR.defaultMessage());
    assertNoInternals(response.body());
    // 상세 내용은 로그에만 있다: requestId 로 찾을 수 있고 원인 메시지와 스택 트레이스가 남는다.
    String requestId = error.path("requestId").stringValue();
    assertThat(output.getAll())
        .contains("requestId=" + requestId)
        .contains(ErrorProbeTestConfiguration.SECRET_MARKER);
  }

  @Test
  void exceptionThrownByServletFilterUsesSameEnvelopeThroughErrorDispatch() {
    // 유효한 요청 ID 를 보내 필터가 정한 ID 가 /error 디스패치 뒤에도 유지되는지 본다.
    HttpResponse<String> response =
        http.get(ErrorProbeTestConfiguration.FILTER_ERROR_PATH, "filter-error-1");

    JsonNode error = LocalHttp.assertErrorEnvelope(response, 500, ErrorCode.INTERNAL_ERROR);
    assertThat(error.path("message").stringValue())
        .isEqualTo(ErrorCode.INTERNAL_ERROR.defaultMessage());
    assertThat(error.path("requestId").stringValue()).isEqualTo("filter-error-1");
    assertThat(LocalHttp.requestIdHeader(response)).isEqualTo("filter-error-1");
    assertNoInternals(response.body());
  }

  @Test
  void errorResponsesCarryIncomingRequestId() {
    HttpResponse<String> response = http.get("/does-not-exist", "trace-me-42");

    JsonNode error = LocalHttp.assertErrorEnvelope(response, 404, ErrorCode.NOT_FOUND);
    assertThat(error.path("requestId").stringValue()).isEqualTo("trace-me-42");
    assertThat(response.headers().firstValue(RequestIds.HEADER)).hasValue("trace-me-42");
  }

  private HttpResponse<String> postJson(String path, String json) {
    return http.send(
        http.request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build());
  }

  /** 응답 본문에 예외 메시지, 예외 클래스 이름, 스택 트레이스가 없어야 한다. */
  private static void assertNoInternals(String body) {
    assertThat(body)
        .doesNotContain(ErrorProbeTestConfiguration.SECRET_MARKER)
        .doesNotContain("IllegalStateException")
        .doesNotContain("Exception")
        .doesNotContain("java.")
        .doesNotContain("at com.")
        .doesNotContain(".java:");
    assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("trace").doesNotContain("timestamp");
  }
}
