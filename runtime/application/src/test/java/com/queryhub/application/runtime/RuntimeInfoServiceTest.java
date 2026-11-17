package com.queryhub.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryhub.domain.runtime.RuntimeMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RuntimeInfoServiceTest {

  private static final Instant STARTED_AT = Instant.parse("2026-11-16T00:00:00Z");

  private static Clock clockAt(String instant) {
    return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
  }

  @Test
  void computesUptimeFromTheInjectedClock() {
    RuntimeInfoService service =
        new RuntimeInfoService(
            "queryhub-runtime",
            "0.1.0-SNAPSHOT",
            RuntimeMode.DESKTOP,
            STARTED_AT,
            clockAt("2026-11-16T00:01:30.900Z"));

    RuntimeInfo info = service.getRuntimeInfo();

    assertThat(info.name()).isEqualTo("queryhub-runtime");
    assertThat(info.version()).isEqualTo("0.1.0-SNAPSHOT");
    assertThat(info.mode()).isEqualTo(RuntimeMode.DESKTOP);
    assertThat(info.startedAt()).isEqualTo(STARTED_AT);
    // 초 미만은 버린다.
    assertThat(info.uptimeSeconds()).isEqualTo(90L);
  }

  @Test
  void uptimeIsZeroAtStartup() {
    RuntimeInfoService service =
        new RuntimeInfoService(
            "queryhub-runtime",
            "1.0.0",
            RuntimeMode.SERVER,
            STARTED_AT,
            Clock.fixed(STARTED_AT, ZoneOffset.UTC));

    assertThat(service.getRuntimeInfo().uptimeSeconds()).isZero();
    assertThat(service.getRuntimeInfo().mode()).isEqualTo(RuntimeMode.SERVER);
  }

  @Test
  void uptimeNeverGoesNegativeWhenTheClockIsBehindTheStartTime() {
    RuntimeInfoService service =
        new RuntimeInfoService(
            "queryhub-runtime",
            "1.0.0",
            RuntimeMode.DESKTOP,
            STARTED_AT,
            clockAt("2026-11-15T23:59:00Z"));

    assertThat(service.getRuntimeInfo().uptimeSeconds()).isZero();
  }

  @Test
  void rejectsNullOrBlankInputs() {
    Clock clock = Clock.fixed(STARTED_AT, ZoneOffset.UTC);

    assertThatThrownBy(
            () -> new RuntimeInfoService(null, "1.0.0", RuntimeMode.DESKTOP, STARTED_AT, clock))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(
            () -> new RuntimeInfoService(" ", "1.0.0", RuntimeMode.DESKTOP, STARTED_AT, clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(
            () -> new RuntimeInfoService("queryhub", "", RuntimeMode.DESKTOP, STARTED_AT, clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
    assertThatThrownBy(() -> new RuntimeInfoService("queryhub", "1.0.0", null, STARTED_AT, clock))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("mode");
    assertThatThrownBy(
            () -> new RuntimeInfoService("queryhub", "1.0.0", RuntimeMode.DESKTOP, null, clock))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("startedAt");
    assertThatThrownBy(
            () ->
                new RuntimeInfoService("queryhub", "1.0.0", RuntimeMode.DESKTOP, STARTED_AT, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("clock");
  }
}
