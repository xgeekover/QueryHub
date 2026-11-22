package com.queryhub.adapter.web.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.adapter.web.common.requestid.RequestIds;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** P1-03 F3, F4: Authorization 헤더 형식과 401 응답. */
class AdminTokenFilterTest {

  private static final String TOKEN = "filter-test-token-0123456789abcdef01234567";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final AdminTokenFilter filter =
      new AdminTokenFilter(new AdminTokenVerifier(TOKEN), new SecurityErrorResponses(JSON));

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void validBearerTokenPassesWithAFixedAdminAuthentication() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Authorization", "Bearer " + TOKEN);
    AtomicReference<Authentication> seen = new AtomicReference<>();
    MockFilterChain chain =
        new MockFilterChain(
            new jakarta.servlet.http.HttpServlet() {},
            (req, res, next) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(seen.get()).isNotNull();
    assertThat(seen.get().isAuthenticated()).isTrue();
    assertThat(seen.get().getName()).isEqualTo(AdminTokenFilter.PRINCIPAL);
    assertThat(seen.get().getCredentials()).isNull();
    assertThat(seen.get().toString()).doesNotContain(TOKEN);
  }

  @Test
  void schemeIsCaseInsensitive() throws Exception {
    MockHttpServletRequest request = request();
    request.addHeader("Authorization", "bEaReR " + TOKEN);
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  static Stream<List<String>> rejectedHeaders() {
    return Stream.of(
        List.of(),
        List.of("Bearer"),
        List.of("Bearer "),
        List.of("Bearer  " + TOKEN),
        List.of("Bearer\t" + TOKEN),
        List.of("Basic " + TOKEN),
        List.of(TOKEN),
        List.of("Token " + TOKEN),
        List.of("Bearer " + TOKEN.substring(1)),
        List.of("Bearer " + TOKEN + "x"),
        List.of("Bearer " + TOKEN.toUpperCase(java.util.Locale.ROOT)),
        List.of("Bearer " + TOKEN, "Bearer " + TOKEN),
        List.of("Bearer " + TOKEN, "Bearer wrong"));
  }

  @ParameterizedTest
  @MethodSource("rejectedHeaders")
  void everyOtherFormGetsTheSameUnauthorized(List<String> headers) throws Exception {
    MockHttpServletRequest request = request();
    for (String header : headers) {
      request.addHeader("Authorization", header);
    }
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).as("다음 필터로 넘기지 않는다").isNull();
    assertUnauthorized(response, "rid-1");
  }

  @Test
  void tokenInAQueryParameterIsIgnored() throws ServletException, IOException {
    MockHttpServletRequest request = request();
    request.setParameter("access_token", TOKEN);
    request.setQueryString("access_token=" + TOKEN);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertUnauthorized(response, "rid-1");
  }

  @Test
  void healthSubPathsGetNotFoundAfterTheToken() throws Exception {
    MockHttpServletRequest request = request();
    request.setServletPath("/actuator/health/x");
    request.addHeader("Authorization", "Bearer " + TOKEN);
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNull();
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(
            JSON.readTree(response.getContentAsString()).path("error").path("code").stringValue())
        .isEqualTo("NOT_FOUND");
  }

  static void assertUnauthorized(MockHttpServletResponse response, String requestId)
      throws IOException {
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeaders("WWW-Authenticate")).containsExactly("Bearer");
    assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo(requestId);
    assertThat(response.getContentType()).startsWith("application/json");
    JsonNode body = JSON.readTree(response.getContentAsString());
    assertThat(body.propertyNames()).containsExactlyInAnyOrder("success", "error");
    assertThat(body.path("error").path("code").stringValue()).isEqualTo("UNAUTHORIZED");
    assertThat(body.path("error").path("message").stringValue()).isEqualTo("인증이 필요합니다.");
    assertThat(body.path("error").path("requestId").stringValue()).isEqualTo(requestId);
    assertThat(response.getContentAsString()).doesNotContain(TOKEN);
  }

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/api/v1/runtime");
    request.setServletPath("/admin/api/v1/runtime");
    request.setAttribute(RequestIds.ATTRIBUTE, "rid-1");
    return request;
  }
}
