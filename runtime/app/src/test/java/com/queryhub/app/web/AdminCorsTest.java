package com.queryhub.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.queryhub.adapter.web.admin.cors.AdminCorsProperties;
import com.queryhub.app.QueryHubApplication;
import com.queryhub.app.support.LocalHttp;
import com.queryhub.app.support.QueryHubWebTest;
import com.queryhub.app.support.TestAdminToken;
import com.queryhub.app.support.TestComponentScanExclusion;
import com.queryhub.app.support.TestMasterKey;
import com.queryhub.app.support.WithoutMetadataDatabase;
import com.queryhub.domain.error.ErrorCode;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;

/**
 * AC-14: 관리 API 의 CORS. 실제 Tomcat(127.0.0.1, 임의 포트)에 HTTP 로 보낸다. Docker 없이 돈다.
 *
 * <p>desktop 프로파일은 Tauri WebView 의 출처 두 개만 GET 으로 허용한다. server 프로파일에는 CORS 매핑이 없다. 잘못된 출처 설정은 웹 서버가
 * 포트를 열기 전에 기동을 멈춘다.
 */
class AdminCorsTest {

  private static final String RUNTIME_PATH = "/admin/api/v1/runtime";
  private static final String HEALTH_PATH = "/actuator/health";

  private static final String TAURI_ORIGIN = "tauri://localhost";
  private static final String WINDOWS_ORIGIN = "http://tauri.localhost";

  private static final String ACAO = "Access-Control-Allow-Origin";
  private static final String ACAC = "Access-Control-Allow-Credentials";
  private static final String ACAM = "Access-Control-Allow-Methods";
  private static final String ACEH = "Access-Control-Expose-Headers";

  @Nested
  @QueryHubWebTest
  class DesktopProfile {

    @LocalServerPort private int port;

    @Autowired private AdminCorsProperties properties;

    private LocalHttp http;

    @BeforeEach
    void setUp() {
      http = new LocalHttp(port);
    }

