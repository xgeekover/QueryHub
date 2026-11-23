package com.queryhub.app.config.admin;

import java.time.Duration;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;

/**
 * 관리 체인 자기 점검이 기동을 멈춘 뒤의 강제 종료 예약 (P1-03 4회차, C3-R1). 점검이 실패하면 refresh 가 웹 서버를 멈추고 빈을 정리한다. 그런데
 * 메타데이터 DB 가 멈춰 있으면 그 정리(Hikari 종료)가 오래 걸려 프로세스가 끝나지 않을 수 있다. 그래서 점검은 예외를 던지기 전에 이 예약을 건다: {@link
 * #HALT_AFTER} 가 지나도 JVM 이 살아 있으면 표준 오류에 한 줄을 남기고 {@code Runtime.halt}({@value #EXIT_HALTED}) 로
 * 끝낸다. 정리가 먼저 끝나면 JVM 이 평소대로 끝나고 데몬 스레드인 예약도 함께 사라진다.
 *
 * <p>예약은 {@code QueryHubApplication.main} 이 {@link #installForProcess()} 를 부른 프로세스에서만 건다. 테스트처럼
 * main 을 거치지 않고 컨텍스트를 띄우면 아무것도 하지 않는다(테스트 JVM 을 끝내지 않는다).
 */
public final class StartupFailureHalt {

  /** 기동 실패를 알린 뒤 이 시간이 지나도 JVM 이 살아 있으면 강제로 끝낸다. 평소의 정리는 1 초 안쪽이다. */
  static final Duration HALT_AFTER = Duration.ofSeconds(15);

  /** 강제로 끝낼 때의 종료 코드 (기동 실패와 같은 1). */
  static final int EXIT_HALTED = 1;

  /** 강제 종료 알림의 ASCII 표식. 로그 수집기와 사람이 로캘과 관계없이 찾을 수 있다. */
  static final String MARKER = "QUERYHUB_STARTUP_HALT";

  private static volatile @Nullable StartupFailureHalt process;

  private final Duration delay;
  private final IntConsumer halt;

  StartupFailureHalt(Duration delay, IntConsumer halt) {
    this.delay = delay;
    this.halt = halt;
  }

  /** 운영 프로세스에 예약 동작을 설치한다 ({@code QueryHubApplication.main} 만 부른다). */
  public static void installForProcess() {
    process = new StartupFailureHalt(HALT_AFTER, code -> Runtime.getRuntime().halt(code));
  }

  /** main 이 설치했으면 예약을 건다. 설치되지 않았으면(테스트) 아무것도 하지 않는다. */
  static void armIfInstalled() {
    StartupFailureHalt installed = process;
    if (installed != null) {
      installed.arm();
    }
  }

  /**
   * 강제 종료 직전에 표준 오류에 남기는 한 줄. ASCII 표식({@value #MARKER} exit=1 after=15s)으로 시작하므로 LANG=C 처럼 콘솔 인코딩이
   * 한글을 표현하지 못해도 원인을 알아볼 수 있다 (P1-04, C4-R1). 그 뒤에 기존 한국어 문장이 온다.
   */
  static String notice(Duration delay) {
    return MARKER
        + " exit="
        + EXIT_HALTED
        + " after="
        + delay.toSeconds()
        + "s "
        + "관리 체인 자기 점검이 기동을 멈춘 뒤 "
        + delay.toSeconds()
        + " 초 안에 종료하지 못해 런타임을 강제로 끝낸다 (Runtime.halt, 종료 코드 "
        + EXIT_HALTED
        + ").";
  }

  /** 지연 뒤 강제 종료를 부르는 데몬 스레드를 시작하고 돌려준다. */
  Thread arm() {
    Thread halter =
        new Thread(
            () -> {
              try {
                Thread.sleep(delay.toMillis());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              System.err.println(notice(delay));
              halt.accept(EXIT_HALTED);
            },
            "queryhub-startup-failure-halt");
    halter.setDaemon(true);
    halter.start();
    return halter;
  }
}
