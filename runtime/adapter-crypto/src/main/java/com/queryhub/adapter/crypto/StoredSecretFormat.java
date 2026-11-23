package com.queryhub.adapter.crypto;

import com.queryhub.application.secret.SecretCipherException;
import com.queryhub.application.secret.SecretCipherException.Reason;
import com.queryhub.application.secret.SecretContext;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 저장 형식 v1 (P1-04 F3, ADR-024): {@code qh1.<kid>.<base64url(nonce || ciphertext || tag)>}.
 *
 * <ul>
 *   <li>{@code qh1}: 형식 버전. {@code qh} 뒤에 다른 버전 번호가 오면 {@link Reason#UNSUPPORTED_VERSION}.
 *   <li>{@code kid}: 암호화한 마스터 키의 ID, 소문자 16진수 16 자.
 *   <li>본문: nonce 12 바이트, 암호문(평문과 같은 길이), GCM 태그 16 바이트를 이어 붙여 패딩 없는 base64url 로 쓴다. 정규 인코딩만
 *       받는다(디코딩한 값을 다시 인코딩하면 같은 문자열이어야 한다). 그래서 쓰이지 않는 끝 비트만 다른 문자열이 같은 값으로 풀리지 않는다.
 * </ul>
 *
 * <p>AAD: UTF-8 머리말 {@code qh1.<kid>.}, 그 뒤에 용도와 레코드 ID 를 각각 4 바이트 빅엔디언 길이와 UTF-8 바이트로 붙인다. 머리말을
 * AAD 에 넣으므로 버전이나 키 ID 를 바꾼 값은 인증에 실패한다.
 */
final class StoredSecretFormat {

  /** 형식 버전 표식. */
  static final String VERSION = "qh1";

  static final int NONCE_BYTES = 12;

  static final int TAG_BYTES = 16;

  /** 평문의 최대 길이(UTF-8 바이트). */
  static final int MAX_PLAINTEXT_BYTES = 4096;

  /** 본문(nonce, 암호문, 태그)의 최소와 최대 길이(바이트). */
  static final int MIN_PAYLOAD_BYTES = NONCE_BYTES + TAG_BYTES;

  static final int MAX_PAYLOAD_BYTES = MIN_PAYLOAD_BYTES + MAX_PLAINTEXT_BYTES;

  private static final Pattern KID = Pattern.compile("[0-9a-f]{16}");

  private static final Pattern OTHER_VERSION = Pattern.compile("qh[0-9]{1,9}");

  private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  /** 패딩 없는 base64url 로 본문 최대 길이를 쓴 문자 수. 이보다 긴 본문은 디코딩하지 않는다. */
  private static final int MAX_PAYLOAD_CHARS = (MAX_PAYLOAD_BYTES * 4 + 2) / 3;

  private StoredSecretFormat() {}

  /** 파싱한 저장 값: 키 ID 와 본문(nonce, 암호문, 태그). */
  record Parsed(String kid, byte[] payload) {}

  /** 머리말 {@code qh1.<kid>.}. */
  static String header(String kid) {
    return VERSION + "." + kid + ".";
  }

  /** 저장 문자열을 만든다. */
  static String encode(String kid, byte[] payload) {
    return header(kid) + ENCODER.encodeToString(payload);
  }

  /** AAD: 머리말, 그리고 길이를 앞에 붙인 용도와 레코드 ID. */
  static byte[] aad(String kid, SecretContext context) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(header(kid).getBytes(StandardCharsets.UTF_8));
    writeWithLength(out, context.purpose());
    writeWithLength(out, context.recordId());
    return out.toByteArray();
  }

  /**
   * 저장 문자열을 나눈다. 키 ID 가 키 목록에 있는지와 인증은 보지 않는다.
   *
   * @throws SecretCipherException {@link Reason#UNSUPPORTED_VERSION} 또는 {@link Reason#MALFORMED}
   */
  static Parsed parse(String stored) {
    if (stored == null) {
      throw new SecretCipherException(Reason.MALFORMED);
    }
    int firstDot = stored.indexOf('.');
    String version = firstDot < 0 ? stored : stored.substring(0, firstDot);
    if (!VERSION.equals(version)) {
      throw new SecretCipherException(
          OTHER_VERSION.matcher(version).matches() ? Reason.UNSUPPORTED_VERSION : Reason.MALFORMED);
    }
    String[] parts = stored.split("\\.", -1);
    if (parts.length != 3) {
      throw new SecretCipherException(Reason.MALFORMED);
    }
    String kid = parts[1];
    String body = parts[2];
    if (!KID.matcher(kid).matches()
        || body.length() > MAX_PAYLOAD_CHARS
        || !BASE64URL.matcher(body).matches()) {
      throw new SecretCipherException(Reason.MALFORMED);
    }
    byte[] payload;
    try {
      payload = DECODER.decode(body);
    } catch (IllegalArgumentException e) {
      throw new SecretCipherException(Reason.MALFORMED);
    }
    if (payload.length < MIN_PAYLOAD_BYTES
        || payload.length > MAX_PAYLOAD_BYTES
        || !ENCODER.encodeToString(payload).equals(body)) {
      throw new SecretCipherException(Reason.MALFORMED);
    }
    return new Parsed(kid, payload);
  }

  private static void writeWithLength(ByteArrayOutputStream out, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    out.writeBytes(bytes);
  }
}
