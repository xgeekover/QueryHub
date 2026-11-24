package com.queryhub.adapter.dbprovider.provider.mariadb;

import com.queryhub.adapter.dbprovider.JdbcProviderSpi;
import com.queryhub.adapter.dbprovider.JdbcUrls;
import com.queryhub.adapter.dbprovider.StandardFailureClassifier;
import com.queryhub.domain.connection.ConnectionFailureKind;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.DatabaseType;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * MariaDB Provider (MariaDB Connector/J 3.5.11).
 *
 * <p>고정 속성:
 *
 * <ul>
 *   <li>{@code connectTimeout}, {@code socketTimeout}: 설정의 접속 제한 시간(밀리초). 응답하지 않는 서버에서 드라이버의 읽기도 이
 *       시간에 끝나서 접속 테스트의 작업 스레드가 남지 않는다. 업무 쿼리의 제한 시간은 쿼리 실행 작업(P1-12)이 다시 정한다.
 *   <li>{@code allowLocalInfile=false}: 서버가 LOAD DATA LOCAL INFILE 로 클라이언트 파일을 읽지 못한다
 *       (GHSA-fhx5-2xqw-2cgm).
 *   <li>{@code readOnlyPropagatesToServer=true}: Connector/J 3 은 이 값이 없으면 {@code setReadOnly(true)}
 *       를 서버에 보내지 않는다. 풀(readOnly=true)의 연결은 이 값으로 {@code SET SESSION TRANSACTION READ ONLY} 가 되어
 *       쓰기가 거부된다.
 *   <li>{@code permitRedirect=false}: 서버가 다른 호스트로 연결을 옮기지 못한다 (검사된 호스트에만 접속한다).
 * </ul>
 *
 * <p>옵션 허용 목록은 TLS 모드({@code sslMode}) 하나다. 나머지(allowLocalInfile, sessionVariables, credentialType,
 * socketFactory 등)는 이름을 밝혀 거부된다. {@code sslMode} 의 값은 {@link #SSL_MODES} 중 하나와 정확히 같아야 한다.
 * Connector/J 3.5.11 이 함께 받는 {@code allow} 와 별칭(DISABLED, 0, false, REQUIRED, VERIFY_CA,
 * VERIFY_IDENTITY, 1, true), 대소문자 변형은 일부러 받지 않는다 (별칭은 드라이버 버전마다 다르다).
 */
public final class MariaDbJdbcProvider implements JdbcProviderSpi {

  static final String URL_PREFIX = "jdbc:mariadb:";

  /** ER_BAD_DB_ERROR: 모르는 데이터베이스 (SQLState 42000). 전역 권한이 있는 사용자가 받는다. */
  static final int ER_BAD_DB_ERROR = 1049;

  /**
   * ER_DBACCESS_DENIED_ERROR: 이 데이터베이스에 권한이 없다 (SQLState 42000). 권한이 없는 사용자는 없는 데이터베이스에도 이 코드를 받는다
   * (서버가 있는지 없는지 밝히지 않는다).
   */
  static final int ER_DBACCESS_DENIED_ERROR = 1044;

  /** Connector/J 3.5.11 SslMode 의 기본 이름 (allow 제외). */
  static final Set<String> SSL_MODES = Set.of("disable", "trust", "verify-ca", "verify-full");

  static final Map<String, Set<String>> ALLOWED_OPTIONS = Map.of("sslMode", SSL_MODES);

  private static final int MAX_CAUSE_DEPTH = 16;

  private final Driver driver = new org.mariadb.jdbc.Driver();

  @Override
  public DatabaseType type() {
    return DatabaseType.MARIADB;
  }

  @Override
  public Driver driver() {
    return driver;
  }

  @Override
  public String jdbcUrl(ConnectionSettings settings) {
    return JdbcUrls.hostPortDatabase(URL_PREFIX, settings);
  }

  @Override
  public Map<String, String> fixedProperties(ConnectionSettings settings) {
    String timeoutMillis = Long.toString(settings.connectTimeout().toMillis());
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("connectTimeout", timeoutMillis);
    properties.put("socketTimeout", timeoutMillis);
    properties.put("allowLocalInfile", "false");
    properties.put("readOnlyPropagatesToServer", "true");
    properties.put("permitRedirect", "false");
    return Collections.unmodifiableMap(properties);
  }

  @Override
  public Map<String, Set<String>> allowedOptions() {
    return ALLOWED_OPTIONS;
  }

  @Override
  public String validationQuery() {
    return "SELECT 1";
  }

  /**
   * MariaDB 는 모르는 데이터베이스를 SQLState 42000(구문 오류 분류)으로 알린다: 권한 있는 사용자에게는 1049, 권한 없는 사용자에게는 1044.
   * 1044 는 있는 데이터베이스에 권한이 없을 때도 오지만 서버가 둘을 구별해 주지 않으므로 둘 다 DATABASE_NOT_FOUND(데이터베이스가 없거나 권한이 없다는
   * 고정 메시지)로 둔다. 나머지는 표준 분류다.
   */
  @Override
  public ConnectionFailureKind classify(SQLException failure) {
    if (hasErrorCode(failure, ER_BAD_DB_ERROR) || hasErrorCode(failure, ER_DBACCESS_DENIED_ERROR)) {
      return ConnectionFailureKind.DATABASE_NOT_FOUND;
    }
    return StandardFailureClassifier.classify(failure);
  }

  /** 예외나 원인 사슬(최대 16 단계, 순환 방지)의 SQLException 이 이 오류 코드를 가진다. */
  private static boolean hasErrorCode(SQLException failure, int errorCode) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
      if (current instanceof SQLException sql && sql.getErrorCode() == errorCode) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
