package com.queryhub.adapter.web.common.requestid;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 요청(actuator 포함)에 요청 ID 를 붙인다.
 *
 * <p>가장 먼저 실행된다(HIGHEST_PRECEDENCE). 체인을 호출하기 전에 X-Request-Id 응답 헤더를 넣고, 요청 속성과 로그 MDC 에 같은 값을
 * 저장한다. MDC 는 체인이 예외로 끝나도 finally 에서 지운다. 오류 디스패치(/error)는 이 필터를 다시 거치지 않으므로
 * QueryHubErrorController 가 요청 속성에서 값을 읽어 헤더를 다시 넣는다.
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter implements Ordered {

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = RequestIds.resolve(request.getHeader(RequestIds.HEADER));
    response.setHeader(RequestIds.HEADER, requestId);
    request.setAttribute(RequestIds.ATTRIBUTE, requestId);
    MDC.put(RequestIds.MDC_KEY, requestId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(RequestIds.MDC_KEY);
    }
  }
}
