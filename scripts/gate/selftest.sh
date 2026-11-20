#!/usr/bin/env bash
# junit-check.sh 자체 검증. test.sh 가 판정을 믿기 전에 먼저 실행한다.
#
# 임시 디렉터리(mktemp -d, trap 으로 삭제)에 JUnit XML 픽스처를 만들고, 거짓 통과 시나리오는 모두 거부되고
# 깨끗한 픽스처는 통과하는지 확인한다. 기대와 다른 결과가 하나라도 있으면 1 로 끝난다.
# 픽스처의 표준 출력(CDATA)에는 가짜 "<testsuite tests=99" 를 넣어 둔다. 판정기가 그것을 세면 (b) 가 통과해 버린다.
# 이어서 cargo-test-check.sh(P0-C), 게이트 범위 해석기와 docker_preflight(lib.sh), docs-check.sh, workflow-check.sh(P0-D)를
# 같은 방식으로 검증한다.
#
# 사용법: bash scripts/gate/selftest.sh [--no-skip]
#   인자 없음: 이 환경에서 만들 수 없는 시나리오(root 의 읽기 권한 시나리오)는 SKIP 으로 세고 나머지로 판정한다(P0-D 전과 같다).
#   --no-skip: SKIP 이 하나라도 있으면 실패한다(P0A-N1). CI 의 gates 작업이 쓴다. test.sh 와 integration.sh 는 인자 없이 부른다.

set -euo pipefail

NO_SKIP=0
if [ "$#" -eq 1 ] && [ "$1" = --no-skip ]; then
  NO_SKIP=1
elif [ "$#" -ne 0 ]; then
  echo "사용법: bash scripts/gate/selftest.sh [--no-skip]" >&2
  echo "selftest: FAIL (받을 수 없는 인자: $*)" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
CHECK="$SCRIPT_DIR/junit-check.sh"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/queryhub-junit-selftest.XXXXXX")"
# 읽기 권한을 뺀 픽스처가 있으므로 지우기 전에 권한을 되돌린다.
trap 'chmod -R u+rwX "$WORK" 2>/dev/null; rm -rf "$WORK"' EXIT

# write_xml DIR NAME TESTS SKIPPED FAILURES ERRORS
write_xml() {
  mkdir -p "$1"
  cat >"$1/TEST-$2.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="$2" tests="$3" skipped="$4" failures="$5" errors="$6" timestamp="2026-11-16T00:00:00.000Z" hostname="selftest" time="0.012">
  <properties/>
  <testcase name="sample()" classname="$2" time="0.012"/>
  <system-out><![CDATA[로그 속의 가짜 태그는 세지 않는다: <testsuite name="decoy" tests="99" skipped="0" failures="0" errors="0">
]]></system-out>
  <system-err><![CDATA[]]></system-err>
</testsuite>
EOF
}

# 신선도 기준: 기준 파일은 2001년, 오래된 결과는 2000년, 나머지 픽스처는 지금 시각이다.
MARKER="$WORK/marker"
: >"$MARKER"
touch -t 200101010000 "$MARKER"

mkdir -p "$WORK/a-empty"
write_xml "$WORK/b-zero" com.example.ZeroTest 0 0 0 0
write_xml "$WORK/c-failure" com.example.FailingTest 3 0 1 0
write_xml "$WORK/d-error" com.example.ErrorTest 3 0 0 1
write_xml "$WORK/e-skipped" com.example.SkippedTest 3 1 0 0
write_xml "$WORK/f-module1" com.example.FirstModuleTest 2 0 0 0
mkdir -p "$WORK/f-module2"
write_xml "$WORK/g-stale" com.example.StaleTest 2 0 0 0
touch -t 200001010000 "$WORK/g-stale/TEST-com.example.StaleTest.xml"
write_xml "$WORK/clean1" com.example.CleanTest 4 0 0 0
write_xml "$WORK/clean1" com.example.OtherCleanTest 1 0 0 0
write_xml "$WORK/clean2" com.example.SecondModuleTest 2 0 0 0

# 읽거나 해석할 수 없는 결과 파일(INVALID). 각 디렉터리에 깨끗한 XML 을 함께 두어, 문제 파일 하나만으로 거부되는지 본다.
# (i1) 읽기 권한이 없는 파일. 그 파일에는 skipped=1 이 있다: 0 건으로 더해지면 OK 가 되어 버린다.
write_xml "$WORK/i1-unreadable" com.example.GoodTest 2 0 0 0
write_xml "$WORK/i1-unreadable" com.example.HiddenSkipTest 1 1 0 0
chmod 000 "$WORK/i1-unreadable/TEST-com.example.HiddenSkipTest.xml"
# (i2) tests 속성이 없는 <testsuite
write_xml "$WORK/i2-no-tests-attr" com.example.GoodTest 2 0 0 0
cat >"$WORK/i2-no-tests-attr/TEST-com.example.NoTestsAttr.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.NoTestsAttr" skipped="0" failures="0" errors="0">
  <testcase name="sample()" classname="com.example.NoTestsAttr"/>
</testsuite>
EOF
# (i3) 닫히지 않은 <testsuite 시작 태그 (파일이 중간에 잘림)
write_xml "$WORK/i3-unterminated" com.example.GoodTest 2 0 0 0
cat >"$WORK/i3-unterminated/TEST-com.example.Truncated.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.Truncated" tests="2" skipped="0"
  failures="0" errors="0"
