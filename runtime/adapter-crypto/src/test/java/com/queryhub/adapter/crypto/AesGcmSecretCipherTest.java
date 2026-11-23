package com.queryhub.adapter.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.queryhub.application.secret.SecretCipherException;
import com.queryhub.application.secret.SecretCipherException.Reason;
import com.queryhub.application.secret.SecretContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** P1-04 F2, F3, AC5-AC11: AES-256-GCM 비밀 값 암호기. */
class AesGcmSecretCipherTest {

  private static final SecretContext CONTEXT = SecretContext.databaseConnectionPassword("conn-42");

  private static final String KAT_PLAINTEXT = "비밀번호 p@ss-1";

  private static final String KAT_NONCE_HEX = "a0a1a2a3a4a5a6a7a8a9aaab";

  /**
   * 고정 값 검사의 기대 저장 값. 형식 설명(F3)대로 JDK Cipher 를 직접 부르는 별도 프로그램으로 만들었다(이 모듈의 코드를 쓰지 않았다). 키 ID 는
   * openssl 로 따로 계산했다. NOT A SECRET: 테스트 키(TestKeys.KAT_KEY_HEX)의 고정 벡터다.
   */
  private static final String KAT_STORED =
      "qh1.a4f112edae0b1004.oKGio6SlpqeoqaqrDaH4xvVL6Q3qiB9rJwqArQOBaN_-V6w-AsnnL-pgS1uH0m0";

  private static final String STORED_PATTERN = "^qh1[.][0-9a-f]{16}[.][A-Za-z0-9_-]+$";

  private static final String BASE64URL =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

  private final AesGcmSecretCipher cipher = new AesGcmSecretCipher(TestKeys.kat(), List.of());

  @ParameterizedTest
  @ValueSource(strings = {"plain-ascii-Password!1", "한글 비밀번호 😀 ü", ""})
  void roundTrips(String plaintext) {
    String stored = cipher.encrypt(plaintext, CONTEXT);

    assertStoredShape(stored);
    assertThat(cipher.decrypt(stored, CONTEXT)).isEqualTo(plaintext);
  }

  @Test
  void roundTripsTheLongestPlaintext() {
    String ascii = "a".repeat(4096);
    // 1365 자 x 3 바이트 + 1 = 4096 바이트
    String korean = "가".repeat(1365) + "a";
    assertThat(korean.getBytes(StandardCharsets.UTF_8)).hasSize(4096);

    for (String plaintext : List.of(ascii, korean)) {
      String stored = cipher.encrypt(plaintext, CONTEXT);
      assertStoredShape(stored);
      assertThat(Base64.getUrlDecoder().decode(stored.substring(21))).hasSize(12 + 4096 + 16);
      assertThat(cipher.decrypt(stored, CONTEXT)).isEqualTo(plaintext);
    }
  }

  @Test
  void refusesPlaintextLongerThan4096Utf8Bytes() {
    assertReason(() -> cipher.encrypt("a".repeat(4097), CONTEXT), Reason.PLAINTEXT_TOO_LONG);
    // 1366 자 x 3 바이트 = 4098 바이트 (문자 수는 4096 보다 적다)
    assertReason(() -> cipher.encrypt("가".repeat(1366), CONTEXT), Reason.PLAINTEXT_TOO_LONG);
  }

  @Test
  void refusesPlaintextThatIsNotValidUnicode() {
    assertThatThrownBy(() -> cipher.encrypt("pw\uDC00", CONTEXT))
        .isInstanceOf(IllegalArgumentException.class)
        .message()
        .doesNotContain("pw");
  }

  @Test
  void knownAnswer() {
    AesGcmSecretCipher fixed =
        new AesGcmSecretCipher(
            TestKeys.kat(),
            List.of(),
            nonce -> System.arraycopy(HexFormat.of().parseHex(KAT_NONCE_HEX), 0, nonce, 0, 12));

    assertThat(fixed.encrypt(KAT_PLAINTEXT, CONTEXT)).isEqualTo(KAT_STORED);
    assertThat(cipher.decrypt(KAT_STORED, CONTEXT)).isEqualTo(KAT_PLAINTEXT);
    assertThat(fixed.currentKid()).isEqualTo(TestKeys.KAT_KID);
  }

