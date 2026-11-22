package com.queryhub.adapter.web.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 관리 SecurityFilterChain 이 맡는 요청.
 *
 * <ul>
 *   <li>소문자로 바꾼 경로가 {@code /admin} 이거나 {@code /admin/} 으로 시작하는 요청 (관리 API 전체, 없는 관리 경로 포함).
 *   <li>소문자로 바꾼 경로가 {@code /actuator} 이거나 {@code /actuator/} 로 시작하는 요청. 단, 정확히 {@link
 *       #OPEN_HEALTH_PATHS} 중 하나인 경로는 빼고 인증 없이 연다.
 * </ul>
 *
 * <p>경로는 두 가지로 본다: 디코딩하고 정규화한 서블릿 경로(Spring MVC 가 핸들러를 찾는 경로)와 디코딩하지 않은 요청 URI. 어느 한 쪽이라도 보호 대상이면
 * 체인이 맡는다. 그래서 {@code /admin/api/v1/%72untime} 처럼 인코딩한 변형도 토큰 없이는 지나가지 못한다. 경로 정규화와 위험한 문자({@code
 * ;}, {@code %2F}, {@code //}, {@code ..} 등)는 이 matcher 앞의 StrictHttpFirewall 이 400 으로 거부한다.
 */
public final class AdminRequestMatcher implements RequestMatcher {

  /** 인증 없이 여는 health 경로 (정확히 일치할 때만). */
  public static final Set<String> OPEN_HEALTH_PATHS =
      Set.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");

  private static final String ADMIN = "/admin";

  /**
   * 보호하는 actuator 경로 접두사. 고정 값이므로 런타임은 management.endpoints.web.base-path 가 이 값이 아니거나 actuator 가 다른
   * 포트(management.server.port)로 열리면 기동을 거부한다 (app 모듈의 AdminAccessConfiguration).
   */
  public static final String ACTUATOR = "/actuator";

  @Override
  public boolean matches(HttpServletRequest request) {
    return isProtected(decodedPath(request)) || isProtected(rawPath(request));
  }

  /** 경로 하나가 관리 체인의 보호 대상인지. null 은 보호 대상이 아니다(다른 쪽 경로가 판단한다). */
  public static boolean isProtected(@Nullable String path) {
    if (path == null) {
      return false;
    }
    String lower = path.toLowerCase(Locale.ROOT);
    if (isUnder(lower, ADMIN)) {
      return true;
    }
    return isUnder(lower, ACTUATOR) && !OPEN_HEALTH_PATHS.contains(path);
  }

  private static boolean isUnder(String lowerPath, String prefix) {
    return lowerPath.equals(prefix) || lowerPath.startsWith(prefix + "/");
  }

  private static String decodedPath(HttpServletRequest request) {
    String servletPath = request.getServletPath();
    String pathInfo = request.getPathInfo();
    return (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
  }

  private static @Nullable String rawPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null) {
      return null;
    }
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }

  @Override
  public String toString() {
    return "AdminRequestMatcher[/admin/**, /actuator/** except " + OPEN_HEALTH_PATHS + "]";
  }
}
