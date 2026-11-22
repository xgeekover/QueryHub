package com.queryhub.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.app.support.LocalHttp;
import com.queryhub.app.support.QueryHubWebTest;
import com.queryhub.app.support.RawHttp;
import com.queryhub.domain.error.ErrorCode;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * AC-08: 인증 없이 열리는 health 엔드포인트와, 공개하지 않은 actuator 엔드포인트의 404. P1-03 AC5: health 밖의 actuator 는 토큰이
 * 없으면 401 이다.
 */
@QueryHubWebTest
class HealthEndpointTest {

  @LocalServerPort private int port;

  private LocalHttp http;

  @BeforeEach
  void setUp() {
    http = new LocalHttp(port);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
  void healthIsUpWithoutCredentialsAndWithoutDetails(String path) {
    HttpResponse<String> response = http.get(path);

    assertThat(response.statusCode()).as("%s body=%s", path, response.body()).isEqualTo(200);
    assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    assertThat(LocalHttp.requestIdHeader(response)).as("X-Request-Id").isNotBlank();
    JsonNode body = LocalHttp.json(response);
    assertThat(body.path("status").stringValue()).isEqualTo("UP");
    // show-details=never: 구성 요소와 상세 정보가 없다.
    assertThat(body.has("components")).isFalse();
    assertThat(body.has("details")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/actuator",
        "/actuator/env",
        "/actuator/beans",
        "/actuator/info",
        "/actuator/health/x"
      })
  void otherActuatorEndpointsAreNotExposed(String path) {
    // 유효한 관리자 토큰을 보내도 공개하지 않은 엔드포인트는 404 다 (P1-03 전과 같은 의미).
    LocalHttp.assertErrorEnvelope(http.adminGet(path), 404, ErrorCode.NOT_FOUND);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/actuator",
        "/actuator/env",
        "/actuator/beans",
        "/actuator/info",
        "/actuator/health/x"
      })
  void otherActuatorEndpointsNeedTheAdminToken(String path) {
    // P1-03: health 세 경로 밖의 actuator 는 관리 체인이 맡는다. 노출 범위를 넓혀도 토큰 없이는 열리지 않는다.
    HttpResponse<String> response = http.get(path);

    LocalHttp.assertErrorEnvelope(response, 401, ErrorCode.UNAUTHORIZED);
    assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
  void healthIgnoresTheHostAllowListOnDesktop(String path) {
    // Host 허용 목록은 관리 체인에만 있다. health 는 Host: evil.example 이어도 열린다.
    RawHttp.Response response =
        RawHttp.send(port, RawHttp.request("GET " + path + " HTTP/1.1", "Host: evil.example"));

    assertThat(response.status()).as(response.everything()).isEqualTo(200);
    assertThat(response.header("WWW-Authenticate")).isEmpty();
    assertThat(response.json().path("status").stringValue()).isEqualTo("UP");
  }

  @Test
  void unknownDataApiPathIsNotFoundWithoutChallenge() {
    HttpResponse<String> response = http.get("/api/v1/does-not-exist");

    LocalHttp.assertErrorEnvelope(response, 404, ErrorCode.NOT_FOUND);
    assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
  }
}
