package com.queryhub.adapter.dbprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryhub.domain.connection.ConnectionFailureKind;
import com.queryhub.domain.connection.ConnectionTestResult;
import com.queryhub.domain.connection.DatabaseType;
import com.queryhub.domain.connection.InvalidConnectionSettingsException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** P1-08a A-AC3 (R2): 옵션 값의 허용 목록. 값은 정확히 같아야 하고(대소문자 구분), 값 집합이 없는 허용 옵션은 Provider 의 잘못이다. */
class ConnectionPropertiesTest {

  private static FakeProviderSpi spi(Map<String, Set<String>> allowed) {
    return new FakeProviderSpi(new FakeDriver(), DatabaseType.POSTGRESQL, allowed);
  }

  private static Properties build(JdbcProviderSpi spi, String option, String value) {
    return ConnectionProperties.build(
        spi, JdbcDatabaseProviderTest.settings(Map.of(option, value)));
  }

  @Test
  void everyAllowedValueReachesTheDriverUnchanged() {
    FakeProviderSpi both =
        spi(
            Map.of(
                "sslmode",
                JdbcDatabaseProviderTest.PG_SSL_MODES,
                "sslMode",
                JdbcDatabaseProviderTest.MARIADB_SSL_MODES));
    for (String value : JdbcDatabaseProviderTest.PG_SSL_MODES) {
      assertThat(build(both, "sslmode", value)).containsEntry("sslmode", value);
    }
    for (String value : JdbcDatabaseProviderTest.MARIADB_SSL_MODES) {
      assertThat(build(both, "sslMode", value)).containsEntry("sslMode", value);
    }
    // 옵션이 없으면 고정 속성만 간다.
    assertThat(ConnectionProperties.build(both, JdbcDatabaseProviderTest.settings(Map.of())))
        .containsOnlyKeys("connectTimeout", "allowLocalInfile");
  }

  @Test
  void valuesMatchExactlyByCaseAndRefusalNamesOnlyTheOption() {
    FakeProviderSpi pg = spi(Map.of("sslmode", JdbcDatabaseProviderTest.PG_SSL_MODES));
    for (String value : new String[] {"requir", "REQUIRE", "Prefer", " require", "require ", ""}) {
      assertThatThrownBy(() -> build(pg, "sslmode", value))
          .as("[%s]", value)
          .isInstanceOfSatisfying(
              InvalidConnectionSettingsException.class,
              refused -> {
                assertThat(refused.rejectedOption()).isEqualTo("sslmode");
                assertThat(refused.getMessage())
                    .isEqualTo(ConnectionFailureKind.OPTION_REJECTED.message("sslmode"));
              });
    }
  }

  @Test
  void theFirstRefusedOptionByNameIsReported() {
    FakeProviderSpi pg = spi(Map.of("sslmode", JdbcDatabaseProviderTest.PG_SSL_MODES));
    // 이름 순: aaa (목록에 없음) 가 sslmode (값이 틀림) 보다 먼저다.
    assertThatThrownBy(
            () ->
                ConnectionProperties.build(
                    pg, JdbcDatabaseProviderTest.settings(Map.of("sslmode", "bad", "aaa", "x"))))
        .isInstanceOfSatisfying(
            InvalidConnectionSettingsException.class,
            refused -> assertThat(refused.rejectedOption()).isEqualTo("aaa"));
    assertThatThrownBy(
            () ->
                ConnectionProperties.build(
                    pg, JdbcDatabaseProviderTest.settings(Map.of("sslmode", "bad", "zeta", "x"))))
        .isInstanceOfSatisfying(
            InvalidConnectionSettingsException.class,
            refused -> assertThat(refused.rejectedOption()).isEqualTo("sslmode"));
  }

  @Test
  void anAllowedOptionWithoutAValueSetIsAProviderBug() {
    Map<String, Set<String>> nullValues = new HashMap<>();
    nullValues.put("sslMode", null);
    for (Map<String, Set<String>> careless :
        List.<Map<String, Set<String>>>of(
            Map.of("sslMode", Set.of()),
            Map.of("sslMode", FakeProviderSpi.SSL_MODES, "tcpKeepAlive", Set.of()),
            nullValues)) {
      FakeProviderSpi spi = spi(careless);
      // 옵션을 주지 않아도, 다른 옵션만 주어도 실패한다: 잘못 만든 Provider 를 처음 쓰는 순간 드러낸다.
      assertThatThrownBy(
              () -> ConnectionProperties.build(spi, JdbcDatabaseProviderTest.settings(Map.of())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("허용 값이 없다");
      assertThatThrownBy(() -> build(spi, "sslMode", "require"))
          .isInstanceOf(IllegalStateException.class);
      // 접속 테스트도 드라이버를 부르기 전에 같은 예외로 멈춘다 (설정 거부 결과로 숨기지 않는다).
      assertThatThrownBy(
              () ->
                  new JdbcDatabaseProvider(spi)
                      .testConnection(
                          JdbcDatabaseProviderTest.settings(Map.of()),
                          JdbcDatabaseProviderTest.password()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(spi.driver.connects).hasValue(0);
    }
  }

  @Test
  void theDefaultFakeProviderRefusesAnUnknownTlsModeThroughTheTestConnection() {
    FakeProviderSpi spi = new FakeProviderSpi(new FakeDriver());
    ConnectionTestResult result =
        new JdbcDatabaseProvider(spi)
            .testConnection(
                JdbcDatabaseProviderTest.settings(Map.of("sslMode", "VERIFY_IDENTITY")),
                JdbcDatabaseProviderTest.password());
    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.OPTION_REJECTED);
    assertThat(result.rejectedOption()).isEqualTo("sslMode");
    assertThat(spi.driver.connects).hasValue(0);
  }
}
