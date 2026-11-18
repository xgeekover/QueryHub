#!/usr/bin/env bash
# cargo test 출력 판정기.
#
# 사용법: cargo-test-check.sh [--require-lib] FILE
#
# FILE 은 cargo test 의 stdout+stderr 를 담은 파일이다. 테스트 바이너리마다 나오는 머리 줄("Running ...", "Doc-tests ...")과
# "test result:" 줄을 읽어 합산한다. 다음 중 하나라도 해당하면 0 이 아닌 값으로 끝난다:
#   [NO_RESULTS]         "test result:" 줄이 하나도 없다 (컴파일 실패, 빈 출력, 다른 명령의 출력)
#   [ZERO_TESTS]         passed 합계가 0 이다
#   [FAILURES]           failed 합계가 0 보다 크다, FAILED 결과가 있다, 또는 "error: test failed" 가 있다
#   [IGNORED]            ignored 합계가 0 보다 크다 (#[ignore] 로는 통과할 수 없다)
#   [FILTERED]           filtered out 합계가 0 보다 크다 (이름 필터로 일부만 실행했다)
#   [LIB_WITHOUT_TESTS]  --require-lib 일 때 "unittests src/lib.rs" 블록이 없거나 그 블록의 passed 가 0 이다
#   [INVALID]            파일을 읽을 수 없다, 해석할 수 없는 "test result:" 줄이 있다, 결과 줄이 없는 테스트 바이너리가 있다
#                        (바이너리가 비정상 종료), 또는 합산 결과가 음이 아닌 정수가 아니다
# 판정 전에 요약 줄(passed, failed, ignored, filtered, binaries, lib)을 출력한다. 사용법 오류는 2 로 끝난다.
#
# 이식성: bash 3.2 와 POSIX awk(macOS 의 BSD awk, Linux 의 mawk/gawk)만 쓴다. ANSI 색 코드는 지우고 읽는다.

set -euo pipefail

usage() {
  echo "usage: cargo-test-check.sh [--require-lib] FILE" >&2
  exit 2
}

require_lib=0
file=''
while [ $# -gt 0 ]; do
  case "$1" in
    --require-lib)
      require_lib=1
      shift
      ;;
    -*)
      usage
      ;;
    *)
      [ -z "$file" ] || usage
      file="$1"
      shift
      ;;
  esac
done
[ -n "$file" ] || usage

if [ ! -f "$file" ] || [ ! -r "$file" ]; then
  echo "cargo-test-check: [INVALID] cargo test 출력 파일을 읽을 수 없다: $file"
  exit 1
fi

set +e
parsed="$(LC_ALL=C awk '
  function header(name) {
    if (open) {
      missing++
      print "MISSING_RESULT: " current
    }
    current = name
    open = 1
    binaries++
    lib_block = (name ~ /^unittests src\/lib\.rs/)
    if (lib_block) {
      lib_found = 1
    }
  }
  function number(text) {
    gsub(/[^0-9]/, "", text)
    return text + 0
  }
  {
    gsub(/\033\[[0-9;]*[A-Za-z]/, "")
  }
  /^[ \t]*Running / {
    name = $0
    sub(/^[ \t]*Running /, "", name)
    header(name)
    next
  }
  /^[ \t]*Doc-tests / {
    name = $0
    sub(/^[ \t]*/, "", name)
    header(name)
    next
  }
  /^error: test failed/ {
    test_failed = 1
    next
  }
  /^test result:/ {
    results++
    n = split($0, part, ";")
    ok = (n >= 5 &&
      part[1] ~ /^test result: (ok|FAILED)\. [0-9]+ passed$/ &&
      part[2] ~ /^ [0-9]+ failed$/ &&
      part[3] ~ /^ [0-9]+ ignored$/ &&
      part[4] ~ /^ [0-9]+ measured$/ &&
      part[5] ~ /^ [0-9]+ filtered out$/)
    if (!ok) {
      malformed++
      print "MALFORMED: " $0
      next
    }
    if (part[1] ~ /FAILED/) {
      failed_result = 1
    }
    head = part[1]
    sub(/^test result: (ok|FAILED)\. /, "", head)
    p = number(head)
    passed += p
    failed += number(part[2])
    ignored += number(part[3])
    filtered += number(part[5])
    if (open && lib_block) {
      lib_passed += p
    }
    open = 0
    next
  }
  END {
    if (open) {
      missing++
      print "MISSING_RESULT: " current
    }
    printf "SUMMARY %d %d %d %d %d %d %d %d %d %d %d %d\n", results, passed, failed, ignored, filtered, binaries, lib_found, lib_passed, failed_result, malformed, missing, test_failed
  }
' "$file")"
awk_status=$?
set -e

if [ "$awk_status" -ne 0 ]; then
  echo "cargo-test-check: [INVALID] 출력을 해석하지 못했다 (awk exit $awk_status): $file"
  exit 1
fi

summary="$(printf '%s\n' "$parsed" | grep '^SUMMARY ' | tail -n 1 || true)"
read -r _ results passed failed ignored filtered binaries lib_found lib_passed failed_result malformed missing test_failed <<EOF
$summary
EOF
for value in "$results" "$passed" "$failed" "$ignored" "$filtered" "$binaries" "$lib_found" "$lib_passed" \
  "$failed_result" "$malformed" "$missing" "$test_failed"; do
  case "$value" in
    '' | *[!0-9]*)
      echo "cargo-test-check: [INVALID] 합산 결과가 음이 아닌 정수가 아니다: '$summary'"
      exit 1
      ;;
  esac
done

printf '%s\n' "$parsed" | grep -v '^SUMMARY ' | sed 's/^/  /' || true
echo "cargo-test-check: passed=$passed failed=$failed ignored=$ignored filtered=$filtered binaries=$binaries lib=$lib_passed (results=$results)"

problems=0
reject() {
  echo "cargo-test-check: [$1] $2"
  problems=$((problems + 1))
}

if [ "$malformed" -ne 0 ]; then
  reject INVALID "해석할 수 없는 test result 줄이 ${malformed}개 있다"
fi
if [ "$missing" -ne 0 ]; then
  reject INVALID "결과 줄 없이 끝난 테스트 바이너리가 ${missing}개 있다 (비정상 종료?)"
fi
if [ "$results" -eq 0 ]; then
  reject NO_RESULTS "test result 줄이 없다 (cargo test 가 실행되지 않았거나 컴파일에 실패했다)"
elif [ "$passed" -eq 0 ]; then
  reject ZERO_TESTS "통과한 테스트가 0 건이다"
fi
if [ "$failed" -ne 0 ] || [ "$failed_result" -ne 0 ] || [ "$test_failed" -ne 0 ]; then
  reject FAILURES "실패한 테스트가 있다 (failed=$failed)"
fi
if [ "$ignored" -ne 0 ]; then
  reject IGNORED "건너뛴(#[ignore]) 테스트가 ${ignored}개 있다"
fi
if [ "$filtered" -ne 0 ]; then
  reject FILTERED "필터로 빠진 테스트가 ${filtered}개 있다"
fi
if [ "$require_lib" -eq 1 ] && { [ "$lib_found" -eq 0 ] || [ "$lib_passed" -eq 0 ]; }; then
  reject LIB_WITHOUT_TESTS "unittests src/lib.rs 블록이 없거나 통과한 테스트가 0 건이다"
fi

if [ "$problems" -ne 0 ]; then
  exit 1
fi
echo "cargo-test-check: OK"