EOF
# (i4) XML 이 아닌 파일 (텍스트와 UTF-8 이 아닌 바이트)
write_xml "$WORK/i4-not-xml" com.example.GoodTest 2 0 0 0
printf 'not a junit report\n\377\376\001garbage\n' >"$WORK/i4-not-xml/TEST-x.xml"
# (i5) 일반 파일이 아닌 결과: 디렉터리와 깨진 심볼릭 링크
write_xml "$WORK/i5-not-regular" com.example.GoodTest 2 0 0 0
mkdir -p "$WORK/i5-not-regular/TEST-dir.xml"
write_xml "$WORK/i6-dangling-link" com.example.GoodTest 2 0 0 0
ln -s "$WORK/no-such-file.xml" "$WORK/i6-dangling-link/TEST-link.xml"

# 속성이 여러 줄에 걸친 <testsuite 시작 태그. (ml) 는 통과해야 하고, (ml-s) 는 뒤쪽 줄의 skipped 를 읽어 거부해야 한다.
# write_multiline DIR NAME SKIPPED
write_multiline() {
  mkdir -p "$1"
  cat >"$1/TEST-$2.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<testsuite
  name="$2"
  tests="3"
  skipped="$3"
  failures="0"
  errors="0"
  timestamp="2026-11-16T00:00:00.000Z">
  <testcase name="sample()" classname="$2" time="0.001"/>
</testsuite>
EOF
}
write_multiline "$WORK/ml-clean" com.example.MultiLineTest 0
write_multiline "$WORK/ml-skipped" com.example.MultiLineSkippedTest 1

unmet=0
skipped_scenarios=0

# expect WANT REASON LABEL ARGS...
#   WANT: pass(종료 코드 0) 또는 reject(0 이 아님). REASON: 출력에 있어야 하는 판정 코드(없으면 -).
expect() {
  local want="$1" reason="$2" label="$3"
  shift 3
  local output status verdict
  set +e
  output="$(bash "$CHECK" "$@" 2>&1)"
  status=$?
  set -e
  verdict=FAIL
  if [ "$want" = pass ] && [ "$status" -eq 0 ]; then
    verdict=PASS
  elif [ "$want" = reject ] && [ "$status" -ne 0 ]; then
    if [ "$reason" = - ] || printf '%s\n' "$output" | grep -q "\[$reason\]"; then
      verdict=PASS
    fi
  fi
  echo "$verdict $label: expected $want${reason:+ [$reason]}, exit $status"
  if [ "$verdict" != PASS ]; then
    printf '%s\n' "$output" | sed 's/^/    | /'
    unmet=$((unmet + 1))
  fi
}

expect reject NO_RESULTS '(a) 결과 디렉터리에 TEST-*.xml 없음' "$WORK/a-empty"
expect reject ZERO_TESTS '(b) tests=0 인 XML' "$WORK/b-zero"
expect reject FAILURES '(c) failures>0' "$WORK/c-failure"
expect reject ERRORS '(d) errors>0' "$WORK/d-error"
expect reject SKIPPED '(e) skipped>0' "$WORK/e-skipped"
expect pass - '(f0) --require-each 없이는 테스트 없는 모듈 디렉터리를 허용' "$WORK/f-module1" "$WORK/f-module2"
expect reject MODULE_WITHOUT_TESTS '(f) --require-each 에서 실행된 테스트가 없는 모듈' \
  --require-each "$WORK/f-module1" "$WORK/f-module2"
expect reject STALE '(g) --newer 기준보다 오래된 XML' --newer "$MARKER" "$WORK/g-stale"
expect reject - '(u1) 사용법 오류: DIR 없음' --require-each
expect reject - '(u2) 사용법 오류: --newer 기준 파일 없음' --newer "$WORK/no-such-marker" "$WORK/clean1"
expect pass - '(ok) 깨끗한 픽스처' --require-each --newer "$MARKER" "$WORK/clean1" "$WORK/clean2"

if [ -r "$WORK/i1-unreadable/TEST-com.example.HiddenSkipTest.xml" ]; then
  # root 는 권한과 관계없이 파일을 읽는다. 이 환경에서는 읽기 실패를 만들 수 없다((i5), (i6) 이 다른 읽기 실패를 검사한다).
  echo "SKIP (i1) 읽기 권한이 없는 결과 파일: 현재 사용자는 권한 000 인 파일도 읽을 수 있다 (root)"
  skipped_scenarios=$((skipped_scenarios + 1))
else
  expect reject INVALID '(i1) 읽기 권한이 없는 결과 파일' --require-each "$WORK/i1-unreadable"
fi
expect reject INVALID '(i2) tests 속성이 없는 <testsuite' --require-each "$WORK/i2-no-tests-attr"
expect reject INVALID '(i3) 닫히지 않은 <testsuite 태그' --require-each "$WORK/i3-unterminated"
expect reject INVALID '(i4) XML 이 아닌 TEST-x.xml' --require-each "$WORK/i4-not-xml"
expect reject INVALID '(i5) 디렉터리인 TEST-dir.xml' --require-each "$WORK/i5-not-regular"
expect reject INVALID '(i6) 깨진 심볼릭 링크인 TEST-link.xml' --require-each "$WORK/i6-dangling-link"
expect pass - '(ml) 속성이 여러 줄에 걸친 <testsuite' --require-each --newer "$MARKER" "$WORK/ml-clean"
expect reject SKIPPED '(ml-s) 여러 줄 태그의 뒤쪽 줄에 있는 skipped=1' --require-each "$WORK/ml-skipped"

