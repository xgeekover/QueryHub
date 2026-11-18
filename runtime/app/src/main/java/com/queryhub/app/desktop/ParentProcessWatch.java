package com.queryhub.app.desktop;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * 부모 프로세스 감시 (desktop). 런타임을 띄운 셸(Tauri 앱)이 끝나면 런타임도 끝낸다. 셸이 SIGKILL 로 죽거나 비정상 종료해 스스로 자식을 정리하지 못해도
 * 런타임 JVM 이 남지 않는다.
 *
 * <ul>
 *   <li>{@value #ENV_PARENT_PID} 가 없으면 아무것도 하지 않는다(server 모드, 손으로 {@code java -jar} 를 실행한 경우).
 *   <li>값이 있으면 0 보다 큰 10진수여야 하고, 그 프로세스가 살아 있어야 한다. 아니면 {@link IllegalStateException} 으로 기동을 멈춘다.
 *       메시지는 변수 이름을 말한다.
 *   <li>감시는 Spring 컨텍스트를 만들기 전에 시작한다({@code QueryHubApplication.main}). 그래서 기동이 멈춰 있는 동안(예: Hikari
 *       가 DB 를 10 초 기다리는 중) 셸이 끝나도 런타임이 끝난다.
 *   <li>JDK 는 자식이 아닌 프로세스를 길어야 5 초마다 확인하고, 시작 시각을 비교해 PID 재사용을 알아챈다(JDK 21
 *       ProcessHandleImpl.completion). 그래서 감지는 길어야 5 초쯤 늦다.
 *   <li>부모가 끝나면 INFO 한 줄을 남기고 종료 동작을 부른다. 운영 동작({@link #exitJvm()})은 System.exit 로 Boot 의 종료 훅이
 *       컨텍스트를 닫게 하고, 30 초 뒤에도 JVM 이 살아 있으면 Runtime.halt 로 끝낸다. 테스트는 종료 동작을 바꿔 넣는다.
 * </ul>
 */
public final class ParentProcessWatch {

  /** 감시할 부모 프로세스의 PID 를 담는 환경변수. 셸이 자기 PID 를 넣는다. */
  public static final String ENV_PARENT_PID = "QUERYHUB_DESKTOP_PARENT_PID";

  /** System.exit 뒤 이 시간이 지나도 JVM 이 끝나지 않으면 Runtime.halt 를 부른다. */
  static final Duration HALT_AFTER = Duration.ofSeconds(30);

  /** 부모가 끝나 정상 종료할 때의 종료 코드. */
  static final int EXIT_PARENT_GONE = 0;

  /** 종료 훅이 멈춰 강제로 끝낼 때의 종료 코드. */
  static final int EXIT_HALTED = 3;

  /** 0 보다 큰 10진수 (부호, 공백, 앞의 0 만으로 된 값은 아래에서 따로 거른다). */
  private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,19}");

  private final long parentPid;

  private ParentProcessWatch(long parentPid) {
    this.parentPid = parentPid;
  }

  /** 감시 중인 부모 프로세스의 PID. */
  public long parentPid() {
    return parentPid;
  }

  /**
   * 환경변수를 읽어 감시를 시작한다.
   *
   * @param environment 환경변수 (운영: {@code System.getenv()}, 테스트: 직접 만든 맵)
   * @param onParentExit 부모가 끝났을 때 부를 종료 동작. 인자는 부모 PID 다.
   * @return 변수가 없으면 빈 값(감시를 시작하지 않는다), 있으면 시작한 감시
   * @throws IllegalStateException 값이 0 보다 큰 10진수가 아니거나, 그 프로세스가 없거나 이미 끝났거나, 이 JVM 자신일 때. 메시지는
   *     {@value #ENV_PARENT_PID} 를 말한다.
   */
  public static Optional<ParentProcessWatch> start(
      Map<String, String> environment, LongConsumer onParentExit) {
    String value = environment.get(ENV_PARENT_PID);
    if (value == null) {
      return Optional.empty();
    }
    long pid = parsePid(value);
    if (pid == ProcessHandle.current().pid()) {
      throw new IllegalStateException(
          ENV_PARENT_PID + "=" + pid + " 는 이 런타임 자신의 PID 다. 셸(부모) 프로세스의 PID 를 넣어야 한다.");
    }
    Optional<ProcessHandle> parent = ProcessHandle.of(pid);
    if (parent.isEmpty() || !parent.get().isAlive()) {
      throw new IllegalStateException(
          ENV_PARENT_PID + "=" + pid + " 프로세스가 없거나 이미 끝났다. 런타임을 띄운 셸이 살아 있을 때만 기동한다.");
    }
    parent
        .get()
        .onExit()
        .thenRun(
            () -> {
              LoggerFactory.getLogger(ParentProcessWatch.class)
                  .info("부모 프로세스(PID {}, {})가 끝나 런타임을 종료한다.", pid, ENV_PARENT_PID);
              onParentExit.accept(pid);
            });
    return Optional.of(new ParentProcessWatch(pid));
  }

  /**
   * 운영 종료 동작: Runtime.halt 를 예약한 뒤 System.exit 를 부른다. System.exit 는 Boot 의 종료 훅(컨텍스트 닫기)이 끝날 때까지
   * 돌아오지 않는다. 예약 스레드는 데몬이므로 그 전에 JVM 이 끝나면 함께 사라진다.
   */
  public static LongConsumer exitJvm() {
    return pid -> {
      Thread halter =
          new Thread(
              () -> {
                try {
                  Thread.sleep(HALT_AFTER.toMillis());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                System.err.println(
                    "부모 프로세스가 끝난 뒤 "
                        + HALT_AFTER.toSeconds()
                        + " 초 안에 종료하지 못해 런타임을 강제로 끝낸다 (Runtime.halt).");
                Runtime.getRuntime().halt(EXIT_HALTED);
              },
              "queryhub-parent-watch-halt");
      halter.setDaemon(true);
      halter.start();
      System.exit(EXIT_PARENT_GONE);
    };
  }

  private static long parsePid(@Nullable String value) {
    if (value == null || !DECIMAL.matcher(value).matches()) {
      throw invalidValue();
    }
    long pid;
    try {
      pid = Long.parseLong(value);
    } catch (NumberFormatException tooLarge) {
      throw invalidValue();
    }
    if (pid <= 0) {
      throw invalidValue();
    }
    return pid;
  }

  private static IllegalStateException invalidValue() {
    // 값은 되풀이하지 않는다(제어 문자 등으로 출력을 어지럽히지 않게).
    return new IllegalStateException(
        ENV_PARENT_PID + " 는 0 보다 큰 10진수 프로세스 ID 여야 한다 (부호, 공백, 빈 값 불가).");
  }
}
