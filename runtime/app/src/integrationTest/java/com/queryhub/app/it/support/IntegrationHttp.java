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
