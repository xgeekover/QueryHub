package com.queryhub.domain.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** AC10: 비밀번호 타입은 어떤 문자열 표현에도 값을 드러내지 않고 값을 char[] 에만 둔다. */
class ConnectionPasswordTest {

  private static final String SECRET = "Pa55-w0rd-secret";

  /** 비밀번호를 구성 요소로 가진 레코드: 레코드가 만든 toString 도 값을 보이지 않아야 한다. */
  private record Holder(String name, ConnectionPassword password) {}

  @Test
  void noStringRepresentationContainsTheValue() {
    ConnectionPassword password = ConnectionPassword.of(SECRET.toCharArray());

    List<String> renderings =
        List.of(
            password.toString(),
            String.valueOf(password),
            "" + password,
            String.format("%s", password),
            Objects.toString(password),
            new Holder("conn", password).toString(),
            List.of(password).toString());

    assertThat(renderings)
        .allSatisfy(
            text ->
                assertThat(text)
                    .doesNotContain(SECRET)
                    .doesNotContain("Pa55")
                    .contains("ConnectionPassword[******]"));
    // 길이도 드러내지 않는다.
    assertThat(ConnectionPassword.of("x".toCharArray()).toString()).isEqualTo(password.toString());
  }

  @Test
  void valueLivesOnlyInACharArrayThatDestroyZeroes() throws Exception {
    List<Field> instanceFields =
        Arrays.stream(ConnectionPassword.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .toList();
    assertThat(instanceFields)
        .as("인스턴스 필드에 String 이 없다")
        .noneMatch(field -> field.getType() == String.class);
    Field valueField = ConnectionPassword.class.getDeclaredField("value");
    assertThat(valueField.getType()).isEqualTo(char[].class);
    valueField.setAccessible(true);

    char[] input = SECRET.toCharArray();
    ConnectionPassword password = ConnectionPassword.of(input);
    Arrays.fill(input, 'x');
    assertThat(new String(password.copyChars())).as("입력 배열을 복사해 둔다").isEqualTo(SECRET);

    char[] copy = password.copyChars();
    copy[0] = 'Z';
    assertThat(password.copyChars()[0]).as("copyChars 는 사본이다").isEqualTo('P');

    char[] stored = (char[]) valueField.get(password);
    ConnectionPassword independent = password.copy();
    password.destroy();
    assertThat(password.isDestroyed()).isTrue();
    assertThat(stored).containsOnly('\0');
    assertThatThrownBy(password::copyChars).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(password::copy).isInstanceOf(IllegalStateException.class);
    assertThat(new String(independent.copyChars())).isEqualTo(SECRET);
  }

  @Test
  void refusesEmptyNullAndOverlongValuesWithoutEchoingThem() {
    assertThatThrownBy(() -> ConnectionPassword.of(new char[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConnectionPassword.of(null)).isInstanceOf(NullPointerException.class);
    char[] tooLong = new char[ConnectionPassword.MAX_LENGTH + 1];
    Arrays.fill(tooLong, 's');
    assertThatThrownBy(() -> ConnectionPassword.of(tooLong))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("sss");
    assertThat(ConnectionPassword.of(new char[ConnectionPassword.MAX_LENGTH]).isDestroyed())
        .isFalse();
  }

  @Test
  void equalityIsIdentityNotContent() {
    ConnectionPassword first = ConnectionPassword.of(SECRET.toCharArray());
    ConnectionPassword second = ConnectionPassword.of(SECRET.toCharArray());
    assertThat(first).isNotEqualTo(second).isEqualTo(first);
  }
}
