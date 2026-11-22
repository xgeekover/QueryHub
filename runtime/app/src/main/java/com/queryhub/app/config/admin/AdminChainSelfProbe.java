package com.queryhub.app.config.admin;

import com.queryhub.adapter.web.admin.security.AdminRequestMatcher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.catalina.connector.Connector;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기동 직후의 관리 체인 자기 점검 (P1-03 3회차, 두 프로파일 모두). 속성 검사와 필터 매핑 검사가 보지 못한 설정이 관리 API 나 actuator 를 체인 밖으로
 * 옮기거나 체인을 꺼도 런타임이 그대로 서비스하지 않게 하는 마지막 안전장치다.
 *
 * <p>포트가 열린 뒤 런타임은 자기 포트로 평문 HTTP/1.1 요청을 보낸다: {@code Host: localhost}, 토큰(Authorization) 없음, 경로는
 * {@link #PROBE_PATHS}. 응답이 모두 관리 체인의 것(401 + {@code WWW-Authenticate: Bearer} + UNAUTHORIZED, 또는
 * 네트워크 가드의 403 FORBIDDEN)일 때만 통과한다. 2xx, 404, 그 밖의 상태, 응답 없음은 체인이 그 경로를 지키지 않는다는 뜻이므로 예외를 던진다. 그러면
 * refresh 가 실패하고 웹 서버가 멈추며 프로세스는 0 이 아닌 코드로 끝난다. 메시지는 경로와 상태 코드만 말한다. 토큰은 보내지도 적지도 않는다.
 *
 * <p>실행 시점: 포트를 여는 WebServerStartStopLifecycle(phase {@value
 * WebServerApplicationContext#START_STOP_LIFECYCLE_PHASE}) 바로 뒤의 phase 다. 아직 refresh 안이므로
 * ApplicationReadyEvent 와 readiness ACCEPTING_TRAFFIC 은 점검이 통과한 뒤에야 나온다. 점검이 도는 동안(루프백에서 수 ms ~ 수백
 * ms, 첫 요청이 DispatcherServlet 을 초기화한다) 포트는 열려 있다.
 *
 * <p>연결 주소는 {@code server.address} 다(비어 있거나 와일드카드면 루프백). 소켓은 프록시를 거치지 않는다({@link Proxy#NO_PROXY}).
 * context path 가 있으면 앞에 붙인다. 내장 TLS(server.ssl)는 속성 검사({@link AdminAccessConfiguration})가 포트를 열기 전에
 * 거부하고, 다른 길로 TLS 커넥터가 생겨도 이 점검이 이중 안전장치로 거부한다.
 *
 * <p>실패할 때(P1-03 4회차, C3-R1): 경로와 상태만 담은 이유를 먼저 ERROR 로그에 남기고, {@link StartupFailureHalt} 예약을 건 뒤
 * 예외를 던진다. 그래서 컨텍스트 정리가 멈춰도(예: 메타데이터 DB 가 멈춰 Hikari 종료가 끝나지 않는다) 이유는 로그에 있고 프로세스는 정해진 시간 안에 끝난다. 열린
 * health 경로가 {@link #TIMEOUT} 안에 답하지 않으면(health 점검이 메타데이터 DB 를 기다리는 등) 엔드포인트가 옮겨졌다고 하지 않고 시간 초과를 따로
 * 말한다. 응답 본문은 JSON 으로 읽는다(오류 응답의 {@code error.code}, health 문서의 최상위 키). 그래서 {@code
 * spring.jackson.serialization.indent-output} 같은 출력 형식 설정과 상관없다.
 */
public final class AdminChainSelfProbe implements SmartLifecycle {

  /** 토큰 없이 보내는 경로: 관리 API 의 대표 경로와 health 밖의 actuator 두 곳. */
  static final List<String> PROBE_PATHS =
      List.of("/admin/api/v1/runtime", "/actuator/env", "/actuator/beans");

  /** 연결과 읽기 제한. 루프백에서는 보통 수 ms 다. */
  static final Duration TIMEOUT = Duration.ofSeconds(5);

  /**
   * 토큰 없이 여는 health 경로. 응답은 health 문서(상태 코드와 상관없이 최상위 키가 status, components, groups, description
   * 뿐)이거나 404 NOT_FOUND 여야 한다. 다른 엔드포인트(env 등)가 이 경로로 옮겨지면 잡는다.
   */
  static final List<String> OPEN_HEALTH_PATHS =
      AdminRequestMatcher.OPEN_HEALTH_PATHS.stream().sorted().toList();

  private static final Set<String> HEALTH_KEYS =
      Set.of("status", "components", "groups", "description");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final int MAX_RESPONSE_BYTES = 64 * 1024;
  private static final Logger LOG = LoggerFactory.getLogger(AdminChainSelfProbe.class);

  private final ApplicationContext applicationContext;
  private final Environment environment;
  private volatile List<String> verifiedPaths = List.of();
  private volatile boolean running;

  AdminChainSelfProbe(ApplicationContext applicationContext, Environment environment) {
    this.applicationContext = applicationContext;
    this.environment = environment;
  }

  @Override
  public void start() {
    WebServer webServer =
        applicationContext instanceof WebServerApplicationContext webContext
            ? webContext.getWebServer()
            : null;
    if (webServer != null && webServer.getPort() > 0) {
      verifiedPaths = probe(webServer);
    }
    running = true;
  }

  private List<String> probe(WebServer webServer) {
    if (webServer instanceof TomcatWebServer tomcat && usesTls(tomcat)) {
      throw fail(
          "내장 TLS 커넥터가 있어 관리 체인 자기 점검(평문 HTTP)을 할 수 없으므로 런타임을 멈춘다. TLS 는 앞단의 리버스 프록시에서 끝낸다.");
    }
    InetAddress address = probeAddress(environment);
    String contextPath = contextPath(applicationContext);
    long started = System.nanoTime();
    List<String> verified = new ArrayList<>();
    for (String path : PROBE_PATHS) {
      Answer answer = send(address, webServer.getPort(), contextPath + path, TIMEOUT);
      if (!answer.fromAdminChain()) {
        throw fail(
            "관리 체인 자기 점검에 실패해 런타임을 멈춘다: 토큰 없는 GET "
                + contextPath
                + path
                + " 의 응답이 "
                + answer.describe()
                + " 이다 (관리 체인의 401 Bearer 나 403 FORBIDDEN 이어야 한다). 관리 API 나 actuator 가 관리 체인 밖에 있거나 체인이 꺼져"
                + " 토큰 없이 열릴 수 있다.");
      }
      verified.add(path);
    }
    for (String path : OPEN_HEALTH_PATHS) {
      Answer answer = send(address, webServer.getPort(), contextPath + path, TIMEOUT);
      if (answer.timedOut()) {
        throw fail(
            "관리 체인 자기 점검에 실패해 런타임을 멈춘다: 토큰 없이 여는 GET "
                + contextPath
                + path
                + " 가 "
                + TIMEOUT.toSeconds()
                + " 초 안에 답하지 않았다 (시간 초과). health 점검(메타데이터 DB 연결 확인 등)이 멈춰 있어 열린 health 경로의 응답을 확인할 수"
                + " 없다. 메타데이터 DB 의 상태를 확인하라.");
      }
      if (!answer.healthOrNotFound()) {
        throw fail(
            "관리 체인 자기 점검에 실패해 런타임을 멈춘다: 토큰 없이 여는 GET "
                + contextPath
                + path
                + " 의 응답("
                + answer.describe()
                + ")이 health 문서도 404 NOT_FOUND 도 아니다. 다른 엔드포인트나 핸들러가 열린 health 경로로 옮겨져 토큰 없이 열릴 수 있다.");
      }
      verified.add(path);
    }
    LOG.info(
        "관리 체인 자기 점검 통과: 토큰 없는 요청 {}개가 관리 체인의 401/403 이거나 health 응답이었다 ({} ms)",
        verified.size(),
        Duration.ofNanos(System.nanoTime() - started).toMillis());
    return List.copyOf(verified);
  }

  /** 이유(경로와 상태만, 토큰과 속성 값 없음)를 먼저 로그에 남기고 강제 종료 예약을 건 뒤 던질 예외를 만든다. */
  private static IllegalStateException fail(String reason) {
    LOG.error(reason);
    StartupFailureHalt.armIfInstalled();
    return new IllegalStateException(reason);
  }

  /** 점검을 통과한 경로 (웹 서버가 없으면 비어 있다). */
  List<String> verifiedPaths() {
    return verifiedPaths;
  }

  private static boolean usesTls(TomcatWebServer webServer) {
    for (Connector connector : webServer.getTomcat().getService().findConnectors()) {
      if (connector.getSecure() || "https".equalsIgnoreCase(connector.getScheme())) {
        return true;
      }
    }
    return false;
  }

  private static InetAddress probeAddress(Environment environment) {
    InetAddress configured =
        Binder.get(environment).bind("server.address", InetAddress.class).orElse(null);
    return configured == null || configured.isAnyLocalAddress()
        ? InetAddress.getLoopbackAddress()
        : configured;
  }

  private static String contextPath(ApplicationContext context) {
    if (context instanceof WebApplicationContext web && web.getServletContext() != null) {
      return web.getServletContext().getContextPath();
    }
    return "";
  }

  /**
   * 토큰 없는 요청 하나를 보내고 응답을 읽는다. 연결이나 읽기가 시간 안에 끝나지 않으면 {@link Answer#TIMEOUT}, 그 밖의 실패는 {@link
   * Answer#NONE} 이다.
   */
  static Answer send(InetAddress address, int port, String path, Duration timeout) {
    int millis = (int) timeout.toMillis();
    long deadline = System.nanoTime() + timeout.toNanos();
    try (Socket socket = new Socket(Proxy.NO_PROXY)) {
      socket.connect(new InetSocketAddress(address, port), millis);
      socket.setSoTimeout(millis);
      OutputStream out = socket.getOutputStream();
      out.write(
          ("GET "
                  + path
                  + " HTTP/1.1\r\nHost: localhost\r\nAccept: application/json\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int read;
      while (buffer.size() < MAX_RESPONSE_BYTES && (read = in.read(chunk)) != -1) {
        buffer.write(chunk, 0, read);
        if (System.nanoTime() > deadline) {
          break;
        }
      }
      return Answer.parse(buffer.toString(StandardCharsets.ISO_8859_1));
    } catch (SocketTimeoutException e) {
      return Answer.TIMEOUT;
    } catch (IOException | RuntimeException e) {
      return Answer.NONE;
    }
  }

  /** 응답에서 판단에 쓰는 것만 남긴다. 본문과 헤더 값은 남기지 않는다. */
  record Answer(
      int status,
      boolean bearerChallenge,
      String errorCode,
      boolean healthDocument,
      boolean timedOut) {

    static final Answer NONE = new Answer(0, false, "", false, false);

    static final Answer TIMEOUT = new Answer(0, false, "", false, true);

    /**
     * 열린 health 경로의 응답으로 받을 수 있는지: health 문서(상태 코드는 status.http-mapping 에 따라 다를 수 있다), 또는 404
     * NOT_FOUND.
     */
    boolean healthOrNotFound() {
      return (status >= 200 && healthDocument) || (status == 404 && "NOT_FOUND".equals(errorCode));
    }

    /** 관리 체인이 답했는지: 401 + Bearer + UNAUTHORIZED, 또는 403 + FORBIDDEN. */
    boolean fromAdminChain() {
      return (status == 401 && bearerChallenge && "UNAUTHORIZED".equals(errorCode))
          || (status == 403 && "FORBIDDEN".equals(errorCode));
    }

    String describe() {
      if (status != 0) {
        return "HTTP " + status;
      }
      return timedOut ? "없음(시간 초과)" : "없음(연결 실패, 해석할 수 없는 응답)";
    }

    static Answer parse(String raw) {
      int headerEnd = raw.indexOf("\r\n\r\n");
      if (!raw.startsWith("HTTP/1.") || headerEnd < 0) {
        return NONE;
      }
      String[] lines = raw.substring(0, headerEnd).split("\r\n");
      String[] statusLine = lines[0].split(" ", 3);
      int status;
      try {
        status = statusLine.length < 2 ? 0 : Integer.parseInt(statusLine[1]);
      } catch (NumberFormatException e) {
        return NONE;
      }
      boolean bearer = false;
      boolean chunked = false;
      for (int i = 1; i < lines.length; i++) {
        int colon = lines[i].indexOf(':');
        if (colon > 0 && lines[i].substring(0, colon).trim().equalsIgnoreCase("WWW-Authenticate")) {
          bearer |= lines[i].substring(colon + 1).trim().regionMatches(true, 0, "Bearer", 0, 6);
        }
        if (colon > 0
            && lines[i].substring(0, colon).trim().equalsIgnoreCase("Transfer-Encoding")) {
          chunked |= lines[i].substring(colon + 1).toLowerCase(Locale.ROOT).contains("chunked");
        }
      }
      String body = raw.substring(headerEnd + 4);
      if (chunked) {
        body = dechunk(body);
      }
      JsonNode json = readJson(body);
      return new Answer(status, bearer, errorCode(json), isHealthDocument(json), false);
    }

    /** 본문을 JSON 으로 읽는다. 비었거나 JSON 이 아니면 null. 공백·들여쓰기·키 순서와 상관없다. */
    private static @Nullable JsonNode readJson(String body) {
      try {
        return JSON.readTree(body);
      } catch (RuntimeException e) {
        return null;
      }
    }

    /** 오류 응답(ErrorEnvelope)의 {@code error.code}. 최상위가 객체이고 error 가 객체이며 code 가 문자열일 때만 읽는다. */
    private static String errorCode(@Nullable JsonNode json) {
      if (json == null || !json.isObject()) {
        return "";
      }
      JsonNode error = json.get("error");
      if (error == null || !error.isObject()) {
        return "";
      }
      JsonNode code = error.get("code");
      return code != null && code.isString() ? code.asString() : "";
    }

    /** 최상위가 JSON 객체이고 문자열 status 가 있으며 다른 키는 health 의 것뿐인지. */
    private static boolean isHealthDocument(@Nullable JsonNode node) {
      if (node == null
          || !node.isObject()
          || node.get("status") == null
          || !node.get("status").isString()) {
        return false;
      }
      for (Map.Entry<String, JsonNode> property : node.properties()) {
        if (!HEALTH_KEYS.contains(property.getKey())) {
          return false;
        }
      }
      return true;
    }

    /** chunked 본문을 잇는다. 형식이 깨지면 그때까지 읽은 것만 돌려준다. */
    private static String dechunk(String body) {
      StringBuilder out = new StringBuilder();
      int at = 0;
      while (at < body.length()) {
        int lineEnd = body.indexOf("\r\n", at);
        if (lineEnd < 0) {
          break;
        }
        String sizeField = body.substring(at, lineEnd);
        int semicolon = sizeField.indexOf(';');
        int size;
        try {
          size =
              Integer.parseInt(
                  (semicolon < 0 ? sizeField : sizeField.substring(0, semicolon)).trim(), 16);
        } catch (NumberFormatException e) {
          break;
        }
        if (size == 0 || lineEnd + 2 + size > body.length()) {
          break;
        }
        out.append(body, lineEnd + 2, lineEnd + 2 + size);
        at = lineEnd + 2 + size + 2;
      }
      return out.toString();
    }
  }

  @Override
  public void stop() {
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** 포트를 여는 WebServerStartStopLifecycle 바로 뒤. */
  @Override
  public int getPhase() {
    return WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE + 1;
  }
}
