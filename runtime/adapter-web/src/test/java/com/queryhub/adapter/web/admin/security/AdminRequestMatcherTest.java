package com.queryhub.adapter.web.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** P1-03 F2: 관리 체인이 맡는 경로. 소문자 /admin 접두사 전체와, health 세 경로를 뺀 /actuator 전체. */
class AdminRequestMatcherTest {

  private final AdminRequestMatcher matcher = new AdminRequestMatcher();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/admin",
        "/admin/",
        "/admin/api/v1/runtime",
        "/admin/api/v1/does-not-exist",
        "/ADMIN/api/v1/runtime",
        "/Admin",
        "/actuator",
        "/actuator/",
        "/actuator/env",
        "/actuator/beans",
        "/actuator/info",
        "/actuator/heapdump",
        "/actuator/health/",
        "/actuator/health/x",
        "/actuator/health/db",
        "/actuator/Health",
        "/ACTUATOR/health",
        "/actuator/health/liveness/x"
      })
  void protectsAdminAndNonHealthActuatorPaths(String path) {
    assertThat(matcher.matches(request(path, path))).as(path).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/actuator/health",
        "/actuator/health/liveness",
        "/actuator/health/readiness",
        "/api/v1/x",
        "/api/v1/admin",
        "/error",
        "/",
        "/administrator",
        "/admins/x",
        "/actuators",
        "/does-not-exist"
      })
  void leavesHealthDataApiAndOtherPathsAlone(String path) {
    assertThat(matcher.matches(request(path, path))).as(path).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/admin/api/v1/%72untime", "/%61dmin/api/v1/runtime"})
  void protectsWhenEitherTheDecodedOrTheRawPathIsProtected(String rawUri) {
    // 디코딩한 서블릿 경로는 /admin/api/v1/runtime 이다. 원본 URI 만 보면 놓칠 수 있는 변형이다.
    assertThat(matcher.matches(request("/admin/api/v1/runtime", rawUri))).isTrue();
    // 원본 URI 가 보호 대상이면 서블릿 경로가 달라도 맡는다.
    assertThat(matcher.matches(request("/x", "/admin/x"))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness"})
  void openHealthIsExactMatchOnly(String path) {
    assertThat(AdminRequestMatcher.isProtected(path)).isFalse();
    assertThat(AdminRequestMatcher.isProtected(path + "/")).isTrue();
    assertThat(AdminRequestMatcher.isProtected(path.toUpperCase(java.util.Locale.ROOT))).isTrue();
    assertThat(AdminRequestMatcher.isProtected(null)).isFalse();
  }

  private static MockHttpServletRequest request(String servletPath, String requestUri) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
    request.setServletPath(servletPath);
    return request;
  }
}
