package com.queryhub.adapter.web.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** P1-03 AC11: SHA-256 + MessageDigest.isEqual 비교의 결과. 상수 시간 자체는 ArchUnit R9 가 구조로 검사한다. */
class AdminTokenVerifierTest {

  private static final String TOKEN = "unit-test-token-0123456789abcdef0123456789";

  private final AdminTokenVerifier verifier = new AdminTokenVerifier(TOKEN);

  @Test
  void equalTokenMatches() {
    assertThat(verifier.matches(new String(TOKEN.toCharArray()))).isTrue();
  }

  @Test
  void sameLengthDifferentTokenDoesNotMatch() {
    String other = TOKEN.substring(0, TOKEN.length() - 1) + "X";
    assertThat(other).hasSameSizeAs(TOKEN);
    assertThat(verifier.matches(other)).isFalse();
  }

  @Test
  void prefixLongerDifferentCaseEmptyAndNullDoNotMatch() {
    assertThat(verifier.matches(TOKEN.substring(0, TOKEN.length() - 1))).isFalse();
    assertThat(verifier.matches(TOKEN + "x")).isFalse();
    assertThat(verifier.matches(TOKEN.toUpperCase(java.util.Locale.ROOT))).isFalse();
    assertThat(verifier.matches(" " + TOKEN)).isFalse();
    assertThat(verifier.matches("")).isFalse();
    assertThat(verifier.matches(null)).isFalse();
  }

  @Test
  void toStringHidesTheToken() {
    assertThat(verifier.toString()).doesNotContain(TOKEN).doesNotContain("0123456789");
  }

  @Test
  void emptyConfiguredTokenIsRejected() {
    assertThatThrownBy(() -> new AdminTokenVerifier(""))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