# (i7) 형식이 틀린 tests 속성(빈 값, 숫자가 아님). awk 의 num() 이 -1 을 돌려 그 파일을 invalid 로 센다(x_invalid 분기).
# 각 디렉터리에 깨끗한 XML 을 함께 두어, 문제 파일이 0 건으로 더해져 OK 가 되지 않는지 본다(P0A-N1, P0-D).
write_xml "$WORK/i7-empty-tests" com.example.GoodTest 2 0 0 0
write_xml "$WORK/i7-empty-tests" com.example.EmptyTestsAttr '' 0 0 0
write_xml "$WORK/i7-word-tests" com.example.GoodTest 2 0 0 0
write_xml "$WORK/i7-word-tests" com.example.WordTestsAttr abc 0 0 0
expect reject INVALID '(i7a) tests="" 인 <testsuite' --require-each "$WORK/i7-empty-tests"
expect reject INVALID '(i7b) tests="abc" 인 <testsuite' --require-each "$WORK/i7-word-tests"

# (i8) 합계 형식 거부 분기(junit-check.sh 의 valid_counts, P0A-N1 의 둘째 부분). 진짜 awk 는 늘 음이 아닌 정수 6 개를 찍으므로
# 이 분기에 닿으려면 awk 의 출력이 틀려야 한다. PATH 맨 앞의 가짜 awk 가 count_xml 의 프로그램(suite_start 를 담은 것)에만
# SELFTEST_FAKE_COUNTS 를 한 줄로 찍고 0 으로 끝나며, 다른 호출은 진짜 awk 로 넘긴다. 거부 시나리오는 [INVALID] 와 함께
# valid_counts 분기에서만 나오는 '합계를 해석하지 못했다' 줄을 요구한다. (i8b) 의 앞자리 0 은 이 분기가 없으면 bash 산술이
# 05 를 5 로 읽어 통과해 버리는 값이다. (i8-ok) 는 같은 가짜 awk 로 올바른 합계가 통과하는지 본다(장치 자체의 검증).
REAL_AWK="$(command -v awk)"
FAKE_AWK_BIN="$WORK/fake-awk-bin"
mkdir -p "$FAKE_AWK_BIN"
cat >"$FAKE_AWK_BIN/awk" <<EOF
#!/bin/sh
case "\$1" in
  *suite_start*)
    printf '%s\n' "\$SELFTEST_FAKE_COUNTS"
    exit 0
    ;;
esac
exec "$REAL_AWK" "\$@"
EOF
chmod +x "$FAKE_AWK_BIN/awk"
write_xml "$WORK/i8-counts" com.example.CountsTest 5 0 0 0

# expect_counts WANT LABEL COUNTS: 가짜 awk 가 COUNTS 를 합계로 찍을 때 junit-check.sh 의 결과.
expect_counts() {
  local want="$1" label="$2" counts="$3"
  local output status verdict
  set +e
  output="$(SELFTEST_FAKE_COUNTS="$counts" PATH="$FAKE_AWK_BIN:$PATH" bash "$CHECK" --require-each "$WORK/i8-counts" 2>&1)"
  status=$?
  set -e
  verdict=FAIL
  if [ "$want" = pass ] && [ "$status" -eq 0 ]; then
    verdict=PASS
  elif [ "$want" = reject ] && [ "$status" -ne 0 ] &&
    printf '%s\n' "$output" | grep -q '\[INVALID\]' &&
    printf '%s\n' "$output" | grep -q '합계를 해석하지 못했다'; then
    verdict=PASS
  fi
  echo "$verdict $label: expected $want$([ "$want" = reject ] && echo ' [INVALID] (valid_counts)'), exit $status"
  if [ "$verdict" != PASS ]; then
    printf '%s\n' "$output" | sed 's/^/    | /'
    unmet=$((unmet + 1))
  fi
}

expect_counts pass '(i8-ok) 가짜 awk 의 올바른 합계 1 5 0 0 0 0' '1 5 0 0 0 0'
expect_counts reject '(i8a) 빈 합계 출력' ''
expect_counts reject '(i8b) 앞자리 0 이 있는 합계 1 05 0 0 0 0' '1 05 0 0 0 0'
expect_counts reject '(i8c) 숫자가 아닌 글자 1 x 0 0 0 0' '1 x 0 0 0 0'
expect_counts reject '(i8d) 값이 5 개뿐인 합계 1 5 0 0 0' '1 5 0 0 0'
expect_counts reject '(i8e) 음수 1 -5 0 0 0 0' '1 -5 0 0 0 0'
expect_counts reject '(i8f) 공백 두 칸 1  5 0 0 0 0' '1  5 0 0 0 0'

# ---- cargo-test-check.sh 자체 검증 (P0-C): cargo test 출력 픽스처 ----
# 깨끗한 다중 바이너리 출력(lib 에 테스트, main 과 doc-tests 는 0 건)은 통과하고, 거짓 통과 시나리오는 모두 거부되어야 한다.
CARGO_CHECK="$SCRIPT_DIR/cargo-test-check.sh"
CARGO_WORK="$WORK/cargo"
mkdir -p "$CARGO_WORK"

# cargo_result PASSED FAILED IGNORED FILTERED: cargo 의 test result 줄
cargo_result() {
  local verdict=ok
  [ "$2" -eq 0 ] || verdict=FAILED
  echo "test result: $verdict. $1 passed; $2 failed; $3 ignored; 0 measured; $4 filtered out; finished in 0.01s"
}

