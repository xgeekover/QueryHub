#!/usr/bin/env bash
# 빌드 게이트: main 과 test 를 -Werror 로 컴파일하고 실행 JAR 을 만든다.
#
# build 태스크는 쓰지 않는다. build 는 테스트까지 실행해 테스트 슬롯을 차지한다. 테스트는 test.sh 의 몫이다.
# 이후 스택(desktop: pnpm, Rust: cargo)은 이 파일에 gate_step 줄을 더한다.

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

BOOT_JAR="runtime/app/build/libs/queryhub-runtime.jar"

check_boot_jar() {
  if [ ! -s "$BOOT_JAR" ]; then
    echo "실행 JAR 이 없거나 비어 있다: $BOOT_JAR" >&2
    return 1
  fi
  echo "실행 JAR: $BOOT_JAR ($(wc -c <"$BOOT_JAR" | tr -d ' ') bytes)"
}

gate_step 'runtime: assemble testClasses' gradle_runtime assemble testClasses
gate_step 'runtime: boot jar' check_boot_jar

echo "GATE PASS: build"
