package com.queryhub.adapter.web.admin.cors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

/** AC-14: 등록되는 CORS 매핑이 Spring 기본값이 아니라 명시한 값인지 확인한다. 실제 HTTP 동작은 app 의 AdminCorsTest 가 본다. */
class AdminCorsConfigurationTest {

  @Test
  void noOriginsRegisterNoMapping() {
    InspectableCorsRegistry registry = new InspectableCorsRegistry();

    new AdminCorsConfiguration(new AdminCorsProperties(null)).addCorsMappings(registry);

    assertThat(registry.configurations()).isEmpty();
  }

  @Test
  void originsRegisterOneGetOnlyMappingForTheAdminApi() {
    InspectableCorsRegistry registry = new InspectableCorsRegistry();

    new AdminCorsConfiguration(
            new AdminCorsProperties(List.of("tauri://localhost", "http://tauri.localhost")))
        .addCorsMappings(registry);

    Map<String, CorsConfiguration> configurations = registry.configurations();
    assertThat(configurations).containsOnlyKeys("/admin/api/v1/**");
    CorsConfiguration config = configurations.get("/admin/api/v1/**");
    assertThat(config.getAllowedOrigins())
        .containsExactly("tauri://localhost", "http://tauri.localhost");
    assertThat(config.getAllowedOriginPatterns()).isNullOrEmpty();
    assertThat(config.getAllowedMethods()).containsExactly("GET");
    assertThat(config.getAllowedHeaders()).isNotNull().isEmpty();
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
    assertThat(config.checkHeaders(List.of("X-Custom"))).isNull();
    assertThat(config.checkOrigin("https://evil.example")).isNull();
    assertThat(config.checkOrigin("tauri://localhost")).isEqualTo("tauri://localhost");
  }

  /** getCorsConfigurations 는 protected 라 하위 클래스로 꺼낸다. */
  private static final class InspectableCorsRegistry extends CorsRegistry {

    Map<String, CorsConfiguration> configurations() {
      return getCorsConfigurations();
    }
  }
}
