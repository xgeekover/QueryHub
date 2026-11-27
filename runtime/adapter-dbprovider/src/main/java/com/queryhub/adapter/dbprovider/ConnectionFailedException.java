package com.queryhub.adapter.dbprovider;

import com.queryhub.domain.connection.ConnectionFailureKind;
import java.sql.SQLException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 정리된 접속 실패. 드라이버 예외 대신 던진다: 메시지는 분류의 고정 메시지이고, 원인(cause)과 덧붙은 예외가 없다. SQLState(형식이 맞을 때)와 오류 코드만
 * 옮긴다.
 *
 * <p>정리는 {@link java.sql.Driver#connect} 의 실패에만 해당한다 ({@code
 * DriverConnectDataSource.getConnection()}). 커넥션 풀(HikariCP)도 접속 실패로는 이 예외만 보므로, 접속 실패 때 풀이 남기는 로그와
 * 예외에는 드라이버 메시지가 없다. 그러나 Driver.connect 뒤에 HikariCP 7.0.2 가 그 Connection 에 부르는 모든 호출(연결 준비의
 * setReadOnly, getAutoCommit, getTransactionIsolation, getNetworkTimeout/setNetworkTimeout, isValid
 * 같은 것, 빌릴 때의 확인, 돌려받을 때의 상태 되돌리기, close)은 실패하면 드라이버 예외를 그대로 Hikari(PoolBase)에 주므로, 그 메시지가 Hikari 의
 * 로그(WARN/INFO/DEBUG)와 예외 원인에 남을 수 있다. 위 이름들은 예일 뿐이다: 이 경로도 정리하려면 돌려받은 Connection 전체(또는 Hikari 가
 * 부르는 모든 메서드)를 감싸야 하고, 그 일은 P1-12 로 미룬다 (BACKLOG P107-R3). P1-08 까지 업무 코드는 풀에서 연결을 꺼내지 않는다.
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
