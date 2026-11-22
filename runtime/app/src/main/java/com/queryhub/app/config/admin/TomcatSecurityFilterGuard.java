package com.queryhub.app.config.admin;

import com.queryhub.adapter.web.common.requestid.RequestIdFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Service;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.filter.DelegatingFilterProxy;

/**
 * 만들어진 내장 Tomcat 의 필터 매핑에서 Spring Security 필터({@value #SECURITY_FILTER})가 모든 요청을 덮는지 확인한다 (두 프로파일
 * 모두). 속성 검사({@link AdminAccessConfiguration})의 이중 안전장치다: dispatcher 종류를 줄이거나, 필터 등록을 끄거나 다시 등록하거나,
 * 보안 필터 앞에 응답을 쓸 수 있는 필터를 두는 길을 속성 이름과 상관없이 잡는다.
 *
 * <ul>
 *   <li>{@value #SECURITY_FILTER} 매핑이 URL 패턴 {@code /*} 와 dispatcher REQUEST, FORWARD, INCLUDE,
 *       ERROR, ASYNC 를 모두 가져야 한다.
 *   <li>그 이름의 필터는 {@link DelegatingFilterProxy}(Boot 의 DelegatingFilterProxyRegistrationBean 이 만드는
 *       것)여야 한다. 같은 이름으로 다른 필터를 등록하는 길을 막는다 (P1-03 4회차 C3-R6).
 *   <li>그 매핑보다 앞의 URL 패턴 매핑은 {@link #ALLOWED_FILTERS_BEFORE} 의 필터(요청을 준비만 하고 답하지 않는다)만 될 수 있다. 서블릿
 *       이름 매핑은 Tomcat 이 URL 패턴 매핑 뒤에 붙이므로 보안 필터보다 늘 뒤다.
 * </ul>
 *
 * <p>실행 시점: {@link TomcatRemoteAddressGuard} 와 같다(가장 낮은 phase, 포트를 열기 전). 필터는 웹 서버를 만들 때 context 가
 * 시작되며 이미 등록된다. 예외를 던지면 refresh 가 실패하고 커넥터는 시작되지 않는다.
 */
public final class TomcatSecurityFilterGuard implements SmartLifecycle {

  /** Spring Security 필터의 등록 이름 (Boot 의 SecurityFilterAutoConfiguration). */
  static final String SECURITY_FILTER = "springSecurityFilterChain";

  /** 보안 필터에 요구하는 dispatcher 종류 (Boot 기본과 같다). */
  static final int REQUIRED_DISPATCH =
      FilterMap.REQUEST | FilterMap.FORWARD | FilterMap.INCLUDE | FilterMap.ERROR | FilterMap.ASYNC;

  /** 보안 필터보다 앞에 올 수 있는 필터 (정확한 클래스 이름). 인코딩, 요청 문맥, 관측, 요청 ID 를 다루고 응답 본문을 쓰지 않는다. */
  static final Set<String> ALLOWED_FILTERS_BEFORE =
      Set.of(
          "org.springframework.boot.servlet.filter.OrderedCharacterEncodingFilter",
          "org.springframework.boot.servlet.filter.OrderedFormContentFilter",
          "org.springframework.boot.servlet.filter.OrderedRequestContextFilter",
          "org.springframework.boot.servlet.filter.OrderedHiddenHttpMethodFilter",
          "org.springframework.web.filter.ServerHttpObservationFilter",
          RequestIdFilter.class.getName());

  /**
   * forward 헤더로 원격 주소와 Host 를 바꾸는 필터 (server.forward-headers-strategy=framework). allow-remote=true
   * 일 때만 보안 필터 앞에 둘 수 있다. allow-remote=false 이면 속성 검사가 먼저 거부하고, 다른 길로 등록되어도 이 검사가 거부한다(루프백 검사의 이중
   * 안전장치).
   */
  static final String FORWARDED_HEADER_FILTER =
      "org.springframework.web.filter.ForwardedHeaderFilter";

  private final ApplicationContext applicationContext;
  private final boolean allowRemote;
  private volatile boolean running;

  TomcatSecurityFilterGuard(ApplicationContext applicationContext, boolean allowRemote) {
    this.applicationContext = applicationContext;
    this.allowRemote = allowRemote;
  }

