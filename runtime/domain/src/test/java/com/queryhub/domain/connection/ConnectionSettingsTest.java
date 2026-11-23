package com.queryhub.domain.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** AC11: 호스트, 포트, 데이터베이스 이름 규칙. 드라이버를 부르기 전에 이 생성자에서 거부된다. */
class ConnectionSettingsTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  static ConnectionSettings settings(String host, int port, String databaseName) {
    return new ConnectionSettings(
        DatabaseType.POSTGRESQL, host, port, databaseName, "app_user", TIMEOUT, 5, Map.of());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "db.example.com",
        "localhost",
        "my_db",
        "db-1.internal",
        "a",
        "127.0.0.1",
        "10.0.0.255",
        "0.0.0.0",
        "::1",
        "2001:db8::1",
        "fe80::1:2:3:4",
        "2001:0db8:85a3:0000:0000:8a2e:0370:7334",
        "::",
        "::ffff:192.0.2.1",
        "64:ff9b::192.0.2.33",
        "1:2:3:4:5:6:7::",
        "1::8"
      })
  void acceptsHostNamesIpv4AndIpv6Literals(String host) {
    assertThat(settings(host, 5432, "app").host()).isEqualTo(host);
  }

  @Test
  void bracketedIpv6IsStoredWithoutBrackets() {
    ConnectionSettings settings = settings("[2001:db8::1]", 5432, "app");
    assertThat(settings.host()).isEqualTo("2001:db8::1");
    assertThat(settings.hostIsIpv6Literal()).isTrue();
    assertThat(settings("db.example.com", 5432, "app").hostIsIpv6Literal()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "db/evil",
        "db?x=1",
        "db#frag",
        "user@db",
        "db;x=1",
        "db&x=1",
        "db=1",
        "db\\evil",
        "db evil",
        " db",
        "db\t",
        "db\nx",
        "db\u0000",
        "db\u007f",
        "db:5432",
        "db%2f",
        "db'x",
        "db\"x",
        "[::1",
        "::1]",
        "[]",
        "[[::1]]",
        "[db.example.com]",
        "db[::1]",
        "[::1]:5432",
        "[fe80::1%en0]",
        "fe80::1%en0",
        ".db",
        "db.",
        "db..example",
        "-db",
        "db-",
        "999.1.1.1",
        "1.2.3",
        "01.2.3.4",
        "12345",
        "1:2:3:4:5:6:7:8:9",
        "1::2::3",
        ":::1",
        "12345::1",
        "g::1"
      })
  void refusesHostsWithUrlStructuralOrInvalidCharacters(String host) {
    assertThatThrownBy(() -> settings(host, 5432, "app"))
        .isInstanceOfSatisfying(
            InvalidConnectionSettingsException.class,
            failure -> {
              assertThat(failure.field()).isEqualTo("host");
              // 메시지에 입력 값을 넣지 않는다.
              if (host.length() > 3) {
                assertThat(failure.getMessage()).doesNotContain(host);
              }
            });
  }

  @Test
  void refusesNullAndOverlongHosts() {
    assertThatThrownBy(() -> settings(null, 5432, "app"))
        .isInstanceOf(InvalidConnectionSettingsException.class);
    String label = "a".repeat(63);
    String host253 = String.join(".", label, label, label, "a".repeat(61));
    assertThat(host253).hasSize(253);
    assertThat(settings(host253, 5432, "app").host()).isEqualTo(host253);
    assertThatThrownBy(() -> settings(host253 + "a", 5432, "app"))
        .isInstanceOf(InvalidConnectionSettingsException.class);
    assertThatThrownBy(() -> settings("a".repeat(64) + ".example", 5432, "app"))
        .as("64 자 레이블")
        .isInstanceOf(InvalidConnectionSettingsException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 65536, Integer.MAX_VALUE, Integer.MIN_VALUE})
  void refusesPortsOutside1To65535(int port) {
    assertThatThrownBy(() -> settings("db.example.com", port, "app"))
        .isInstanceOfSatisfying(
            InvalidConnectionSettingsException.class,
            failure -> assertThat(failure.field()).isEqualTo("port"));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3306, 5432, 65535})
  void acceptsPortsInside1To65535(int port) {
    assertThat(settings("db.example.com", port, "app").port()).isEqualTo(port);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "app/other",
        "app?x=1",
        "app#x",
        "a@b",
        "app;x=1",
        "app&x",
        "app=1",
        "app\\x",
        "app x",
        "app\n",
        "app:1",
        "app%2f",
        "app'x",
        "[app]"
      })
  void refusesDatabaseNamesWithUrlStructuralCharacters(String databaseName) {
    assertThatThrownBy(() -> settings("db.example.com", 5432, databaseName))
        .isInstanceOfSatisfying(
            InvalidConnectionSettingsException.class,
            failure -> assertThat(failure.field()).isEqualTo("databaseName"));
  }

  @Test
  void acceptsOrdinaryDatabaseNames() {
    assertThat(settings("db", 5432, "app_db-1.v2$x").databaseName()).isEqualTo("app_db-1.v2$x");
    assertThat(settings("db", 5432, "ORCLPDB1.localdomain").databaseName())
        .isEqualTo("ORCLPDB1.localdomain");
    assertThatThrownBy(() -> settings("db", 5432, "a".repeat(129)))
        .isInstanceOf(InvalidConnectionSettingsException.class);
  }

  @Test
  void refusesInvalidUsernamesTimeoutsPoolSizesAndTypes() {
    assertField(
        () -> new ConnectionSettings(DatabaseType.MARIADB, "db", 1, "a", "", TIMEOUT, 1, null),
        "username");
    assertField(
        () ->
            new ConnectionSettings(DatabaseType.MARIADB, "db", 1, "a", "u\u0000", TIMEOUT, 1, null),
        "username");
    assertField(
        () -> new ConnectionSettings(DatabaseType.MARIADB, "db", 1, "a", " u", TIMEOUT, 1, null),
        "username");
    assertField(
        () ->
            new ConnectionSettings(
                DatabaseType.MARIADB, "db", 1, "a", "u", Duration.ofMillis(999), 1, null),
        "connectTimeout");
    assertField(
        () ->
            new ConnectionSettings(
                DatabaseType.MARIADB, "db", 1, "a", "u", Duration.ofSeconds(121), 1, null),
        "connectTimeout");
    assertField(
        () -> new ConnectionSettings(DatabaseType.MARIADB, "db", 1, "a", "u", null, 1, null),
        "connectTimeout");
    assertField(
        () -> new ConnectionSettings(DatabaseType.MARIADB, "db", 1, "a", "u", TIMEOUT, 0, null),
        "maxPoolSize");
    assertField(
        () -> new ConnectionSettings(DatabaseType.MARIADB, "db", 1, "a", "u", TIMEOUT, 21, null),
        "maxPoolSize");
    assertField(() -> new ConnectionSettings(null, "db", 1, "a", "u", TIMEOUT, 1, null), "type");
    ConnectionSettings bounds =
        new ConnectionSettings(
            DatabaseType.MARIADB, "db", 1, "a", "u", Duration.ofSeconds(120), 20, null);
    assertThat(bounds.maxPoolSize()).isEqualTo(20);
    assertThat(bounds.options()).isEmpty();
  }

  @Test
  void optionNamesAreCheckedAndValuesNeverShowInToStringOrMessages() {
    Map<String, String> options = new HashMap<>();
    options.put("sslmode", "verify-full");
    options.put("ssl.secret", "s3cr3t-value");
    ConnectionSettings settings =
        new ConnectionSettings(
            DatabaseType.POSTGRESQL, "db", 5432, "app", "u", TIMEOUT, 2, options);
    options.put("later", "x");
    assertThat(settings.options()).containsOnlyKeys("sslmode", "ssl.secret");
    assertThatThrownBy(() -> settings.options().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(settings.toString())
        .contains("sslmode", "ssl.secret")
        .doesNotContain("verify-full")
        .doesNotContain("s3cr3t-value");

    assertField(() -> withOption("1bad", "x"), "options");
    assertField(() -> withOption("bad name", "x"), "options");
    assertField(() -> withOption("bad;name", "x"), "options");
    assertThatThrownBy(() -> withOption("sslmode", "s3cr3t\u0000"))
        .isInstanceOf(InvalidConnectionSettingsException.class)
        .hasMessageNotContaining("s3cr3t");
    assertField(() -> withOption("sslmode", null), "options");
  }

  private static ConnectionSettings withOption(String name, String value) {
    Map<String, String> options = new HashMap<>();
    options.put(name, value);
    return new ConnectionSettings(
        DatabaseType.POSTGRESQL, "db", 5432, "app", "u", TIMEOUT, 2, options);
  }

  private static void assertField(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            InvalidConnectionSettingsException.class,
            failure -> assertThat(failure.field()).isEqualTo(field));
  }
}