# write_cargo NAME LIB_RESULT_ARGS... : lib(4 개 인자), main(0 건), doc-tests(0 건) 블록이 있는 출력 파일
write_cargo() {
  local name="$1"
  shift
  {
    echo "   Compiling queryhub-desktop v0.1.0 (/x/desktop/src-tauri)"
    echo "    Finished \`test\` profile [unoptimized + debuginfo] target(s) in 1.00s"
    echo "     Running unittests src/lib.rs (target/debug/deps/queryhub_desktop_lib-0123)"
    echo ""
    echo "running $1 tests"
    echo "test runtime::tests::sample ... ok"
    echo ""
    cargo_result "$@"
    echo ""
    echo "     Running unittests src/main.rs (target/debug/deps/queryhub_desktop-4567)"
    echo ""
    echo "running 0 tests"
    echo ""
    cargo_result 0 0 0 0
    echo ""
    echo "   Doc-tests queryhub_desktop_lib"
    echo ""
    echo "running 0 tests"
    echo ""
    cargo_result 0 0 0 0
  } >"$CARGO_WORK/$name.txt"
}

write_cargo ok 3 0 0 0
write_cargo zero 0 0 0 0
write_cargo failures 2 1 0 0
echo "error: test failed, to rerun pass \`--lib\`" >>"$CARGO_WORK/failures.txt"
write_cargo ignored 2 0 1 0
write_cargo filtered 2 0 0 3
# lib 블록은 0 건이고 다른 바이너리(tests/it.rs)에만 테스트가 있다: --require-lib 이면 거부, 없으면 통과.
write_cargo lib-empty 0 0 0 0
{
  echo "     Running tests/it.rs (target/debug/deps/it-89ab)"
  echo ""
  echo "running 3 tests"
  echo ""
  cargo_result 3 0 0 0
} >>"$CARGO_WORK/lib-empty.txt"
# 컴파일 실패: test result 줄이 없다.
printf 'error[E0308]: mismatched types\nerror: could not compile `queryhub-desktop` (lib test) due to 1 previous error\n' \
  >"$CARGO_WORK/no-results.txt"
# 해석할 수 없는 test result 줄 (숫자가 아니다)
write_cargo malformed 3 0 0 0
echo "test result: ok. many passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s" \
  >>"$CARGO_WORK/malformed.txt"
# 결과 줄 없이 끝난 바이너리 (비정상 종료)
write_cargo crashed 3 0 0 0
printf '     Running tests/crash.rs (target/debug/deps/crash-cdef)\n\nrunning 2 tests\n' >>"$CARGO_WORK/crashed.txt"
# 색 코드가 섞인 깨끗한 출력도 통과한다.
esc="$(printf '\033')"
sed "s/Running/${esc}[1m${esc}[92mRunning${esc}[0m/" "$CARGO_WORK/ok.txt" >"$CARGO_WORK/colored.txt"
# 읽기 권한이 없는 파일
write_cargo unreadable 3 0 0 0
chmod 000 "$CARGO_WORK/unreadable.txt"

# expect_cargo WANT REASON LABEL ARGS...: expect 와 같지만 cargo-test-check.sh 를 실행한다.
expect_cargo() {
  local want="$1" reason="$2" label="$3"
  shift 3
  local output status verdict
  set +e
  output="$(bash "$CARGO_CHECK" "$@" 2>&1)"
  status=$?
  set -e
  verdict=FAIL
  if [ "$want" = pass ] && [ "$status" -eq 0 ]; then
    verdict=PASS
  elif [ "$want" = reject ] && [ "$status" -ne 0 ]; then
    if [ "$reason" = - ] || printf '%s\n' "$output" | grep -q "\[$reason\]"; then
      verdict=PASS
    fi
  fi
  echo "$verdict cargo $label: expected $want${reason:+ [$reason]}, exit $status"
  if [ "$verdict" != PASS ]; then
    printf '%s\n' "$output" | sed 's/^/    | /'
    unmet=$((unmet + 1))
  fi
}

expect_cargo pass - '(c-ok) 깨끗한 다중 바이너리 출력 (lib 3, main 0, doc-tests 0)' --require-lib "$CARGO_WORK/ok.txt"
expect_cargo pass - '(c-color) ANSI 색 코드가 섞인 깨끗한 출력' --require-lib "$CARGO_WORK/colored.txt"
expect_cargo reject NO_RESULTS '(c-a) test result 줄 없음 (컴파일 실패)' "$CARGO_WORK/no-results.txt"
expect_cargo reject ZERO_TESTS '(c-b) 모든 바이너리가 0 건' "$CARGO_WORK/zero.txt"
expect_cargo reject FAILURES '(c-c) failed>0 과 FAILED 결과' "$CARGO_WORK/failures.txt"
expect_cargo reject IGNORED '(c-d) ignored>0' "$CARGO_WORK/ignored.txt"
expect_cargo reject FILTERED '(c-e) filtered out>0' "$CARGO_WORK/filtered.txt"
expect_cargo pass - '(c-f0) --require-lib 없이는 lib 블록 0 건을 허용' "$CARGO_WORK/lib-empty.txt"
expect_cargo reject LIB_WITHOUT_TESTS '(c-f) --require-lib 에서 lib 블록 0 건' --require-lib "$CARGO_WORK/lib-empty.txt"
expect_cargo reject LIB_WITHOUT_TESTS '(c-f2) --require-lib 에서 lib 블록 없음' --require-lib "$CARGO_WORK/no-results.txt"
expect_cargo reject INVALID '(c-i1) 해석할 수 없는 test result 줄' "$CARGO_WORK/malformed.txt"
expect_cargo reject INVALID '(c-i2) 결과 줄 없이 끝난 바이너리' "$CARGO_WORK/crashed.txt"
expect_cargo reject INVALID '(c-i3) 없는 파일' "$CARGO_WORK/no-such-file.txt"
if [ -r "$CARGO_WORK/unreadable.txt" ]; then
  echo "SKIP cargo (c-i4) 읽기 권한이 없는 출력 파일: 현재 사용자는 권한 000 인 파일도 읽을 수 있다 (root)"
  skipped_scenarios=$((skipped_scenarios + 1))
