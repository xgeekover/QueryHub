package com.queryhub.adapter.web.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** P1-03 F6, AC6: 소켓 원격 주소의 루프백 판단. DNS 를 찾지 않고, 해석할 수 없으면 거부한다. */
class LoopbackAddressesTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "127.0.0.1",
        "127.1.2.3",
        "127.255.255.255",
        "127.0.0.0",
        "::1",
        "0:0:0:0:0:0:0:1",
        "::ffff:127.0.0.1",
        "::ffff:127.9.8.7",
        "::FFFF:127.0.0.1",
        "::ffff:7f00:1"
      })
  void loopbackLiteralsPass(String address) {
    assertThat(LoopbackAddresses.isLoopback(address)).as(address).isTrue();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "192.0.2.10",
        "10.0.0.1",
        "128.0.0.1",
        "126.255.255.255",
        "0.0.0.0",
        "::ffff:192.0.2.10",
        "::ffff:128.0.0.1",
        "::127.0.0.1",
        "fe80::1",
        "fe80::1%lo0",
        "::1%lo0",
        "::",
        "::2",
        "not-an-ip",
        "localhost",
        "127.1",
        "127.0.1",
        "0177.0.0.1",
        "127.0.0.01",
        "0x7f.0.0.1",
        "2130706433",
        "127.0.0.1 ",
        " 127.0.0.1",
        "127.0.0.1.",
        "127.0.0.256",
        "[::1]",
        ".::1",
        "g::1",
        ":::1",
        "1::2::3"
      })
  void everythingElseIsDenied(String address) {
    assertThat(LoopbackAddresses.isLoopback(address)).as("'%s'", address).isFalse();
  }
}
