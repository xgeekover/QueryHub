package com.queryhub.adapter.dbprovider.provider.postgresql;

import com.queryhub.adapter.dbprovider.JdbcDatabaseProvider;
import com.queryhub.adapter.dbprovider.JdbcProviderSpi;
import com.queryhub.adapter.dbprovider.JdbcUrls;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.DatabaseType;
import java.sql.Driver;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL Provider (pgjdbc 42.7.14).
 *
 * <p>고정 속성 (pgjdbc 의 제한 시간 단위는 초):
 *
 * <ul>
 *   <li>{@code connectTimeout}, {@code socketTimeout}: 설정의 접속 제한 시간(올림한 초). 응답하지 않는 서버에서 드라이버의 읽기도
 *       이 시간에 끝난다. 업무 쿼리의 제한 시간은 쿼리 실행 작업(P1-12)이 다시 정한다.
 *   <li>{@code loginTimeout}: 접속 제한 시간 + {@link JdbcDatabaseProvider#DEADLINE_MARGIN} + 1 초. pgjdbc
 *       는 이 시간이 지나면 원인 없는 08001 을 던져서 연결 불가와 구별되지 않는다. 그래서 공통 제한 시간(접속 제한 시간 + 여유)이 먼저 TIMEOUT 으로
 *       답하고, 이 값은 pgjdbc 자신의 접속 스레드를 끝내는 마지막 경계로만 둔다.
 *   <li>{@code readOnlyMode=always}: 풀(readOnly=true)의 연결은 자동 커밋에서도 {@code SET SESSION
 *       CHARACTERISTICS AS TRANSACTION READ ONLY} 가 되어 쓰기가 거부된다 (pgjdbc 기본값 transaction 은 자동 커밋에서
 *       읽기 전용을 걸지 않는다).
 *   <li>{@code sslcert=""}, {@code sslkey=""} (P1-08a): 클라이언트 인증서와 키 파일을 쓰지 않는다. 이 값이 없으면 pgjdbc 의
 *       {@code LibPQFactory} 는 disable 밖의 모든 sslmode 에서 실행 계정의 {@code
 *       ~/.postgresql/postgresql.crt}, {@code postgresql.pk8} (Windows 는 {@code
 *       %APPDATA%\postgresql}) 로 키 관리자를 만들어, 인증서를 요청하는 서버에 그 인증서를 보낸다. 빈 문자열이면 파일 없는 키 관리자가 되어 어떤
 *       sslmode 에서도 그 파일을 읽지 않는다 (옵션 이름 sslcert, sslkey 는 고정 속성이라 대소문자와 상관없이 거부된다). {@code
 *       sslrootcert} 는 바꾸지 않는다: 빈 값이면 verify-ca, verify-full 이 그 경로를 열다가 실패한다. 그래서 두 모드는 여전히 {@code
 *       ~/.postgresql/root.crt} 를 읽는다 (BACKLOG P107-6).
 * </ul>
 *
 * <p>비밀번호는 언제나 속성으로 가므로 pgjdbc 는 .pgpass 를 읽지 않는다. {@code service}(pg_service.conf) 를 포함해 TLS
 * 모드({@code sslmode}) 밖의 옵션은 이름을 밝혀 거부된다. {@code sslmode} 의 값은 {@link #SSL_MODES} 중 하나와 정확히 같아야 한다
 * (pgjdbc 는 대소문자를 무시하지만 여기서는 구분한다).
 */
public final class PostgreSqlJdbcProvider implements JdbcProviderSpi {

  static final String URL_PREFIX = "jdbc:postgresql:";

  /** pgjdbc 42.7.14 SslMode 의 값 (소문자 그대로). */
  static final Set<String> SSL_MODES =
      Set.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full");

  static final Map<String, Set<String>> ALLOWED_OPTIONS = Map.of("sslmode", SSL_MODES);

  /** loginTimeout 이 공통 제한 시간보다 늦게 오도록 더하는 시간 (여유 위에 1 초). */
  static final Duration LOGIN_TIMEOUT_MARGIN =
      JdbcDatabaseProvider.DEADLINE_MARGIN.plus(Duration.ofSeconds(1));

  private final Driver driver = new org.postgresql.Driver();

  @Override
  public DatabaseType type() {
    return DatabaseType.POSTGRESQL;
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
    long seconds = ceilSeconds(settings.connectTimeout());
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("connectTimeout", Long.toString(seconds));
    properties.put("socketTimeout", Long.toString(seconds));
    properties.put("loginTimeout", Long.toString(seconds + ceilSeconds(LOGIN_TIMEOUT_MARGIN)));
    properties.put("readOnlyMode", "always");
    properties.put("sslcert", "");
    properties.put("sslkey", "");
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

  private static long ceilSeconds(Duration duration) {
    return Math.max(1, (duration.toMillis() + 999) / 1000);
  }
}
