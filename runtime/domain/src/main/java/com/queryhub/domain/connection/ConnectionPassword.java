package com.queryhub.domain.connection;

import java.util.Arrays;
import java.util.Objects;

/**
 * 업무 DB 비밀번호. 값은 char[] 에만 두고 어떤 문자열 표현에도 드러내지 않는다.
 *
 * <p>{@link #toString()} 은 고정 문자열이라서 {@code String.valueOf}, 문자열 연결, 레코드가 만든 toString, 로그 인자 어디에서도
 * 값이 보이지 않는다. equals/hashCode 는 내용이 아닌 객체 동일성이다 (값 비교로 내용을 추측하는 길을 만들지 않는다).
 *
 * <p>값을 받는 쪽(DB 드라이버)에는 접속하는 순간에만 {@link #copyChars()} 로 넘긴다. 다 쓴 객체는 {@link #destroy()} 로 0 으로
 * 덮는다.
 */
public final class ConnectionPassword {

  /** 비밀번호의 최대 길이(문자 수). */
  public static final int MAX_LENGTH = 1024;

  private static final String MASK = "ConnectionPassword[******]";

  private final char[] value;
  private volatile boolean destroyed;

  private ConnectionPassword(char[] value) {
    this.value = value;
  }

  /**
   * 받은 배열을 복사해 둔다. 호출한 쪽은 자기 배열을 지워도 된다.
   *
   * @throws IllegalArgumentException 비어 있거나 {@link #MAX_LENGTH} 보다 길면. 메시지에 값은 없다.
   */
  public static ConnectionPassword of(char[] value) {
    Objects.requireNonNull(value, "value");
    if (value.length == 0 || value.length > MAX_LENGTH) {
      // 빈 비밀번호를 받지 않는다: 드라이버가 비밀번호 파일(~/.pgpass 등)을 대신 읽는 길을 막는다.
      throw new IllegalArgumentException("비밀번호는 1-" + MAX_LENGTH + " 자여야 한다.");
    }
    return new ConnectionPassword(value.clone());
  }

  /** 값의 사본. 받은 쪽이 다 쓰고 지운다. */
  public char[] copyChars() {
    requireAlive();
    return value.clone();
  }

  /** 같은 값을 가진 새 객체. 원본과 수명이 따로다. */
  public ConnectionPassword copy() {
    requireAlive();
    return new ConnectionPassword(value.clone());
  }

  /** 값을 0 으로 덮는다. 이후 {@link #copyChars()} 와 {@link #copy()} 는 실패한다. */
  public void destroy() {
    destroyed = true;
    Arrays.fill(value, '\0');
  }

  public boolean isDestroyed() {
    return destroyed;
  }

  private void requireAlive() {
    if (destroyed) {
      throw new IllegalStateException("이미 지운 비밀번호다.");
    }
  }

  /** 값도 길이도 드러내지 않는 고정 문자열. */
  @Override
  public String toString() {
    return MASK;
  }
}
