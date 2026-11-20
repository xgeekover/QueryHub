#!/usr/bin/env bash
# 빌드 게이트: main, test, integrationTest 를 -Werror 로 컴파일하고 실행 JAR 을 만든다. Docker 는 필요 없다.
#
# build 태스크는 쓰지 않는다. build 는 테스트까지 실행해 테스트 슬롯을 차지한다. 테스트는 test.sh 와 integration.sh 의 몫이다.
# 이후 스택(desktop: pnpm, Rust: cargo)은 이 파일에 gate_step 줄을 더한다.
# desktop(P0-C): 필수 도구, desktop 존재 확인, 잠금 파일 그대로 설치, tsc --noEmit + vite build(dist/index.html 확인),
# cargo build --locked. desktop 이 없으면 건너뛰지 않고 실패한다.

# 범위(P0-D): 인자 없음은 모든 단계. '--desktop-only' 는 runtime 단계(assemble, boot jar)를 빼고 마지막 줄에 그 사실을 적는다.
# 그 밖의 인자는 어떤 도구도 실행하기 전에 사용법 오류로 끝난다.

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

gate_parse_scope build "$@"

BOOT_JAR="runtime/app/build/libs/queryhub-runtime.jar"

check_boot_jar() {
  if [ ! -s "$BOOT_JAR" ]; then
    echo "실행 JAR 이 없거나 비어 있다: $BOOT_JAR" >&2
    return 1
  fi
  echo "실행 JAR: $BOOT_JAR ($(wc -c <"$BOOT_JAR" | tr -d ' ') bytes)"
}

# 이전 빌드의 dist 로 통과하지 않도록 지우고 다시 만든다.
desktop_build() {
  rm -rf desktop/dist
  pnpm_desktop run build
}

check_dist_index() {
  if [ ! -s desktop/dist/index.html ]; then
    echo "desktop/dist/index.html 이 없거나 비어 있다" >&2
    return 1
  fi
  echo "desktop/dist/index.html ($(wc -c <desktop/dist/index.html | tr -d ' ') bytes)"
}

if gate_runtime_in_scope; then
  gate_step 'runtime: assemble testClasses integrationTestClasses' \
    gradle_runtime assemble testClasses integrationTestClasses
  gate_step 'runtime: boot jar' check_boot_jar
fi

gate_step 'desktop: 필수 도구 (node, pnpm, cargo)' require_tool node pnpm cargo
gate_step 'desktop: 존재 확인' require_desktop
gate_step 'desktop: pnpm install --frozen-lockfile' desktop_install
gate_step 'desktop: build (tsc --noEmit, vite build)' desktop_build
gate_step 'desktop: dist/index.html' check_dist_index
gate_step 'desktop: cargo build --locked' cargo_desktop build

gate_pass build
