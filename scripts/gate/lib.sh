#!/usr/bin/env bash
# 게이트 스크립트 공용 함수. 직접 실행하지 않고 각 게이트 스크립트가 source 한다.
#
# - bash 3.2(macOS 기본) 호환: 연관 배열, mapfile, ${var,,} 를 쓰지 않는다.
# - POSIX 도구만 쓴다(find, awk, grep, mktemp, date, wc, sed, sort). python3, jq 를 쓰지 않는다.
# - 어느 cwd 에서 실행해도 저장소 루트를 기준으로 동작한다.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$REPO_ROOT"

# runtime/ Gradle 빌드를 실행한다.
# - sh 로 실행하므로 새 clone 에서 gradlew 의 실행 비트가 없어도 동작한다.
# - --no-daemon: 게이트가 끝난 뒤 Gradle 데몬이 남지 않는다.
gradle_runtime() {
  sh runtime/gradlew -p runtime --no-daemon --console=plain "$@"
}

# 실패 메시지를 stderr 에 남기고 종료한다.
gate_fail() {
  echo "GATE FAIL: $*" >&2
  exit 1
}

# gate_step LABEL CMD...
# 시작 표시를 출력하고 명령을 실행한 뒤 걸린 시간을 출력한다. 명령이 실패하면 게이트를 실패시킨다.
gate_step() {
  local label="$1"
  shift
  local started status
  started=$(date +%s)
  printf '\n==> [gate] %s\n' "$label"
  set +e
  "$@"
  status=$?
  set -e
  printf '<== [gate] %s: exit %d (%ss)\n' "$label" "$status" "$(($(date +%s) - started))"
  if [ "$status" -ne 0 ]; then
    gate_fail "$label (exit $status)"
  fi
}

# runtime/ 아래에서 build.gradle 을 가진 모든 디렉터리(= Gradle 모듈)를 깊이에 관계없이 나열한다.
# runtime/build.gradle(루트 프로젝트)은 제외하고, 모든 깊이의 build/, .gradle/ 디렉터리 안으로는 들어가지 않는다(prune).
# 새 모듈은 자동으로 포함되고, 그러면 테스트가 있어야 한다.
# settings.gradle 에 include 된 프로젝트는 모두 runtime/<이름>/build.gradle 을 가져야 한다. runtime/build.gradle 의
# 모듈 규칙이 이것을 강제하므로(없으면 빌드가 실패한다) 이 목록에서 빠지는 프로젝트가 없다.
# find 가 실패하면(예: 읽을 수 없는 디렉터리) 0 이 아닌 값으로 끝난다(pipefail). 호출하는 쪽이 그것을 게이트 실패로 다룬다.
runtime_module_dirs() {
  find runtime \( -type d \( -name build -o -name .gradle \) -prune \) -o \
    \( -type f -name build.gradle ! -path runtime/build.gradle -print \) |
    sed 's#/build\.gradle$##' |
    LC_ALL=C sort
}

# 통합 테스트 스위트가 있는 runtime 모듈(= runtime_module_dirs 중 src/integrationTest 디렉터리가 있는 모듈)을 나열한다.
# 모듈 탐색은 runtime_module_dirs 를 그대로 쓴다(build/, .gradle/ 은 건너뛴다). 모듈 탐색이 실패하면 0 이 아닌 값으로 끝난다.
# 출력이 비어 있을 수 있다. 비어 있으면 실패로 다루는 것은 호출하는 쪽(integration.sh)의 몫이다.
integration_module_dirs() {
  local modules module
  if ! modules="$(runtime_module_dirs)"; then
    return 1
  fi
  while IFS= read -r module; do
    [ -n "$module" ] || continue
    if [ -d "$module/src/integrationTest" ]; then
      echo "$module"
    fi
  done <<EOF
$modules
EOF
}

# run_with_timeout SECONDS CMD...
# 명령을 실행하고 SECONDS 초 안에 끝나지 않으면 TERM(이어서 KILL)으로 멈추고 124 로 끝난다. 그 밖에는 명령의 종료 코드를 돌려준다.
# macOS 에는 timeout(1) 이 없으므로 bash 3.2 의 백그라운드 작업과 1 초 간격의 확인으로 구현한다. 감시용 자식 프로세스를 따로 두지
# 않으므로 끝난 뒤 남는 프로세스가 없다.
run_with_timeout() {
  local seconds="$1"
  shift
  local pid waited=0 status=0
  "$@" &
  pid=$!
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$waited" -ge "$seconds" ]; then
      kill -TERM "$pid" 2>/dev/null || true
      sleep 1
      kill -KILL "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      echo "시간 초과(${seconds}s): $*" >&2
      return 124
    fi
    sleep 1
    waited=$((waited + 1))
  done
  wait "$pid" || status=$?
  return "$status"
}

# ---- desktop (P0-C): pnpm 프로젝트 desktop/ 와 Tauri 크레이트 desktop/src-tauri/ ----

DESKTOP_MANIFEST="desktop/src-tauri/Cargo.toml"

# require_tool NAME...
# 도구가 PATH 에 있는지 확인하고 버전을 출력한다. 하나라도 없으면 0 이 아닌 값으로 끝난다(게이트는 건너뛰지 않고 실패한다).
require_tool() {
  local tool status=0
  for tool in "$@"; do
    if command -v "$tool" >/dev/null 2>&1; then
      printf '  %-6s %s\n' "$tool" "$("$tool" --version 2>&1 | head -n 1)"
    else
      echo "  $tool 이 없다 (PATH 에서 찾지 못했다)" >&2
      status=1
    fi
  done
  return "$status"
}

# desktop 이 있는지 확인한다. 없거나 불완전하면 실패한다(desktop 단계를 건너뛰어 녹색이 되는 일을 막는다).
require_desktop() {
  local path status=0
  for path in desktop/package.json desktop/pnpm-lock.yaml "$DESKTOP_MANIFEST" desktop/src-tauri/Cargo.lock; do
    if [ ! -f "$path" ]; then
      echo "desktop 이 없거나 불완전하다: $path 가 없다. desktop 단계는 건너뛰지 않고 실패한다." >&2
      status=1
    fi
  done
  return "$status"
}

# pnpm_desktop ARGS...: desktop/ 에서 pnpm 을 실행한다.
pnpm_desktop() {
  pnpm --dir desktop "$@"
}

# cargo_desktop SUBCOMMAND ARGS...: desktop/src-tauri 크레이트에 cargo 를 --locked 로 실행한다(잠금 파일을 바꾸지 않는다).
cargo_desktop() {
  local subcommand="$1"
  shift
  cargo "$subcommand" --manifest-path "$DESKTOP_MANIFEST" --locked "$@"
}

# 잠금 파일 그대로 의존성을 설치한다. package.json 과 pnpm-lock.yaml 이 어긋나면 실패한다.
desktop_install() {
  if [ ! -f desktop/package.json ]; then
    echo "desktop/package.json 이 없다: 설치할 수 없다" >&2
    return 1
  fi
  pnpm --dir desktop install --frozen-lockfile
}
