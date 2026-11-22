package com.queryhub.adapter.web.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.firewall.RequestRejectedException;

/** P1-03 AC4: 관리 경로의 퍼센트 인코딩 거부. 관리 체인 밖의 경로는 StrictHttpFirewall 기본 규칙 그대로다. */
class AdminPathFirewallTest {

  private final AdminPathFirewall firewall = AdminPathFirewall.strict();

  @ParameterizedTest
  @CsvSource({
    "/admin/api/v1/runtime, /admin/api/v1/%72untime",
    "/admin/api/v1/runtime, /%61dmin/api/v1/runtime",
    "/actuator/env, /actuator/%65nv",
    "/admin/api/v1/a b, /admin/api/v1/a%20b"
  })
  void encodedAdminPathsAreRejected(String servletPath, String uri) {
    assertThatThrownBy(() -> firewall.getFirewalledRequest(request(servletPath, uri)))
        .isInstanceOf(RequestRejectedException.class);
  }

  @ParameterizedTest
  @CsvSource({
    "/admin/api/v1/runtime, /admin/api/v1/runtime",
    "/api/v1/a b, /api/v1/a%20b",
    "/actuator/health, /actuator/health"
  })
  void otherPathsPass(String servletPath, String uri) {
    assertThat(firewall.getFirewalledRequest(request(servletPath, uri))).isNotNull();
  }

  @ParameterizedTest
  @CsvSource({
    "/admin/api/v1/runtime, /admin/api/v1/runtime;x=y",
    "/admin/api/v1/runtime, //admin/api/v1/runtime",
    "/admin/api/v1/runtime, /admin/api/v1/./runtime"
  })
  void strictFirewallRulesStillApply(String servletPath, String uri) {
    assertThatThrownBy(() -> firewall.getFirewalledRequest(request(servletPath, uri)))
        .isInstanceOf(RequestRejectedException.class);
  }

  private static MockHttpServletRequest request(String servletPath, String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    request.setServletPath(servletPath);
    return request;
  }
}
