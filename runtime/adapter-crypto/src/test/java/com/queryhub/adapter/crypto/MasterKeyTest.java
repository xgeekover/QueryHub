package com.queryhub.adapter.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** P1-04 F2, AC6, AC11: 마스터 키의 길이와 약한 키 거부, 키 ID 유도, 방어적 복사, 키가 보이지 않는 toString. */
class MasterKeyTest {

  @Test
  void kidIsTheFirstEightBytesOfTheHmacInLowercaseHex() {
    // 기대값은 openssl 로 따로 계산했다 (TestKeys.KAT_KID).
    assertThat(TestKeys.kat().kid()).isEqualTo(TestKeys.KAT_KID).matches("[0-9a-f]{16}");
  }

  @Test
  void differentKeysHaveDifferentKids() {
    assertThat(TestKeys.kat().kid()).isNotEqualTo(TestKeys.other().kid());
    assertThat(TestKeys.other().kid()).isEqualTo(TestKeys.other().kid());
  }

  @Test
  void kidIsNotAPrefixOrPartOfTheKeyHex() {
    for (String keyHex : new String[] {TestKeys.KAT_KEY_HEX, TestKeys.OTHER_KEY_HEX}) {
      MasterKey key = new MasterKey(HexFormat.of().parseHex(keyHex));
      assertThat(keyHex).doesNotStartWith(key.kid()).doesNotContain(key.kid());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 16, 31, 33, 64})
  void refusesKeysThatAreNot32Bytes(int length) {
    byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) i;
    }
    assertThatThrownBy(() -> new MasterKey(bytes))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32");
  }

  @ParameterizedTest
  @ValueSource(ints = {0x00, 0x01, 0x7f, 0xff})
  void refusesKeysWhoseBytesAreAllIdentical(int value) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) value);
    assertThatThrownBy(() -> new MasterKey(bytes)).isInstanceOf(IllegalArgumentException.class);
    assertThat(MasterKey.allIdentical(bytes)).isTrue();
  }

  @Test
  void aKeyWithOneDifferentByteIsAccepted() {
    byte[] bytes = new byte[32];
    bytes[31] = 1;
    assertThat(MasterKey.allIdentical(bytes)).isFalse();
    assertThat(new MasterKey(bytes).kid()).hasSize(16);
  }

  @Test
  void keepsADefensiveCopy() {
    byte[] bytes = HexFormat.of().parseHex(TestKeys.KAT_KEY_HEX);
    MasterKey key = new MasterKey(bytes);
    Arrays.fill(bytes, (byte) 0);

    assertThat(key.kid()).isEqualTo(TestKeys.KAT_KID);
    assertThat(key.aesKey().getEncoded()).isEqualTo(HexFormat.of().parseHex(TestKeys.KAT_KEY_HEX));
    // 내보낸 AES 키를 바꿔도 보관한 키는 그대로다.
    Arrays.fill(key.aesKey().getEncoded(), (byte) 0);
    assertThat(key.aesKey().getEncoded()).isEqualTo(HexFormat.of().parseHex(TestKeys.KAT_KEY_HEX));
  }

  @Test
  void toStringShowsOnlyTheKid() {
    MasterKey key = TestKeys.kat();
    assertThat(key.toString()).isEqualTo("MasterKey[kid=" + TestKeys.KAT_KID + "]");
    assertNoKeyHex(key.toString(), TestKeys.KAT_KEY_HEX);
  }

  @Test
  void refusalMessagesDoNotContainTheKey() {
    byte[] shortKey = HexFormat.of().parseHex(TestKeys.KAT_KEY_HEX.substring(0, 62));
    assertThatThrownBy(() -> new MasterKey(shortKey))
        .satisfies(e -> assertNoKeyHex(e.getMessage(), TestKeys.KAT_KEY_HEX.substring(0, 62)));
  }

  static void assertNoKeyHex(String text, String keyHex) {
    assertThat(text)
        .doesNotContain(keyHex.toLowerCase(Locale.ROOT))
        .doesNotContain(keyHex.toUpperCase(Locale.ROOT));
  }
}
