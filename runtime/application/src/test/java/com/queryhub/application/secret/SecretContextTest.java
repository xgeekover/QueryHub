package com.queryhub.application.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** P1-04 F1, AC9: 비밀 값 문맥의 검사. */
class SecretContextTest {

  @ParameterizedTest
  @ValueSource(strings = {"a", "database_connection.password", "x9_.", "abcdefghij0123456789"})
  void acceptsPurposesMatchingThePattern(String purpose) {
    assertThat(new SecretContext(purpose, "1").purpose()).isEqualTo(purpose);
  }

  @Test
  void acceptsTheLongestPurpose() {
    String purpose = "a" + "b".repeat(63);
    assertThat(new SecretContext(purpose, "1").purpose()).hasSize(64);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "", "A", "1abc", "_abc", ".abc", "abc-def", "abc def", "abC", " abc", "abc\n", "가나다"
      })
  void refusesPurposesOutsideThePattern(String purpose) {
    assertThatThrownBy(() -> new SecretContext(purpose, "1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("purpose");
  }

  @Test
  void refusesAPurposeLongerThan64Characters() {
    String purpose = "a" + "b".repeat(64);
    assertThatThrownBy(() -> new SecretContext(purpose, "1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesNullParts() {
    assertThatThrownBy(() -> new SecretContext(null, "1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SecretContext("a", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recordIdIsOneTo256Utf8Bytes() {
    assertThat(new SecretContext("a", "x").recordId()).isEqualTo("x");
    assertThat(new SecretContext("a", "x".repeat(256)).recordId()).hasSize(256);
    // 한글 한 글자는 UTF-8 3 바이트: 85 자 = 255 바이트, 86 자 = 258 바이트.
    assertThat(new SecretContext("a", "가".repeat(85)).recordId()).hasSize(85);

    assertThatThrownBy(() -> new SecretContext("a", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recordId");
    assertThatThrownBy(() -> new SecretContext("a", "x".repeat(257)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recordId");
    assertThatThrownBy(() -> new SecretContext("a", "가".repeat(86)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesARecordIdThatIsNotValidUnicode() {
    assertThatThrownBy(() -> new SecretContext("a", "id-\uD800"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusalMessagesDoNotEchoTheValues() {
    String recordId = "record-id-marker-".repeat(20);
    assertThatThrownBy(() -> new SecretContext("purpose-marker", "1"))
        .message()
        .doesNotContain("purpose-marker");
    assertThatThrownBy(() -> new SecretContext("a", recordId))
        .message()
        .doesNotContain("record-id-marker");
  }

  @Test
  void databaseConnectionPasswordUsesTheConstantPurpose() {
    SecretContext context = SecretContext.databaseConnectionPassword("conn-1");
    assertThat(context.purpose())
        .isEqualTo(SecretContext.DATABASE_CONNECTION_PASSWORD)
        .isEqualTo("database_connection.password");
    assertThat(context.recordId()).isEqualTo("conn-1");
  }
}
