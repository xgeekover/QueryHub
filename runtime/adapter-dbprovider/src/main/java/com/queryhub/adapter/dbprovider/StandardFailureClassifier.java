package com.queryhub.adapter.dbprovider;

import com.queryhub.domain.connection.ConnectionFailureKind;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.net.ssl.SSLException;

/**
 * SQLState(SQL 표준 클래스), JDBC 예외 종류, 원인 예외 종류로 접속 실패를 분류한다. 예외 메시지는 읽지 않는다.
 *
 * <p>Provider 는 DB 별 오류 코드를 먼저 보고, 모르는 경우에 이 분류로 넘긴다.
 */
public final class StandardFailureClassifier {

  private static final int MAX_CAUSE_DEPTH = 16;

  private StandardFailureClassifier() {}

  public static ConnectionFailureKind classify(SQLException failure) {
    if (failure == null) {
      return ConnectionFailureKind.CONNECTION_FAILED;
    }
    if (hasCause(failure, SSLException.class)) {
      return ConnectionFailureKind.TLS_FAILED;
    }
    if (failure instanceof SQLTimeoutException || hasCause(failure, SocketTimeoutException.class)) {
      return ConnectionFailureKind.TIMEOUT;
    }
    String sqlState = failure.getSQLState();
    if (failure instanceof SQLInvalidAuthorizationSpecException || hasClass(sqlState, "28")) {
      return ConnectionFailureKind.AUTHENTICATION_FAILED;
    }
    if ("3D000".equals(sqlState)) {
      return ConnectionFailureKind.DATABASE_NOT_FOUND;
    }
    if (hasCause(failure, ConnectException.class)
        || hasCause(failure, UnknownHostException.class)
        || hasCause(failure, NoRouteToHostException.class)
        || hasClass(sqlState, "08")
        || failure instanceof SQLNonTransientConnectionException
        || failure instanceof SQLTransientConnectionException) {
      return ConnectionFailureKind.UNREACHABLE;
    }
    return ConnectionFailureKind.CONNECTION_FAILED;
  }

  /** 예외 자신이나 원인 사슬(최대 16 단계, 순환 방지)에 type 이 있다. */
  public static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
      if (type.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static boolean hasClass(String sqlState, String sqlStateClass) {
    return sqlState != null && sqlState.length() == 5 && sqlState.startsWith(sqlStateClass);
  }
}
