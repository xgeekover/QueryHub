#!/usr/bin/env bash
# 통합 테스트 게이트: 통합 테스트(runtime/<모듈>/src/integrationTest)를 실행하고 JUnit XML 로 결과를 판정한다. P1-06 부터 통합
# 테스트는 임시 데이터 디렉터리의 내장 SQLite Metadata 저장소와 실행 JAR 자식 프로세스로 돈다(MariaDB 와 Testcontainers 를 쓰지 않는다).
#
# Docker 가 필요하다. Docker 가 없거나 응답하지 않으면 건너뛰지 않고 실패한다(건너뛴 게이트로는 통과할 수 없다).
# P1-06 의 통합 테스트는 Docker 를 쓰지 않지만 사전 점검(3)은 남긴다: 다음 업무 DB Provider 작업의 테스트가 다시 Docker 를 쓴다.
# test.sh 와 같은 거짓 통과 방어를 쓴다: 실행된 테스트 0건, 실패·오류가 있는데 exit 0, 건너뛴 테스트로 녹색, 이전 실행의 오래된 결과.
# 단위 테스트(test 태스크)는 실행하지 않는다. 그것은 test.sh 의 몫이다.
# 순서:
#   1. selftest.sh 로 판정기(junit-check.sh)가 스스로를 증명한다. 실패하면 판정을 믿을 수 없으므로 중단한다.
#   2. 통합 테스트 스위트(src/integrationTest)가 있는 runtime 모듈을 찾는다. 하나도 없거나 runtime/app 이 없으면 중단한다.
#   3. Docker 사전 점검(docker info, 제한 시간 있음). 실패하면 Gradle 을 실행하기 전에 중단한다.
#   4. 신선도 표식 파일을 만든다(mktemp, trap 으로 삭제).
#   5. cleanIntegrationTest integrationTest --continue --no-build-cache: UP-TO-DATE 와 빌드 캐시(FROM-CACHE)로 실행 없이 결과가
#      생기는 일을 막는다.
#   6. 찾은 모든 모듈의 결과를 --require-each --newer 표식 으로 판정한다.
#   7. Gradle 종료 코드와 판정 결과를 합쳐 마지막에 결론과 걸린 시간을 출력한다.
#
# 이 스크립트는 컨테이너를 직접 만들거나 지우지 않는다. 통합 테스트가 남기는 임시 데이터 디렉터리는 시스템 임시 디렉터리 아래에 있다.

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# 인자를 받지 않는다(--desktop-only 도 거부한다). 통합 테스트는 runtime 단계뿐이라 범위를 고를 것이 없다. 어떤 도구도 실행하기 전에 끝난다.
gate_reject_args integration "$@"

REQUIRED_MODULE="runtime/app"
gate_started=$(date +%s)

# 3 의 docker_preflight 는 lib.sh 에 있다(표준 출력에서만 버전을 읽는다, P0B-R4). 제한 시간은 DOCKER_PREFLIGHT_TIMEOUT_SECONDS 다.

# 1. 판정기 자체 검증
gate_step 'gate scripts: junit-check self-test' bash scripts/gate/selftest.sh

# 2. 통합 테스트 스위트 탐색. 목록을 변수로 먼저 받아 탐색의 실패를 게이트 실패로 만든다(프로세스 치환은 실패를 숨긴다).
if ! suite_list="$(integration_module_dirs)"; then
  gate_fail "runtime 모듈 탐색(find)이 실패했다"
fi
result_dirs=()
has_required=0
while IFS= read -r module_dir; do
  [ -n "$module_dir" ] || continue
  echo "통합 테스트 스위트: $module_dir/src/integrationTest"
  result_dirs+=("$module_dir/build/test-results/integrationTest")
  if [ "$module_dir" = "$REQUIRED_MODULE" ]; then
    has_required=1
  fi
done <<EOF
$suite_list
EOF

if [ "${#result_dirs[@]}" -eq 0 ]; then
  gate_fail "통합 테스트 스위트(runtime/<모듈>/src/integrationTest)가 있는 모듈이 하나도 없다" \
    "(필수: $REQUIRED_MODULE 의 통합 테스트 스위트 $REQUIRED_MODULE/src/integrationTest 가 없다)"
fi
if [ "$has_required" -ne 1 ]; then
  gate_fail "$REQUIRED_MODULE 의 통합 테스트 스위트($REQUIRED_MODULE/src/integrationTest)가 없다"
fi

# 3. Docker 사전 점검 (건너뛰지 않는다)
gate_step "docker: preflight (docker info, 최대 ${DOCKER_PREFLIGHT_TIMEOUT_SECONDS}s)" docker_preflight

# 4. 신선도 표식: 이 파일보다 새 결과 XML 만 이번 실행의 결과로 인정한다.
MARKER="$(mktemp "${TMPDIR:-/tmp}/queryhub-integration-marker.XXXXXX")"
trap 'rm -f "$MARKER"' EXIT

# 5. 통합 테스트 실행. 실패해도 판정 요약을 출력하도록 종료 코드만 보관한다.
printf '\n==> [gate] %s\n' 'runtime: cleanIntegrationTest integrationTest --continue --no-build-cache'
started=$(date +%s)
set +e
gradle_runtime cleanIntegrationTest integrationTest --continue --no-build-cache
gradle_status=$?
set -e
printf '<== [gate] %s: exit %d (%ss)\n' 'runtime: cleanIntegrationTest integrationTest --continue --no-build-cache' \
  "$gradle_status" "$(($(date +%s) - started))"

# 6. 결과 판정
printf '\n==> [gate] %s\n' 'runtime: 통합 테스트 JUnit 결과 판정'
set +e
bash scripts/gate/junit-check.sh --require-each --newer "$MARKER" "${result_dirs[@]}"
check_status=$?
set -e

# 7. 결론 (출력의 마지막 줄)
elapsed=$(($(date +%s) - gate_started))
if [ "$gradle_status" -ne 0 ] || [ "$check_status" -ne 0 ]; then
  gate_fail "integration (gradle exit=$gradle_status, junit-check exit=$check_status, ${elapsed}s)"
fi
echo "GATE PASS: integration (gradle exit=0, junit-check exit=0, ${elapsed}s)"
