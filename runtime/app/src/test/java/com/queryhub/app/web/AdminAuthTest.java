package com.queryhub.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.app.support.QueryHubWebTest;
import com.queryhub.app.support.RawHttp;
import com.queryhub.app.support.TestAdminToken;
import com.queryhub.domain.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** P1-03 AC3: 실제 포트에서 Bearer 토큰 검사. 실패는 모두 같은 401 이고(requestId 만 다르다) 맞는 토큰만 200 이다. */
@QueryHubWebTest
class AdminAuthTest {

  private static final String RUNTIME = "/admin/api/v1/runtime";
  private static final String TOKEN = TestAdminToken.VALUE;

  @LocalServerPort private int port;

  static Stream<List<String>> rejectedAuthorizationHeaders() {
    String sameLength = TOKEN.substring(0, TOKEN.length() - 1) + (TOKEN.endsWith("X") ? "Y" : "X");
    return Stream.of(
        List.of(),
        List.of("Authorization: Bearer " + sameLength),
        List.of("Authorization: Bearer " + TOKEN.substring(0, TOKEN.length() - 1)),
        List.of("Authorization: Bearer " + TOKEN + "x"),
        List.of("Authorization: Bearer " + TOKEN.toLowerCase(Locale.ROOT)),
        List.of("Authorization: Basic " + TOKEN),
        List.of("Authorization: Bearer "),
        List.of("Authorization: Bearer " + TOKEN, "Authorization: Bearer " + TOKEN));
  }

  @Test
  void everyRejectedFormGetsTheSameUnauthorizedResponse() {
    List<JsonNode> bodies = new ArrayList<>();
    for (List<String> headers : rejectedAuthorizationHeaders().toList()) {
      RawHttp.Response response = get(RUNTIME, headers);

      JsonNode error = RawHttp.assertErrorEnvelope(response, Set.of(401));
      assertThat(error.path("code").stringValue()).as("%s", headers).isEqualTo("UNAUTHORIZED");
      assertThat(error.path("message").stringValue())
          .isEqualTo(ErrorCode.UNAUTHORIZED.defaultMessage());
      assertThat(response.header("WWW-Authenticate")).as("%s", headers).containsExactly("Bearer");
      assertThat(response.firstHeader("X-Request-Id"))
          .hasValueSatisfying(id -> assertThat(id).isNotBlank());
      assertThat(error.path("requestId").stringValue())
          .isEqualTo(response.firstHeader("X-Request-Id").orElseThrow());
      assertThat(response.everything()).doesNotContain(TOKEN).doesNotContain("queryhub-runtime");
      bodies.add(withoutRequestId(response.json()));
    }
    assertThat(bodies).hasSize(8).allSatisfy(body -> assertThat(body).isEqualTo(bodies.get(0)));
  }

  @Test
  void tokenInTheQueryStringIsNotAccepted() {
    RawHttp.Response response = get(RUNTIME + "?access_token=" + TOKEN, List.of());

    RawHttp.assertErrorEnvelope(response, Set.of(401));
  }

  @Test
  void correctTokenReturnsTheRuntimeInfo() {
    for (String scheme : List.of("Bearer", "bearer", "BEARER")) {
      RawHttp.Response response = get(RUNTIME, List.of("Authorization: " + scheme + " " + TOKEN));

      assertThat(response.status()).as(response.everything()).isEqualTo(200);
      JsonNode body = response.json();
      assertThat(body.path("success").booleanValue()).isTrue();
      assertThat(body.path("data").path("name").stringValue()).isEqualTo("queryhub-runtime");
      assertThat(body.path("data").path("mode").stringValue()).isEqualTo("desktop");
      assertThat(response.header("WWW-Authenticate")).isEmpty();
      assertThat(response.firstHeader("Cache-Control"))
          .hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    }
  }

  private RawHttp.Response get(String target, List<String> headers) {
    List<String> lines = new ArrayList<>(List.of("Host: 127.0.0.1:" + port));
    lines.addAll(headers);
    return RawHttp.send(
        port, RawHttp.request("GET " + target + " HTTP/1.1", lines.toArray(String[]::new)));
  }

  private static JsonNode withoutRequestId(JsonNode body) {
    ObjectNode copy = (ObjectNode) body.deepCopy();
    ((ObjectNode) copy.path("error")).remove("requestId");
    return copy;
  }
}
