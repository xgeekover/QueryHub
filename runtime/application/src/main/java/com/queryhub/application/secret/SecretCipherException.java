package com.queryhub.application.secret;

import java.util.Objects;

/**
 * 비밀 값을 암호화하거나 복호화할 수 없다 (P1-04). 메시지는 {@link Reason} 마다 고정된 문장이고 키, 평문, 저장 값을 담지 않는다. 원인 예외도 붙이지
 * 않는다.
 */
public final class SecretCipherException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 실패 이유. */
  public enum Reason {
    /** 저장 값의 구조, 문자, 길이가 형식에 맞지 않는다. */
    MALFORMED("저장된 비밀 값의 형식이 올바르지 않다."),
    /** 저장 값의 형식 버전을 모른다. */
    UNSUPPORTED_VERSION("저장된 비밀 값의 형식 버전을 지원하지 않는다."),
    /** 저장 값의 키 ID 가 키 목록에 없다. */
    UNKNOWN_KEY("저장된 비밀 값을 암호화한 마스터 키가 키 목록에 없다."),
    /** 인증 태그가 맞지 않는다: 값이 바뀌었거나 다른 문맥의 값이다. */
    AUTHENTICATION_FAILED("저장된 비밀 값을 인증하지 못했다 (값이 바뀌었거나 다른 문맥의 값이다)."),
    /** 평문이 최대 길이(UTF-8 4096 바이트)를 넘는다. */
    PLAINTEXT_TOO_LONG("비밀 값이 너무 길다 (최대 4096 바이트, UTF-8).");

    private final String message;

    Reason(String message) {
      this.message = message;
    }

    /** 이 이유의 고정 메시지. */
    public String message() {
      return message;
    }
  }

  private final Reason reason;

  public SecretCipherException(Reason reason) {
    super(Objects.requireNonNull(reason, "reason").message());
    this.reason = reason;
  }

  /** 실패 이유. */
  public Reason reason() {
    return reason;
  }
}
