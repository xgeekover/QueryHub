package com.queryhub.adapter.dbprovider;

import com.queryhub.domain.connection.ConnectionFailureKind;
import java.sql.SQLException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 정리된 접속 실패. 드라이버 예외 대신 던진다: 메시지는 분류의 고정 메시지이고, 원인(cause)과 덧붙은 예외가 없다. SQLState(형식이 맞을 때)와 오류 코드만
 * 옮긴다.
 *
 * <p>커넥션 풀(HikariCP)도 이 예외만 보므로 풀이 남기는 로그와 예외에도 드라이버 메시지가 없다.
 */
public final class ConnectionFailedException extends SQLException {

  private static final long serialVersionUID = 1L;

  private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Z]{5}");

  private final ConnectionFailureKind kind;

  ConnectionFailedException(ConnectionFailureKind kind, String sqlState, int vendorCode) {
    super(Objects.requireNonNull(kind, "kind").message(null), safeSqlState(sqlState), vendorCode);
    this.kind = kind;
  }

  public ConnectionFailureKind kind() {
    return kind;
  }

  /** 다섯 자 영숫자가 아니면 null (SQLState 자리에 다른 글이 들어오는 것을 막는다). */
  static String safeSqlState(String sqlState) {
    return sqlState != null && SQL_STATE.matcher(sqlState).matches() ? sqlState : null;
  }
}
