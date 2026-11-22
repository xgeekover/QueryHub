package com.queryhub.adapter.web.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.springframework.security.web.firewall.FirewalledRequest;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;

/**
 * StrictHttpFirewall 에 관리 경로 규칙 하나를 더한다: 관리 체인이 맡는 경로({@link AdminRequestMatcher})의 원본 URI 에는 퍼센트
 * 인코딩이 없어야 한다.
 *
 * <p>StrictHttpFirewall 은 {@code %2F}, {@code %25}, {@code %2E}, {@code ;}, {@code //}, 정규화되지 않은
 * 경로, 널 바이트를 모든 경로에서 거부하지만 {@code /admin/api/v1/%72untime} 같은 불필요한 인코딩은 통과시킨다. Spring MVC 는 그 경로를
 * 디코딩해 실제 핸들러에 연결하므로, 관리 API 는 정규 경로 하나로만 닿게 한다. 거부는 RequestRejectedException → 400 → /error →
 * INVALID_REQUEST 공통 오류 형식이 된다. 관리 API 경로는 ASCII 만 쓰므로 정상 요청에는 영향이 없다.
 */
public final class AdminPathFirewall implements HttpFirewall {

  private final HttpFirewall delegate;

  public AdminPathFirewall(HttpFirewall delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /** 기본 StrictHttpFirewall 에 관리 경로 규칙을 더한 방화벽. */
  public static AdminPathFirewall strict() {
    return new AdminPathFirewall(new StrictHttpFirewall());
  }

  @Override
  public FirewalledRequest getFirewalledRequest(HttpServletRequest request) {
    FirewalledRequest firewalled = delegate.getFirewalledRequest(request);
    String uri = request.getRequestURI();
    if (uri != null && uri.indexOf('%') >= 0 && new AdminRequestMatcher().matches(request)) {
      throw new RequestRejectedException("관리 경로에 퍼센트 인코딩이 있어 요청을 거부한다");
    }
    return firewalled;
  }

  @Override
  public HttpServletResponse getFirewalledResponse(HttpServletResponse response) {
    return delegate.getFirewalledResponse(response);
  }
}
