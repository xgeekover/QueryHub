package com.queryhub.adapter.web.admin.security;

import com.queryhub.adapter.web.common.requestid.RequestIds;
import com.queryhub.adapter.web.common.response.ErrorEnvelope;
import com.queryhub.domain.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리 체인이 거부한 요청에 공통 오류 형식({@link ErrorEnvelope})을 쓴다.
 *
 * <ul>
 *   <li>401 UNAUTHORIZED: 토큰이 없거나 틀렸다. {@code WWW-Authenticate: Bearer} 를 붙인다. 없는 토큰과 틀린 토큰의 응답은
 *       requestId 말고는 같다.
 *   <li>403 FORBIDDEN: 네트워크 가드(루프백, Host)나 권한 검사가 거부했다.
 * </ul>
 *
 * <p>본문은 애플리케이션의 Jackson mapper 로 직접 쓴다(sendError 를 쓰지 않으므로 /error 로 다시 디스패치되지 않는다). 메시지는 {@link
 * ErrorCode} 의 고정 메시지이고, 요청 값(토큰, Host 등)은 응답에도 로그에도 넣지 않는다.
 */
public final class SecurityErrorResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

  /** 401 응답의 WWW-Authenticate 값. 오류 종류(invalid_token 등)를 알려 주지 않는다. */
  public static final String BEARER_CHALLENGE = "Bearer";

  private final JsonMapper jsonMapper;

  public SecurityErrorResponses(JsonMapper jsonMapper) {
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    unauthorized(request, response);
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    forbidden(request, response);
  }

  /** 401 UNAUTHORIZED 와 {@code WWW-Authenticate: Bearer}. */
  public void unauthorized(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
    write(request, response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
  }

  /** 404 NOT_FOUND (토큰을 확인한 뒤, 제공하지 않는 actuator 하위 경로). */
  public void notFound(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    write(request, response, HttpServletResponse.SC_NOT_FOUND, ErrorCode.NOT_FOUND);
  }

  /** 403 FORBIDDEN. */
  public void forbidden(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    write(request, response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN);
  }

  private void write(
      HttpServletRequest request, HttpServletResponse response, int status, ErrorCode code)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    String requestId = RequestIds.currentOrGenerate(request);
    byte[] body =
        jsonMapper.writeValueAsBytes(ErrorEnvelope.of(code, code.defaultMessage(), requestId));
    response.setStatus(status);
    response.setHeader(RequestIds.HEADER, requestId);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentLength(body.length);
    response.getOutputStream().write(body);
    response.flushBuffer();
  }
}
