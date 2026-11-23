package com.queryhub.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.queryhub.app.QueryHubApplication;
import com.queryhub.app.support.ErrorProbeTestConfiguration;
import com.queryhub.app.support.RawHttp;
import com.queryhub.app.support.TestAdminToken;
import com.queryhub.app.support.TestComponentScanExclusion;
import com.queryhub.app.support.TestMasterKey;
import com.queryhub.app.support.WithoutMetadataDatabase;
import com.queryhub.application.secret.SecretCipher;
import com.queryhub.application.secret.SecretCipherException;
import com.queryhub.application.secret.SecretContext;
import com.queryhub.domain.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * P1-04 AC13: 마스터 키가 로그, 응답 본문, 응답 헤더, actuator 에 나오지 않는다. 컨텍스트를 이 테스트 안에서 새로 띄워 기동 로그까지 잡고,
 * 보안·웹·QueryHub 로그를 DEBUG 로 켜서 가장 많이 남는 경우를 본다(AdminTokenLeakTest 와 같은 방식). env, configprops 엔드포인트의
 * 값 표시를 ALWAYS 로 켜도 엔드포인트가 공개되지 않으므로 404 다. 거부 경로(명령행의 키, 형식이 틀린 키 파일)도 값과 파일 경로를 출력하지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class MasterKeyLeakTest {

  private static final String KEY_LOWER = TestMasterKey.VALUE;

  private static final String KEY_UPPER = TestMasterKey.VALUE.toUpperCase(Locale.ROOT);

  @TempDir Path dir;

  @Test
  void keyNeverAppearsInOutputResponsesOrActuator(CapturedOutput output) {
    List<RawHttp.Response> responses = new ArrayList<>();
    String kid;
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(QueryHubApplication.class, ErrorProbeTestConfiguration.class)
            .web(WebApplicationType.SERVLET)
            .profiles("desktop")
            .properties(
                WithoutMetadataDatabase.PROPERTY, TestAdminToken.PROPERTY, TestMasterKey.PROPERTY)
            .initializers(TestComponentScanExclusion::register)
            .run(
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--management.endpoint.env.show-values=ALWAYS",
                "--management.endpoint.configprops.show-values=ALWAYS",
                "--logging.level.org.springframework.security=DEBUG",
                "--logging.level.org.springframework.web=DEBUG",
                "--logging.level.com.queryhub=DEBUG")) {
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      String host = "Host: 127.0.0.1:" + port;
      String auth = "Authorization: " + TestAdminToken.AUTHORIZATION;
      SecretCipher cipher = context.getBean(SecretCipher.class);
      kid = cipher.encrypt("x", SecretContext.databaseConnectionPassword("1")).split("\\.")[1];

      RawHttp.Response ok = send(port, "/admin/api/v1/runtime", host, auth);
      assertThat(ok.status()).isEqualTo(200);
      RawHttp.Response unauthorized = send(port, "/admin/api/v1/runtime", host);
      assertThat(unauthorized.status()).isEqualTo(401);
      RawHttp.Response forbidden = send(port, "/admin/api/v1/runtime", "Host: evil.example", auth);
      assertThat(forbidden.status()).isEqualTo(403);
      RawHttp.Response notFound = send(port, "/admin/api/v1/no-such-resource", host, auth);
      assertThat(notFound.status()).isEqualTo(404);
      RawHttp.Response env = send(port, "/actuator/env", host, auth);
      assertThat(env.status()).as("env 는 공개하지 않는다").isEqualTo(404);
      RawHttp.Response envKey = send(port, "/actuator/env/queryhub.master-key", host, auth);
      assertThat(envKey.status()).isEqualTo(404);
      RawHttp.Response configprops = send(port, "/actuator/configprops", host, auth);
      assertThat(configprops.status()).as("configprops 는 공개하지 않는다").isEqualTo(404);
      RawHttp.Response probe =
          send(port, ErrorProbeTestConfiguration.SECRET_CIPHER_ERROR_PATH, host);
      assertThat(probe.status()).isEqualTo(500);
      assertThat(probe.json().path("success").booleanValue()).isFalse();
      assertThat(probe.json().path("error").path("code").stringValue())
          .isEqualTo(ErrorCode.INTERNAL_ERROR.name());
      assertThat(probe.json().path("error").path("message").stringValue())
          .isEqualTo(ErrorCode.INTERNAL_ERROR.defaultMessage());
      responses.addAll(
          List.of(ok, unauthorized, forbidden, notFound, env, envKey, configprops, probe));

      // S5: 키는 어떤 @ConfigurationProperties 빈에도 묶이지 않는다(Binder 로만 읽는다).
      for (ConfigurationPropertiesBean bean :
          ConfigurationPropertiesBean.getAll(context).values()) {
        assertThat(bean.getAnnotation().prefix() + bean.getAnnotation().value())
            .doesNotContain("master-key");
        assertThat(String.valueOf(bean.getInstance())).doesNotContainIgnoringCase(KEY_LOWER);
      }
      assertThat(cipher.toString()).doesNotContainIgnoringCase(KEY_LOWER).contains(kid);
    }

    String all = output.getAll();
    assertThat(all).as("DEBUG 로그가 실제로 잡혔다").contains("/admin/api/v1/runtime");
    assertThat(all).as("키 ID 만 남는다").contains("마스터 키를 읽었다 (키 ID " + kid + ")");
    assertThat(all)
        .as("복호화 실패의 고정 메시지는 로그에 남는다")
        .contains(SecretCipherException.Reason.AUTHENTICATION_FAILED.message());
    assertThat(all).doesNotContain(KEY_LOWER).doesNotContain(KEY_UPPER);
    for (RawHttp.Response response : responses) {
      assertThat(response.everything()).doesNotContainIgnoringCase(KEY_LOWER);
    }
  }

  @Test
  void refusalsPrintNeitherTheKeyNorTheFilePath(CapturedOutput output) throws IOException {
    Path malformed = dir.resolve("malformed-master.key");
    Files.writeString(malformed, KEY_UPPER.substring(2) + "zz\n", StandardCharsets.UTF_8);

    Throwable onArgv = startRefused(Map.of(), "--queryhub.master-key=" + KEY_LOWER);
    Throwable badFile = startRefused(Map.of("QUERYHUB_MASTER_KEY_FILE", malformed.toString()));

    assertThat(onArgv).isNotNull();
    assertThat(badFile).isNotNull();
    String all = output.getAll();
    assertThat(all).as("거부가 출력에 남았다").contains("마스터 키 설정이 잘못되어 런타임을 시작하지 않는다");
    assertThat(all)
        .doesNotContain(KEY_LOWER)
        .doesNotContain(KEY_UPPER)
        .doesNotContain(KEY_UPPER.substring(2) + "zz")
        .doesNotContain(malformed.toString())
        .doesNotContain(dir.toString());
  }

  private static Throwable startRefused(Map<String, Object> variables, String... extraArgs) {
    List<String> args = new ArrayList<>(List.of("--server.address=127.0.0.1", "--server.port=0"));
    args.addAll(List.of(extraArgs));
    return catchThrowable(
        () ->
            new SpringApplicationBuilder(QueryHubApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("server")
                .properties(WithoutMetadataDatabase.PROPERTY, TestAdminToken.PROPERTY)
                .initializers(
                    TestComponentScanExclusion::register,
                    context ->
                        context
                            .getEnvironment()
                            .getPropertySources()
                            .addFirst(
                                new SystemEnvironmentPropertySource(
                                    "qhTest-"
                                        + StandardEnvironment
                                            .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                    new HashMap<>(variables))))
                .run(args.toArray(String[]::new))
                .close());
  }

  private static RawHttp.Response send(int port, String path, String... headers) {
    return RawHttp.send(port, RawHttp.request("GET " + path + " HTTP/1.1", headers));
  }
}
