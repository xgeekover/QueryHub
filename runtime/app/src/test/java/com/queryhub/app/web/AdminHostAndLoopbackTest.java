package com.queryhub.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.app.QueryHubApplication;
import com.queryhub.app.config.admin.TomcatRemoteAddressGuard;
import com.queryhub.app.support.QueryHubWebTest;
import com.queryhub.app.support.RawHttp;
import com.queryhub.app.support.TestAdminToken;
import com.queryhub.app.support.TestComponentScanExclusion;
import com.queryhub.app.support.WithoutMetadataDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.catalina.Container;
import org.apache.catalina.Service;
import org.apache.catalina.Valve;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * P1-03 AC7: 실제 포트에서 Host 허용 목록(DNS rebinding 방어). 유효한 토큰을 보내고 Host 만 바꾼다. 거부는 가드의 403 이거나 컨테이너의
 * 400 이다. 소켓 원격 주소 검사(AC6)는 루프백이 아닌 주소를 실제 소켓으로 만들 수 없으므로 AdminNetworkGuardFilterTest 가 맡는다.
 */
class AdminHostAndLoopbackTest {

  private static final String AUTH = "Authorization: " + TestAdminToken.AUTHORIZATION;

  @Nested
  @QueryHubWebTest
  class Desktop {

    @LocalServerPort private int port;

    @Autowired private WebServerApplicationContext webServerContext;

    @ParameterizedTest
    @ValueSource(
        strings = {"localhost", "LOCALHOST:18580", "127.0.0.1:1420", "[::1]", "[::1]:18580"})
    void allowedHostsReachTheRuntimeInfo(String host) {
      RawHttp.Response response =
          send(port, "GET /admin/api/v1/runtime HTTP/1.1", "Host: " + host, AUTH);

      assertThat(response.status()).as("%s %s", host, response.everything()).isEqualTo(200);
      assertThat(response.json().path("success").booleanValue()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "evil.example",
          "evil.example:18580",
          "localhost.evil.example",
          "localhost.",
          "127.0.0.2",
          "::1",
          "[::1%25lo0]",
          "user@localhost",
          "localhost:abc",
          "localhost:0",
          "localhost:65536"
        })
    void foreignOrMalformedHostsAreRejected(String host) {
      assertRejected(send(port, "GET /admin/api/v1/runtime HTTP/1.1", "Host: " + host, AUTH), host);
    }

    @Test
    void missingHostOnHttp10IsForbidden() {
      RawHttp.Response response = send(port, "GET /admin/api/v1/runtime HTTP/1.0", AUTH);

      assertThat(response.status()).as(response.everything()).isEqualTo(403);
      RawHttp.assertErrorEnvelope(response, Set.of(403));
    }

    @Test
    void twoHostHeadersAreRejected() {
      assertRejected(
          send(
              port,
              "GET /admin/api/v1/runtime HTTP/1.1",
              "Host: localhost",
              "Host: localhost",
              AUTH),
          "two");
    }

    @Test
    void absoluteFormTargetNamingAForeignHostIsRejected() {
      assertRejected(
          send(
              port,
              "GET http://evil.example/admin/api/v1/runtime HTTP/1.1",
              "Host: 127.0.0.1",
              AUTH),
          "absolute-form");
    }

    /** R1: desktop 의 Tomcat 파이프라인에는 RemoteIpValve 도, 허용 목록 밖의 valve 도 없다. */
    @Test
    void desktopPipelineHasNoRemoteAddressRewritingValve() {
      Tomcat tomcat = ((TomcatWebServer) webServerContext.getWebServer()).getTomcat();
      List<String> valves = new ArrayList<>();
      for (Service service : tomcat.getServer().findServices()) {
        collectValves(service.getContainer(), valves);
      }

      assertThat(valves).isNotEmpty().noneMatch(v -> v.contains("RemoteIp"));
      assertThat(TomcatRemoteAddressGuard.disallowedValves(tomcat)).isEmpty();
    }

    /** R1: X-Forwarded-For 와 Forwarded 는 루프백 판정에 쓰이지 않는다 (RemoteIpValve 가 있으면 403 이 된다). */
    @Test
    void forwardedHeadersDoNotChangeTheLoopbackDecision() {
      RawHttp.Response response =
          send(
              port,
              "GET /admin/api/v1/runtime HTTP/1.1",
              "Host: localhost",
              "X-Forwarded-For: 192.0.2.10",
              "X-Forwarded-Proto: https",
              "Forwarded: for=192.0.2.10",
              AUTH);

      assertThat(response.status()).as(response.everything()).isEqualTo(200);
    }

