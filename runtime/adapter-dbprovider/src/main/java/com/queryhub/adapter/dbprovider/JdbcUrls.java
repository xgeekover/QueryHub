package com.queryhub.adapter.dbprovider;

import com.queryhub.domain.connection.ConnectionSettings;
import java.util.Objects;
import java.util.regex.Pattern;

/** Provider 가 검사된 필드로 JDBC URL 을 만들 때 쓰는 도우미. */
public final class JdbcUrls {

  private static final Pattern PREFIX = Pattern.compile("jdbc:[a-z][a-z0-9]*(?::[a-z][a-z0-9]*)*:");

  private JdbcUrls() {}

  /** URL 의 호스트 부분. IPv6 리터럴은 [ ] 로 감싼다 (RFC 3986 3.2.2). */
  public static String host(ConnectionSettings settings) {
    Objects.requireNonNull(settings, "settings");
    return settings.hostIsIpv6Literal() ? "[" + settings.host() + "]" : settings.host();
  }

  /**
   * {@code <prefix>//<host>:<port>/<databaseName>} (MariaDB, PostgreSQL 형식).
   *
   * @param prefix 예: {@code jdbc:postgresql:}. 소문자 이름과 콜론만
   */
  public static String hostPortDatabase(String prefix, ConnectionSettings settings) {
    if (prefix == null || !PREFIX.matcher(prefix).matches()) {
      throw new IllegalArgumentException("JDBC URL 접두어는 jdbc:<이름>: 형식이어야 한다.");
    }
    return prefix + "//" + host(settings) + ":" + settings.port() + "/" + settings.databaseName();
  }
}
