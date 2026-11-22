package com.queryhub.app.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.domain.error.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 소켓으로 바이트 그대로의 HTTP 요청을 보낸다. java.net.http 는 요청 대상을 정규화하고 Host 헤더를 직접 정하므로, 경로 변형(;, %2F, //, ..)과
 * Host 변형(없음, 두 개, userinfo 등)은 이 클라이언트로 보낸다. 늘 127.0.0.1 로 연결하고 응답은 연결이 닫힐 때까지 읽는다.
 */
public final class RawHttp {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int TIMEOUT_MILLIS = 10_000;

  private RawHttp() {}

  /** 응답. 헤더 이름은 소문자다. */
  public record Response(int status, Map<String, List<String>> headers, String body) {

    public List<String> header(String name) {
      return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    public Optional<String> firstHeader(String name) {
      return header(name).stream().findFirst();
    }

    public JsonNode json() {
      return JSON.readTree(body);
    }

    /** 상태 줄, 헤더, 본문 전체(누출 검사용). */
    public String everything() {
      return status + "\n" + headers + "\n" + body;
    }
  }

  /** 요청 줄과 헤더 줄로 요청을 만든다. {@code Connection: close} 와 빈 줄을 덧붙인다. Host 는 넣지 않으므로 필요하면 헤더 줄로 넣는다. */
  public static String request(String requestLine, String... headerLines) {
    StringBuilder builder = new StringBuilder(requestLine).append("\r\n");
    for (String line : headerLines) {
      builder.append(line).append("\r\n");
    }
    return builder.append("Connection: close\r\n\r\n").toString();
  }

  /** 요청을 그대로 보내고 응답을 읽는다. */
  public static Response send(int port, String rawRequest) {
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      socket.setSoTimeout(TIMEOUT_MILLIS);
      OutputStream out = socket.getOutputStream();
      out.write(rawRequest.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      return parse(socket.getInputStream().readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * 공통 오류 형식을 검사한다: 상태가 allowed 중 하나, application/json, {@code {success:false, error:{code,
   * message, requestId}}}, code 는 ErrorCode 이름.
   */
  public static JsonNode assertErrorEnvelope(Response response, Set<Integer> allowedStatuses) {
    assertThat(response.status())
        .as("status, response=%s", response.everything())
        .isIn(allowedStatuses);
    assertThat(response.firstHeader("Content-Type"))
        .as("Content-Type, response=%s", response.everything())
        .hasValueSatisfying(
            value ->
                assertThat(
                        MediaType.APPLICATION_JSON.equalsTypeAndSubtype(
                            MediaType.parseMediaType(value)))
                    .isTrue());
    JsonNode body = response.json();
    assertThat(body.propertyNames()).containsExactlyInAnyOrder("success", "error");
    assertThat(body.path("success").booleanValue()).isFalse();
    JsonNode error = body.path("error");
    assertThat(error.propertyNames()).containsExactlyInAnyOrder("code", "message", "requestId");
    assertThat(ErrorCode.valueOf(error.path("code").stringValue())).isNotNull();
    assertThat(error.path("requestId").stringValue()).isNotBlank();
    return error;
  }

  private static Response parse(byte[] raw) {
    int headerEnd = indexOf(raw, new byte[] {'\r', '\n', '\r', '\n'});
    assertThat(headerEnd)
        .as("응답 헤더 끝을 찾지 못했다: %s", new String(raw, StandardCharsets.ISO_8859_1))
        .isGreaterThan(0);
    String head = new String(raw, 0, headerEnd, StandardCharsets.ISO_8859_1);
    String[] lines = head.split("\r\n");
    int status = Integer.parseInt(lines[0].split(" ")[1]);
    Map<String, List<String>> headers = new LinkedHashMap<>();
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
      headers
          .computeIfAbsent(name, key -> new ArrayList<>())
          .add(lines[i].substring(colon + 1).trim());
    }
    byte[] body = java.util.Arrays.copyOfRange(raw, headerEnd + 4, raw.length);
    if (headers.getOrDefault("transfer-encoding", List.of()).stream()
        .anyMatch(value -> value.equalsIgnoreCase("chunked"))) {
      body = dechunk(body);
    }
    return new Response(status, headers, new String(body, StandardCharsets.UTF_8));
  }

  private static byte[] dechunk(byte[] chunked) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int position = 0;
    while (position < chunked.length) {
      int lineEnd = indexOf(chunked, new byte[] {'\r', '\n'}, position);
      if (lineEnd < 0) {
        break;
      }
      String sizeLine =
          new String(chunked, position, lineEnd - position, StandardCharsets.ISO_8859_1);
      int size = Integer.parseInt(sizeLine.split(";")[0].trim(), 16);
      if (size == 0) {
        break;
      }
      out.write(chunked, lineEnd + 2, size);
      position = lineEnd + 2 + size + 2;
    }
    return out.toByteArray();
  }

  private static int indexOf(byte[] data, byte[] pattern) {
    return indexOf(data, pattern, 0);
  }

  private static int indexOf(byte[] data, byte[] pattern, int from) {
    outer:
    for (int i = from; i <= data.length - pattern.length; i++) {
      for (int j = 0; j < pattern.length; j++) {
        if (data[i + j] != pattern[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}
