#!/usr/bin/env bash
# 정적 분석 게이트: 게이트 스크립트 문법, Spotless 형식 검사, -Xlint:all -Werror 컴파일(main, test, integrationTest).
#
# check 태스크는 쓰지 않는다. check 는 테스트까지 실행해 테스트 슬롯을 차지한다.
# desktop(P0-C): 필수 도구, desktop 존재 확인, Tauri 버전 고정 검사, 잠금 파일 그대로 설치, tsc --noEmit,
# eslint --max-warnings 0, cargo fmt --check, cargo clippy --locked --all-targets -- -D warnings.
# Tauri 고정 검사는 파일만 읽으므로 설치보다 먼저 한다. 설치가 먼저면 package.json 의 범위 표기(^2.11.1)는 pnpm 의 잠금 파일
# 불일치 오류로만 보이고, 어느 항목이 고정을 어겼는지 말하지 못한다.

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

check_gate_script_syntax() {
  local script status=0
  for script in scripts/gate/*.sh; do
    if bash -n "$script"; then
      echo "  ok   $script"
    else
      echo "  FAIL $script"
      status=1
    fi
  done
  return "$status"
}

# package.json 의 "NAME": "VERSION" 에서 VERSION 을 모두 출력한다(dependencies 와 devDependencies).
npm_pin_values() {
  grep -E "^[[:space:]]*\"$1\"[[:space:]]*:" desktop/package.json |
    sed -E 's/^[^:]*:[[:space:]]*"([^"]*)".*$/\1/' || true
}

# Cargo.toml 의 NAME = "REQ" 또는 NAME = { version = "REQ", ... } 에서 REQ 를 출력한다(작은따옴표 문자열도 읽는다).
cargo_pin_value() {
  local line
  line="$(grep -E "^$1[[:space:]]*=" "$DESKTOP_MANIFEST" | head -n 1 || true)"
  case "$line" in
    '') ;;
    *'{'*) printf '%s\n' "$line" | sed -nE "s/.*version[[:space:]]*=[[:space:]]*[\"']([^\"']*)[\"'].*/\1/p" ;;
    *) printf '%s\n' "$line" | sed -nE "s/^[^=]*=[[:space:]]*[\"']([^\"']*)[\"'].*/\1/p" ;;
  esac
}

# Tauri 고정 검사(FR-19). Tauri CLI 는 major.minor 가 다른 크레이트와 npm 패키지를 거부하고, 새로 해석하면 하루 만에 2.12 가
# 섞인다. 그래서 npm 두 패키지는 정확한 2.11.x, Cargo.toml 은 tauri =2.11.5 와 tauri-build =2.6.3, Cargo.lock 의 tauri 는
# 2.11.5 하나이고 npm 패키지와 major.minor 가 같아야 한다. 실패 메시지는 어긴 항목을 말한다.
check_tauri_pins() {
  local status=0 name value values lock_versions lock_mm
  lock_versions="$(awk '/^name = /{n=$3} /^version = /{ if (n == "\"tauri\"") print $3 }' desktop/src-tauri/Cargo.lock |
    tr -d '"' || true)"
  if [ "$lock_versions" = 2.11.5 ]; then
    echo "  ok   desktop/src-tauri/Cargo.lock: tauri 2.11.5"
  else
    echo "  FAIL desktop/src-tauri/Cargo.lock: tauri 가 '$(printf '%s' "$lock_versions" | tr '\n' ' ')' 이다 (2.11.5 하나여야 한다)"
    status=1
  fi
  lock_mm="${lock_versions%.*}"
  for name in @tauri-apps/api @tauri-apps/cli; do
    values="$(npm_pin_values "$name")"
    if [ -z "$values" ]; then
      echo "  FAIL desktop/package.json: $name 이 없다"
      status=1
      continue
    fi
    while IFS= read -r value; do
      if ! printf '%s\n' "$value" | grep -Eq '^2\.11\.[0-9]+$'; then
        echo "  FAIL desktop/package.json: $name 이 \"$value\" 다 (정확한 2.11.x 여야 한다. ^, ~, 범위, 2.12 이상은 안 된다)"
        status=1
      elif [ "${value%.*}" != "$lock_mm" ]; then
        echo "  FAIL desktop/package.json: $name $value 의 major.minor 가 Cargo.lock 의 tauri $lock_versions 와 다르다"
        status=1
      else
        echo "  ok   desktop/package.json: $name $value"
      fi
    done <<EOF
$values
EOF
  done
  for name in tauri:=2.11.5 tauri-build:=2.6.3; do
    value="$(cargo_pin_value "${name%%:*}")"
    if [ "$value" = "${name#*:}" ]; then
      echo "  ok   $DESKTOP_MANIFEST: ${name%%:*} = \"$value\""
    else
      echo "  FAIL $DESKTOP_MANIFEST: ${name%%:*} 의 버전 요구가 \"$value\" 다 (\"${name#*:}\" 여야 한다)"
      status=1
    fi
  done
  return "$status"
}

gate_step 'gate scripts: bash -n' check_gate_script_syntax
gate_step 'runtime: spotlessCheck compileJava compileTestJava compileIntegrationTestJava' \
  gradle_runtime spotlessCheck compileJava compileTestJava compileIntegrationTestJava

gate_step 'desktop: 필수 도구 (node, pnpm, cargo)' require_tool node pnpm cargo
gate_step 'desktop: 존재 확인' require_desktop
gate_step 'desktop: Tauri 버전 고정 검사' check_tauri_pins
gate_step 'desktop: pnpm install --frozen-lockfile' desktop_install
gate_step 'desktop: typecheck (tsc --noEmit)' pnpm_desktop run typecheck
gate_step 'desktop: eslint --max-warnings 0' pnpm_desktop run lint
gate_step 'desktop: cargo fmt --check' cargo fmt --manifest-path "$DESKTOP_MANIFEST" --check
gate_step 'desktop: cargo clippy --locked --all-targets -- -D warnings' \
  cargo_desktop clippy --all-targets -- -D warnings

echo "GATE PASS: lint"
