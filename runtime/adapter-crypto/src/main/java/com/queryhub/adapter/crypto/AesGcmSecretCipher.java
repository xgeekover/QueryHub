package com.queryhub.adapter.crypto;

import com.queryhub.application.secret.SecretCipher;
import com.queryhub.application.secret.SecretCipherException;
import com.queryhub.application.secret.SecretCipherException.Reason;
import com.queryhub.application.secret.SecretContext;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM 으로 구현한 {@link SecretCipher} (P1-04 F2, ADR-024). 저장 형식은 {@link StoredSecretFormat}
 * 이다.
 *
 * <ul>
 *   <li>암호화는 언제나 현재 키로 한다. 복호화는 현재 키와 복호화 전용 키(이전 키) 중 저장 값의 키 ID 와 같은 키로 한다.
 *   <li>호출마다 새 {@link Cipher} 를 만들므로 여러 스레드에서 함께 써도 된다. nonce 는 암호화마다 하나의 {@link SecureRandom} 에서
 *       새로 뽑은 96 비트이고 태그는 128 비트다. 무작위 nonce 의 한도(키 하나에 2^32 번, NIST SP 800-38D)는 비밀번호 저장량보다 훨씬 크다.
 *   <li>{@link #toString()} 은 키 ID 만 보여 준다. 예외 메시지에는 키, 평문, 저장 값이 없다.
 * </ul>
 */
public final class AesGcmSecretCipher implements SecretCipher {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  private static final int TAG_BITS = StoredSecretFormat.TAG_BYTES * Byte.SIZE;

  /** nonce 를 채우는 곳. 운영은 SecureRandom 이고, 고정 값 검사(known-answer test)만 다른 것을 넣는다. */
  @FunctionalInterface
  interface NonceSource {
    void fill(byte[] nonce);
  }

  private final MasterKey current;
  private final Map<String, MasterKey> keyRing;
  private final NonceSource nonces;

  /**
   * @param current 암호화와 복호화에 쓰는 현재 키
   * @param decryptOnly 복호화에만 쓰는 이전 키들. 키 ID 가 겹치면(현재 키 포함) 거부한다.
   * @throws IllegalArgumentException 키 ID 가 겹치면
   */
  public AesGcmSecretCipher(MasterKey current, List<MasterKey> decryptOnly) {
    this(current, decryptOnly, secureRandom());
  }

  AesGcmSecretCipher(MasterKey current, List<MasterKey> decryptOnly, NonceSource nonces) {
    this.current = Objects.requireNonNull(current, "current");
    this.nonces = Objects.requireNonNull(nonces, "nonces");
    Map<String, MasterKey> ring = new LinkedHashMap<>();
    ring.put(current.kid(), current);
    for (MasterKey key : Objects.requireNonNull(decryptOnly, "decryptOnly")) {
      Objects.requireNonNull(key, "decryptOnly key");
      if (ring.putIfAbsent(key.kid(), key) != null) {
        throw new IllegalArgumentException("키 목록에 같은 키 ID 가 두 번 있다: " + key.kid());
      }
    }
    this.keyRing = Collections.unmodifiableMap(ring);
  }

  @Override
  public String encrypt(String plaintext, SecretContext context) {
    Objects.requireNonNull(context, "context");
    byte[] plain = utf8(Objects.requireNonNull(plaintext, "plaintext"));
    if (plain.length > StoredSecretFormat.MAX_PLAINTEXT_BYTES) {
      throw new SecretCipherException(Reason.PLAINTEXT_TOO_LONG);
    }
    byte[] nonce = new byte[StoredSecretFormat.NONCE_BYTES];
    nonces.fill(nonce);
    String kid = current.kid();
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, current.aesKey(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(StoredSecretFormat.aad(kid, context));
      byte[] sealed = cipher.doFinal(plain);
      byte[] payload = new byte[nonce.length + sealed.length];
      System.arraycopy(nonce, 0, payload, 0, nonce.length);
      System.arraycopy(sealed, 0, payload, nonce.length, sealed.length);
      return StoredSecretFormat.encode(kid, payload);
    } catch (GeneralSecurityException e) {
      // JDK 의 AES-GCM 이 없거나 키를 받지 않는 경우뿐이다. 값은 메시지에 넣지 않는다.
      throw new IllegalStateException("AES-GCM 으로 암호화할 수 없다 (" + TRANSFORMATION + ").", e);
    }
  }

  @Override
  public String decrypt(String stored, SecretContext context) {
    Objects.requireNonNull(context, "context");
    StoredSecretFormat.Parsed parsed = StoredSecretFormat.parse(stored);
    MasterKey key = keyRing.get(parsed.kid());
    if (key == null) {
      throw new SecretCipherException(Reason.UNKNOWN_KEY);
    }
    byte[] payload = parsed.payload();
    byte[] plain;
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(
          Cipher.DECRYPT_MODE,
          key.aesKey(),
          new GCMParameterSpec(TAG_BITS, payload, 0, StoredSecretFormat.NONCE_BYTES));
      cipher.updateAAD(StoredSecretFormat.aad(parsed.kid(), context));
      plain =
          cipher.doFinal(
              payload,
              StoredSecretFormat.NONCE_BYTES,
              payload.length - StoredSecretFormat.NONCE_BYTES);
    } catch (AEADBadTagException e) {
      throw new SecretCipherException(Reason.AUTHENTICATION_FAILED);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM 으로 복호화할 수 없다 (" + TRANSFORMATION + ").", e);
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(plain))
          .toString();
    } catch (CharacterCodingException e) {
      // 인증된 값이므로 이 키로 UTF-8 이 아닌 평문을 암호화한 경우뿐이다.
      throw new SecretCipherException(Reason.MALFORMED);
    }
  }

  @Override
  public boolean needsReencryption(String stored) {
    return !StoredSecretFormat.parse(stored).kid().equals(current.kid());
  }

  @Override
  public String reencrypt(String stored, SecretContext context) {
    return encrypt(decrypt(stored, context), context);
  }

  /** 현재 키의 ID. */
  public String currentKid() {
    return current.kid();
  }

  @Override
  public String toString() {
    List<String> decryptOnly = new ArrayList<>(keyRing.keySet());
    decryptOnly.remove(current.kid());
    return "AesGcmSecretCipher[current=" + current.kid() + ", decryptOnly=" + decryptOnly + "]";
  }

  /** 평문을 UTF-8 로 바꾼다. 짝이 맞지 않는 서로게이트는 '?' 로 바꾸지 않고 거부한다(되돌린 평문이 달라진다). */
  private static byte[] utf8(String text) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(text));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("비밀 값이 올바른 유니코드 문자열이 아니다.");
    }
  }

  private static NonceSource secureRandom() {
    SecureRandom random = new SecureRandom();
    return random::nextBytes;
  }
}
