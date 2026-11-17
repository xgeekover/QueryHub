#!/usr/bin/env bash
# 정적 분석 게이트: 게이트 스크립트 문법, Spotless 형식 검사, -Xlint:all -Werror 컴파일.
#
# check 태스크는 쓰지 않는다. check 는 테스트까지 실행해 테스트 슬롯을 차지한다.

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

gate_step 'gate scripts: bash -n' check_gate_script_syntax
gate_step 'runtime: spotlessCheck compileJava compileTestJava' \
  gradle_runtime spotlessCheck compileJava compileTestJava

echo "GATE PASS: lint"
