package com.queryhub.adapter.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * 256 비트 마스터 키 (P1-04, ADR-024). 바이트는 방어적으로 복사해 보관하고 밖으로 내보내지 않는다.
 *
 * <p>키 ID({@link #kid()})는 HMAC-SHA256(키, ASCII {@value #KID_LABEL}) 의 앞 8 바이트를 소문자 16진수로 쓴 16 자다.
 * 키에서 한 방향으로만 나오므로 저장 값과 로그에 남겨도 키를 알 수 없고, 키 교체 때 어느 키로 암호화했는지 구분한다. {@link #toString()} 은 키 ID 만
 * 보여 준다.
 */
public final class MasterKey {

  /** 키 길이(바이트). */
  public static final int LENGTH = 32;

  /** 키 ID 를 만드는 HMAC 의 메시지. */
  static final String KID_LABEL = "queryhub master key id v1";

  /** 키 ID 의 바이트 수 (16진수로 16 자). */
  static final int KID_BYTES = 8;

  private static final String HMAC = "HmacSHA256";

  private static final String AES = "AES";

  private final byte[] key;
  private final String kid;

  /**
   * @param key 정확히 32 바이트. 복사해서 보관하므로 호출한 쪽은 배열을 지워도 된다.
   * @throws IllegalArgumentException 길이가 32 가 아니거나 모든 바이트가 같으면. 메시지에 값은 없다.
   */
  public MasterKey(byte[] key) {
    Objects.requireNonNull(key, "key");
    if (key.length != LENGTH) {
      throw new IllegalArgumentException("마스터 키는 정확히 " + LENGTH + " 바이트여야 한다.");
    }
    if (allIdentical(key)) {
      throw new IllegalArgumentException("마스터 키의 모든 바이트가 같다. 무작위로 만든 키를 쓴다.");
    }
    this.key = key.clone();
    this.kid = deriveKid(this.key);
  }

  /** 모든 바이트가 같은지 (예: 0 으로 채운 키). */
  public static boolean allIdentical(byte[] bytes) {
    for (byte b : bytes) {
      if (b != bytes[0]) {
        return false;
      }
    }
    return true;
  }

  /** 키 ID: 소문자 16진수 16 자. */
  public String kid() {
    return kid;
  }

  /** AES 키. 부를 때마다 새 객체이고, 바이트는 SecretKeySpec 이 다시 복사한다. */
  SecretKey aesKey() {
    return new SecretKeySpec(key, AES);
  }

  @Override
  public String toString() {
    return "MasterKey[kid=" + kid + "]";
  }

  private static String deriveKid(byte[] key) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(new SecretKeySpec(key, HMAC));
      byte[] digest = mac.doFinal(KID_LABEL.getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(Arrays.copyOf(digest, KID_BYTES));
    } catch (GeneralSecurityException e) {
      // 모든 Java SE 구현은 HmacSHA256 을 제공해야 한다. 키 값은 메시지에 넣지 않는다.
      throw new IllegalStateException("HmacSHA256 으로 마스터 키 ID 를 만들 수 없다.", e);
    }
  }
}
