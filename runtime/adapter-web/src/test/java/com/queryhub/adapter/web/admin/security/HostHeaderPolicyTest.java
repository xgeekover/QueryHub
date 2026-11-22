package com.queryhub.adapter.web.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** P1-03 F7, AC7: Host 허용 목록의 규칙(형식이 클래스로 정의되어 있는지 예시 밖의 값으로도 확인한다). */
class HostHeaderPolicyTest {

  private final HostHeaderPolicy builtIn = HostHeaderPolicy.of(List.of());

  @ParameterizedTest
  @ValueSource(
      strings = {
        "localhost",
        "LOCALHOST:18580",
        "LocalHost",
        "localhost:1",
        "localhost:65535",
        "localhost:1420",
        "127.0.0.1",
        "127.0.0.1:1420",
        "[::1]",
        "[::1]:18580"
      })
  void builtInNamesWithAnOptionalValidPortPass(String host) {
    assertThat(builtIn.allowsHostHeader(host)).as(host).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "evil.example",
        "evil.example:18580",
        "localhost.evil.example",
        "localhost.",
        "localhost..",
        ".localhost",
        "127.0.0.2",
        "127.1",
        "127.0.0.1.",
        "::1",
        "[::1%25lo0]",
        "[::1%lo0]",
        "[0:0:0:0:0:0:0:1]",
        "[::ffff:127.0.0.1]",
        "user@localhost",
        "user:pass@localhost",
        "localhost:abc",
        "localhost:0",
        "localhost:00000",
        "localhost:65536",
        "localhost:99999",
        "localhost:123456",
        "localhost:",
        "localhost:-1",
        "localhost:+80",
        " localhost",
        "localhost ",
        "localhost/x",
        "localhost?x",
        "localhost#x",
        "local_host",
        "*",
        "localhost,evil.example"
      })
  void everythingElseIsDenied(String host) {
    assertThat(builtIn.allowsHostHeader(host)).as("'%s'", host).isFalse();
  }

  @Test
  void requestNeedsExactlyOneHostHeaderAndAnAllowedServerName() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setServerName("localhost");
    assertThat(builtIn.allows(request)).as("Host 없음").isFalse();

    request.addHeader("Host", "localhost:18580");
    assertThat(builtIn.allows(request)).isTrue();

    request.addHeader("Host", "localhost:18580");
    assertThat(builtIn.allows(request)).as("Host 두 개").isFalse();
  }

  @Test
  void serverNameFromAnAbsoluteFormTargetMustAlsoBeAllowed() {
    // MockHttpServletRequest 는 서버 이름을 Host 헤더에서 꺼내므로, 컨테이너가 absolute-form 대상에서 정한 이름을 래퍼로 흉내 낸다.
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Host", "127.0.0.1");
    assertThat(builtIn.allows(withServerName(request, "evil.example"))).isFalse();
    assertThat(builtIn.allows(withServerName(request, "localhost.evil.example"))).isFalse();
    assertThat(builtIn.allows(withServerName(request, "::1")))
        .as("대괄호 없는 IPv6 서버 이름은 대괄호를 붙여 비교한다")
        .isTrue();
    assertThat(builtIn.allows(withServerName(request, "[::1]"))).isTrue();
    assertThat(builtIn.allows(withServerName(request, ""))).isFalse();
    assertThat(builtIn.allows(withServerName(request, null))).isFalse();
  }

  private static HttpServletRequest withServerName(
      HttpServletRequest request, @Nullable String serverName) {
    return new HttpServletRequestWrapper(request) {
      @Override
      public String getServerName() {
        return serverName;
      }
    };
  }

  @Test
  void configuredNamesAreAddedCaseInsensitively() {
    HostHeaderPolicy policy =
        HostHeaderPolicy.of(List.of("Admin.Example", "192.0.2.7", "[2001:db8::1]"));

    assertThat(policy.allowedNames())
        .containsExactly(
            "localhost", "127.0.0.1", "[::1]", "admin.example", "192.0.2.7", "[2001:db8::1]");
    assertThat(policy.allowsHostHeader("admin.example")).isTrue();
    assertThat(policy.allowsHostHeader("ADMIN.EXAMPLE:443")).isTrue();
    assertThat(policy.allowsHostHeader("[2001:DB8::1]:8080")).isTrue();
    assertThat(policy.allowsHostHeader("x.admin.example")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "*.example",
        "*",
        "host:80",
        "admin.example/path",
        "admin.example.",
        "user@admin.example",
        "::1",
        "[::1]:80",
        "[::1%lo0]",
        "[not-ipv6]",
        "[1.2.3.4]",
        "-admin.example",
        "admin-.example",
        "admin..example",
        "",
        " admin.example",
        "http://admin.example"
      })
  void invalidEntriesAreRejected(String entry) {
    assertThat(HostHeaderPolicy.isValidEntry(entry)).as("'%s'", entry).isFalse();
    assertThatThrownBy(() -> HostHeaderPolicy.of(List.of(entry)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void longestValidLabelIsAccepted() {
    assertThat(HostHeaderPolicy.isValidEntry("a".repeat(63) + ".example")).isTrue();
    assertThat(HostHeaderPolicy.isValidEntry("a".repeat(64) + ".example")).isFalse();
  }
}