    @Test
    void preflightIsAlsoGuarded() {
      RawHttp.Response response =
          send(
              port,
              "OPTIONS /admin/api/v1/runtime HTTP/1.1",
              "Host: evil.example",
              "Origin: tauri://localhost",
              "Access-Control-Request-Method: GET");

      assertThat(response.status()).isEqualTo(403);
      assertThat(response.header("Access-Control-Allow-Origin")).isEmpty();
    }
  }

  @Nested
  class ConfiguredHosts {

    @Test
    void desktopAcceptsAConfiguredHostName() {
      try (ConfigurableApplicationContext context =
          start("desktop", "--queryhub.admin.allowed-hosts=admin.example")) {
        int port = port(context);
        assertThat(
                send(port, "GET /admin/api/v1/runtime HTTP/1.1", "Host: admin.example", AUTH)
                    .status())
            .isEqualTo(200);
        assertThat(
                send(port, "GET /admin/api/v1/runtime HTTP/1.1", "Host: localhost", AUTH).status())
            .isEqualTo(200);
        assertRejected(
            send(port, "GET /admin/api/v1/runtime HTTP/1.1", "Host: evil.example", AUTH), "evil");
      }
    }

    @Test
    void serverWithoutAllowedHostsAcceptsAnyHostWithTheToken() {
      try (ConfigurableApplicationContext context = start("server")) {
        int port = port(context);
        RawHttp.Response response =
            send(port, "GET /admin/api/v1/runtime HTTP/1.1", "Host: anything.example", AUTH);
        assertThat(response.status()).as(response.everything()).isEqualTo(200);
        assertThat(response.json().path("data").path("mode").stringValue()).isEqualTo("server");
        // 토큰은 그대로 필요하다.
        assertThat(
                send(port, "GET /admin/api/v1/runtime HTTP/1.1", "Host: anything.example").status())
            .isEqualTo(401);
      }
    }

    @Test
    void serverWithAllowedHostsChecksTheHost() {
      try (ConfigurableApplicationContext context =
          start("server", "--queryhub.admin.allowed-hosts=admin.example")) {
        int port = port(context);
        assertThat(
                send(port, "GET /admin/api/v1/runtime HTTP/1.1", "Host: admin.example", AUTH)
                    .status())
            .isEqualTo(200);
        assertRejected(
            send(port, "GET /admin/api/v1/runtime HTTP/1.1", "Host: anything.example", AUTH),
            "server");
      }
    }

    private ConfigurableApplicationContext start(String profile, String... extraArgs) {
      List<String> args = new ArrayList<>(List.of("--server.address=127.0.0.1", "--server.port=0"));
      args.addAll(List.of(extraArgs));
      return new SpringApplicationBuilder(QueryHubApplication.class)
          .web(WebApplicationType.SERVLET)
          .profiles(profile)
          .properties(WithoutMetadataDatabase.PROPERTY, TestAdminToken.PROPERTY)
          .initializers(TestComponentScanExclusion::register)
          .run(args.toArray(String[]::new));
    }

    private int port(ConfigurableApplicationContext context) {
      return ((WebServerApplicationContext) context).getWebServer().getPort();
    }
  }

  private static void collectValves(Container container, List<String> valves) {
    for (Valve valve : container.getPipeline().getValves()) {
      valves.add(valve.getClass().getName());
    }
    for (Container child : container.findChildren()) {
      collectValves(child, valves);
    }
  }

  private static RawHttp.Response send(int port, String requestLine, String... headers) {
    return RawHttp.send(port, RawHttp.request(requestLine, headers));
  }

  private static void assertRejected(RawHttp.Response response, String label) {
    assertThat(response.status()).as("%s %s", label, response.everything()).isIn(400, 403);
    assertThat(response.body()).doesNotContain("queryhub-runtime");
    if (response.status() == 403) {
      RawHttp.assertErrorEnvelope(response, Set.of(403));
      assertThat(response.json().path("error").path("code").stringValue()).isEqualTo("FORBIDDEN");
    }
  }
}
