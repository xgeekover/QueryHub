package com.queryhub.adapter.web.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/**
 * Host 허용 목록 (DNS rebinding 방어).
 *
 * <p>요청은 다음을 모두 만족해야 한다.
 *
 * <ol>
 *   <li>원본 Host 헤더가 정확히 하나다.
 *   <li>값이 {@code name[:port]} 형식이다. name 은 이름(글자, 숫자, '.', '-')이거나 대괄호로 감싼 IPv6 리터럴이다. port 는 있으면
 *       1-65535 의 숫자다(로컬 포트와 비교하지 않는다).
 *   <li>name 을 소문자로 바꾼 값이 {@link #BUILT_IN_NAMES} 나 설정한 이름 중 하나와 정확히 같다.
 *   <li>request.getServerName() 도 허용된 이름이다(absolute-form 요청 대상의 호스트도 막는다).
 * </ol>
 *
 * <p>그래서 userinfo({@code user@localhost}), zone id, 끝의 점({@code localhost.}), 대괄호 없는 IPv6, 다른 127.x
 * 표기는 거부된다. 판단할 수 없는 값은 모두 거부한다.
 */
public final class HostHeaderPolicy {

  /** 늘 허용하는 이름. */
  public static final List<String> BUILT_IN_NAMES = List.of("localhost", "127.0.0.1", "[::1]");

  private static final Pattern HOST_HEADER =
      Pattern.compile("(\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9.-]+)(?::([0-9]{1,5}))?");

  /** RFC 1123 호스트 이름(레이블 1-63 자, 전체 253 자 이하, 끝의 점 없음). IPv4 점 표기도 이 형식에 맞는다. */
  private static final Pattern HOST_NAME =
      Pattern.compile(
          "(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
              + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");

  private static final Pattern BRACKETED_IPV6 = Pattern.compile("\\[([0-9A-Fa-f:.]+)\\]");

  private static final int MAX_PORT = 65535;

  private final Set<String> allowedNames;

  private HostHeaderPolicy(Set<String> allowedNames) {
    this.allowedNames = Collections.unmodifiableSet(allowedNames);
  }

  /**
   * 기본 이름과 설정한 이름으로 정책을 만든다.
   *
   * @throws IllegalArgumentException 설정한 이름 중 형식이 맞지 않는 것이 있을 때
   */
  public static HostHeaderPolicy of(Collection<String> configuredNames) {
    Set<String> names = new LinkedHashSet<>(BUILT_IN_NAMES);
    for (String name : configuredNames) {
      names.add(normalizeEntry(name));
    }
    return new HostHeaderPolicy(names);
  }

  /**
   * 허용 목록 항목 하나를 검사하고 소문자로 바꾼다. 호스트 이름, IPv4 점 표기, 대괄호로 감싼 IPv6 만 받는다. 와일드카드, 포트, 경로, userinfo, 끝의
   * 점은 받지 않는다.
   *
   * @throws IllegalArgumentException 형식이 맞지 않을 때
   */
  public static String normalizeEntry(@Nullable String entry) {
    if (entry == null || !isValidEntry(entry)) {
      throw new IllegalArgumentException("허용 Host 항목 형식이 아니다: '" + entry + "'");
    }
    return entry.toLowerCase(Locale.ROOT);
  }

  /** 허용 목록 항목으로 쓸 수 있는 값인지. */
  public static boolean isValidEntry(String entry) {
    if (HOST_NAME.matcher(entry).matches()) {
      return true;
    }
    Matcher ipv6 = BRACKETED_IPV6.matcher(entry);
    if (!ipv6.matches() || ipv6.group(1).indexOf(':') < 0 || ipv6.group(1).startsWith(".")) {
      return false;
    }
    try {
      return InetAddress.getByName(ipv6.group(1)) instanceof Inet6Address;
    } catch (UnknownHostException | IllegalArgumentException e) {
      return false;
    }
  }

  /** 허용된 이름(소문자). */
  public Set<String> allowedNames() {
    return allowedNames;
  }

  /** 요청의 Host 헤더와 서버 이름이 허용 목록에 맞는지. */
  public boolean allows(HttpServletRequest request) {
    Enumeration<String> headers = request.getHeaders(HttpHeaders.HOST);
    List<String> values = headers == null ? List.of() : Collections.list(headers);
    if (values.size() != 1 || !allowsHostHeader(values.get(0))) {
      return false;
    }
    return allowsServerName(request.getServerName());
  }

  /** Host 헤더 값 하나가 허용되는지. */
  public boolean allowsHostHeader(@Nullable String value) {
    if (value == null) {
      return false;
    }
    Matcher matcher = HOST_HEADER.matcher(value);
    if (!matcher.matches()) {
      return false;
    }
    String port = matcher.group(2);
    if (port != null) {
      int number = Integer.parseInt(port);
      if (number < 1 || number > MAX_PORT) {
        return false;
      }
    }
    return allowedNames.contains(matcher.group(1).toLowerCase(Locale.ROOT));
  }

  private boolean allowsServerName(@Nullable String serverName) {
    if (serverName == null || serverName.isEmpty()) {
      return false;
    }
    String name = serverName.toLowerCase(Locale.ROOT);
    // 컨테이너에 따라 IPv6 서버 이름을 대괄호 없이 돌려준다.
    if (name.indexOf(':') >= 0 && !name.startsWith("[")) {
      name = "[" + name + "]";
    }
    return allowedNames.contains(name);
  }

  @Override
  public String toString() {
    return "HostHeaderPolicy" + allowedNames;
  }
}
