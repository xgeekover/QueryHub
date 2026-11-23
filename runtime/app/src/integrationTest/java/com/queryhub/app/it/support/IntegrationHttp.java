package com.queryhub.app.it.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 루프백(127.0.0.1)의 실제 포트로 HTTP GET 을 보낸다. HTTP/1.1 로 고정한다. */
public final class IntegrationHttp {

  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .build();

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private IntegrationHttp() {}

  /** 통합 테스트 전용 관리자 토큰. 실제 비밀 값이 아니다(고정 픽스처). */
  public static final String ADMIN_TOKEN = "it-only-admin-token-0123456789-NOT-A-SECRET";

  /**
   * {@code key=value} 형식의 토큰 속성. 프로세스 목록에 보이지 않는 소스(인라인 테스트 속성, SpringApplication 기본 속성)로만 넘긴다. 실행
   * JAR 은 환경변수 QUERYHUB_ADMIN_TOKEN 으로 받는다.
   */
  public static final String ADMIN_TOKEN_PROPERTY = "queryhub.admin.token=" + ADMIN_TOKEN;

  /**
   * 통합 테스트 전용 마스터 키(64 자리 16진수, P1-04). NOT A SECRET: 고정 픽스처이고 운영에서 쓰지 않는다. 단위 테스트의 TestMasterKey 와
   * 다르다.
   */
  public static final String MASTER_KEY =
      "c0ffee00c0ffee01c0ffee02c0ffee03c0ffee04c0ffee05c0ffee06c0ffee07";

  /**
   * {@code key=value} 형식의 마스터 키 속성. 토큰과 같이 프로세스 목록에 보이지 않는 소스로만 넘긴다. 실행 JAR 은 환경변수
   * QUERYHUB_MASTER_KEY 로 받는다.
   */
  public static final String MASTER_KEY_PROPERTY = "queryhub.master-key=" + MASTER_KEY;

  /** Authorization: Bearer 를 붙인 GET. */
  public static HttpResponse<String> adminGet(int port, String path)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + ADMIN_TOKEN)
            .GET()
            .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** GET 응답. 연결이 거부되면 IOException 을 그대로 던진다(기동 대기 루프가 쓴다). */
  public static HttpResponse<String> get(int port, String path)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  public static JsonNode json(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }
}
