package com.queryhub.app.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.adapter.web.common.requestid.RequestIds;
import com.queryhub.domain.error.ErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트가 띄운 실제 포트(127.0.0.1)로 HTTP 요청을 보낸다.
 *
 * <p>MockMvc 와 달리 서블릿 컨테이너를 그대로 거치므로 필터 예외와 /error 디스패치까지 검사할 수 있다. HTTP/1.1 로 고정한다(h2c 업그레이드 시도를
 * 피한다).
 */
public final class LocalHttp {

  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .build();

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final int port;

  public LocalHttp(int port) {
    this.port = port;
  }

  public HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(10));
  }

  public HttpResponse<String> get(String path) {
    return send(request(path).GET().build());
  }

  public HttpResponse<String> get(String path, String requestId) {
    return send(request(path).header(RequestIds.HEADER, requestId).GET().build());
  }

  /** 테스트 관리자 토큰을 Authorization: Bearer 로 붙인 요청. */
  public HttpRequest.Builder adminRequest(String path) {
    return request(path).header("Authorization", TestAdminToken.AUTHORIZATION);
  }

  /** 테스트 관리자 토큰을 붙인 GET. */
  public HttpResponse<String> adminGet(String path) {
    return send(adminRequest(path).GET().build());
  }

  /** 테스트 관리자 토큰과 X-Request-Id 를 붙인 GET. */
  public HttpResponse<String> adminGet(String path, String requestId) {
    return send(adminRequest(path).header(RequestIds.HEADER, requestId).GET().build());
  }

  public HttpResponse<String> send(HttpRequest request) {
    try {
      return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("HTTP 요청이 중단되었다", e);
    }
  }

  public static JsonNode json(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }

  /** X-Request-Id 응답 헤더. 없으면 빈 문자열. */
  public static String requestIdHeader(HttpResponse<String> response) {
    return response.headers().firstValue(RequestIds.HEADER).orElse("");
  }

  /** Content-Type 이 application/json 인지 검사한다(charset 등 파라미터는 무시). */
  public static void assertJsonContentType(HttpResponse<String> response) {
    assertThat(response.headers().firstValue("Content-Type"))
        .as("Content-Type")
        .hasValueSatisfying(
            value ->
                assertThat(
                        MediaType.APPLICATION_JSON.equalsTypeAndSubtype(
                            MediaType.parseMediaType(value)))
                    .as("Content-Type %s", value)
                    .isTrue());
  }

  /**
   * 공통 오류 형식을 검사한다: 상태, application/json, {@code {success:false, error:{code, message, requestId}}}
   * 외의 필드 없음, error.requestId 와 X-Request-Id 헤더가 같음.
   *
   * @return 추가 검사를 위한 error 노드
   */
  public static JsonNode assertErrorEnvelope(
      HttpResponse<String> response, int expectedStatus, ErrorCode expectedCode) {
    assertThat(response.statusCode())
        .as("status, body=%s", response.body())
        .isEqualTo(expectedStatus);
    assertJsonContentType(response);
    String headerId = requestIdHeader(response);
    assertThat(headerId).as("X-Request-Id 헤더").isNotBlank();

    JsonNode body = json(response);
    assertThat(body.propertyNames()).containsExactlyInAnyOrder("success", "error");
    assertThat(body.path("success").isBoolean()).isTrue();
    assertThat(body.path("success").booleanValue()).isFalse();

    JsonNode error = body.path("error");
    assertThat(error.propertyNames()).containsExactlyInAnyOrder("code", "message", "requestId");
    assertThat(error.path("code").stringValue()).isEqualTo(expectedCode.name());
    assertThat(error.path("message").stringValue()).isNotBlank();
    assertThat(error.path("requestId").stringValue()).isEqualTo(headerId);
    return error;
  }
}
