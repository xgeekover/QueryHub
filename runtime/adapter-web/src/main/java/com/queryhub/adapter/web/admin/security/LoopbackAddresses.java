package com.queryhub.adapter.web.admin.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 소켓의 원격 주소(request.getRemoteAddr())가 루프백인지 판단한다. DNS 를 찾지 않는다.
 *
 * <p>루프백: IPv4 127.0.0.0/8, IPv6 ::1, IPv4-mapped ::ffff:127.0.0.0/104. 그 밖의 값과 IP 리터럴이 아닌 값(빈 문자열,
 * 이름, zone id 가 붙은 주소, 127.1 같은 축약형)은 모두 루프백이 아니다. X-Forwarded-For 와 Forwarded 헤더는 보지 않는다(호출자가 소켓
 * 주소만 넘긴다).
 */
public final class LoopbackAddresses {

  /** 점으로 나눈 네 개의 10진 옥텟(0-255, 앞자리 0 없음). */
  private static final Pattern IPV4 =
      Pattern.compile(
          "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
              + "(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}");

  /**
   * IPv6 리터럴에 쓰는 글자만(16진수, ':', '.'). 첫 글자는 16진수나 ':' 여서 InetAddress 가 이름으로 보지 않는다. '%'(zone id)는
   * 허용하지 않는다.
   */
  private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]*");

  private static final int LOOPBACK_FIRST_OCTET = 127;

  private LoopbackAddresses() {}

  /** 원격 주소가 루프백 IP 리터럴인지. 해석할 수 없으면 false(거부). */
  public static boolean isLoopback(@Nullable String remoteAddress) {
    if (remoteAddress == null || remoteAddress.isEmpty()) {
      return false;
    }
    if (IPV4.matcher(remoteAddress).matches()) {
      int firstDot = remoteAddress.indexOf('.');
      return Integer.parseInt(remoteAddress.substring(0, firstDot)) == LOOPBACK_FIRST_OCTET;
    }
    if (remoteAddress.indexOf(':') < 0 || !IPV6_LITERAL.matcher(remoteAddress).matches()) {
      return false;
    }
    InetAddress parsed;
    try {
      // ':' 를 담고 16진수나 ':' 로 시작하는 값은 리터럴로만 해석된다(잘못된 값은 이름 조회 없이 UnknownHostException).
      parsed = InetAddress.getByName(remoteAddress);
    } catch (UnknownHostException | IllegalArgumentException e) {
      return false;
    }
    // ::ffff:a.b.c.d 는 Inet4Address 로 바뀐다. 127/8 이면 루프백이다.
    if (parsed instanceof Inet4Address) {
      return parsed.isLoopbackAddress();
    }
    return parsed instanceof Inet6Address && parsed.isLoopbackAddress();
  }
}
