package com.queryhub.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.app.support.QueryHubWebTest;
import com.queryhub.app.support.RawHttp;
import com.queryhub.app.support.TestAdminToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * P1-03 AC4: 관리 API 전체가 토큰 뒤에 있다. 예시 목록만이 아니라 핸들러 매핑을 열거해 관리 패키지의 모든 핸들러를 검사하고, 경로 변형은 소켓으로 바이트 그대로
 * 보낸다.
 */
@QueryHubWebTest
class AdminPathCoverageTest {

  private static final String ADMIN_PACKAGE = "com.queryhub.adapter.web.admin";
  private static final Set<Integer> ERROR_STATUSES = Set.of(400, 401, 403, 404);

  @LocalServerPort private int port;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  @Test
  void everyAdminHandlerLivesUnderTheAdminApiAndNeedsTheToken() {
    List<String> adminPatterns = new ArrayList<>();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
        handlerMapping.getHandlerMethods().entrySet()) {
      Class<?> beanType = entry.getValue().getBeanType();
      Set<String> patterns = entry.getKey().getPatternValues();
      if (beanType.getPackageName().startsWith(ADMIN_PACKAGE)) {
        assertThat(patterns)
            .as("%s", beanType)
            .isNotEmpty()
            .allMatch(p -> p.startsWith("/admin/api/v1/"));
        Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
        for (String pattern : patterns) {
          adminPatterns.add(pattern);
          String target = sampleTarget(pattern);
          for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
            RawHttp.Response response =
                send(method.name() + " " + target + " HTTP/1.1", "Content-Length: 0");
            assertThat(response.status()).as("%s %s", method, pattern).isEqualTo(401);
          }
        }
      } else {
        // 관리 패키지 밖의 핸들러가 관리 경로를 쓰지 않는다 (쓰면 R5 와 이 테스트의 전제가 깨진다).
        assertThat(patterns)
            .as("%s", beanType)
            .noneMatch(p -> p.toLowerCase(java.util.Locale.ROOT).startsWith("/admin"));
      }
    }
    assertThat(adminPatterns).as("관리 핸들러가 하나 이상 있어야 한다").contains("/admin/api/v1/runtime");
  }

  /**
   * 매핑 패턴을 실제로 보낼 수 있는 요청 대상으로 바꾼다. URI 템플릿 변수({@code {id}}, {@code {id:\d+}}, {@code {*rest}})는 표본
   * 값으로 채운다. 그대로 보내면 '{' 와 '}' 때문에 Tomcat 이 애플리케이션 전에 400 을 내 401 검사가 성립하지 않는다.
   */
  static String sampleTarget(String pattern) {
    String target = TEMPLATE_VARIABLE.matcher(pattern).replaceAll("1");
    return target.replace("/**", "/1").replace("/*", "/1");
  }

  private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{[^/]*}");

  @Test
  void templatedPatternsBecomeSendableTargets() {
    assertThat(sampleTarget("/admin/api/v1/runtime")).isEqualTo("/admin/api/v1/runtime");
    assertThat(sampleTarget("/admin/api/v1/connections/{id}"))
        .isEqualTo("/admin/api/v1/connections/1");
    assertThat(sampleTarget("/admin/api/v1/connections/{id:\\d+}/tables/{name}"))
        .isEqualTo("/admin/api/v1/connections/1/tables/1");
    assertThat(sampleTarget("/admin/api/v1/files/{*path}")).isEqualTo("/admin/api/v1/files/1");
    assertThat(sampleTarget("/admin/api/v1/x/**")).isEqualTo("/admin/api/v1/x/1");
    assertThat(sampleTarget("/admin/api/v1/connections/{id}"))
        .as("보낼 수 있는 대상에는 중괄호가 없다")
        .doesNotContain("{", "}");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/admin/api/v1/runtime/",
        "/admin/api/v1/runtime;x=y",
        "//admin/api/v1/runtime",
        "/admin/api/v1//runtime",
        "/admin/api/v1/./runtime",
        "/admin/api/v1/x/../runtime",
        "/admin%2Fapi/v1/runtime",
        "/admin/api/v1/%2e/runtime",
        "/admin/api/v1/%72untime",
        "/admin/api/v1/runtime%00",
        "/ADMIN/api/v1/runtime",
        "/admin/api/v1/does-not-exist"
      })
  void pathVariantsNeverReachTheRuntimeInfo(String target) {
    for (boolean withToken : new boolean[] {false, true}) {
      RawHttp.Response response =
          withToken
              ? send(
                  "GET " + target + " HTTP/1.1", "Authorization: " + TestAdminToken.AUTHORIZATION)
              : send("GET " + target + " HTTP/1.1");

      assertThat(response.status() / 100)
          .as("%s token=%s %s", target, withToken, response.everything())
          .isNotEqualTo(2);
      assertThat(response.body()).doesNotContain("queryhub-runtime");
      if (CONTAINER_REJECTED.contains(target)) {
        // Tomcat 커넥터가 %2F(encodedSolidusHandling=reject)와 %00 을 서블릿 필터보다 먼저 400 으로 거부한다. 요청 ID 가
        // 없다는 것은
        // 애플리케이션 코드(RequestIdFilter 포함)에 닿지 않았다는 뜻이다. 이 응답은 공통 오류 형식이 아니다(알려진 한계).
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.header("X-Request-Id")).isEmpty();
      } else {
        RawHttp.assertErrorEnvelope(response, ERROR_STATUSES);
      }
    }
  }

  /** 컨테이너(Tomcat)가 애플리케이션 전에 거부하는 대상. */
  private static final Set<String> CONTAINER_REJECTED =
      Set.of("/admin%2Fapi/v1/runtime", "/admin/api/v1/runtime%00");

  @ParameterizedTest
  @ValueSource(strings = {"POST", "DELETE", "OPTIONS", "PUT", "PATCH"})
  void otherMethodsWithoutTokenAreUnauthorized(String method) {
    RawHttp.Response response =
        send(method + " /admin/api/v1/runtime HTTP/1.1", "Content-Length: 0");

    RawHttp.assertErrorEnvelope(response, Set.of(401));
    assertThat(response.header("WWW-Authenticate")).containsExactly("Bearer");
  }

  @Test
  void headWithoutTokenIsUnauthorizedAndWithTokenSucceeds() {
    RawHttp.Response without = send("HEAD /admin/api/v1/runtime HTTP/1.1");
    assertThat(without.status()).isEqualTo(401);
    assertThat(without.header("WWW-Authenticate")).containsExactly("Bearer");

    RawHttp.Response with =
        send(
            "HEAD /admin/api/v1/runtime HTTP/1.1",
            "Authorization: " + TestAdminToken.AUTHORIZATION);
    assertThat(with.status()).isEqualTo(200);
  }

  @Test
  void onlyTheCanonicalPathSucceedsWithTheToken() {
    RawHttp.Response response =
        send(
            "GET /admin/api/v1/runtime HTTP/1.1", "Authorization: " + TestAdminToken.AUTHORIZATION);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.json().path("data").path("name").stringValue())
        .isEqualTo("queryhub-runtime");
  }

  private RawHttp.Response send(String requestLine, String... headers) {
    List<String> lines = new ArrayList<>(List.of("Host: 127.0.0.1:" + port));
    lines.addAll(List.of(headers));
    return RawHttp.send(port, RawHttp.request(requestLine, lines.toArray(String[]::new)));
  }
}
