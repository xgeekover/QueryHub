package com.queryhub.adapter.web.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** P1-03 F6, F7, AC6: 루프백(소켓 주소)과 Host 검사. X-Forwarded-For·Forwarded 로 루프백을 흉내 낼 수 없다. */
class AdminNetworkGuardFilterTest {

  private static final AdminTokenVerifier VERIFIER =
      new AdminTokenVerifier("guard-test-token-0123456789abcdef0123456789");

  private static final SecurityErrorResponses ERRORS =
      new SecurityErrorResponses(JsonMapper.builder().build());

  private static AdminNetworkGuardFilter desktop() {
    return new AdminNetworkGuardFilter(
        new AdminAccessPolicy(VERIFIER, false, true, HostHeaderPolicy.of(List.of())), ERRORS);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"192.0.2.10", "10.0.0.1", "::ffff:192.0.2.10", "fe80::1", "", "not-an-ip"})
  void nonLoopbackRemoteAddressesAreForbiddenEvenWithForwardedHeaders(String remote)
      throws Exception {
    MockHttpServletRequest request = request(remote, "localhost:18580");
    request.addHeader("X-Forwarded-For", "127.0.0.1");
    request.addHeader("Forwarded", "for=127.0.0.1");
    request.addHeader("X-Real-IP", "127.0.0.1");

    assertForbidden(desktop(), request);
  }

  @ParameterizedTest
  @ValueSource(strings = {"127.0.0.1", "127.1.2.3", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1"})
  void loopbackRemoteAddressesPass(String remote) throws Exception {
    assertPasses(desktop(), request(remote, "localhost:18580"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"192.0.2.10", "10.0.0.1", "fe80::1"})
  void allowRemoteAcceptsOtherAddresses(String remote) throws Exception {
    AdminNetworkGuardFilter filter =
        new AdminNetworkGuardFilter(
            new AdminAccessPolicy(VERIFIER, true, true, HostHeaderPolicy.of(List.of())), ERRORS);
    assertPasses(filter, request(remote, "localhost"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"evil.example", "evil.example:18580", "localhost.", "127.0.0.2"})
  void hostCheckForbidsForeignHostsFromLoopback(String host) throws Exception {
    assertForbidden(desktop(), request("127.0.0.1", host));
  }

  @ParameterizedTest
  @ValueSource(strings = {"anything.example", "evil.example:8080"})
  void serverWithoutAllowedHostsSkipsTheHostCheck(String host) throws Exception {
    AdminNetworkGuardFilter server =
        new AdminNetworkGuardFilter(
            new AdminAccessPolicy(VERIFIER, true, false, HostHeaderPolicy.of(List.of())), ERRORS);
    assertPasses(server, request("192.0.2.10", host));
  }

  private static MockHttpServletRequest request(String remote, String host) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/api/v1/runtime");
    request.setRemoteAddr(remote);
    request.addHeader("Host", host);
    request.setServerName(
        host.contains(":") && !host.startsWith("[") ? host.substring(0, host.indexOf(':')) : host);
    return request;
  }

  private static void assertForbidden(
      AdminNetworkGuardFilter filter, MockHttpServletRequest request) throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    assertThat(chain.getRequest()).as("다음 필터로 넘기지 않는다").isNull();
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("\"code\":\"FORBIDDEN\"");
    assertThat(response.getHeader("WWW-Authenticate")).isNull();
  }

  private static void assertPasses(AdminNetworkGuardFilter filter, MockHttpServletRequest request)
      throws Exception {
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    assertThat(chain.getRequest()).isNotNull();
  }
}
