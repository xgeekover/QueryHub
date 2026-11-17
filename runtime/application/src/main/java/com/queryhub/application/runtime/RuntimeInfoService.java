package com.queryhub.application.runtime;

import com.queryhub.domain.runtime.RuntimeMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * {@link GetRuntimeInfoUseCase} 구현. 프레임워크에 의존하지 않는 순수 Java 다.
 *
 * <p>가동 시간은 주입받은 {@link Clock} 으로 계산한다. 그래서 테스트는 고정된 시계로 결과를 확정할 수 있다. 시계가 기동 시각보다 앞서 있으면(시계 조정 등)
 * 가동 시간은 0 으로 둔다.
 */
public final class RuntimeInfoService implements GetRuntimeInfoUseCase {

  private final String name;
  private final String version;
  private final RuntimeMode mode;
  private final Instant startedAt;
  private final Clock clock;

  public RuntimeInfoService(
      String name, String version, RuntimeMode mode, Instant startedAt, Clock clock) {
    this.name = requireText(name, "name");
    this.version = requireText(version, "version");
    this.mode = Objects.requireNonNull(mode, "mode");
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public RuntimeInfo getRuntimeInfo() {
    long uptimeSeconds = Math.max(0L, Duration.between(startedAt, clock.instant()).toSeconds());
    return new RuntimeInfo(name, version, mode, startedAt, uptimeSeconds);
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
