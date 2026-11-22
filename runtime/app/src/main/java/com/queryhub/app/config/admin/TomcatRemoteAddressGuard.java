package com.queryhub.app.config.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.catalina.Container;
import org.apache.catalina.Service;
import org.apache.catalina.Valve;
import org.apache.catalina.startup.Tomcat;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;

/**
 * 만들어진 내장 Tomcat 의 파이프라인(engine, host, context, wrapper)에 소켓 원격 주소를 바꿀 수 있는 valve 가 없는지 확인한다
 * ({@value AdminSecurityProperties#ALLOW_REMOTE}=false 일 때만). 속성 검사({@link
 * AdminAccessConfiguration})의 이중 안전장치다: 속성 이름을 놓치거나 다른 코드가 valve 를 넣어도 루프백 검사가 X-Forwarded-For 값에
 * 속지 않게 한다.
 *
 * <p>판단은 허용 목록으로 한다. 아래 {@link #ALLOWED_VALVES} 에 없는 valve 는 하는 일을 알 수 없으므로 RemoteIpValve 와 똑같이
 * 거부한다(fail closed).
 *
 * <p>실행 시점: 웹 서버는 refresh 의 onRefresh 단계에서 만들어지고(커넥터는 아직 포트를 열지 않는다), 포트는 phase {@code
 * Integer.MAX_VALUE - 2048} 의 WebServerStartStopLifecycle 이 연다. 이 검사는 가장 낮은 phase 의 {@link
 * SmartLifecycle} 이라 그보다 먼저 돈다. 예외를 던지면 refresh 가 실패하고 컨텍스트가 닫히므로 커넥터는 시작되지 않는다.
 */
public final class TomcatRemoteAddressGuard implements SmartLifecycle {

  /** 원격 주소를 바꾸지 않는 것으로 확인한 Tomcat valve (정확한 클래스 이름, 하위 클래스는 허용하지 않는다). */
  static final Set<String> ALLOWED_VALVES =
      Set.of(
          "org.apache.catalina.core.StandardEngineValve",
          "org.apache.catalina.core.StandardHostValve",
          "org.apache.catalina.core.StandardContextValve",
          "org.apache.catalina.core.StandardWrapperValve",
          "org.apache.catalina.valves.ErrorReportValve",
          // 로그인 설정이 없는 context 에 Tomcat 이 넣는 인증 valve. 원격 주소를 바꾸지 않는다.
          "org.apache.catalina.authenticator.NonLoginAuthenticator",
          // server.tomcat.accesslog.* 가 넣는 접근 로그 valve. 요청을 읽기만 한다.
          "org.apache.catalina.valves.AccessLogValve",
          "org.apache.catalina.valves.ExtendedAccessLogValve",
          "org.apache.catalina.valves.JsonAccessLogValve");

  private final ApplicationContext applicationContext;
  private final boolean allowRemote;
  private volatile boolean running;

  TomcatRemoteAddressGuard(ApplicationContext applicationContext, boolean allowRemote) {
    this.applicationContext = applicationContext;
    this.allowRemote = allowRemote;
  }

  @Override
  public void start() {
    if (!allowRemote) {
      verify(webServer(applicationContext));
    }
    running = true;
  }

  /**
   * allow-remote=false 인 웹 서버를 검사한다. 웹 서버가 없으면(소켓을 열지 않는 컨텍스트) 할 일이 없다. Tomcat 이 아닌 웹 서버는 검사할 수
   * 없으므로 거부한다.
   *
   * @throws IllegalStateException 허용 목록 밖의 valve 가 있거나 Tomcat 이 아닌 웹 서버일 때. 메시지는 valve 클래스와 컨테이너
   *     이름만 말한다.
   */
  static void verify(@Nullable WebServer webServer) {
    if (webServer == null) {
      return;
    }
    if (!(webServer instanceof TomcatWebServer tomcatWebServer)) {
      throw new IllegalStateException(
          AdminSecurityProperties.ALLOW_REMOTE
              + "=false 인데 내장 웹 서버가 Tomcat 이 아니라 원격 주소를 바꾸는 구성 요소가 없는지 확인할 수 없어 런타임을 시작하지 않는다: "
              + webServer.getClass().getName());
    }
    List<String> disallowed = disallowedValves(tomcatWebServer.getTomcat());
    if (!disallowed.isEmpty()) {
      throw new IllegalStateException(
          AdminSecurityProperties.ALLOW_REMOTE
              + "=false 인데 Tomcat 파이프라인에 원격 주소를 바꿀 수 있거나 확인되지 않은 valve 가 있어 런타임을 시작하지 않는다"
              + " (루프백 검사가 X-Forwarded-For 값에 속을 수 있다): "
              + String.join(", ", disallowed)
              + ". server.tomcat.remoteip.* 와 "
              + AdminAccessConfiguration.FORWARD_HEADERS_STRATEGY
              + " 를 확인하라.");
    }
  }

  /** Tomcat 의 모든 서비스에서 허용 목록 밖의 valve 를 "컨테이너: valve 클래스" 꼴로 찾는다. */
  public static List<String> disallowedValves(Tomcat tomcat) {
    List<String> found = new ArrayList<>();
    for (Service service : tomcat.getServer().findServices()) {
      collect(service.getContainer(), found);
    }
    return found;
  }

  /** 컨테이너와 그 자식 전체에서 허용 목록 밖의 valve 를 찾는다. */
  public static List<String> disallowedValves(Container root) {
    List<String> found = new ArrayList<>();
    collect(root, found);
    return found;
  }

  private static void collect(@Nullable Container container, List<String> found) {
    if (container == null) {
      return;
    }
    for (Valve valve : container.getPipeline().getValves()) {
      String type = valve.getClass().getName();
      if (!ALLOWED_VALVES.contains(type)) {
        found.add(container.getClass().getSimpleName() + "[" + container.getName() + "]: " + type);
      }
    }
    for (Container child : container.findChildren()) {
      collect(child, found);
    }
  }

  private static @Nullable WebServer webServer(ApplicationContext context) {
    return context instanceof WebServerApplicationContext webContext
        ? webContext.getWebServer()
        : null;
  }

  @Override
  public void stop() {
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** WebServerStartStopLifecycle(포트를 연다)보다 먼저 돈다. */
  @Override
  public int getPhase() {
    return Integer.MIN_VALUE;
  }
}
