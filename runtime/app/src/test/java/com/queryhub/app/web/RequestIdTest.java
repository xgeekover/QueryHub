package com.queryhub.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.app.support.LocalHttp;
import com.queryhub.app.support.QueryHubWebTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;

/** AC-11: 실제 HTTP 로 확인하는 X-Request-Id 처리. 형식에 맞는 값만 그대로 쓰고 나머지는 새 UUID 로 바꾼다. */
@QueryHubWebTest
class RequestIdTest {

  private static final String RUNTIME_PATH = "/admin/api/v1/runtime";

  @LocalServerPort private int port;

  private LocalHttp http;

  @BeforeEach
  void setUp() {
    http = new LocalHttp(port);
  }

  @Test
  void generatesUuidWhenHeaderIsAbsent() {
    HttpResponse<String> response = http.adminGet(RUNTIME_PATH);

    String requestId = LocalHttp.requestIdHeader(response);
    assertIsUuid(requestId);
    assertThat(LocalHttp.json(response).path("meta").path("requestId").stringValue())
        .isEqualTo(requestId);
  }

  @Test
  void generatedIdsDifferBetweenRequests() {
    String first = LocalHttp.requestIdHeader(http.adminGet(RUNTIME_PATH));
    String second = LocalHttp.requestIdHeader(http.adminGet(RUNTIME_PATH));

    assertIsUuid(first);
    assertIsUuid(second);
    assertThat(first).isNotEqualTo(second);
  }

  @ParameterizedTest
  @MethodSource("validIds")
  void echoesValidId(String requestId) {
    HttpResponse<String> response = http.adminGet(RUNTIME_PATH, requestId);

    assertThat(LocalHttp.requestIdHeader(response)).isEqualTo(requestId);
    assertThat(LocalHttp.json(response).path("meta").path("requestId").stringValue())
        .isEqualTo(requestId);
  }

  @ParameterizedTest
  @MethodSource("invalidIds")
  void replacesInvalidIdWithGeneratedUuid(String requestId) {
    HttpResponse<String> response = http.adminGet(RUNTIME_PATH, requestId);

    String returned = LocalHttp.requestIdHeader(response);
    assertThat(returned).isNotEqualTo(requestId);
    assertIsUuid(returned);
    assertThat(LocalHttp.json(response).path("meta").path("requestId").stringValue())
        .isEqualTo(returned);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/actuator/health", "/does-not-exist"})
  void actuatorAndErrorResponsesAlsoCarryTheId(String path) {
    assertThat(LocalHttp.requestIdHeader(http.get(path, "abc-123.X_9"))).isEqualTo("abc-123.X_9");
  }

  @Test
  void unauthorizedAdminResponsesAlsoCarryTheId() {
    // 401 은 Spring Security 가 쓰지만 RequestIdFilter 가 먼저 돈다 (FilterChainProxy 보다 앞).
    HttpResponse<String> response = http.get(RUNTIME_PATH, "abc-123.X_9");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(LocalHttp.requestIdHeader(response)).isEqualTo("abc-123.X_9");
    assertThat(LocalHttp.json(response).path("error").path("requestId").stringValue())
        .isEqualTo("abc-123.X_9");
  }

  static Stream<String> validIds() {
    return Stream.of("abc-123.X_9", "a", "x".repeat(64));
  }

  static Stream<String> invalidIds() {
    return Stream.of("x".repeat(65), "abc def", "abc;def", "../etc/passwd", "<script>", "id,other");
  }

  private static void assertIsUuid(String value) {
    assertThat(value).isNotBlank();
    assertThat(UUID.fromString(value).toString()).isEqualTo(value);
  }
}
