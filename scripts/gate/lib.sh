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

# ---- 게이트 범위 (P0-D): build.sh, test.sh, lint.sh 의 --desktop-only ----
#
# 범위는 명시한 인자로만 고른다(환경변수로는 고르지 않는다). 인자가 없으면 all 이고 모든 단계를 실행한다. 팩토리 게이트 명령에는
# 인자가 없다. desktop-only 는 runtime(Gradle) 단계만 빼고, 그 사실을 시작 안내, 실패 줄과 마지막 GATE PASS 줄에 적는다.
# CI 의 desktop 작업(macOS, Windows)처럼 Java 가 없는 곳에서 desktop 단계만 돌릴 때 쓴다.
GATE_SCOPE=all
GATE_DESKTOP_ONLY_NOTE='desktop-only, runtime(Gradle) 단계는 실행하지 않았다'

# gate_usage_error NAME USAGE ARGS...: 사용법 오류. 어떤 도구도 실행하기 전에 2 로 끝난다.
gate_usage_error() {
  local name="$1" usage="$2"
  shift 2
  echo "사용법: $usage" >&2
  echo "GATE FAIL: $name: 받을 수 없는 인자 ($#개: $*). 아무 단계도 실행하지 않았다." >&2
  exit 2
}

# gate_parse_scope NAME ARGS...: 인자 없음은 all, '--desktop-only' 하나는 desktop-only, 그 밖(모르는 인자, 둘 이상)은 사용법 오류.
gate_parse_scope() {
  local name="$1"
  shift
  GATE_SCOPE=all
  if [ "$#" -eq 0 ]; then
    return 0
  fi
  if [ "$#" -eq 1 ] && [ "$1" = --desktop-only ]; then
    GATE_SCOPE=desktop-only
    echo "게이트 범위: $GATE_DESKTOP_ONLY_NOTE (runtime 단계까지 실행하려면 인자 없이 실행한다)"
    return 0
  fi
  gate_usage_error "$name" "bash scripts/gate/$name.sh [--desktop-only]" "$@"
}

# gate_reject_args NAME ARGS...: 인자를 받지 않는 게이트(integration.sh). 인자가 하나라도 있으면 사용법 오류.
gate_reject_args() {
  local name="$1"
  shift
  if [ "$#" -ne 0 ]; then
    gate_usage_error "$name" "bash scripts/gate/$name.sh (인자를 받지 않는다)" "$@"
  fi
}

# runtime(Gradle) 단계를 실행하는 범위인가. all 일 때만 참이다.
gate_runtime_in_scope() {
  [ "$GATE_SCOPE" = all ]
}

# 실패 메시지를 stderr 에 남기고 종료한다. desktop-only 범위에서는 그 사실을 덧붙인다.
gate_fail() {
  if [ "$GATE_SCOPE" = desktop-only ]; then
    echo "GATE FAIL: $* [$GATE_DESKTOP_ONLY_NOTE]" >&2
  else
    echo "GATE FAIL: $*" >&2
  fi
  exit 1
}

# gate_pass NAME [SUMMARY]: 출력의 마지막 줄. all 범위의 형식은 P0-D 전과 같다("GATE PASS: NAME" 또는 "GATE PASS: NAME (SUMMARY)").
# desktop-only 범위는 "GATE PASS: NAME (desktop-only, runtime(Gradle) 단계는 실행하지 않았다[; SUMMARY])" 다.
gate_pass() {
  local name="$1" summary="${2:-}"
  if [ "$GATE_SCOPE" = desktop-only ]; then
    summary="$GATE_DESKTOP_ONLY_NOTE${summary:+; $summary}"
  fi
  if [ -n "$summary" ]; then
    echo "GATE PASS: $name ($summary)"
  else
    echo "GATE PASS: $name"
  fi
}

# gate_sha256: 표준 입력의 SHA-256(소문자 16진수)을 출력한다. sha256sum, 없으면 shasum -a 256 을 쓴다.
# 둘 다 없으면 건너뛰지 않고 실패한다(해시 검사를 생략해 녹색이 되는 일을 막는다).
gate_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{ print $1 }'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 | awk '{ print $1 }'
  else
    echo "sha256sum 과 shasum 이 모두 없다: SHA-256 을 계산할 수 없다 (건너뛰지 않고 실패한다)" >&2
    return 1
  fi
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

# ---- Docker 사전 점검 (integration.sh, P0B-R4) ----

DOCKER_PREFLIGHT_TIMEOUT_SECONDS=60

# docker_preflight: docker info 로 데몬이 응답하는지 본다. 제한 시간 안에 서버 버전을 돌려주지 않으면 실패한다.
# - 버전은 표준 출력에서만 읽는다. 표준 오류(경고 등)는 mktemp 파일에 따로 담아 실패할 때만 보여 준다. 그래서 경고가 버전으로
#   읽히지 않는다(P0B-R4: 전에는 2>&1 로 합쳐 읽었다).
# - 표준 출력은 숫자로 시작하는 버전 모양의 한 줄이어야 한다. 비었거나, 여러 줄이거나, 'WARNING: ...' 같은 줄이면 실패한다.
# - 본문은 하위 셸이다. 임시 파일은 그 하위 셸의 EXIT trap 이 지우고, 호출한 스크립트의 trap 은 바뀌지 않는다.
docker_preflight() (
  status=0
  if ! command -v docker >/dev/null 2>&1; then
    echo "Docker CLI(docker)를 찾지 못했다. 통합 테스트에는 Docker 가 필요하다." >&2
    exit 1
  fi
  err_file="$(mktemp "${TMPDIR:-/tmp}/queryhub-docker-preflight.XXXXXX")"
  trap 'rm -f "$err_file"' EXIT
  output="$(run_with_timeout "$DOCKER_PREFLIGHT_TIMEOUT_SECONDS" docker info --format '{{.ServerVersion}}' 2>"$err_file")" ||
    status=$?
  problem=''
  if [ "$status" -ne 0 ]; then
    problem="docker info exit $status"
  elif [ -z "$output" ]; then
    problem="docker info 가 표준 출력에 서버 버전을 내지 않았다"
  elif [ "$(printf '%s\n' "$output" | wc -l | tr -d ' ')" -ne 1 ]; then
    problem="docker info 의 표준 출력이 한 줄이 아니다"
  elif ! printf '%s\n' "$output" | grep -Eq '^[0-9][0-9A-Za-z.+_~-]*$'; then
    problem="docker info 의 표준 출력이 버전 모양(숫자로 시작하는 한 줄)이 아니다"
  fi
  if [ -n "$problem" ]; then
    printf '%s\n' "$output" | sed 's/^/    stdout | /' >&2
    sed 's/^/    stderr | /' "$err_file" >&2
    echo "Docker 데몬에 연결하지 못했다 ($problem). 통합 테스트에는 실행 중인 Docker 가 필요하다." >&2
    exit 1
  fi
  echo "Docker 서버 버전: $output"
)

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
