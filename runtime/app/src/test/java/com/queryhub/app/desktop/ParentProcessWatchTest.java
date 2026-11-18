package com.queryhub.app.desktop;

import static com.queryhub.app.desktop.ParentProcessWatch.ENV_PARENT_PID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * AC-15: 부모 프로세스 감시. 부모 대신 도우미 프로세스(잠만 자는 Java 소스 프로그램)를 띄운다. Docker 없이 돈다.
 *
 * <p>종료 동작은 기록만 하는 함수로 바꿔 넣는다. 테스트는 System.exit 를 부르지 않고 실제 환경변수를 바꾸지 않는다(환경은 직접 만든 맵이다).
 */
class ParentProcessWatchTest {

  /** 도우미가 스스로 끝나기까지의 시간(초). 테스트가 끝나면 그 전에 지운다. */
  private static final int HELPER_LIFETIME_SECONDS = 120;

  @TempDir private Path tempDir;

  private final List<Process> helpers = new ArrayList<>();

  @AfterEach
  void destroyHelpers() throws InterruptedException {
    for (Process helper : helpers) {
      helper.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void exitActionRunsAfterTheParentEndsAndNotBefore() throws Exception {
    Process helper = startHelper();
    CountDownLatch exited = new CountDownLatch(1);
    AtomicLong reportedPid = new AtomicLong();

    Optional<ParentProcessWatch> watch =
        ParentProcessWatch.start(
            Map.of(ENV_PARENT_PID, String.valueOf(helper.pid())),
            pid -> {
              reportedPid.set(pid);
              exited.countDown();
            });

    assertThat(watch).hasValueSatisfying(w -> assertThat(w.parentPid()).isEqualTo(helper.pid()));
    assertThat(exited.await(1500, TimeUnit.MILLISECONDS))
        .as("부모가 살아 있는 동안에는 종료 동작을 부르지 않는다")
        .isFalse();
    assertThat(helper.isAlive()).isTrue();

    helper.toHandle().destroyForcibly();

    assertThat(exited.await(10, TimeUnit.SECONDS)).as("부모가 끝나면 10 초 안에 종료 동작을 부른다").isTrue();
    assertThat(reportedPid.get()).isEqualTo(helper.pid());
  }

  @Test
  void unsetVariableStartsNothing() {
    AtomicInteger calls = new AtomicInteger();

    Optional<ParentProcessWatch> watch =
        ParentProcessWatch.start(
            Map.of("QUERYHUB_OTHER", "1", "PATH", "/usr/bin"), pid -> calls.incrementAndGet());

    assertThat(watch).isEmpty();
    assertThat(calls).hasValue(0);
  }

  @ParameterizedTest(name = "[{index}] ''{0}''")
  @ValueSource(
      strings = {
        "abc",
        "0",
        "-5",
        "",
        "000",
        "+42",
        " 42",
        "42 ",
        "4.2",
        "0x2a",
        "9999999999999999999",
        "99999999999999999999"
      })
  void invalidValuesFailTheStartupPathNamingTheVariable(String value) {
    assertThatThrownBy(() -> ParentProcessWatch.start(Map.of(ENV_PARENT_PID, value), neverCalled()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ENV_PARENT_PID);
  }

  @Test
  void pidOfAProcessThatHasEndedFailsTheStartupPath() throws Exception {
    Process helper = startHelper();
    long pid = helper.pid();
    helper.destroyForcibly();
    assertThat(helper.waitFor(10, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(
            () ->
                ParentProcessWatch.start(
                    Map.of(ENV_PARENT_PID, String.valueOf(pid)), neverCalled()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ENV_PARENT_PID)
        .hasMessageContaining(String.valueOf(pid));
  }

  @Test
  void ownPidFailsTheStartupPath() {
    String own = String.valueOf(ProcessHandle.current().pid());

    assertThatThrownBy(() -> ParentProcessWatch.start(Map.of(ENV_PARENT_PID, own), neverCalled()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ENV_PARENT_PID);
  }

  private static LongConsumer neverCalled() {
    return pid -> {
      throw new AssertionError("종료 동작이 불리면 안 된다 (pid " + pid + ")");
    };
  }

  /** 잠만 자는 Java 프로그램을 소스 파일 모드로 띄운다(플랫폼과 무관하다). */
  private Process startHelper() throws IOException {
    Path source = tempDir.resolve("ParentHelper.java");
    if (!Files.exists(source)) {
      Files.writeString(
          source,
          "class ParentHelper { public static void main(String[] args) throws Exception {"
              + " Thread.sleep("
              + HELPER_LIFETIME_SECONDS
              + "_000L); } }",
          StandardCharsets.UTF_8);
    }
    Process helper =
        new ProcessBuilder(javaExecutable(), source.toString())
            .redirectErrorStream(true)
            .redirectOutput(tempDir.resolve("helper-" + helpers.size() + ".log").toFile())
            .start();
    helpers.add(helper);
    return helper;
  }

  /** 지금 JVM 의 java 실행 파일(Gradle toolchain 의 Java 21). */
  private static String javaExecutable() {
    return ProcessHandle.current()
        .info()
        .command()
        .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
  }
}
