package com.queryhub.domain.connection;

/**
 * 접속 실패의 분류와 사용자에게 보일 고정 한국어 메시지.
 *
 * <p>드라이버나 서버의 메시지는 비밀번호, 내부 호스트 이름, SQL 을 담을 수 있어서 어떤 메시지에도 옮기지 않는다. 분류는 SQLState, 오류 코드, 예외 종류로만
 * 정한다 (adapter-dbprovider).
 */
public enum ConnectionFailureKind {
  AUTHENTICATION_FAILED("사용자 이름 또는 비밀번호가 올바르지 않습니다."),
  DATABASE_NOT_FOUND("데이터베이스를 찾을 수 없거나 이 사용자에게 접근 권한이 없습니다. 데이터베이스 이름과 사용자 권한을 확인하세요."),
  UNREACHABLE("DB 서버에 연결할 수 없습니다. 호스트와 포트를 확인하세요."),
  TIMEOUT("DB 서버가 제한 시간 안에 응답하지 않았습니다."),
  TLS_FAILED("TLS(SSL) 연결을 맺지 못했습니다. TLS 옵션과 서버 인증서를 확인하세요."),
  OPTION_REJECTED("허용되지 않은 접속 옵션입니다: "),
  UNSUPPORTED_DATABASE_TYPE("이 버전은 아직 이 DB 종류에 접속하지 못합니다."),
  VALIDATION_FAILED("연결은 되었지만 확인 쿼리가 실패했습니다."),
  CONNECTION_FAILED("DB 에 연결하지 못했습니다.");

  private final String message;

  ConnectionFailureKind(String message) {
    this.message = message;
  }

  /**
   * 사용자에게 보여도 되는 고정 메시지.
   *
   * @param rejectedOption {@link #OPTION_REJECTED} 일 때 거부된 옵션 이름(형식 검사를 통과한 이름). 다른 분류에서는 무시한다.
   */
  public String message(String rejectedOption) {
    if (this == OPTION_REJECTED && rejectedOption != null) {
      return message + rejectedOption;
    }
    return this == OPTION_REJECTED ? "허용되지 않은 접속 옵션입니다." : message;
  }
}