    @Test
    void desktopProfileAllowsExactlyTheTwoWebViewOrigins() {
      assertThat(properties.allowedOrigins()).containsExactly(TAURI_ORIGIN, WINDOWS_ORIGIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {TAURI_ORIGIN, WINDOWS_ORIGIN})
    void webViewOriginReadsTheRuntimeInfo(String origin) {
      HttpResponse<String> response = adminGet(http, RUNTIME_PATH, origin);

      assertThat(response.statusCode()).as("body=%s", response.body()).isEqualTo(200);
      assertThat(response.headers().firstValue(ACAO)).hasValue(origin);
      assertThat(headerValues(response, "Vary")).contains("Origin");
      assertThat(headerValues(response, ACEH)).contains("X-Request-Id");
      assertThat(response.headers().firstValue(ACAC)).isEmpty();
      assertThat(LocalHttp.json(response).path("success").booleanValue()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:1420", "https://evil.example"})
    void otherOriginsAreRejected(String origin) {
      // 유효한 토큰이 있어도 CORS 가 거부한다 (P1-03 전과 같은 의미).
      HttpResponse<String> response = adminGet(http, RUNTIME_PATH, origin);

      assertThat(response.statusCode()).as("body=%s", response.body()).isEqualTo(403);
      assertThat(response.headers().firstValue(ACAO)).isEmpty();
      assertThat(response.body()).doesNotContain("queryhub-runtime");
    }

    @Test
    void preflightForGetIsAllowedAndListsOnlyGet() {
      HttpResponse<String> response = preflight(http, TAURI_ORIGIN, "GET", null);

      assertThat(response.statusCode()).as("body=%s", response.body()).isEqualTo(200);
      assertThat(response.headers().firstValue(ACAO)).hasValue(TAURI_ORIGIN);
      List<String> methods = headerValues(response, ACAM);
      assertThat(methods).contains("GET").doesNotContain("POST", "PUT", "PATCH", "DELETE");
      assertThat(response.headers().firstValue(ACAC)).isEmpty();
      assertThat(response.headers().firstValue("Access-Control-Allow-Headers")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void preflightForOtherMethodsIsRejected(String method) {
      HttpResponse<String> response = preflight(http, TAURI_ORIGIN, method, null);

      assertThat(response.statusCode()).as("body=%s", response.body()).isEqualTo(403);
      assertThat(response.headers().firstValue(ACAO)).isEmpty();
    }

    @Test
    void preflightWithARequestHeaderIsRejected() {
      HttpResponse<String> response = preflight(http, TAURI_ORIGIN, "GET", "X-Custom");

      assertThat(response.statusCode()).as("body=%s", response.body()).isEqualTo(403);
      assertThat(response.headers().firstValue(ACAO)).isEmpty();
    }

    @Test
    void preflightFromAnotherOriginIsRejected() {
      HttpResponse<String> response = preflight(http, "http://localhost:1420", "GET", null);

      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(response.headers().firstValue(ACAO)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {TAURI_ORIGIN, WINDOWS_ORIGIN})
    void preflightAllowingTheAuthorizationHeaderNeedsNoToken(String origin) {
      // P1-03 AC10: preflight 는 토큰 없이 답한다. 허용 요청 헤더는 authorization 하나뿐이다.
      HttpResponse<String> response = preflight(http, origin, "GET", "authorization");

      assertThat(response.statusCode()).as("body=%s", response.body()).isBetween(200, 299);
      assertThat(response.headers().allValues(ACAO)).containsExactly(origin);
      assertThat(headerValues(response, "Access-Control-Allow-Headers"))
          .extracting(value -> value.toLowerCase(java.util.Locale.ROOT))
          .containsExactly("authorization");
      assertThat(headerValues(response, ACAM)).containsExactly("GET");
      assertThat(response.headers().firstValue(ACAC)).isEmpty();
      assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"x-foo", "authorization, x-foo"})
    void preflightAskingForOtherHeadersIsRejected(String requestHeaders) {
      HttpResponse<String> response = preflight(http, TAURI_ORIGIN, "GET", requestHeaders);

      assertThat(response.statusCode()).as("body=%s", response.body()).isEqualTo(403);
      assertThat(response.headers().firstValue(ACAO)).isEmpty();
    }

    @Test
    void preflightForPostWithAuthorizationIsRejected() {
      HttpResponse<String> response = preflight(http, TAURI_ORIGIN, "POST", "authorization");

      assertThat(response.statusCode()).as("body=%s", response.body()).isEqualTo(403);
      assertThat(response.headers().firstValue(ACAO)).isEmpty();
    }

    @Test
    void tokenlessGetFromTheWebViewGetsUnauthorizedWithCorsHeadersOnce() {
      // 401 에도 CORS 헤더가 붙어야 UI 가 네트워크 오류가 아닌 401 을 본다. MVC 매핑이 없으므로 한 번만 붙는다.
      HttpResponse<String> response = get(http, RUNTIME_PATH, TAURI_ORIGIN);

      LocalHttp.assertErrorEnvelope(response, 401, ErrorCode.UNAUTHORIZED);
      assertThat(response.headers().allValues(ACAO)).containsExactly(TAURI_ORIGIN);
      assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
    }

    @Test
    void requestWithoutOriginIsUnchanged() {
      HttpResponse<String> response = http.adminGet(RUNTIME_PATH);

      assertThat(response.statusCode()).as("body=%s", response.body()).isEqualTo(200);
      assertThat(response.headers().firstValue(ACAO)).isEmpty();
      JsonNode body = LocalHttp.json(response);
      assertThat(body.propertyNames()).containsExactlyInAnyOrder("success", "data", "meta");
      assertThat(body.path("data").path("name").stringValue()).isEqualTo("queryhub-runtime");
      assertThat(body.path("data").path("mode").stringValue()).isEqualTo("desktop");
    }

    @Test
    void actuatorHasNoCors() {
      HttpResponse<String> response = get(http, HEALTH_PATH, TAURI_ORIGIN);

      assertThat(response.headers().firstValue(ACAO)).isEmpty();
    }
  }

  @Nested
  class ServerProfile {

    @Test
    void serverProfileRegistersNoCorsMapping() {
      try (ConfigurableApplicationContext context = startServerProfile()) {
        assertThat(context.getBean(AdminCorsProperties.class).enabled()).isFalse();
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        LocalHttp http = new LocalHttp(port);

        HttpResponse<String> get = adminGet(http, RUNTIME_PATH, TAURI_ORIGIN);
        assertThat(get.statusCode()).as("body=%s", get.body()).isEqualTo(200);
        assertThat(get.headers().firstValue(ACAO)).isEmpty();
        assertThat(LocalHttp.json(get).path("data").path("mode").stringValue()).isEqualTo("server");

        HttpResponse<String> preflight = preflight(http, TAURI_ORIGIN, "GET", null);
        assertThat(preflight.headers().firstValue(ACAO)).isEmpty();
        assertThat(preflight.headers().firstValue(ACAM)).isEmpty();
        assertNoCorsHeaders(get);
        assertNoCorsHeaders(preflight);
        HttpResponse<String> tokenless = get(http, RUNTIME_PATH, TAURI_ORIGIN);
        assertThat(tokenless.statusCode()).isEqualTo(401);
        assertNoCorsHeaders(tokenless);
      }
    }

    @Test
    void sharedAndServerConfigurationHaveNoCorsKey() throws IOException {
      for (String file : List.of("application.yml", "application-server.yml")) {
        assertThat(new ClassPathResource(file).getContentAsString(StandardCharsets.UTF_8))
            .as(file)
            .doesNotContainIgnoringCase("cors");
      }
    }

    private ConfigurableApplicationContext startServerProfile() {
      return new SpringApplicationBuilder(QueryHubApplication.class)
          .web(WebApplicationType.SERVLET)
          .profiles("server")
          .properties(
              WithoutMetadataDatabase.PROPERTY, TestAdminToken.PROPERTY, TestMasterKey.PROPERTY)
          .initializers(TestComponentScanExclusion::register)
          .run("--server.address=127.0.0.1", "--server.port=0");
    }
  }

  @Nested
  class Validation {

    @ParameterizedTest
    @ValueSource(strings = {"*", "null", "tauri://localhost/path", "ftp://x", "http://*.example"})
    void invalidOriginStopsStartupBeforeTheWebServer(String origin) {
      assertRejected(start(false, origin), origin);
    }

    @Test
    void invalidOriginStopsStartupAlsoWithLazyInitialization() {
      assertRejected(start(true, "*"), "*");
    }

    private void assertRejected(StartupAttempt attempt, String origin) {
      assertThat(attempt.failure()).as("잘못된 출처 '%s' 로는 기동할 수 없어야 한다", origin).isNotNull();
      assertThat(attempt.failure())
          .rootCause()
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(AdminCorsProperties.ALLOWED_ORIGINS)
          .hasMessageContaining("'" + origin + "'");
      assertThat(attempt.webServerStarted()).as("포트를 열기 전에 실패해야 한다").isFalse();
    }

    private StartupAttempt start(boolean lazy, String origin) {
      AtomicBoolean webServerStarted = new AtomicBoolean();
      ApplicationListener<ApplicationEvent> webServerListener =
          event -> {
            if (event instanceof WebServerInitializedEvent) {
              webServerStarted.set(true);
            }
          };
      SpringApplication application =
          new SpringApplicationBuilder(QueryHubApplication.class)
              .web(WebApplicationType.SERVLET)
              .lazyInitialization(lazy)
              .profiles("desktop")
              .properties(
                  WithoutMetadataDatabase.PROPERTY, TestAdminToken.PROPERTY, TestMasterKey.PROPERTY)
              .initializers(TestComponentScanExclusion::register)
              .listeners(webServerListener)
              .build();
      List<String> args =
          new ArrayList<>(
              List.of(
                  "--server.address=127.0.0.1",
                  "--server.port=0",
                  "--" + AdminCorsProperties.ALLOWED_ORIGINS + "=" + origin));
      Throwable failure =
          catchThrowable(
              () -> {
                try (ConfigurableApplicationContext context =
                    application.run(args.toArray(String[]::new))) {
                  assertThat(context.isActive()).isTrue();
                }
              });
      return new StartupAttempt(failure, webServerStarted.get());
    }
  }

  private static HttpResponse<String> get(LocalHttp http, String path, String origin) {
    return http.send(http.request(path).header("Origin", origin).GET().build());
  }

  private static HttpResponse<String> adminGet(LocalHttp http, String path, String origin) {
    return http.send(http.adminRequest(path).header("Origin", origin).GET().build());
  }

  private static void assertNoCorsHeaders(HttpResponse<String> response) {
    assertThat(response.headers().map().keySet())
        .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith("access-control-"));
  }

  private static HttpResponse<String> preflight(
      LocalHttp http, String origin, String method, @Nullable String requestHeaders) {
    HttpRequest.Builder builder =
        http.request(RUNTIME_PATH)
            .header("Origin", origin)
            .header("Access-Control-Request-Method", method)
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody());
    if (requestHeaders != null) {
      builder.header("Access-Control-Request-Headers", requestHeaders);
    }
    return http.send(builder.build());
  }

  /** 같은 이름의 모든 헤더 값을 쉼표로 나눠 모은다. */
  private static List<String> headerValues(HttpResponse<String> response, String name) {
    List<String> values = new ArrayList<>();
    for (String value : response.headers().allValues(name)) {
      for (String part : value.split(",")) {
        values.add(part.trim());
      }
    }
    return values;
  }

  private record StartupAttempt(@Nullable Throwable failure, boolean webServerStarted) {}
}
