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
 * </ul>
 *
 * <p>비밀번호는 언제나 속성으로 가므로 pgjdbc 는 .pgpass 를 읽지 않는다. {@code service}(pg_service.conf) 를 포함해 TLS
 * 모드({@code sslmode}) 밖의 옵션은 이름을 밝혀 거부된다.
 */
public final class PostgreSqlJdbcProvider implements JdbcProviderSpi {

  static final String URL_PREFIX = "jdbc:postgresql:";

  static final Set<String> ALLOWED_OPTIONS = Set.of("sslmode");

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
    return Collections.unmodifiableMap(properties);
  }

  @Override
  public Set<String> allowedOptions() {
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
