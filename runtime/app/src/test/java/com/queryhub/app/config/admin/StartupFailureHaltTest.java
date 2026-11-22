package com.queryhub.app.config.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** P1-03 4회차 C3-R1: 자기 점검이 기동을 멈춘 뒤 정리가 멈춰도 정해진 시간 뒤에 종료 코드 1 로 끝내는 예약. 실제 halt 대신 기록한다. */
class StartupFailureHaltTest {

  @Test
  void armedHaltFiresWithExitCodeOneAfterTheDelay() throws Exception {
    CompletableFuture<Integer> halted = new CompletableFuture<>();
    long started = System.nanoTime();

    Thread halter = new StartupFailureHalt(Duration.ofMillis(400), halted::complete).arm();

    assertThat(halter.isDaemon()).as("JVM 이 먼저 끝나면 예약도 함께 사라져야 한다").isTrue();
    Thread.sleep(100);
    assertThat(halted).as("지연 전에는 끝내지 않는다").isNotDone();
    assertThat(halted.get(5, TimeUnit.SECONDS))
        .isEqualTo(StartupFailureHalt.EXIT_HALTED)
        .isEqualTo(1);
    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isGreaterThanOrEqualTo(Duration.ofMillis(400))
        .isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void theProcessDelayIsBounded() {
    assertThat(StartupFailureHalt.HALT_AFTER)
        .isGreaterThanOrEqualTo(Duration.ofSeconds(5))
        .isLessThanOrEqualTo(Duration.ofSeconds(30));
  }
}
