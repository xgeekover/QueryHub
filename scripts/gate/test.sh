#!/usr/bin/env bash
# 테스트 게이트: 단위 테스트를 실제로 실행하고 JUnit XML 로 결과를 판정한다.
#
# 팩토리 게이트는 종료 코드만 본다. 그래서 이 스크립트가 거짓 통과를 직접 막는다:
#   - 실행된 테스트 0건, 실패·오류가 있는데 exit 0, 건너뛴 테스트로 녹색, 이전 실행의 오래된 결과.
# 순서:
#   1. selftest.sh 로 판정기(junit-check.sh)가 스스로를 증명한다. 실패하면 판정을 믿을 수 없으므로 중단한다.
#   2. runtime 모듈을 찾는다. 탐색이 실패하거나 모듈이 없으면 테스트를 실행하기 전에 중단한다.
#   3. 신선도 표식 파일을 만든다(mktemp, trap 으로 삭제).
#   4. cleanTest test --continue --no-build-cache: cleanTest 가 UP-TO-DATE 로 건너뛴 0건 실행을 막고,
#      --no-build-cache 가 빌드 캐시에서 결과를 가져오는(FROM-CACHE) 실행 없는 결과를 막는다.
#   5. 모든 runtime 모듈의 결과를 --require-each --newer 표식 으로 판정한다.
#   6. Gradle 종료 코드와 판정 결과를 합쳐 마지막에 결론을 출력한다.

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# 1. 판정기 자체 검증
gate_step 'gate scripts: junit-check self-test' bash scripts/gate/selftest.sh

# 2. 모듈 탐색: 발견된 모든 모듈에 실행된 테스트가 있어야 한다.
#    목록을 변수로 먼저 받아 find 의 실패를 게이트 실패로 만든다(프로세스 치환은 실패를 숨긴다).
if ! module_list="$(runtime_module_dirs)"; then
  gate_fail "runtime 모듈 탐색(find)이 실패했다"
fi
result_dirs=()
while IFS= read -r module_dir; do
  [ -n "$module_dir" ] || continue
  result_dirs+=("$module_dir/build/test-results/test")
done <<EOF
$module_list
EOF

if [ "${#result_dirs[@]}" -eq 0 ]; then
  gate_fail "runtime 아래에서 Gradle 모듈(build.gradle)을 찾지 못했다"
fi

# 3. 신선도 표식: 이 파일보다 새 결과 XML 만 이번 실행의 결과로 인정한다.
MARKER="$(mktemp "${TMPDIR:-/tmp}/queryhub-test-marker.XXXXXX")"
trap 'rm -f "$MARKER"' EXIT

# 4. 테스트 실행. 실패해도 판정 요약을 출력하도록 종료 코드만 보관한다.
#    --no-build-cache: 사용자 홈이나 CI 에서 빌드 캐시를 켜 두어도 Test 태스크를 캐시에서 복원하지 않고 실제로 실행한다.
printf '\n==> [gate] %s\n' 'runtime: cleanTest test --continue --no-build-cache'
started=$(date +%s)
set +e
gradle_runtime cleanTest test --continue --no-build-cache
gradle_status=$?
set -e
printf '<== [gate] %s: exit %d (%ss)\n' 'runtime: cleanTest test --continue --no-build-cache' "$gradle_status" "$(($(date +%s) - started))"

# 5. 결과 판정
printf '\n==> [gate] %s\n' 'runtime: JUnit 결과 판정'
set +e
bash scripts/gate/junit-check.sh --require-each --newer "$MARKER" "${result_dirs[@]}"
check_status=$?
set -e

# 6. 결론 (출력의 마지막 줄)
if [ "$gradle_status" -ne 0 ] || [ "$check_status" -ne 0 ]; then
  gate_fail "test (gradle exit=$gradle_status, junit-check exit=$check_status)"
fi
echo "GATE PASS: test (gradle exit=0, junit-check exit=0)"
