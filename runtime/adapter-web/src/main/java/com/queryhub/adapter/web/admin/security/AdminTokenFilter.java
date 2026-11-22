package com.queryhub.adapter.web.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 관리 체인의 Bearer 토큰 검사.
 *
 * <p>Authorization 헤더가 정확히 하나이고 {@code Bearer <token>} 형식(scheme 은 대소문자 무관, 공백 하나)이며 토큰이 {@link
 * AdminTokenVerifier} 와 맞을 때만 다음 필터로 넘긴다. 이때 SecurityContext 에 고정된 관리자 인증을 이 요청 동안만 넣는다(세션에 저장하지
 * 않는다). 헤더가 없거나 여러 개, 다른 scheme(Basic 등), 빈 토큰, 틀린 토큰은 모두 같은 401 이 된다. 쿼리 파라미터의 토큰은 읽지 않는다.
 */
public final class AdminTokenFilter extends OncePerRequestFilter {

  /** 인증된 관리자 요청의 주체 이름. 토큰 값과는 관계가 없다. */
  public static final String PRINCIPAL = "queryhub-admin";

  /** 관리자 권한. */
  public static final String AUTHORITY = "ROLE_ADMIN";

  private static final String SCHEME_PREFIX = "Bearer ";

  private static final String HEALTH_PREFIX = "/actuator/health/";

  private final AdminTokenVerifier verifier;
  private final SecurityErrorResponses errors;
  private final SecurityContextHolderStrategy contextHolder =
      SecurityContextHolder.getContextHolderStrategy();

  public AdminTokenFilter(AdminTokenVerifier verifier, SecurityErrorResponses errors) {
    this.verifier = Objects.requireNonNull(verifier, "verifier");
    this.errors = Objects.requireNonNull(errors, "errors");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!verifier.matches(bearerToken(request))) {
      errors.unauthorized(request, response);
      return;
    }
    SecurityContext context = contextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            PRINCIPAL, null, AuthorityUtils.createAuthorityList(AUTHORITY)));
    contextHolder.setContext(context);
    if (isUnservedHealthSubPath(request)) {
      // actuator 는 /actuator/health/{*path} 의 없는 구성 요소에 본문 없는 404 를 쓴다. 공통 오류 형식으로 답하고, 열린 세 경로 밖의
      // health 하위 경로(구성 요소, 그룹)는 토큰이 있어도 제공하지 않는다 (show-details=never 라 상태 말고는 보여 줄 것이 없다).
      errors.notFound(request, response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static boolean isUnservedHealthSubPath(HttpServletRequest request) {
    String servletPath = request.getServletPath();
    String path =
        (servletPath == null ? "" : servletPath)
            + (request.getPathInfo() == null ? "" : request.getPathInfo());
    return path.toLowerCase(java.util.Locale.ROOT).startsWith(HEALTH_PREFIX)
        && !AdminRequestMatcher.OPEN_HEALTH_PATHS.contains(path);
  }

  /** 정확히 하나의 {@code Bearer <token>} 헤더에서 토큰을 꺼낸다. 형식이 다르면 null. */
  static @Nullable String bearerToken(HttpServletRequest request) {
    Enumeration<String> headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
    List<String> values = headers == null ? List.of() : Collections.list(headers);
    if (values.size() != 1) {
      return null;
    }
    String value = values.get(0);
    if (value == null
        || value.length() <= SCHEME_PREFIX.length()
        || !value.regionMatches(true, 0, SCHEME_PREFIX, 0, SCHEME_PREFIX.length())) {
      return null;
    }
    return value.substring(SCHEME_PREFIX.length());
  }
}
