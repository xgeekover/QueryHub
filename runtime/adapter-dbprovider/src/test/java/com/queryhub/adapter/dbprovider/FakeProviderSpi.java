package com.queryhub.adapter.dbprovider;

import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.DatabaseType;
import java.sql.Driver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 단위 테스트용 Provider SPI: FakeDriver, 고정 속성 두 개, 허용 옵션 하나(sslMode, 값 {@link #SSL_MODES}). 다른 허용 목록은 세
 * 인자 생성자로 준다 (옵션 이름 → 허용 값).
 */
final class FakeProviderSpi implements JdbcProviderSpi {

  static final Set<String> SSL_MODES = Set.of("disable", "require", "verify-full");

  final FakeDriver driver;
  private final DatabaseType type;
  private final Map<String, Set<String>> allowed;

  FakeProviderSpi(FakeDriver driver) {
    this(driver, DatabaseType.POSTGRESQL, Map.of("sslMode", SSL_MODES));
  }

  FakeProviderSpi(FakeDriver driver, DatabaseType type, Map<String, Set<String>> allowed) {
    this.driver = driver;
    this.type = type;
    this.allowed = allowed;
  }

  @Override
  public DatabaseType type() {
    return type;
  }

  @Override
  public Driver driver() {
    return driver;
  }

  @Override
  public String jdbcUrl(ConnectionSettings settings) {
    return JdbcUrls.hostPortDatabase(FakeDriver.URL_PREFIX, settings);
  }

  @Override
  public Map<String, String> fixedProperties(ConnectionSettings settings) {
    Map<String, String> fixed = new LinkedHashMap<>();
    fixed.put("connectTimeout", String.valueOf(settings.connectTimeout().toMillis()));
    fixed.put("allowLocalInfile", "false");
    return fixed;
  }

  @Override
  public Map<String, Set<String>> allowedOptions() {
    return allowed;
  }

  @Override
  public String validationQuery() {
    return "SELECT 1";
  }
}