else
  expect_cargo reject INVALID '(c-i4) 읽기 권한이 없는 출력 파일' "$CARGO_WORK/unreadable.txt"
fi
expect_cargo reject - '(c-u1) 사용법 오류: FILE 없음' --require-lib
expect_cargo reject - '(c-u2) 사용법 오류: 모르는 옵션' --strict "$CARGO_WORK/ok.txt"

# ---- P0-D: 게이트 범위 해석기, docker_preflight, docs-check.sh, workflow-check.sh ----
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"

# expect_run WANT TEXT LABEL CMD...: 명령을 실행한다. WANT 는 pass(종료 코드 0) 또는 reject(0 이 아님),
#   TEXT 는 출력(stdout+stderr)에 있어야 하는 고정 문자열이다(없으면 -).
expect_run() {
  local want="$1" text="$2" label="$3"
  shift 3
  local output status verdict
  set +e
  output="$("$@" 2>&1)"
  status=$?
  set -e
  verdict=FAIL
  if { [ "$want" = pass ] && [ "$status" -eq 0 ]; } || { [ "$want" = reject ] && [ "$status" -ne 0 ]; }; then
    case "$output" in
      *"$text"*) verdict=PASS ;;
    esac
    if [ "$text" = - ]; then
      verdict=PASS
    fi
  fi
  echo "$verdict $label: expected $want [$text], exit $status"
  if [ "$verdict" != PASS ]; then
    printf '%s\n' "$output" | sed 's/^/    | /'
    unmet=$((unmet + 1))
  fi
}

# (s) 게이트 범위 해석기(lib.sh gate_parse_scope, gate_reject_args). 하위 셸에서 lib.sh 를 읽는다.
scope_of() (
  . "$SCRIPT_DIR/lib.sh"
  gate_parse_scope selftest "$@"
  echo "scope=$GATE_SCOPE"
)
no_args_of() (
  . "$SCRIPT_DIR/lib.sh"
  gate_reject_args selftest "$@"
  echo "no-args=ok"
)
expect_run pass 'scope=all' '(s1) 범위: 인자 없음은 all' scope_of
expect_run pass 'scope=desktop-only' '(s2) 범위: --desktop-only 는 desktop-only' scope_of --desktop-only
expect_run reject '받을 수 없는 인자' '(s3) 범위: 모르는 인자 --bogus' scope_of --bogus
expect_run reject '받을 수 없는 인자' '(s4) 범위: --desktop-only 뒤의 추가 인자' scope_of --desktop-only extra
expect_run reject '받을 수 없는 인자' '(s5) 범위: 대소문자가 다른 --Desktop-Only' scope_of --Desktop-Only
expect_run reject '받을 수 없는 인자' '(s6) 범위: 빈 문자열 인자' scope_of ''
expect_run pass 'no-args=ok' '(s7) 인자 없는 게이트: 인자 없음' no_args_of
expect_run reject '받을 수 없는 인자' '(s8) 인자 없는 게이트: --desktop-only 도 거부' no_args_of --desktop-only
# 범위는 인자로만 고른다: 셸에서 내보낸 GATE_SCOPE 가 인자 없는 게이트(팩토리 명령)를 desktop-only 로 바꾸지 못한다.
scope_with_exported_env() (
  GATE_SCOPE=desktop-only
  export GATE_SCOPE
  scope_of
)
expect_run pass 'scope=all' '(s1e) 범위: 내보낸 GATE_SCOPE=desktop-only 가 있어도 인자 없음은 all' scope_with_exported_env

# (s9-s10) build.sh, lint.sh 는 잘못된 인자에서 어떤 도구도 실행하지 않는다. 가짜 도구(sh, gradle, java, node, pnpm, cargo)가
# PATH 맨 앞에서 호출을 기록한다. test.sh 와 integration.sh 는 selftest 를 부르므로 여기서 실행하지 않는다(같은 해석기를 쓴다).
FAKE_TOOLS="$WORK/fake-tools"
FAKE_TOOL_LOG="$WORK/fake-tools.log"
mkdir -p "$FAKE_TOOLS"
: >"$FAKE_TOOL_LOG"
for tool in sh gradle java node pnpm cargo; do
  printf '#!/bin/sh\necho "%s $*" >>"%s"\nexit 1\n' "$tool" "$FAKE_TOOL_LOG" >"$FAKE_TOOLS/$tool"
  chmod +x "$FAKE_TOOLS/$tool"
done
gate_with_fake_tools() {
  PATH="$FAKE_TOOLS:$PATH" bash "$SCRIPT_DIR/$1.sh" "${@:2}"
}
expect_run reject '받을 수 없는 인자' '(s9) build.sh --bogus' gate_with_fake_tools build --bogus
expect_run reject '받을 수 없는 인자' '(s10) lint.sh --desktop-only extra' gate_with_fake_tools lint --desktop-only extra
if [ -s "$FAKE_TOOL_LOG" ]; then
  echo "FAIL (s11) 잘못된 인자에서 도구가 실행됐다:"
  sed 's/^/    | /' "$FAKE_TOOL_LOG"
  unmet=$((unmet + 1))