  @Test
  void everySingleCharacterChangeOfTheBodyIsRefused() {
    int bodyStart = KAT_STORED.lastIndexOf('.') + 1;
    Set<Reason> seen = EnumSet.noneOf(Reason.class);
    int attempts = 0;
    for (int i = bodyStart; i < KAT_STORED.length(); i++) {
      for (char replacement : BASE64URL.toCharArray()) {
        if (replacement == KAT_STORED.charAt(i)) {
          continue;
        }
        String tampered = replaceAt(KAT_STORED, i, replacement);
        seen.add(
            refusedReason(tampered, EnumSet.of(Reason.AUTHENTICATION_FAILED, Reason.MALFORMED)));
        attempts++;
      }
    }
    assertThat(attempts).isEqualTo((KAT_STORED.length() - bodyStart) * 63);
    assertThat(seen).contains(Reason.AUTHENTICATION_FAILED, Reason.MALFORMED);
  }

  @Test
  void everySingleCharacterChangeOfVersionKidOrSeparatorIsRefused() {
    String alphabet = BASE64URL + ".=+/ ";
    int bodyStart = KAT_STORED.lastIndexOf('.') + 1;
    for (int i = 0; i < bodyStart; i++) {
      for (char replacement : alphabet.toCharArray()) {
        if (replacement == KAT_STORED.charAt(i)) {
          continue;
        }
        refusedReason(
            replaceAt(KAT_STORED, i, replacement),
            EnumSet.of(Reason.UNSUPPORTED_VERSION, Reason.MALFORMED, Reason.UNKNOWN_KEY));
      }
    }
    assertReason(
        () -> cipher.decrypt("qh2" + KAT_STORED.substring(3), CONTEXT), Reason.UNSUPPORTED_VERSION);
    assertReason(
        () -> cipher.decrypt(KAT_STORED.replace(".a4f1", ".b4f1"), CONTEXT), Reason.UNKNOWN_KEY);
  }

  @Test
  void changingTheKidToAnotherKeyOfTheRingFailsAuthentication() {
    // 머리말이 AAD 에 들어 있으므로, 키 ID 만 다른 키의 것으로 바꾼 값은 그 키로도 인증되지 않는다.
    AesGcmSecretCipher ring = new AesGcmSecretCipher(TestKeys.kat(), List.of(TestKeys.other()));
    String swapped = KAT_STORED.replace(TestKeys.KAT_KID, TestKeys.other().kid());

    assertReason(() -> ring.decrypt(swapped, CONTEXT), Reason.AUTHENTICATION_FAILED);
  }

  @Test
  void everyTruncationIsRefusedAndShortOnesAreMalformed() {
    int minimumLength = KAT_STORED.lastIndexOf('.') + 1 + 38;
    for (int length = 0; length < KAT_STORED.length(); length++) {
      String truncated = KAT_STORED.substring(0, length);
      if (length < minimumLength) {
        // 머리말 안에서 잘렸거나 본문이 28 바이트보다 짧다.
        assertReason(() -> cipher.decrypt(truncated, CONTEXT), Reason.MALFORMED);
      } else {
        // 본문에는 길이 필드가 없다: 여전히 28 바이트 이상의 정규 base64url 이면 GCM 태그가 거부한다.
        refusedReason(truncated, EnumSet.of(Reason.MALFORMED, Reason.AUTHENTICATION_FAILED));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"=", "==", " ", "\n", "\r\n", "\t", ".", ".x", "..", "\u0000", "가", "+", "/"})
  void extendedOrPaddedValuesAreMalformed(String suffix) {
    assertReason(() -> cipher.decrypt(KAT_STORED + suffix, CONTEXT), Reason.MALFORMED);
    assertReason(() -> cipher.decrypt(" " + KAT_STORED, CONTEXT), Reason.MALFORMED);
  }

  @Test
  void appendedBase64CharactersAreRefused() {
    for (char extra : BASE64URL.toCharArray()) {
      refusedReason(KAT_STORED + extra, EnumSet.of(Reason.MALFORMED, Reason.AUTHENTICATION_FAILED));
      refusedReason(
          KAT_STORED + extra + extra, EnumSet.of(Reason.MALFORMED, Reason.AUTHENTICATION_FAILED));
    }
  }

  @Test
  void whitespaceInsideAndStandardBase64AreMalformed() {
    int bodyStart = KAT_STORED.lastIndexOf('.') + 1;
    String body = KAT_STORED.substring(bodyStart);
    String header = KAT_STORED.substring(0, bodyStart);
    byte[] payload = Base64.getUrlDecoder().decode(body);

    assertReason(
        () -> cipher.decrypt(header + body.substring(0, 5) + " " + body.substring(5), CONTEXT),
        Reason.MALFORMED);
    assertReason(
        () -> cipher.decrypt(header + body.substring(0, 5) + "\n" + body.substring(5), CONTEXT),
        Reason.MALFORMED);
    String standard = Base64.getEncoder().encodeToString(payload);
    assertThat(standard).as("이 벡터는 표준 base64 의 + / 와 패딩을 쓴다").containsAnyOf("+", "/", "=");
    assertReason(() -> cipher.decrypt(header + standard, CONTEXT), Reason.MALFORMED);
  }

  @Test
  void nonceIsFreshForEveryEncryption() {
    Set<String> nonces = new HashSet<>();
    Set<String> stored = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      String value = cipher.encrypt("same plaintext", CONTEXT);
      stored.add(value);
      byte[] payload = Base64.getUrlDecoder().decode(value.substring(value.lastIndexOf('.') + 1));
      nonces.add(HexFormat.of().formatHex(payload, 0, 12));
    }
    assertThat(nonces).hasSize(10_000);
    assertThat(stored).hasSize(10_000);
  }

