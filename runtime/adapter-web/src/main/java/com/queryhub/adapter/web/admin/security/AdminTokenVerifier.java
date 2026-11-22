package com.queryhub.adapter.web.admin.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 관리자 토큰을 상수 시간으로 비교한다.
 *
 * <p>설정된 토큰은 SHA-256 다이제스트로만 보관한다. 보낸 값도 SHA-256 으로 바꾼 뒤 {@link MessageDigest#isEqual} 로 비교하므로 비교
 * 시간이 두 값의 공통 접두사 길이나 보낸 값의 길이에 따라 달라지지 않는다. 문자열 비교(String.equals 등)와 Arrays.equals 는 쓰지 않는다
 * (ArchUnit R9 가 검사한다). {@link #toString()} 은 값을 보여 주지 않는다.
 */
public final class AdminTokenVerifier {

  private static final String ALGORITHM = "SHA-256";

  private final byte[] expectedDigest;

  /**
   * @param token 설정된 토큰. 비어 있으면 안 된다(형식 검사는 app 의 AdminTokenSource 가 한다).
   */
  public AdminTokenVerifier(String token) {
    Objects.requireNonNull(token, "token");
    if (token.isEmpty()) {
      throw new IllegalArgumentException("관리자 토큰이 비어 있다");
    }
    this.expectedDigest = sha256(token);
  }

  /** 보낸 값이 설정된 토큰과 같은지. null 과 빈 값은 같지 않다. */
  public boolean matches(@Nullable String presented) {
    if (presented == null) {
      return false;
    }
    return MessageDigest.isEqual(expectedDigest, sha256(presented));
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance(ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      // 모든 Java SE 구현은 SHA-256 을 제공해야 한다.
      throw new IllegalStateException(ALGORITHM + " 을 쓸 수 없다", e);
    }
  }

  @Override
  public String toString() {
    return "AdminTokenVerifier[token=****]";
  }
}