else
  echo "PASS (s11) 잘못된 인자에서 sh, gradle, java, node, pnpm, cargo 가 한 번도 실행되지 않았다"
fi

# (dp) docker_preflight (P0B-R4): 가짜 docker 를 PATH 맨 앞에 둔다. 임시 파일은 따로 둔 TMPDIR 에 만들어 남지 않는지 본다.
DP_WORK="$WORK/docker-preflight"
mkdir -p "$DP_WORK/tmp"
# fake_docker NAME BODY: BODY 를 실행하는 가짜 docker
fake_docker() {
  mkdir -p "$DP_WORK/$1"
  printf '#!/bin/sh\n%s\n' "$2" >"$DP_WORK/$1/docker"
  chmod +x "$DP_WORK/$1/docker"
}
fake_docker stderr-only 'echo "WARNING: dp-stderr-marker" >&2; exit 0'
fake_docker exit1 'echo "Cannot connect to the Docker daemon" >&2; exit 1'
fake_docker warning-stdout 'echo "WARNING: x"; exit 0'
fake_docker two-lines 'printf "29.5.2\n29.5.3\n"; exit 0'
fake_docker ok 'echo "WARNING: dp-noise-on-stderr" >&2; echo "29.5.2"; exit 0'
preflight_with() (
  . "$SCRIPT_DIR/lib.sh"
  PATH="$DP_WORK/$1:$PATH"
  TMPDIR="$DP_WORK/tmp"
  export TMPDIR
  docker_preflight
)
expect_run reject 'dp-stderr-marker' '(dp1) docker: 표준 오류의 경고만 있고 표준 출력이 빔 (exit 0)' preflight_with stderr-only
expect_run reject 'docker info exit 1' '(dp2) docker: exit 1' preflight_with exit1
expect_run reject '버전 모양' "(dp3) docker: 표준 출력이 'WARNING: x'" preflight_with warning-stdout
expect_run reject '한 줄이 아니다' '(dp4) docker: 표준 출력이 두 줄' preflight_with two-lines
expect_run pass 'Docker 서버 버전: 29.5.2' "(dp5) docker: 표준 출력 '29.5.2' (표준 오류의 경고는 버전에 섞이지 않는다)" preflight_with ok
if [ -n "$(ls -A "$DP_WORK/tmp")" ]; then
  echo "FAIL (dp6) docker_preflight 의 표준 오류 임시 파일이 남았다: $(ls -A "$DP_WORK/tmp" | tr '\n' ' ')"
  unmet=$((unmet + 1))
else
  echo "PASS (dp6) docker_preflight 의 표준 오류 임시 파일이 trap 으로 지워졌다"
fi

# (d) docs-check.sh (DOC-1): 진짜 docs/REQUIREMENTS.md 의 표시 두 줄과 그 사이 블록을 그대로 옮긴 픽스처. 기준 해시와 줄 수는
# 바꾸지 않는다. 블록 밖의 줄은 옮기지 않는다(다른 문서로 가는 링크가 있어 픽스처에서는 깨진다).
DOCS_CHECK="$SCRIPT_DIR/docs-check.sh"
DOCS_WORK="$WORK/docs"
# docs_root NAME: 깨끗한 문서 픽스처(REQUIREMENTS.md 의 원문 블록, README.md, CHANGELOG.md, docs/INDEX.md)
docs_root() {
  local dir="$DOCS_WORK/$1"
  mkdir -p "$dir/docs"
  {
    echo "# 요구사항 (selftest 픽스처)"
    awk '/^<!-- SPEC-VERBATIM-BEGIN -->$/{f=1} f{print} /^<!-- SPEC-VERBATIM-END -->$/{f=0}' "$REPO_DIR/docs/REQUIREMENTS.md"
  } >"$dir/docs/REQUIREMENTS.md"
  printf '# README\n\n[요구사항](docs/REQUIREMENTS.md), [목차](docs/INDEX.md)\n' >"$dir/README.md"
  printf '# CHANGELOG\n\n[README](README.md)\n' >"$dir/CHANGELOG.md"
  printf '# INDEX\n\n[요구사항](REQUIREMENTS.md) [위로](../README.md)\n' >"$dir/docs/INDEX.md"
}
# spec_edit NAME AWK_PROGRAM: 픽스처의 REQUIREMENTS.md 를 awk 로 바꾼다(바이트 단위로 다루도록 LC_ALL=C).
spec_edit() {
  local file="$DOCS_WORK/$1/docs/REQUIREMENTS.md"
  LC_ALL=C awk "$2" "$file" >"$file.new"
  mv "$file.new" "$file"
}
docs_root ok
docs_root byte
# 블록 안에서 ASCII 로 시작하는 첫 줄의 첫 바이트 하나만 바꾼다(줄 수는 그대로다).
spec_edit byte '/^<!-- SPEC-VERBATIM-BEGIN -->$/{f=1; print; next} f && !done && /^[A-Za-z0-9#*-]/{c=substr($0,1,1); $0=(c=="X"?"Y":"X") substr($0,2); done=1} {print}'
docs_root no-end
spec_edit no-end '!/^<!-- SPEC-VERBATIM-END -->$/'
docs_root dup-begin
spec_edit dup-begin '{print} /^<!-- SPEC-VERBATIM-BEGIN -->$/{print}'
docs_root end-first
spec_edit end-first '/^<!-- SPEC-VERBATIM-BEGIN -->$/{print "<!-- SPEC-VERBATIM-END -->"; print; next} /^<!-- SPEC-VERBATIM-END -->$/{next} {print}'
docs_root broken
printf '\n[없는 문서](MISSING.md)\n' >>"$DOCS_WORK/broken/docs/INDEX.md"
docs_root root-only
printf '\n[저장소 루트 기준으로만 맞는 링크](docs/REQUIREMENTS.md)\n' >>"$DOCS_WORK/root-only/docs/INDEX.md"
docs_root fenced
printf '\n```md\n[펜스 안의 깨진 링크](MISSING.md)\n```\n\n~~~\n[물결 펜스 안](MISSING.md)\n~~~\n\n`[인라인 코드 안](MISSING.md)`\n' \
  >>"$DOCS_WORK/fenced/docs/INDEX.md"
