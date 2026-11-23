package com.queryhub.application.secret;

/**
 * 비밀 값(업무 DB 비밀번호 등)을 저장용 문자열로 암호화하고 되돌리는 포트 (P1-04, ADR-024).
 *
 * <p>구현은 adapter-crypto 에 있고 app 이 빈으로 조립한다. 저장 형식은 구현이 정하는 버전 붙은 ASCII 문자열이며, 암호화할 때 쓴 마스터 키의 키 ID
 * 를 담는다. 같은 평문과 문맥이라도 암호화할 때마다 다른 값이 나온다.
 *
 * <p>모든 메서드는 실패하면 {@link SecretCipherException} 을 던진다. 그 메시지에는 키, 평문, 저장 값이 들어 있지 않다. 구현은 여러 스레드에서
 * 함께 써도 된다.
 */
public interface SecretCipher {

  /**
   * 현재 마스터 키로 평문을 암호화한다.
   *
   * @param plaintext 0-4096 바이트(UTF-8)의 평문
   * @param context 값이 속한 곳. 복호화할 때 같은 문맥을 넘겨야 한다.
   * @return 저장용 문자열
   * @throws SecretCipherException 평문이 너무 길면 {@link SecretCipherException.Reason#PLAINTEXT_TOO_LONG}
   */
  String encrypt(String plaintext, SecretContext context);

  /**
   * 저장 값을 복호화한다. 키 목록(현재 키와 복호화 전용 이전 키)에 있는 키 ID 만 받는다.
   *
   * @throws SecretCipherException 형식, 버전, 키 ID 가 맞지 않거나 인증(변조, 다른 문맥)에 실패하면
   */
  String decrypt(String stored, SecretContext context);

  /**
   * 저장 값이 현재 마스터 키가 아닌 키로 암호화되었는지. 키 ID 만 보므로 복호화하지 않는다.
   *
   * @throws SecretCipherException 형식이나 버전이 맞지 않으면
   */
  boolean needsReencryption(String stored);

  /**
   * 저장 값을 복호화해 현재 마스터 키로 다시 암호화한다 (키 교체).
   *
   * @throws SecretCipherException {@link #decrypt} 와 같은 경우
   */
  String reencrypt(String stored, SecretContext context);
}
