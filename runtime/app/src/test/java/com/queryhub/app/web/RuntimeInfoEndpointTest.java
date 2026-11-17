package com.queryhub.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.app.config.RuntimeInfoConfiguration;
import com.queryhub.app.support.LocalHttp;
import com.queryhub.app.support.QueryHubWebTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/** AC-09: GET /admin/api/v1/runtime 의 성공 응답 형식과 내용. */
@QueryHubWebTest
class RuntimeInfoEndpointTest {

  private static final String RUNTIME_PATH = "/admin/api/v1/runtime";

  @LocalServerPort private int port;

  private LocalHttp http;

  @BeforeEach
  void setUp() {
    http = new LocalHttp(port);
  }

  @Test
  void returnsRuntimeInfoInSuccessEnvelope() {
    Instant before = Instant.now();
    HttpResponse<String> response = http.get(RUNTIME_PATH);

    assertThat(response.statusCode()).as("body=%s", response.body()).isEqualTo(200);
    LocalHttp.assertJsonContentType(response);
    JsonNode body = LocalHttp.json(response);
    assertThat(body.propertyNames()).containsExactlyInAnyOrder("success", "data", "meta");
    assertThat(body.path("success").isBoolean()).isTrue();
    assertThat(body.path("success").booleanValue()).isTrue();

    JsonNode data = body.path("data");
    assertThat(data.propertyNames())
        .containsExactlyInAnyOrder("name", "version", "mode", "startedAt", "uptimeSeconds");
    assertThat(data.path("name").stringValue()).isEqualTo("queryhub-runtime");
    // build-info(META-INF/build-info.properties) 의 버전이어야 한다. 'unknown' 은 build-info 가 없다는 뜻이다.
    assertThat(data.path("version").stringValue())
        .isNotBlank()
        .isNotEqualTo(RuntimeInfoConfiguration.UNKNOWN_VERSION);
    assertThat(data.path("mode").stringValue()).isEqualTo("desktop");
    Instant startedAt = Instant.parse(data.path("startedAt").stringValue());
    assertThat(startedAt).isBeforeOrEqualTo(before);
    assertThat(data.path("uptimeSeconds").isIntegralNumber()).isTrue();
    assertThat(data.path("uptimeSeconds").longValue()).isGreaterThanOrEqualTo(0L);

    assertThat(body.path("meta").path("requestId").stringValue())
        .isNotBlank()
        .isEqualTo(LocalHttp.requestIdHeader(response));
  }

  @Test
  void metaRequestIdEchoesValidIncomingRequestId() {
    HttpResponse<String> response = http.get(RUNTIME_PATH, "runtime-check-1");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(LocalHttp.requestIdHeader(response)).isEqualTo("runtime-check-1");
    assertThat(LocalHttp.json(response).path("meta").path("requestId").stringValue())
        .isEqualTo("runtime-check-1");
  }
}
