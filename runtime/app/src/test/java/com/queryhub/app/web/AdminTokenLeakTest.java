package com.queryhub.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.app.QueryHubApplication;
import com.queryhub.app.support.RawHttp;
import com.queryhub.app.support.TestAdminToken;
import com.queryhub.app.support.TestComponentScanExclusion;
import com.queryhub.app.support.TestMasterKey;
import com.queryhub.app.support.WithoutMetadataDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * P1-03 AC9: 토큰이 로그, 응답 본문, 응답 헤더에 나오지 않는다. 컨텍스트를 이 테스트 안에서 새로 띄워 기동 로그까지 잡고, 보안·웹 로그를 DEBUG 로 켜서
 * 가장 많이 남는 경우를 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminTokenLeakTest {

  /** 틀린 토큰(테스트 전용). 이 값도 어디에도 나오면 안 된다. */
  private static final String WRONG = "wrong-admin-token-value-for-the-leak-test-0001";

  @Test
  void tokenNeverAppearsInOutputOrResponses(CapturedOutput output) {
    List<RawHttp.Response> responses = new ArrayList<>();
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(QueryHubApplication.class)
            .web(WebApplicationType.SERVLET)
            .profiles("desktop")
            .properties(
                WithoutMetadataDatabase.PROPERTY, TestAdminToken.PROPERTY, TestMasterKey.PROPERTY)
            .initializers(TestComponentScanExclusion::register)
            .run(
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--logging.level.org.springframework.security=DEBUG",
                "--logging.level.org.springframework.web=DEBUG",
                "--logging.level.com.queryhub=DEBUG")) {
      assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      String host = "Host: 127.0.0.1:" + port;

      RawHttp.Response ok = send(port, host, "Authorization: " + TestAdminToken.AUTHORIZATION);
      assertThat(ok.status()).isEqualTo(200);
      RawHttp.Response wrong = send(port, host, "Authorization: Bearer " + WRONG);
      assertThat(wrong.status()).isEqualTo(401);
      RawHttp.Response missing = send(port, host);
      assertThat(missing.status()).isEqualTo(401);
      RawHttp.Response forbidden =
          send(port, "Host: evil.example", "Authorization: " + TestAdminToken.AUTHORIZATION);
      assertThat(forbidden.status()).isEqualTo(403);
      RawHttp.Response forbiddenWrong =
          send(port, "Host: evil.example", "Authorization: Bearer " + WRONG);
      assertThat(forbiddenWrong.status()).isEqualTo(403);
      responses.addAll(List.of(ok, wrong, missing, forbidden, forbiddenWrong));
    }

    String all = output.getAll();
    assertThat(all).as("DEBUG 로그가 실제로 잡혔다").contains("/admin/api/v1/runtime");
    assertThat(all).doesNotContain(TestAdminToken.VALUE).doesNotContain(WRONG);
    assertThat(all.toLowerCase(Locale.ROOT)).doesNotContain("generated security password");
    for (RawHttp.Response response : responses) {
      assertThat(response.everything()).doesNotContain(TestAdminToken.VALUE).doesNotContain(WRONG);
    }
  }

  private static RawHttp.Response send(int port, String... headers) {
    return RawHttp.send(port, RawHttp.request("GET /admin/api/v1/runtime HTTP/1.1", headers));
  }
}