  @Test
  void aCipherWithoutTheStoredKidRefusesWithUnknownKey() {
    AesGcmSecretCipher otherKeyOnly = new AesGcmSecretCipher(TestKeys.other(), List.of());

    assertReason(() -> otherKeyOnly.decrypt(KAT_STORED, CONTEXT), Reason.UNKNOWN_KEY);
  }

  @Test
  void aDifferentContextFailsAuthentication() {
    String stored = cipher.encrypt("pw", CONTEXT);

    assertReason(
        () -> cipher.decrypt(stored, SecretContext.databaseConnectionPassword("conn-43")),
        Reason.AUTHENTICATION_FAILED);
    assertReason(
        () -> cipher.decrypt(stored, new SecretContext("other.purpose", "conn-42")),
        Reason.AUTHENTICATION_FAILED);
    assertThat(cipher.decrypt(stored, SecretContext.databaseConnectionPassword("conn-42")))
        .isEqualTo("pw");
  }

  @Test
  void rotationDecryptsOldValuesAndReencryptsWithTheCurrentKey() {
    MasterKey a = TestKeys.kat();
    MasterKey b = TestKeys.other();
    AesGcmSecretCipher underA = new AesGcmSecretCipher(a, List.of());
    AesGcmSecretCipher rotated = new AesGcmSecretCipher(b, List.of(a));
    String oldValue = underA.encrypt("rotate-me", CONTEXT);

    assertThat(rotated.decrypt(oldValue, CONTEXT)).isEqualTo("rotate-me");
    assertThat(rotated.needsReencryption(oldValue)).isTrue();

    String newValue = rotated.reencrypt(oldValue, CONTEXT);
    assertThat(newValue).startsWith("qh1." + b.kid() + ".");
    assertThat(rotated.needsReencryption(newValue)).isFalse();
    assertThat(rotated.decrypt(newValue, CONTEXT)).isEqualTo("rotate-me");
    assertThat(rotated.encrypt("fresh", CONTEXT)).startsWith("qh1." + b.kid() + ".");
    assertThat(rotated.currentKid()).isEqualTo(b.kid());
    // 이전 키를 뺀 암호기는 옛 값을 더 이상 풀지 못한다.
    assertReason(
        () -> new AesGcmSecretCipher(b, List.of()).decrypt(oldValue, CONTEXT), Reason.UNKNOWN_KEY);
    // reencrypt 도 문맥을 확인한다.
    assertReason(
        () -> rotated.reencrypt(oldValue, SecretContext.databaseConnectionPassword("x")),
        Reason.AUTHENTICATION_FAILED);
  }

  @Test
  void needsReencryptionRefusesMalformedValues() {
    assertReason(() -> cipher.needsReencryption("not-a-secret"), Reason.MALFORMED);
    assertReason(
        () -> cipher.needsReencryption("qh9" + KAT_STORED.substring(3)),
        Reason.UNSUPPORTED_VERSION);
  }

