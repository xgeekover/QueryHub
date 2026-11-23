package com.queryhub.domain.connection;

import java.util.regex.Pattern;

/**
 * 접속 설정 필드의 글자 규칙. JDBC URL 은 이 규칙을 통과한 필드로만 만든다 (사용자가 준 URL 은 받지 않는다).
 *
 * <p>모두 허용 목록 방식이다: URL 구조 문자(/ ? # @ ; &amp; = \ % 따옴표), 공백, 제어 문자는 허용 목록에 없어서 거부된다.
 */
final class ConnectionFieldRules {

  /** 호스트 이름의 최대 길이 (RFC 1035). */
  static final int MAX_HOST_LENGTH = 253;

  private static final int MAX_LABEL_LENGTH = 63;

  /** IPv6 리터럴(점 표기 IPv4 꼬리 포함)의 최대 길이. */
  private static final int MAX_IPV6_LENGTH = 45;

  private static final Pattern LABEL =
      Pattern.compile(
          "[A-Za-z0-9_](?:[A-Za-z0-9_-]{0," + (MAX_LABEL_LENGTH - 2) + "}[A-Za-z0-9_])?");

  private static final Pattern DIGITS = Pattern.compile("[0-9]+");

  private static final Pattern HEX_GROUP = Pattern.compile("[0-9A-Fa-f]{1,4}");

  private static final Pattern OPTION_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

  private ConnectionFieldRules() {}

  /** 호스트 이름(점으로 나눈 레이블) 또는 점 표기 IPv4. 마지막 레이블이 숫자뿐이면 올바른 IPv4 여야 한다. */
  static boolean isHostNameOrIpv4(String host) {
    if (host.isEmpty() || host.length() > MAX_HOST_LENGTH) {
      return false;
    }
    String[] labels = host.split("\\.", -1);
    for (String label : labels) {
      if (!LABEL.matcher(label).matches()) {
        return false;
      }
    }
    if (DIGITS.matcher(labels[labels.length - 1]).matches()) {
      return isIpv4(host);
    }
    return true;
  }

  /** 점 표기 IPv4 (0-255 네 개, 앞자리 0 없음). */
  static boolean isIpv4(String text) {
    String[] octets = text.split("\\.", -1);
    if (octets.length != 4) {
      return false;
    }
    for (String octet : octets) {
      if (!DIGITS.matcher(octet).matches()
          || octet.length() > 3
          || (octet.length() > 1 && octet.charAt(0) == '0')
          || Integer.parseInt(octet) > 255) {
        return false;
      }
    }
    return true;
  }

  /** 괄호 없는 IPv6 리터럴 (RFC 4291 2.2). 영역 ID(%)는 받지 않는다. */
  static boolean isIpv6(String text) {
    if (text.length() < 2 || text.length() > MAX_IPV6_LENGTH || text.indexOf(':') < 0) {
      return false;
    }
    int doubleColon = text.indexOf("::");
    if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0) {
      return false;
    }
    int groups;
    if (doubleColon < 0) {
      groups = countGroups(text, true);
      return groups == 8;
    }
    String head = text.substring(0, doubleColon);
    String tail = text.substring(doubleColon + 2);
    int headGroups = head.isEmpty() ? 0 : countGroups(head, false);
    int tailGroups = tail.isEmpty() ? 0 : countGroups(tail, true);
    if (headGroups < 0 || tailGroups < 0) {
      return false;
    }
    return headGroups + tailGroups <= 7;
  }

  /** 콜론으로 나눈 16 비트 묶음 수. ipv4TailAllowed 이면 마지막 묶음이 IPv4 일 수 있다(2 묶음). 형식이 틀리면 -1. */
  private static int countGroups(String part, boolean ipv4TailAllowed) {
    String[] groups = part.split(":", -1);
    int count = 0;
    for (int i = 0; i < groups.length; i++) {
      String group = groups[i];
      if (HEX_GROUP.matcher(group).matches()) {
        count++;
      } else if (ipv4TailAllowed && i == groups.length - 1 && isIpv4(group)) {
        count += 2;
      } else {
        return -1;
      }
    }
    return count;
  }

  /** 데이터베이스(또는 서비스) 이름: 글자, 숫자, _ $ - . 만. */
  static boolean isDatabaseName(String name, int maxLength) {
    if (name.isEmpty() || name.length() > maxLength) {
      return false;
    }
    return name.codePoints()
        .allMatch(
            codePoint ->
                Character.isLetterOrDigit(codePoint)
                    || codePoint == '_'
                    || codePoint == '$'
                    || codePoint == '-'
                    || codePoint == '.');
  }

  /** 옵션 이름: {@code [A-Za-z][A-Za-z0-9_.-]{0,63}}. */
  static boolean isOptionName(String name) {
    return OPTION_NAME.matcher(name).matches();
  }

  /** 제어 문자가 없다. */
  static boolean hasNoControlCharacters(String text) {
    return text.codePoints().noneMatch(Character::isISOControl);
  }
}
