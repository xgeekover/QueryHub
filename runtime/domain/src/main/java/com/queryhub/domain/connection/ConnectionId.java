package com.queryhub.domain.connection;

import java.util.regex.Pattern;

/**
 * 업무 DB 접속 정보의 식별자.
 *
 * <p>풀 이름(queryhub-conn-&lt;id&gt;)과 로그에 그대로 쓰이므로 글자를 좁게 제한한다.
 *
 * @param value {@code [A-Za-z0-9][A-Za-z0-9_-]{0,63}}
 */
public record ConnectionId(String value) {

  private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

  /**
   * @throws IllegalArgumentException 규칙에 맞지 않으면. 메시지에 값은 없다.
   */
  public ConnectionId {
    if (value == null || !VALUE.matcher(value).matches()) {
      throw new IllegalArgumentException("접속 ID 는 [A-Za-z0-9][A-Za-z0-9_-]{0,63} 이어야 한다.");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
