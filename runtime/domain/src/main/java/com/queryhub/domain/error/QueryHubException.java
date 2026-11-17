package com.queryhub.domain.error;

import java.util.Objects;

/**
 * QueryHub 예외 계층의 뿌리.
 *
 * <p>{@link ErrorCode} 와 클라이언트에 보여도 안전한 메시지를 가진다. 원인 예외(cause)는 서버 로그에만 남기고 응답에는 절대 넣지 않는다. 그래서
 * {@link #getMessage()} 도 클라이언트 메시지만 돌려주고 원인의 메시지를 덧붙이지 않는다. 이후 Phase 에서 이 클래스를 상속해 구체적인 예외를 만든다.
 */
public class QueryHubException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ErrorCode errorCode;
  private final String clientMessage;

  /** 코드의 기본 메시지를 클라이언트 메시지로 쓴다. */
  public QueryHubException(ErrorCode errorCode) {
    this(errorCode, null, null);
  }

  /**
   * @param clientMessage 클라이언트에 그대로 보일 메시지. DB 메시지, SQL, 내부 식별자를 넣지 않는다. null 이나 공백이면 코드의 기본 메시지를
   *     쓴다.
   */
  public QueryHubException(ErrorCode errorCode, String clientMessage) {
    this(errorCode, clientMessage, null);
  }

  /**
   * @param cause 로그용 원인 예외. 응답에는 쓰이지 않는다.
   */
  public QueryHubException(ErrorCode errorCode, String clientMessage, Throwable cause) {
    super(resolveMessage(errorCode, clientMessage), cause);
    this.errorCode = errorCode;
    this.clientMessage = resolveMessage(errorCode, clientMessage);
  }

  private static String resolveMessage(ErrorCode errorCode, String clientMessage) {
    Objects.requireNonNull(errorCode, "errorCode");
    if (clientMessage == null || clientMessage.isBlank()) {
      return errorCode.defaultMessage();
    }
    return clientMessage;
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  /** 응답 본문에 넣어도 되는 메시지. */
  public String clientMessage() {
    return clientMessage;
  }
}
