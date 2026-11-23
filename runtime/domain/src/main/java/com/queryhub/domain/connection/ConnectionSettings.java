package com.queryhub.domain.connection;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 업무 DB 접속 설정. 생성자가 모든 필드를 검사하므로, 이 객체가 있으면 JDBC URL 을 필드로 안전하게 만들 수 있다.
 *
 * <p>비밀번호는 담지 않는다({@link ConnectionPassword} 로 따로 넘긴다). 그래서 이 레코드의 toString, equals 에는 비밀번호가 없다.
 * toString 은 옵션 값도 빼고 이름만 보인다.
 *
 * <p>검사 실패는 {@link InvalidConnectionSettingsException} 이고 메시지에 입력 값은 없다.
 *
 * @param type DB 종류
 * @param host 호스트 이름, 점 표기 IPv4, IPv6 리터럴. IPv6 는 괄호를 붙여 받아도 되고 괄호 없이 저장된다
 * @param port 1-65535
 * @param databaseName 데이터베이스(또는 서비스) 이름. 글자, 숫자, _ $ - . 만
 * @param username 사용자 이름. 1-128 자, 제어 문자와 앞뒤 공백 없음
 * @param connectTimeout 접속 제한 시간. {@link #MIN_CONNECT_TIMEOUT}-{@link #MAX_CONNECT_TIMEOUT}
 * @param maxPoolSize 풀의 최대 연결 수. 1-{@link #MAX_POOL_SIZE}
 * @param options 드라이버 옵션. 이름 형식만 여기서 검사하고, DB 별 허용 목록은 adapter-dbprovider 가 검사한다
 */
public record ConnectionSettings(
    DatabaseType type,
    String host,
    int port,
    String databaseName,
    String username,
    Duration connectTimeout,
    int maxPoolSize,
    Map<String, String> options) {

  public static final Duration MIN_CONNECT_TIMEOUT = Duration.ofSeconds(1);
  public static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(120);
  public static final int MAX_POOL_SIZE = 20;
  public static final int MAX_DATABASE_NAME_LENGTH = 128;
  public static final int MAX_USERNAME_LENGTH = 128;
  public static final int MAX_OPTIONS = 32;
  public static final int MAX_OPTION_VALUE_LENGTH = 512;

  public ConnectionSettings {
    if (type == null) {
      throw invalid("type", "DB 종류가 없습니다.");
    }
    host = checkHost(host);
    if (port < 1 || port > 65535) {
      throw invalid("port", "포트는 1-65535 이어야 합니다.");
    }
    if (databaseName == null
        || !ConnectionFieldRules.isDatabaseName(databaseName, MAX_DATABASE_NAME_LENGTH)) {
      throw invalid(
          "databaseName",
          "데이터베이스 이름은 1-" + MAX_DATABASE_NAME_LENGTH + " 자의 글자, 숫자, _ $ - . 이어야 합니다.");
    }
    if (username == null
        || username.isEmpty()
        || username.length() > MAX_USERNAME_LENGTH
        || !username.strip().equals(username)
        || !ConnectionFieldRules.hasNoControlCharacters(username)) {
      throw invalid("username", "사용자 이름은 1-" + MAX_USERNAME_LENGTH + " 자이고 제어 문자나 앞뒤 공백이 없어야 합니다.");
    }
    if (connectTimeout == null
        || connectTimeout.compareTo(MIN_CONNECT_TIMEOUT) < 0
        || connectTimeout.compareTo(MAX_CONNECT_TIMEOUT) > 0) {
      throw invalid(
          "connectTimeout",
          "접속 제한 시간은 "
              + MIN_CONNECT_TIMEOUT.toSeconds()
              + "-"
              + MAX_CONNECT_TIMEOUT.toSeconds()
              + " 초여야 합니다.");
    }
    if (maxPoolSize < 1 || maxPoolSize > MAX_POOL_SIZE) {
      throw invalid("maxPoolSize", "풀 크기는 1-" + MAX_POOL_SIZE + " 이어야 합니다.");
    }
    options = checkOptions(options);
  }

  /** 호스트가 IPv6 리터럴이면 true. URL 에는 괄호를 붙여 넣는다. */
  public boolean hostIsIpv6Literal() {
    return host.indexOf(':') >= 0;
  }

  /** 옵션 값 없이 이름만 보인다. */
  @Override
  public String toString() {
    return "ConnectionSettings[type="
        + type
        + ", host="
        + host
        + ", port="
        + port
        + ", databaseName="
        + databaseName
        + ", username="
        + username
        + ", connectTimeout="
        + connectTimeout
        + ", maxPoolSize="
        + maxPoolSize
        + ", options="
        + options.keySet()
        + "]";
  }

  private static String checkHost(String host) {
    String message =
        "호스트는 "
            + ConnectionFieldRules.MAX_HOST_LENGTH
            + " 자 이하의 호스트 이름, IPv4 또는 IPv6 주소여야 합니다 (IPv6 는 [ ] 로 감쌀 수 있습니다).";
    if (host == null) {
      throw invalid("host", message);
    }
    if (host.startsWith("[")) {
      if (!host.endsWith("]")) {
        throw invalid("host", message);
      }
      String inner = host.substring(1, host.length() - 1);
      if (!ConnectionFieldRules.isIpv6(inner)) {
        throw invalid("host", message);
      }
      return inner;
    }
    if (ConnectionFieldRules.isIpv6(host) || ConnectionFieldRules.isHostNameOrIpv4(host)) {
      return host;
    }
    throw invalid("host", message);
  }

  private static Map<String, String> checkOptions(Map<String, String> options) {
    if (options == null) {
      return Map.of();
    }
    if (options.size() > MAX_OPTIONS) {
      throw invalid("options", "접속 옵션은 " + MAX_OPTIONS + " 개까지입니다.");
    }
    // 이름 순으로 둔다: 거부 메시지와 toString 이 입력 순서에 따라 달라지지 않는다.
    TreeMap<String, String> sorted = new TreeMap<>();
    for (Map.Entry<String, String> entry : options.entrySet()) {
      String name = entry.getKey();
      String value = entry.getValue();
      if (name == null || !ConnectionFieldRules.isOptionName(name)) {
        throw invalid("options", "접속 옵션 이름은 [A-Za-z][A-Za-z0-9_.-]{0,63} 이어야 합니다.");
      }
      if (value == null
          || value.length() > MAX_OPTION_VALUE_LENGTH
          || !ConnectionFieldRules.hasNoControlCharacters(value)) {
        throw invalid(
            "options",
            "접속 옵션 값은 " + MAX_OPTION_VALUE_LENGTH + " 자 이하이고 제어 문자가 없어야 합니다 (옵션 " + name + ").");
      }
      sorted.put(name, value);
    }
    return Collections.unmodifiableMap(sorted);
  }

  private static InvalidConnectionSettingsException invalid(String field, String message) {
    Objects.requireNonNull(message, "message");
    return InvalidConnectionSettingsException.field(field, message);
  }
}
