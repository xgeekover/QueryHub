package com.queryhub.adapter.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.queryhub.application.secret.SecretCipherException;
import com.queryhub.application.secret.SecretCipherException.Reason;
import com.queryhub.application.secret.SecretContext;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** P1-04 F3, AC6, AC7: 저장 형식 v1 의 머리말, AAD 인코딩, 파싱 규칙. */
class StoredSecretFormatTest {

  private static final String KID = TestKeys.KAT_KID;

  /** 28 바이트(최소 본문)를 패딩 없는 base64url 로 쓴 값. */
  private static final String MIN_BODY =
      Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[28]);

  @Test
  void headerIsVersionAndKid() {
    assertThat(StoredSecretFormat.header(KID)).isEqualTo("qh1.a4f112edae0b1004.");
  }

  @Test
  void aadIsTheHeaderFollowedByLengthPrefixedPurposeAndRecordId() {
    // 기대값은 형식 설명(F3)대로 따로 만든 바이트열이다: "qh1.<kid>." + be32(28) + purpose + be32(7) + "conn-42".
    byte[] aad = StoredSecretFormat.aad(KID, SecretContext.databaseConnectionPassword("conn-42"));
    assertThat(HexFormat.of().formatHex(aad))
        .isEqualTo(
            "7168312e613466313132656461653062313030342e"
                + "0000001c"
                + "64617461626173655f636f6e6e656374696f6e2e70617373776f7264"
                + "00000007"
                + "636f6e6e2d3432");
  }

  @Test
  void aadLengthPrefixesSeparatePurposeFromRecordId() {
    // 길이를 앞에 붙이므로 경계를 옮긴 두 문맥("ab"+"c" 와 "a"+"bc")의 AAD 가 다르다.
    assertThat(StoredSecretFormat.aad(KID, new SecretContext("ab", "c")))
        .isNotEqualTo(StoredSecretFormat.aad(KID, new SecretContext("a", "bc")));
  }

  @Test
  void aadCountsUtf8BytesOfTheRecordId() {
    byte[] aad = StoredSecretFormat.aad(KID, new SecretContext("a", "가"));
    String hex = HexFormat.of().formatHex(aad);
    assertThat(hex).endsWith("00000001" + "61" + "00000003" + "eab080");
  }

  @Test
  void parseReturnsKidAndPayload() {
    byte[] payload = new byte[30];
    payload[29] = 7;
    String stored = StoredSecretFormat.encode(KID, payload);

    StoredSecretFormat.Parsed parsed = StoredSecretFormat.parse(stored);

    assertThat(stored).matches("^qh1[.][0-9a-f]{16}[.][A-Za-z0-9_-]+$");
    assertThat(parsed.kid()).isEqualTo(KID);
    assertThat(parsed.payload()).isEqualTo(payload);
  }

  @ParameterizedTest
  @ValueSource(strings = {"qh2", "qh0", "qh10", "qh999999999"})
  void otherVersionsAreUnsupported(String version) {
    assertReason(version + "." + KID + "." + MIN_BODY, Reason.UNSUPPORTED_VERSION);
  }

  @ParameterizedTest
  @ValueSource(strings = {"QH1", "qh", "q1", "qh1a", "xh1", " qh1", "qh-1", "qh1000000000"})
  void unknownPrefixesAreMalformed(String version) {
    assertReason(version + "." + KID + "." + MIN_BODY, Reason.MALFORMED);
  }

  @Test
  void structuralErrorsAreMalformed() {
    assertReason(null, Reason.MALFORMED);
    assertReason("", Reason.MALFORMED);
    assertReason("qh1", Reason.MALFORMED);
    assertReason("qh1.", Reason.MALFORMED);
    assertReason("qh1." + KID, Reason.MALFORMED);
    assertReason("qh1." + KID + ".", Reason.MALFORMED);
    assertReason("qh1." + KID + "." + MIN_BODY + ".", Reason.MALFORMED);
    assertReason("qh1." + KID + "." + MIN_BODY + ".x", Reason.MALFORMED);
    assertReason("qh1.." + MIN_BODY, Reason.MALFORMED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "A4F112EDAE0B1004",
        "a4f112edae0b100",
        "a4f112edae0b10045",
        "a4f112edae0b100g",
        "a4f112edae0b100 "
      })
  void kidMustBeSixteenLowercaseHexDigits(String kid) {
    assertReason("qh1." + kid + "." + MIN_BODY, Reason.MALFORMED);
  }

  @Test
  void bodyMustBeUnpaddedCanonicalBase64UrlOfAtLeast28Bytes() {
    String prefix = "qh1." + KID + ".";
    assertReason(prefix + MIN_BODY + "=", Reason.MALFORMED);
    assertReason(prefix + MIN_BODY + "==", Reason.MALFORMED);
    assertReason(prefix + MIN_BODY + " ", Reason.MALFORMED);
    assertReason(prefix + MIN_BODY + "\n", Reason.MALFORMED);
    assertReason(
        prefix + MIN_BODY.substring(0, 10) + " " + MIN_BODY.substring(10), Reason.MALFORMED);
    assertReason(
        prefix + MIN_BODY.substring(0, 10) + "+" + MIN_BODY.substring(11), Reason.MALFORMED);
    assertReason(
        prefix + MIN_BODY.substring(0, 10) + "/" + MIN_BODY.substring(11), Reason.MALFORMED);
    assertReason(
        prefix + MIN_BODY.substring(0, 10) + "*" + MIN_BODY.substring(11), Reason.MALFORMED);
    // 27 바이트: 최소 본문보다 짧다.
    assertReason(
        prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[27]),
        Reason.MALFORMED);
    // 길이 나머지 1: base64 로 풀 수 없다.
    assertThat((MIN_BODY + "AAA").length() % 4).isEqualTo(1);
    assertReason(prefix + MIN_BODY + "AAA", Reason.MALFORMED);
  }

  @Test
  void nonCanonicalTrailingBitsAreMalformed() {
    // 28 바이트 = 38 자, 마지막 문자는 2 비트만 쓰고 4 비트는 버린다. "A" 대신 "B" 는 같은 바이트로 풀리는 비정규 인코딩이다.
    assertThat(MIN_BODY).hasSize(38).endsWith("A");
    String nonCanonical = MIN_BODY.substring(0, 37) + "B";
    assertThat(Base64.getUrlDecoder().decode(nonCanonical)).isEqualTo(new byte[28]);

    assertReason("qh1." + KID + "." + nonCanonical, Reason.MALFORMED);
  }

  @Test
  void bodiesLongerThanTheLargestPayloadAreMalformed() {
    byte[] largest = new byte[StoredSecretFormat.MAX_PAYLOAD_BYTES];
    StoredSecretFormat.parse(StoredSecretFormat.encode(KID, largest));

    assertReason(
        StoredSecretFormat.encode(KID, new byte[StoredSecretFormat.MAX_PAYLOAD_BYTES + 1]),
        Reason.MALFORMED);
  }

  static void assertReason(String stored, Reason reason) {
    SecretCipherException e =
        catchThrowableOfType(SecretCipherException.class, () -> StoredSecretFormat.parse(stored));
    assertThat(e).as("거부해야 한다: %s", stored).isNotNull();
    assertThat(e.reason()).as("%s", stored).isEqualTo(reason);
    assertThat(e.getMessage()).isEqualTo(reason.message());
  }
}
