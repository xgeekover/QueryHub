package com.queryhub.adapter.web.admin.cors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** AC-14: 관리 API CORS 설정의 출처 검사와 바인딩. */
class AdminCorsPropertiesTest {

  @Test
  void missingListMeansNoOriginsAndNoMapping() {
    AdminCorsProperties properties = bind(Map.of());

    assertThat(properties.allowedOrigins()).isEmpty();
    assertThat(properties.enabled()).isFalse();
  }

  @Test
  void bindsTheTauriOriginsInOrder() {
    AdminCorsProperties properties =
        bind(
            Map.of(
                AdminCorsProperties.ALLOWED_ORIGINS + "[0]", "tauri://localhost",
                AdminCorsProperties.ALLOWED_ORIGINS + "[1]", "http://tauri.localhost"));

    assertThat(properties.allowedOrigins())
        .containsExactly("tauri://localhost", "http://tauri.localhost");
    assertThat(properties.enabled()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "tauri://localhost",
        "http://tauri.localhost",
        "https://app.example",
        "http://127.0.0.1:18580",
        "https://a-b.example:65535"
      })
  void acceptsSchemeHostAndOptionalPort(String origin) {
    assertThat(new AdminCorsProperties(List.of(origin)).allowedOrigins()).containsExactly(origin);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "*",
        "null",
        "tauri://localhost/path",
        "ftp://x",
        "http://*.example",
        "",
        " tauri://localhost",
        "tauri://localhost/",
        "http://localhost?x=1",
        "http://localhost#x",
        "http://user@localhost",
        "http://localhost:0",
        "http://localhost:65536",
        "http://localhost:",
        "localhost",
        "TAURI://localhost",
        "http://[::1]"
      })
  void rejectsAnythingElseNamingTheProperty(String origin) {
    assertThatThrownBy(() -> new AdminCorsProperties(List.of("tauri://localhost", origin)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(AdminCorsProperties.ALLOWED_ORIGINS)
        .hasMessageContaining("'" + origin + "'")
        .message()
        .doesNotContain("'tauri://localhost'");
  }

  @Test
  void bindingFailsWithThePropertyName() {
    assertThatThrownBy(() -> bind(Map.of(AdminCorsProperties.ALLOWED_ORIGINS, "*")))
        .isInstanceOf(BindException.class)
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(AdminCorsProperties.ALLOWED_ORIGINS);
  }

  @Test
  void listIsACopy() {
    String[] source = {"tauri://localhost"};
    List<String> mutable = Arrays.asList(source);
    AdminCorsProperties properties = new AdminCorsProperties(mutable);
    source[0] = "https://evil.example";

    assertThat(properties.allowedOrigins()).containsExactly("tauri://localhost");
    assertThatThrownBy(() -> properties.allowedOrigins().add("https://evil.example"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static AdminCorsProperties bind(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate(AdminCorsProperties.PREFIX, AdminCorsProperties.class);
  }
}
