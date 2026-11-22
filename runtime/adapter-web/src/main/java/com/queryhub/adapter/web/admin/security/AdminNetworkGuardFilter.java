package com.queryhub.adapter.web.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 관리 체인의 네트워크 가드. CORS 와 토큰 검사보다 먼저 돌므로 preflight 에도 적용된다.
 *
 * <ol>
 *   <li>{@link AdminAccessPolicy#allowRemote()} 가 false 이면 소켓 원격 주소(request.getRemoteAddr())가 루프백
 *       IP 리터럴이어야 한다({@link LoopbackAddresses}). X-Forwarded-For 와 Forwarded 는 읽지 않는다.
 *   <li>{@link AdminAccessPolicy#hostCheck()} 가 true 이면 Host 가 허용 목록에 맞아야 한다({@link
 *       HostHeaderPolicy}).
 * </ol>
 *
 * <p>거부하면 403 FORBIDDEN 을 공통 오류 형식으로 쓴다. 로그에는 요청 값을 넣지 않는다.
 */
public final class AdminNetworkGuardFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(AdminNetworkGuardFilter.class);

  private final AdminAccessPolicy policy;
  private final SecurityErrorResponses errors;

  public AdminNetworkGuardFilter(AdminAccessPolicy policy, SecurityErrorResponses errors) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.errors = Objects.requireNonNull(errors, "errors");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!policy.allowRemote() && !LoopbackAddresses.isLoopback(request.getRemoteAddr())) {
      LOG.debug("관리 요청 거부: 루프백이 아닌 원격 주소");
      errors.forbidden(request, response);
      return;
    }
    if (policy.hostCheck() && !policy.hosts().allows(request)) {
      LOG.debug("관리 요청 거부: 허용 목록에 없는 Host");
      errors.forbidden(request, response);
      return;
    }
    filterChain.doFilter(request, response);
  }
}
