package com.queryhub.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.app.support.LocalHttp;
import com.queryhub.app.support.QueryHubWebTest;
import com.queryhub.domain.error.ErrorCode;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/** AC-08: 인증 없이 열리는 health 엔드포인트와, 공개하지 않은 actuator 엔드포인트의 404. */
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
  @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/info"})
  void otherActuatorEndpointsAreNotExposed(String path) {
    LocalHttp.assertErrorEnvelope(http.get(path), 404, ErrorCode.NOT_FOUND);
  }
}