  @Override
  public void start() {
    verify(
        applicationContext instanceof WebServerApplicationContext webContext
            ? webContext.getWebServer()
            : null,
        allowRemote);
    running = true;
  }

  /**
   * 웹 서버의 모든 context 를 검사한다. 웹 서버가 없으면(소켓을 열지 않는 컨텍스트) 할 일이 없다. Tomcat 이 아닌 웹 서버는 검사할 수 없으므로 거부한다.
   *
   * @throws IllegalStateException 문제가 있을 때. 메시지는 필터 이름, 클래스, 패턴만 말한다.
   */
  static void verify(@Nullable WebServer webServer, boolean allowRemote) {
    if (webServer == null) {
      return;
    }
    if (!(webServer instanceof TomcatWebServer tomcatWebServer)) {
      throw new IllegalStateException(
          "내장 웹 서버가 Tomcat 이 아니라 관리 체인의 필터 매핑을 확인할 수 없어 런타임을 시작하지 않는다: "
              + webServer.getClass().getName());
    }
    List<String> problems = new ArrayList<>();
    for (Service service : tomcatWebServer.getTomcat().getServer().findServices()) {
      collect(service.getContainer(), allowRemote, problems);
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "관리 체인(Spring Security 필터 "
              + SECURITY_FILTER
              + ")이 모든 요청을 덮지 않아 관리 API 가 토큰 없이 열릴 수 있으므로 런타임을 시작하지 않는다: "
              + String.join("; ", problems)
              + ". "
              + AdminAccessConfiguration.SECURITY_FILTER_DISPATCHER_TYPES
              + " 와 spring.security.filter.order, 필터 등록을 확인하라.");
    }
  }

  private static void collect(
      @Nullable Container container, boolean allowRemote, List<String> problems) {
    if (container == null) {
      return;
    }
    if (container instanceof Context context) {
      problems.addAll(problems(context, allowRemote));
    }
    for (Container child : container.findChildren()) {
      collect(child, allowRemote, problems);
    }
  }

  /** context 하나의 문제 목록. 비어 있으면 통과다. */
  static List<String> problems(Context context, boolean allowRemote) {
    FilterMap[] maps = context.findFilterMaps();
    int security = -1;
    for (int i = 0; i < maps.length; i++) {
      if (SECURITY_FILTER.equals(maps[i].getFilterName())
          && Arrays.asList(maps[i].getURLPatterns()).contains("/*")) {
        security = i;
        break;
      }
    }
    List<String> problems = new ArrayList<>();
    String where = "context[" + context.getName() + "]";
    if (security < 0) {
      problems.add(where + ": " + SECURITY_FILTER + " 가 URL 패턴 /* 로 매핑되어 있지 않다");
      return problems;
    }
    FilterDef securityDef = context.findFilterDef(SECURITY_FILTER);
    if (securityDef == null || !(securityDef.getFilter() instanceof DelegatingFilterProxy)) {
      problems.add(
          where
              + ": "
              + SECURITY_FILTER
              + " 가 DelegatingFilterProxy 가 아니다 ("
              + filterClass(securityDef)
              + ")");
    }
    if ((maps[security].getDispatcherMapping() & REQUIRED_DISPATCH) != REQUIRED_DISPATCH) {
      problems.add(
          where
              + ": "
              + SECURITY_FILTER
              + " 의 dispatcher 가 "
              + String.join(",", maps[security].getDispatcherNames())
              + " 뿐이다 (REQUEST, FORWARD, INCLUDE, ERROR, ASYNC 가 모두 있어야 한다)");
    }
    for (int i = 0; i < security; i++) {
      if (maps[i].getURLPatterns().length == 0) {
        continue;
      }
      String type = filterClass(context.findFilterDef(maps[i].getFilterName()));
      if (!ALLOWED_FILTERS_BEFORE.contains(type)
          && !(allowRemote && FORWARDED_HEADER_FILTER.equals(type))) {
        problems.add(
            where
                + ": 확인되지 않은 필터 "
                + maps[i].getFilterName()
                + "("
                + type
                + ")가 "
                + SECURITY_FILTER
                + " 보다 앞에 있다");
      }
    }
    return problems;
  }

  private static String filterClass(@Nullable FilterDef def) {
    if (def == null) {
      return "?";
    }
    if (def.getFilter() != null) {
      return def.getFilter().getClass().getName();
    }
    return String.valueOf(def.getFilterClass());
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
