package com.queryhub.app.config.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * P1-03 3회차 C2-R1 3층: 자기 점검이 관리 체인의 응답만 통과로 보고, 토큰 없이 Host: localhost 로 묻는지. 가짜 서버가 정해 둔 응답을 돌려준다.
 */
class AdminChainSelfProbeTest {

  private static final String UNAUTHORIZED_BODY =
      "{\"success\":false,\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"m\",\"requestId\":\"r\"}}";
  private static final String FORBIDDEN_BODY =
      "{\"success\":false,\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"m\",\"requestId\":\"r\"}}";

  @Test
  void unauthorizedWithBearerChallengeFromTheChainPasses() {
    Exchange exchange =
        exchange(
            response("401", "WWW-Authenticate: Bearer", UNAUTHORIZED_BODY),
            "/admin/api/v1/runtime");

    assertThat(exchange.answer().fromAdminChain()).isTrue();
    assertThat(exchange.request())
        .startsWith("GET /admin/api/v1/runtime HTTP/1.1\r\n")
        .contains("\r\nHost: localhost\r\n")
        .doesNotContainIgnoringCase("authorization");
  }

  @Test
  void forbiddenEnvelopeFromTheNetworkGuardPasses() {
    assertThat(
            exchange(response("403", "X-Request-Id: r", FORBIDDEN_BODY), "/actuator/env")
                .answer()
                .fromAdminChain())
        .isTrue();
  }

  /** 체인이 꺼지면 생기는 응답(200, 404), 체인이 아닌 곳의 401/403, 깨진 응답. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "200|Content-Type: application/json|{\"version\":\"x\"}",
        "404|Content-Type: application/json|{\"success\":false,\"error\":{\"code\":\"NOT_FOUND\"}}",
        "401|Content-Type: application/json|" + UNAUTHORIZED_BODY,
        "401|WWW-Authenticate: Basic realm=x|" + UNAUTHORIZED_BODY,
        "401|WWW-Authenticate: Bearer|",
        "403|Content-Type: text/plain|Forbidden",
        "400|Content-Type: text/plain|Bad Request",
        "302|Location: /login|"
      })
  void anyOtherAnswerFails(String spec) {
    String[] parts = spec.split("\\|", 3);
    Exchange exchange = exchange(response(parts[0], parts[1], parts[2]), "/admin/api/v1/runtime");

    assertThat(exchange.answer().fromAdminChain()).as(spec).isFalse();
  }

  /** 열린 health 경로: health 문서나 404 NOT_FOUND 만 받는다. env 같은 다른 엔드포인트의 본문은 거부한다. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "200|Content-Type: application/json|{\"groups\":[\"liveness\",\"readiness\"],\"status\":\"UP\"}",
        "503|Content-Type: application/json|{\"status\":\"OUT_OF_SERVICE\"}",
        "200|Content-Type: application/json|{\"status\":\"UP\",\"components\":{\"db\":{\"status\":\"UP\"}}}",
        "404|Content-Type: application/json|{\"success\":false,\"error\":{\"code\":\"NOT_FOUND\"}}"
      })
  void healthDocumentsOrNotFoundPassOnAnOpenHealthPath(String spec) {
    String[] parts = spec.split("\\|", 3);
    Exchange exchange = exchange(response(parts[0], parts[1], parts[2]), "/actuator/health");

    assertThat(exchange.answer().healthOrNotFound()).as(spec).isTrue();
    assertThat(exchange.answer().fromAdminChain()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "200|Content-Type: application/json|{\"activeProfiles\":[],\"propertySources\":[]}",
        "200|Content-Type: application/json|{\"status\":\"UP\",\"propertySources\":[]}",
        "200|Content-Type: application/json|{}",
        "200|Content-Type: text/plain|UP",
        "404|Content-Type: text/plain|Not Found",
        "401|WWW-Authenticate: Bearer|" + UNAUTHORIZED_BODY
      })
  void otherBodiesFailOnAnOpenHealthPath(String spec) {
    String[] parts = spec.split("\\|", 3);

    assertThat(
            exchange(response(parts[0], parts[1], parts[2]), "/actuator/health")
                .answer()
                .healthOrNotFound())
        .as(spec)
        .isFalse();
  }

  /**
   * C3-R2: spring.jackson.serialization.indent-output=true 가 만드는 들여쓴 본문도 JSON 으로 읽어 같은 판단을 한다 (401
   * Bearer, 403, 열린 health 경로의 404).
   */
  @Test
  void prettyPrintedErrorBodiesAreReadAsJson() {
    String unauthorized =
        "{\n  \"success\" : false,\n  \"error\" : {\n    \"code\" : \"UNAUTHORIZED\",\n    \"message\" : \"m\"\n  }\n}";
    String forbidden =
        "{\r\n\t\"error\" :\t{ \"requestId\" : \"r\" ,\n \"code\"\n:\n\"FORBIDDEN\" },\n  \"success\" : false\n}";
    String notFound =
        "{\n  \"success\" : false,\n  \"error\" : {\n    \"code\" : \"NOT_FOUND\"\n  }\n}";

    assertThat(
            exchange(response("401", "WWW-Authenticate: Bearer", unauthorized), "/actuator/env")
                .answer()
                .fromAdminChain())
        .isTrue();
    assertThat(
            exchange(response("403", "X-Request-Id: r", forbidden), "/admin/api/v1/runtime")
                .answer()
                .fromAdminChain())
        .isTrue();
    AdminChainSelfProbe.Answer health =
        exchange(response("404", "Content-Type: application/json", notFound), "/actuator/health")
            .answer();
    assertThat(health.healthOrNotFound()).isTrue();
    assertThat(health.errorCode()).isEqualTo("NOT_FOUND");
  }

