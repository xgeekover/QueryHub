package com.queryhub.application.secret;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.application.secret.SecretCipherException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** P1-04 F1, AC11: 예외 메시지는 이유마다 고정되어 있고 값(키, 평문, 저장 값)을 담을 자리가 없다. */
class SecretCipherExceptionTest {

  @ParameterizedTest
  @EnumSource(Reason.class)
  void messageIsTheFixedTextOfTheReason(Reason reason) {
    SecretCipherException exception = new SecretCipherException(reason);

    assertThat(exception.reason()).isEqualTo(reason);
    assertThat(exception.getMessage()).isEqualTo(reason.message()).isNotBlank();
    assertThat(exception.getCause()).isNull();
    assertThat(exception.toString())
        .isEqualTo(SecretCipherException.class.getName() + ": " + reason.message());
  }

  @Test
  void everyReasonHasItsOwnMessage() {
    assertThat(Reason.values()).extracting(Reason::message).doesNotHaveDuplicates();
    assertThat(Reason.values())
        .extracting(Reason::name)
        .containsExactly(
            "MALFORMED",
            "UNSUPPORTED_VERSION",
            "UNKNOWN_KEY",
            "AUTHENTICATION_FAILED",
            "PLAINTEXT_TOO_LONG");
  }
}
