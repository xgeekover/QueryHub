package com.queryhub.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RuntimeModeTest {

  @Test
  void idsAreTheProfileNames() {
    assertThat(RuntimeMode.DESKTOP.id()).isEqualTo("desktop");
    assertThat(RuntimeMode.SERVER.id()).isEqualTo("server");
  }

  @Test
  void findsEveryModeByItsId() {
    for (RuntimeMode mode : RuntimeMode.values()) {
      assertThat(RuntimeMode.findById(mode.id())).contains(mode);
    }
  }

  @Test
  void unknownNullAndDifferentlyCasedIdsAreNotFound() {
    assertThat(RuntimeMode.findById("cloud")).isEmpty();
    assertThat(RuntimeMode.findById("")).isEmpty();
    assertThat(RuntimeMode.findById(null)).isEmpty();
    assertThat(RuntimeMode.findById("DESKTOP")).isEmpty();
  }

  @Test
  void idsAreUnique() {
    assertThat(Arrays.stream(RuntimeMode.values()).map(RuntimeMode::id)).doesNotHaveDuplicates();
  }
}
