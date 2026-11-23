package com.queryhub.adapter.crypto;

import java.util.HexFormat;

/** 테스트 전용 마스터 키. NOT A SECRET: 누구나 볼 수 있는 고정 픽스처이고 운영에서 쓰지 않는다. */
final class TestKeys {

  /** 고정 값 검사(known-answer test)의 키: 0x00..0x1f. NOT A SECRET. */
  static final String KAT_KEY_HEX =
      "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

  /** openssl dgst -sha256 -mac HMAC -macopt hexkey:KAT_KEY_HEX 로 따로 계산한 HMAC 의 앞 8 바이트. */
  static final String KAT_KID = "a4f112edae0b1004";

  /** 두 번째 테스트 키(교체 검사의 이전 키). NOT A SECRET. */
  static final String OTHER_KEY_HEX =
      "5e1f0a7c3b9d24e6812f4c0d9a7b3e65c2d81f4a6b0e937d15c8a2f60b4e7d39";

  private TestKeys() {}

  static MasterKey kat() {
    return new MasterKey(HexFormat.of().parseHex(KAT_KEY_HEX));
  }

  static MasterKey other() {
    return new MasterKey(HexFormat.of().parseHex(OTHER_KEY_HEX));
  }
}
