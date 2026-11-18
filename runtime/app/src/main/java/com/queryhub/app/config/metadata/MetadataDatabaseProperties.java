package com.queryhub.app.config.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Metadata DB 접속 설정 ({@value #PREFIX}.*).
 *
 * <p>값은 환경변수로만 받는다. Spring 의 느슨한 바인딩이 {@code QUERYHUB_METADATA_DB_HOST} 를 {@code
 * queryhub.metadata.db.host} 로 연결하므로 application*.yml 에는 자리표시자를 두지 않는다. desktop 과 server 가 같은 변수를
 * 쓴다.
 *
 * <table>
 *   <caption>환경변수</caption>
 *   <tr><th>변수</th><th>필수</th><th>규칙</th></tr>
 *   <tr><td>{@value #ENV_HOST}</td><td>예</td><td>호스트 이름·IPv4(글자, 숫자, '.', '-') 또는 [IPv6]</td></tr>
 *   <tr><td>{@value #ENV_PORT}</td><td>아니요 (기본 {@value #DEFAULT_PORT})</td><td>1-65535</td></tr>
 *   <tr><td>{@value #ENV_NAME}</td><td>예</td><td>[A-Za-z0-9_]{1,64}</td></tr>
 *   <tr><td>{@value #ENV_USERNAME}</td><td>예</td><td>비어 있지 않음</td></tr>
 *   <tr><td>{@value #ENV_PASSWORD}</td><td>예</td><td>비어 있지 않음, 환경변수로만 넘긴다</td></tr>
 * </table>
 *
 * <p>JDBC URL 은 런타임이 만든다({@link #jdbcUrl(String, int, String)}). 호스트와 이름에 URL 구분 문자({@code / ? &
 * # @ ; =}, 공백)를 허용하지 않으므로 호출하는 쪽이 URL 옵션을 끼워 넣거나 안전 옵션을 뺄 수 없다. 사용자 이름과 비밀번호는 URL 에 넣지 않는다.
 *
 * <p>{@link #toString()} 과 모든 검사 메시지는 비밀번호 값을 담지 않는다. 검사 메시지는 다른 값도 되풀이하지 않고 변수 이름만 말한다.
 */
@ConfigurationProperties(prefix = MetadataDatabaseProperties.PREFIX)
public record MetadataDatabaseProperties(
    @Nullable String host,
    @Nullable String port,
    @Nullable String name,
    @Nullable String username,
    @Nullable String password) {

  /** 설정 접두사. */
  public static final String PREFIX = "queryhub.metadata.db";

  /** 비밀번호 속성 이름. 명령행 인자와 시스템 속성으로는 받지 않는다. */
  public static final String PASSWORD_PROPERTY = PREFIX + ".password";

  /** 접속 정보 환경변수의 공통 접두사. */
  public static final String ENV_PREFIX = "QUERYHUB_METADATA_DB_";

  public static final String ENV_HOST = ENV_PREFIX + "HOST";
  public static final String ENV_PORT = ENV_PREFIX + "PORT";
  public static final String ENV_NAME = ENV_PREFIX + "NAME";
  public static final String ENV_USERNAME = ENV_PREFIX + "USERNAME";
  public static final String ENV_PASSWORD = ENV_PREFIX + "PASSWORD";

  public static final int DEFAULT_PORT = 3306;

  /**
   * 런타임이 항상 붙이는 드라이버 옵션. 세션 시간대를 UTC 로 고정하고(서버 기본 시간대와 무관), Instant 를 보존하며, LOCAL INFILE 을 끈다
   * (GHSA-fhx5-2xqw-2cgm 계열의 악성 서버 대책).
   */
  public static final String URL_OPTIONS =
      "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true"
          + "&allowLocalInfile=false";

  private static final String MASK = "******";

  /** 호스트 이름 또는 IPv4(글자, 숫자, '.', '-'), 또는 대괄호로 감싼 IPv6 리터럴. */
  private static final Pattern HOST =
      Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]{2,45}\\]");

  private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,64}");

  /**
   * 값을 검사하고 접속 정보를 만든다.
   *
   * @throws IllegalStateException 필수 값이 없거나(빈 값 포함) 값이 규칙에 맞지 않을 때. 메시지는 문제가 있는 모든 환경변수의 이름을 담고, 값은
   *     담지 않는다.
   */
  public MetadataJdbcConnectionDetails toConnectionDetails() {
    List<String> problems = new ArrayList<>();
    if (isBlank(host)) {
      problems.add(ENV_HOST + ": 값이 없다 (필수)");
    } else if (!HOST.matcher(host).matches()) {
      problems.add(ENV_HOST + ": 호스트 이름, IPv4 주소(글자, 숫자, '.', '-') 또는 대괄호로 감싼 IPv6 주소여야 한다");
    }
    int resolvedPort = DEFAULT_PORT;
    if (!isBlank(port)) {
      resolvedPort = parsePort(port);
      if (resolvedPort < 0) {
        problems.add(ENV_PORT + ": 1 에서 65535 사이의 정수여야 한다 (생략하면 " + DEFAULT_PORT + ")");
      }
    }
    if (isBlank(name)) {
      problems.add(ENV_NAME + ": 값이 없다 (필수)");
    } else if (!NAME.matcher(name).matches()) {
      problems.add(ENV_NAME + ": 영문자, 숫자, '_' 로 된 1-64 자여야 한다");
    }
    if (isBlank(username)) {
      problems.add(ENV_USERNAME + ": 값이 없다 (필수)");
    }
    if (isBlank(password)) {
      problems.add(ENV_PASSWORD + ": 값이 없다 (필수, 환경변수로만 넘긴다)");
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "Metadata DB 접속 설정이 올바르지 않아 런타임을 시작할 수 없다. 다음 환경변수를 확인하라:\n  - "
              + String.join("\n  - ", problems));
    }
    return new MetadataJdbcConnectionDetails(jdbcUrl(host, resolvedPort, name), username, password);
  }

  /** 검사를 통과한 호스트, 포트, 이름으로 JDBC URL 을 만든다. 옵션은 {@link #URL_OPTIONS} 로 고정이다. */
  static String jdbcUrl(String host, int port, String name) {
    return "jdbc:mariadb://" + host + ":" + port + "/" + name + "?" + URL_OPTIONS;
  }

  /** 1-65535 이면 그 값을, 아니면 -1 을 돌려준다. */
  private static int parsePort(String value) {
    if (!PORT.matcher(value).matches()) {
      return -1;
    }
    int parsed = Integer.parseInt(value);
    return parsed >= 1 && parsed <= 65535 ? parsed : -1;
  }

  private static boolean isBlank(@Nullable String value) {
    return value == null || value.isBlank();
  }

  /** 비밀번호는 가린다. 값이 있는지만 보인다. */
  @Override
  public String toString() {
    return "MetadataDatabaseProperties[host="
        + host
        + ", port="
        + port
        + ", name="
        + name
        + ", username="
        + username
        + ", password="
        + (password == null ? "null" : MASK)
        + "]";
  }
}