  @Test
  void duplicateKidsInTheRingAreRefused() {
    assertThatThrownBy(() -> new AesGcmSecretCipher(TestKeys.kat(), List.of(TestKeys.kat())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(TestKeys.KAT_KID);
    assertThatThrownBy(
            () ->
                new AesGcmSecretCipher(TestKeys.kat(), List.of(TestKeys.other(), TestKeys.other())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void toStringAndMessagesContainNoKeyPlaintextOrStoredValue() {
    AesGcmSecretCipher ring = new AesGcmSecretCipher(TestKeys.kat(), List.of(TestKeys.other()));
    String plaintext = "leak-check-plaintext-7f3a";
    String stored = ring.encrypt(plaintext, CONTEXT);
    List<String> texts = new ArrayList<>();
    texts.add(ring.toString());
    texts.add(TestKeys.kat().toString());
    texts.add(
        catchThrowableOfType(
                SecretCipherException.class,
                () -> ring.decrypt(stored, SecretContext.databaseConnectionPassword("x")))
            .toString());
    texts.add(
        catchThrowableOfType(
                SecretCipherException.class,
                () -> ring.decrypt(stored.substring(0, stored.length() - 3), CONTEXT))
            .toString());
    texts.add(
        catchThrowableOfType(
                SecretCipherException.class, () -> ring.encrypt(plaintext.repeat(200), CONTEXT))
            .toString());
    texts.add(
        catchThrowableOfType(
                IllegalArgumentException.class,
                () -> new AesGcmSecretCipher(TestKeys.kat(), List.of(TestKeys.kat())))
            .getMessage());

    assertThat(ring.toString())
        .isEqualTo(
            "AesGcmSecretCipher[current="
                + TestKeys.KAT_KID
                + ", decryptOnly=["
                + TestKeys.other().kid()
                + "]]");
    String body = stored.substring(stored.lastIndexOf('.') + 1);
    for (String text : texts) {
      MasterKeyTest.assertNoKeyHex(text, TestKeys.KAT_KEY_HEX);
      MasterKeyTest.assertNoKeyHex(text, TestKeys.OTHER_KEY_HEX);
      assertThat(text).doesNotContain(plaintext).doesNotContain(body).doesNotContain(stored);
    }
  }

  @Test
  void isSafeToUseFromManyThreads() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<Boolean>> jobs = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        int thread = t;
        jobs.add(
            () -> {
              for (int i = 0; i < 300; i++) {
                String plaintext = "pw-" + thread + "-" + i;
                SecretContext context = SecretContext.databaseConnectionPassword("c" + thread);
                if (!cipher
                    .decrypt(cipher.encrypt(plaintext, context), context)
                    .equals(plaintext)) {
                  return false;
                }
              }
              return true;
            });
      }
      for (Future<Boolean> result : pool.invokeAll(jobs, 60, TimeUnit.SECONDS)) {
        assertThat(result.get()).isTrue();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private Reason refusedReason(String stored, Set<Reason> allowed) {
    return refusedReason(cipher, stored, allowed);
  }

  private static Reason refusedReason(
      AesGcmSecretCipher cipher, String stored, Set<Reason> allowed) {
    SecretCipherException e =
        catchThrowableOfType(SecretCipherException.class, () -> cipher.decrypt(stored, CONTEXT));
    assertThat(e).as("평문을 돌려주면 안 된다: %s", stored).isNotNull();
    assertThat(allowed).as("%s", stored).contains(e.reason());
    assertThat(e.getMessage()).isEqualTo(e.reason().message());
    return e.reason();
  }

  private static void assertReason(ThrowingCallable call, Reason reason) {
    SecretCipherException e = catchThrowableOfType(SecretCipherException.class, call);
    assertThat(e).as("SecretCipherException 이어야 한다").isNotNull();
    assertThat(e.reason()).isEqualTo(reason);
    assertThat(e.getMessage()).isEqualTo(reason.message());
  }

  private static void assertStoredShape(String stored) {
    assertThat(stored).matches(STORED_PATTERN).startsWith("qh1." + TestKeys.KAT_KID + ".");
    byte[] payload = Base64.getUrlDecoder().decode(stored.substring(stored.lastIndexOf('.') + 1));
    assertThat(payload.length).isGreaterThanOrEqualTo(28);
  }

  private static String replaceAt(String text, int index, char replacement) {
    return text.substring(0, index) + replacement + text.substring(index + 1);
  }
}
