package com.queryhub.app.config.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
  void theNoticeStartsWithTheAsciiMarkerForTheProcessDelay() {
    String notice = StartupFailureHalt.notice(StartupFailureHalt.HALT_AFTER);

    assertThat(notice).startsWith("QUERYHUB_STARTUP_HALT exit=1 after=15s ");
    assertThat(notice).as("기존 한국어 문장이 표식 뒤에 남는다").contains("Runtime.halt, 종료 코드 1");
    assertThat(notice.substring(0, notice.indexOf("s ") + 1).chars())
        .as("표식 부분은 ASCII 다")
        .allMatch(c -> c < 0x80);
  }

  @Test
  void theMarkerSurvivesAUsAsciiStderr() throws Exception {
    // P1-04 C4-R1: LANG=C 처럼 표준 오류가 US-ASCII 이면 한글은 '?' 가 된다. 표식은 그대로 읽혀야 한다.
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream original = System.err;
    CompletableFuture<Integer> halted = new CompletableFuture<>();
    System.setErr(new PrintStream(captured, true, StandardCharsets.US_ASCII));
    try {
      new StartupFailureHalt(Duration.ofMillis(50), halted::complete).arm();
      assertThat(halted.get(5, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      System.setErr(original);
    }

    String line = captured.toString(StandardCharsets.US_ASCII);
    assertThat(line).startsWith(StartupFailureHalt.MARKER + " exit=1 after=0s ");
    assertThat(line).as("한글은 US-ASCII 에서 깨진다(표식이 필요한 이유)").contains("?");
  }

  @Test
  void theProcessDelayIsBounded() {
    assertThat(StartupFailureHalt.HALT_AFTER)
        .isGreaterThanOrEqualTo(Duration.ofSeconds(5))
        .isLessThanOrEqualTo(Duration.ofSeconds(30));
  }
}