docs_root external
printf '\n[외부](https://example.com/MISSING.md) [메일](mailto:qh@example.com) [앵커](#없는-제목)\n' >>"$DOCS_WORK/external/docs/INDEX.md"
docs_root fragment
printf '\n[조각](REQUIREMENTS.md#없는-제목도-확인하지-않는다) [제목 포함](../README.md "README")\n' >>"$DOCS_WORK/fragment/docs/INDEX.md"
docs_root no-readme
rm "$DOCS_WORK/no-readme/README.md"
# sha256sum 과 shasum 이 없는 PATH: docs-check 가 쓰는 다른 도구만 링크해 둔다.
NO_SHA_BIN="$WORK/no-sha-bin"
mkdir -p "$NO_SHA_BIN"
for tool in dirname awk grep cut wc tr find sort; do
  ln -s "$(command -v "$tool")" "$NO_SHA_BIN/$tool"
done
docs_without_sha() {
  PATH="$NO_SHA_BIN" "$BASH" "$DOCS_CHECK" --root "$DOCS_WORK/ok"
}

expect_run pass 'docs-check: OK' '(d-ok) 깨끗한 문서 픽스처' bash "$DOCS_CHECK" --root "$DOCS_WORK/ok"
expect_run reject '[SPEC_HASH]' '(d1) REQUIREMENTS.md 의 원문 블록에서 한 바이트를 바꿈' bash "$DOCS_CHECK" --root "$DOCS_WORK/byte"
expect_run reject '[SPEC_MARKERS]' '(d2) END 표시 없음' bash "$DOCS_CHECK" --root "$DOCS_WORK/no-end"
expect_run reject '[SPEC_MARKERS]' '(d3) BEGIN 표시가 두 번' bash "$DOCS_CHECK" --root "$DOCS_WORK/dup-begin"
expect_run reject '[SPEC_ORDER]' '(d4) END 표시가 BEGIN 보다 앞' bash "$DOCS_CHECK" --root "$DOCS_WORK/end-first"
expect_run reject 'docs/INDEX.md:5: MISSING.md' '(d5) 깨진 상대 링크 (file:line: target)' bash "$DOCS_CHECK" --root "$DOCS_WORK/broken"
expect_run reject 'docs/INDEX.md:5: docs/REQUIREMENTS.md' '(d6) 저장소 루트 기준으로만 맞는 링크' \
  bash "$DOCS_CHECK" --root "$DOCS_WORK/root-only"
expect_run pass 'docs-check: OK' '(d7) 펜스 블록과 인라인 코드 안의 깨진 모양 링크는 보지 않는다' \
  bash "$DOCS_CHECK" --root "$DOCS_WORK/fenced"
expect_run pass 'docs-check: OK' '(d8) https, mailto, # 링크는 확인하지 않는다' bash "$DOCS_CHECK" --root "$DOCS_WORK/external"
expect_run pass 'docs-check: OK' '(d9) 있는 파일을 가리키는 # 조각 링크와 제목이 붙은 링크' \
  bash "$DOCS_CHECK" --root "$DOCS_WORK/fragment"
expect_run reject '[MISSING_FILE]' '(d10) README.md 없음' bash "$DOCS_CHECK" --root "$DOCS_WORK/no-readme"
expect_run reject '[NO_SHA256_TOOL]' '(d11) sha256sum 과 shasum 이 모두 없음' docs_without_sha
expect_run reject '[USAGE]' '(d12) 사용법 오류: 모르는 인자' bash "$DOCS_CHECK" --strict

# (w) workflow-check.sh: 깨끗한 워크플로 픽스처와 한 군데씩 어긴 사본
WF_CHECK="$SCRIPT_DIR/workflow-check.sh"
WF_WORK="$WORK/workflows"
WF_SHA=3d3c42e5aac5ba805825da76410c181273ba90b1
# wf_root NAME: 깨끗한 .github/workflows/ci.yml 이 있는 픽스처
wf_root() {
  mkdir -p "$WF_WORK/$1/.github/workflows"
  cat >"$WF_WORK/$1/.github/workflows/ci.yml" <<EOF
name: CI
on:
  push:
    branches: [main]
permissions:
  contents: read
jobs:
  gates:
    runs-on: ubuntu-24.04
    steps:
      - name: Checkout
        uses: actions/checkout@$WF_SHA # v7.0.1
        with:
          persist-credentials: false
      - run: bash scripts/gate/build.sh
      - run: bash scripts/gate/test.sh
      - run: bash scripts/gate/lint.sh
      - run: bash scripts/gate/integration.sh
EOF
}
# wf_variant NAME SED_EXPR: 깨끗한 픽스처를 sed 로 한 군데 바꾼 사본
wf_variant() {
  wf_root "$1"
  sed "$2" "$WF_WORK/$1/.github/workflows/ci.yml" >"$WF_WORK/$1/ci.new"
  mv "$WF_WORK/$1/ci.new" "$WF_WORK/$1/.github/workflows/ci.yml"
}
wf_root ok
wf_variant tag "s/@$WF_SHA # v7.0.1/@v4/"
wf_variant short-sha "s/@$WF_SHA/@3d3c42e/"
wf_variant no-comment "s/ # v7.0.1//"
wf_variant no-permissions '/^permissions:$/d; /^  contents: read$/d'
wf_variant write-permission 's/^  contents: read$/  contents: write/'
wf_variant job-permission 's/^    runs-on: ubuntu-24.04$/    runs-on: ubuntu-24.04\
    permissions:\
      contents: read/'
