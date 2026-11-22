package com.queryhub.adapter.web.admin.cors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * AC-14, P1-03 AC10: 관리 체인의 CORS 설정이 Spring 기본값이 아니라 명시한 값인지 확인한다. 실제 HTTP 동작은 app 의 AdminCorsTest
 * 가 본다.
 */
class AdminCorsConfigurationTest {

  @Test
  void noOriginsRegisterNoConfiguration() {
    UrlBasedCorsConfigurationSource source =
        new AdminCorsConfiguration(new AdminCorsProperties(null)).adminCorsConfigurationSource();

    assertThat(source.getCorsConfigurations()).isEmpty();
    assertThat(source.getCorsConfiguration(request("/admin/api/v1/runtime"))).isNull();
  }

  @Test
  void originsRegisterOneGetOnlyConfigurationForTheAdminApi() {
    UrlBasedCorsConfigurationSource source =
        new AdminCorsConfiguration(
                new AdminCorsProperties(List.of("tauri://localhost", "http://tauri.localhost")))
            .adminCorsConfigurationSource();

    Map<String, CorsConfiguration> configurations = source.getCorsConfigurations();
    assertThat(configurations).containsOnlyKeys("/admin/api/v1/**");
    CorsConfiguration config = configurations.get("/admin/api/v1/**");
    assertThat(config.getAllowedOrigins())
        .containsExactly("tauri://localhost", "http://tauri.localhost");
    assertThat(config.getAllowedOriginPatterns()).isNullOrEmpty();
    assertThat(config.getAllowedMethods()).containsExactly("GET");
    assertThat(config.getAllowedHeaders()).containsExactly("Authorization");
    assertThat(config.getExposedHeaders()).containsExactly("X-Request-Id");
    assertThat(config.getAllowCredentials()).isFalse();
    assertThat(config.getMaxAge()).isEqualTo(1800L);

    assertThat(config.checkHttpMethod(HttpMethod.GET)).containsExactly(HttpMethod.GET);
    for (HttpMethod method :
        List.of(
            HttpMethod.HEAD,
            HttpMethod.POST,
            HttpMethod.PUT,
            HttpMethod.PATCH,
            HttpMethod.DELETE)) {
      assertThat(config.checkHttpMethod(method)).as("%s", method).isNull();
    }
    assertThat(config.checkHeaders(List.of("authorization"))).containsExactly("authorization");
    assertThat(config.checkHeaders(List.of("X-Custom"))).isNull();
    assertThat(config.checkHeaders(List.of("authorization", "X-Custom"))).isNull();
    assertThat(config.checkOrigin("https://evil.example")).isNull();
    assertThat(config.checkOrigin("tauri://localhost")).isEqualTo("tauri://localhost");

    assertThat(source.getCorsConfiguration(request("/admin/api/v1/runtime"))).isSameAs(config);
    assertThat(source.getCorsConfiguration(request("/api/v1/x"))).isNull();
    assertThat(source.getCorsConfiguration(request("/actuator/health"))).isNull();
  }

  private static MockHttpServletRequest request(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);
    return request;
  }
}