  /** C3-R2: 오류 코드는 최상위 error 객체의 code 만 읽는다. 다른 곳에 같은 글자가 있어도 체인의 응답이 아니다. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"code\":\"UNAUTHORIZED\"}",
        "{\"detail\":{\"error\":{\"code\":\"UNAUTHORIZED\"}}}",
        "{\"error\":\"\\\"code\\\":\\\"UNAUTHORIZED\\\"\"}",
        "{\"error\":{\"code\":[\"UNAUTHORIZED\"]}}",
        "[{\"error\":{\"code\":\"UNAUTHORIZED\"}}]",
        "not json \"code\":\"UNAUTHORIZED\""
      })
  void anErrorCodeOutsideTheEnvelopeDoesNotCount(String body) {
    AdminChainSelfProbe.Answer answer =
        exchange(response("401", "WWW-Authenticate: Bearer", body), "/admin/api/v1/runtime")
            .answer();

    assertThat(answer.errorCode()).as(body).isEmpty();
    assertThat(answer.fromAdminChain()).as(body).isFalse();
  }

  @Test
  void chunkedHealthBodyIsJoinedBeforeItIsJudged() {
    String raw =
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "5\r\n{\"sta\r\n"
            + "a\r\ntus\":\"UP\"}\r\n"
            + "0\r\n\r\n";

    assertThat(exchange(raw, "/actuator/health").answer().healthOrNotFound()).isTrue();
  }

  @Test
  void garbageOrAClosedConnectionFails() {
    assertThat(exchange("not http\r\n\r\n", "/admin/api/v1/runtime").answer())
        .isEqualTo(AdminChainSelfProbe.Answer.NONE);
    assertThat(exchange("", "/admin/api/v1/runtime").answer())
        .isEqualTo(AdminChainSelfProbe.Answer.NONE);
  }

  @Test
  void aServerThatNeverAnswersFailsWithinTheTimeout() throws IOException {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      CompletableFuture<Void> accept =
          CompletableFuture.runAsync(
              () -> {
                try (Socket socket = server.accept()) {
                  socket.getInputStream().readAllBytes();
                } catch (IOException ignored) {
                  // 점검 쪽이 시간 초과로 끊는다.
                }
              });
      long started = System.nanoTime();
      AdminChainSelfProbe.Answer answer =
          AdminChainSelfProbe.send(
              InetAddress.getLoopbackAddress(),
              server.getLocalPort(),
              "/admin/api/v1/runtime",
              Duration.ofMillis(300));

      assertThat(answer.fromAdminChain()).isFalse();
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
      accept.cancel(true);
    }
  }

  /** C3-R1: 답하지 않는 서버는 연결 실패와 구별되는 시간 초과다 (열린 health 경로의 실패 메시지가 이것을 따로 말한다). */
  @Test
  void aServerThatNeverAnswersIsReportedAsATimeout() throws IOException {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      CompletableFuture<Void> accept =
          CompletableFuture.runAsync(
              () -> {
                try (Socket socket = server.accept()) {
                  socket.getInputStream().readAllBytes();
                } catch (IOException ignored) {
                  // 점검 쪽이 시간 초과로 끊는다.
                }
              });
      AdminChainSelfProbe.Answer answer =
          AdminChainSelfProbe.send(
              InetAddress.getLoopbackAddress(),
              server.getLocalPort(),
              "/actuator/health",
              Duration.ofMillis(300));

      assertThat(answer).isEqualTo(AdminChainSelfProbe.Answer.TIMEOUT);
      assertThat(answer.timedOut()).isTrue();
      assertThat(answer.healthOrNotFound()).isFalse();
      assertThat(answer.describe()).contains("시간 초과");
      accept.cancel(true);
    }
  }

  @Test
  void aClosedPortFails() throws IOException {
    int port;
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = server.getLocalPort();
    }
    assertThat(
            AdminChainSelfProbe.send(
                InetAddress.getLoopbackAddress(),
                port,
                "/admin/api/v1/runtime",
                Duration.ofMillis(500)))
        .isEqualTo(AdminChainSelfProbe.Answer.NONE);
  }

  private static String response(String status, String header, String body) {
    return "HTTP/1.1 "
        + status
        + " X\r\n"
        + header
        + "\r\nContent-Length: "
        + body.getBytes(StandardCharsets.ISO_8859_1).length
        + "\r\nConnection: close\r\n\r\n"
        + body;
  }

  private record Exchange(AdminChainSelfProbe.Answer answer, String request) {}

  /** 요청 하나를 받아 기록하고 정해 둔 응답을 쓰는 가짜 서버에 점검 요청을 보낸다. */
  private static Exchange exchange(String rawResponse, String path) {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      CompletableFuture<String> received =
          CompletableFuture.supplyAsync(
              () -> {
                try (Socket socket = server.accept()) {
                  String request = readHead(socket.getInputStream());
                  socket.getOutputStream().write(rawResponse.getBytes(StandardCharsets.ISO_8859_1));
                  return request;
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
      AdminChainSelfProbe.Answer answer =
          AdminChainSelfProbe.send(
              InetAddress.getLoopbackAddress(), server.getLocalPort(), path, Duration.ofSeconds(5));
      return new Exchange(answer, received.get(5, TimeUnit.SECONDS));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String readHead(InputStream in) throws IOException {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1) {
      head.write(b);
      @Nullable String text = head.toString(StandardCharsets.ISO_8859_1);
      if (text.endsWith("\r\n\r\n")) {
        break;
      }
    }
    return head.toString(StandardCharsets.ISO_8859_1);
  }
}