wf_variant prt 's/^  push:$/  pull_request_target:/'
wf_root secrets
echo '      - run: echo "${{ secrets.TOKEN }}"' >>"$WF_WORK/secrets/.github/workflows/ci.yml"
wf_root event
echo '      - run: echo "${{ github.event.pull_request.title }}"' >>"$WF_WORK/event/.github/workflows/ci.yml"
wf_root head-ref
echo '      - run: echo "${{ github.head_ref }}"' >>"$WF_WORK/head-ref/.github/workflows/ci.yml"
wf_variant persist 's/persist-credentials: false/persist-credentials: true/'
wf_variant no-command '/bash scripts\/gate\/integration.sh/d'
wf_variant command-arg 's#bash scripts/gate/lint.sh#bash scripts/gate/lint.sh --desktop-only#'
wf_variant latest 's/ubuntu-24.04/ubuntu-latest/'
mkdir -p "$WF_WORK/none/.github/workflows"
: >"$WF_WORK/none/.github/workflows/README.txt"
mkdir -p "$WF_WORK/no-dir"

expect_run pass 'workflow-check: OK' '(w-ok) 깨끗한 워크플로 픽스처' bash "$WF_CHECK" --root "$WF_WORK/ok"
expect_run reject '[USES_NOT_SHA]' '(w1) 태그 고정 (@v4)' bash "$WF_CHECK" --root "$WF_WORK/tag"
expect_run reject '[USES_NOT_SHA]' '(w2) 7자 SHA' bash "$WF_CHECK" --root "$WF_WORK/short-sha"
expect_run reject '[USES_NO_TAG_COMMENT]' "(w3) '# 태그' 주석 없는 SHA" bash "$WF_CHECK" --root "$WF_WORK/no-comment"
expect_run reject '[PERMISSIONS_MISSING]' '(w4) 최상위 permissions 없음' bash "$WF_CHECK" --root "$WF_WORK/no-permissions"
expect_run reject '[PERMISSIONS_WRITE]' '(w5) write 권한' bash "$WF_CHECK" --root "$WF_WORK/write-permission"
expect_run reject '[PERMISSIONS_JOB_LEVEL]' '(w6) job 수준 permissions' bash "$WF_CHECK" --root "$WF_WORK/job-permission"
expect_run reject '[FORBIDDEN_TRIGGER]' '(w7) pull_request_target' bash "$WF_CHECK" --root "$WF_WORK/prt"
expect_run reject '[FORBIDDEN_EXPR]' "(w8) 'secrets.' 참조" bash "$WF_CHECK" --root "$WF_WORK/secrets"
expect_run reject '[FORBIDDEN_EXPR]' "(w9) '\${{ github.event.' 표현식" bash "$WF_CHECK" --root "$WF_WORK/event"
expect_run reject '[FORBIDDEN_EXPR]' '(w10) github.head_ref' bash "$WF_CHECK" --root "$WF_WORK/head-ref"
expect_run reject '[CHECKOUT_PERSIST]' '(w11) persist-credentials: false 없는 checkout' bash "$WF_CHECK" --root "$WF_WORK/persist"
expect_run reject '[FACTORY_COMMAND_MISSING]' '(w12) 팩토리 명령 줄 하나가 없음' bash "$WF_CHECK" --root "$WF_WORK/no-command"
expect_run reject '[FACTORY_COMMAND_MISSING]' '(w13) 팩토리 명령에 인자가 붙음' bash "$WF_CHECK" --root "$WF_WORK/command-arg"
expect_run reject '[LATEST_RUNNER]' '(w14) -latest 러너 레이블' bash "$WF_CHECK" --root "$WF_WORK/latest"
expect_run reject '[NO_WORKFLOWS]' '(w15) .github/workflows 에 *.yml, *.yaml 이 없음' bash "$WF_CHECK" --root "$WF_WORK/none"
expect_run reject '[NO_WORKFLOWS]' '(w16) .github/workflows 디렉터리가 없음' bash "$WF_CHECK" --root "$WF_WORK/no-dir"
expect_run reject '[USAGE]' '(w17) 사용법 오류: 모르는 인자' bash "$WF_CHECK" --strict

if [ "$unmet" -ne 0 ]; then
  echo "selftest: FAIL ($unmet 개 시나리오가 기대와 다르다)"
  exit 1
fi
if [ "$skipped_scenarios" -ne 0 ]; then
  if [ "$NO_SKIP" -eq 1 ]; then
    echo "selftest: FAIL (--no-skip: 건너뛴 시나리오 $skipped_scenarios 개. 이 환경에서는 모든 시나리오를 실행해야 한다. root 가 아닌 사용자로 실행한다)"
    exit 1
  fi
  echo "selftest: PASS (실행한 시나리오는 모두 기대대로 동작한다. 이 환경에서 만들 수 없어 건너뛴 시나리오 $skipped_scenarios 개)"
  exit 0
fi
echo "selftest: PASS (판정기와 검사기가 모든 시나리오에서 기대대로 동작한다)"
