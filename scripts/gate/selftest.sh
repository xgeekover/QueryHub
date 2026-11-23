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
# (i6) Git Bash(MSYS2) 의 ln -s 는 기본으로 대상을 복사하므로 대상이 없는 링크를 만들지 못한다. MSYS=winsymlinks:native 는
#   대상이 없어도 Windows 네이티브 심볼릭 링크를 만든다. 이 명령에만 준다(macOS·Linux 의 ln 은 MSYS 를 읽지 않는다).
#   그래도 만들지 못하면 여기서 멈추지 않고 아래 (i6) 기대에서 SKIP 으로 센다(P1-07a). Windows 동작은 push 뒤 CI 실행 전까지 미검증이다.
MSYS=winsymlinks:native ln -s "$WORK/no-such-file.xml" "$WORK/i6-dangling-link/TEST-link.xml" || true

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
if [ -L "$WORK/i6-dangling-link/TEST-link.xml" ]; then
  expect reject INVALID '(i6) 깨진 심볼릭 링크인 TEST-link.xml' --require-each "$WORK/i6-dangling-link"
else
  # 위 ln -s 가 깨진 링크를 만들지 못했다(그 오류는 위에 출력된다). --no-skip(CI 의 Linux gates 작업)에서는 실패한다.
  echo "SKIP (i6) 깨진 심볼릭 링크를 만들 수 없다: 이 환경의 ln -s 는 대상이 없는 링크를 만들지 못한다 (Git Bash 에서 네이티브 심볼릭 링크 권한이 없음)"
  skipped_scenarios=$((skipped_scenarios + 1))
fi
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

# (d13-d15) P1-01 (P0D-R5): 상대 링크는 저장소 루트 안에 갇힌다. 루트 밖의 파일이 있어도 깨진 링크다.
docs_root escape
printf '# 루트 밖\n' >"$DOCS_WORK/outside.md"
printf '\n[루트 밖](../../outside.md)\n' >>"$DOCS_WORK/escape/docs/INDEX.md"
docs_root sibling
printf '\n[옆 픽스처](../ok/README.md)\n' >>"$DOCS_WORK/sibling/README.md"
# 루트 안에 머무는 '..'(두 단계 깊은 파일에서), './', 'a/../b', 루트 안의 심볼릭 링크 디렉터리
docs_root inroot
mkdir -p "$DOCS_WORK/inroot/docs/ADR"
printf '# X\n\n[위로](../../README.md) [점](./../INDEX.md) [돌아서](../../docs/ADR/../INDEX.md)\n' >"$DOCS_WORK/inroot/docs/ADR/X.md"
ln -s ADR "$DOCS_WORK/inroot/docs/adr-link"
printf '\n[점](./REQUIREMENTS.md) [a/../b](ADR/../REQUIREMENTS.md) [심볼릭 링크 디렉터리](adr-link/X.md)\n' \
  >>"$DOCS_WORK/inroot/docs/INDEX.md"
expect_run reject 'docs/INDEX.md:5: ../../outside.md' '(d13) 루트 밖의 있는 파일로 가는 링크 (../../outside.md)' \
  bash "$DOCS_CHECK" --root "$DOCS_WORK/escape"
expect_run reject 'README.md:5: ../ok/README.md' '(d14) 옆 픽스처의 있는 README.md 로 가는 링크' \
  bash "$DOCS_CHECK" --root "$DOCS_WORK/sibling"
expect_run pass 'docs-check: OK' "(d15) 루트 안의 '..', './', 'a/../b', 심볼릭 링크 디렉터리" bash "$DOCS_CHECK" --root "$DOCS_WORK/inroot"

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

# ---- P1-01 (P0D-R1-R4): 줄 단위 점검기가 다루지 않는 문법은 거부하고, 실패를 덮는 키와 run 값의 표현식을 거부한다 ----
# wf_add NAME: 표준 입력의 줄을 픽스처 ci.yml 끝(마지막 단계 뒤)에 붙인다.
wf_add() {
  cat >>"$WF_WORK/$1/.github/workflows/ci.yml"
}
wf_check() {
  bash "$WF_CHECK" --root "$WF_WORK/$1"
}
# R1: 따옴표로 감싼 키, 흐름 매핑과 그 밖의 YAML 문법
wf_root quoted-uses
wf_add quoted-uses <<'EOF'
      - name: Java
        "uses": actions/setup-java@v4
EOF
wf_variant quoted-perm 's/^    runs-on: ubuntu-24.04$/    runs-on: ubuntu-24.04\
    "permissions": {"contents": "write"}/'
wf_root single-quoted-key
wf_add single-quoted-key <<'EOF'
      - name: single
        'continue-on-error': true
EOF
wf_root flow-item
wf_add flow-item <<'EOF'
      - {name: x, run: echo hi}
EOF
wf_variant flow-seq "s/branches: \\[main\\]/branches: [main, 'v*']/"
wf_root anchor-alias
wf_add anchor-alias <<'EOF'
      - name: anchor
        env: &shared
          A: b
        run: echo a
      - name: alias
        env: *shared
        run: echo b
EOF
wf_root merge-key
wf_add merge-key <<'EOF'
      - name: merge
        env:
          <<:
            A: b
        run: echo a
EOF
wf_root tag
wf_add tag <<'EOF'
      - name: tag
        run: !!str echo hi
EOF
wf_variant doc-marker '1i\
---'
wf_root key-space
wf_add key-space <<'EOF'
      - name: Java
        uses : actions/setup-java@v4
EOF
wf_root explicit-key
wf_add explicit-key <<'EOF'
      - name: explicit
        ? run
        : echo hi
EOF
wf_root block-indent
wf_add block-indent <<'EOF'
      - name: indent digit
        run: |2
            echo hi
EOF
wf_variant quoted-write "s/^  contents: read\$/  contents: 'write'/"
wf_variant multiline-quote 's/^          persist-credentials: false$/          ref: "main\
          persist-credentials: false\
          "/'
wf_root escape-quote
wf_add escape-quote <<'EOF'
      - run: "echo \x24{{ github.ref_name }}"
EOF
wf_root tab-indent
printf '      - name: tab\n\t  run: echo hi\n' | wf_add tab-indent
# R2: persist-credentials 는 checkout 단계의 with: 바로 아래에서만 센다
wf_variant persist-env 's/^        with:$/        env:/'
wf_variant persist-block 's/^        with:$/        with: |/'
wf_variant with-order 's/^          persist-credentials: false$/          fetch-depth: 1\
          persist-credentials: false/'
# R3: 실패를 덮거나 건너뛰게 하는 키, bash 가 아닌 셸, 블록 안에만 있는 팩토리 명령
wf_variant coe-step 's#^      - run: bash scripts/gate/test.sh$#&\
        continue-on-error: true#'
wf_variant coe-job 's/^    runs-on: ubuntu-24.04$/&\
    continue-on-error: true/'
wf_variant if-step 's#^      - run: bash scripts/gate/lint.sh$#&\
        if: false#'
wf_variant if-job 's/^    runs-on: ubuntu-24.04$/&\
    if: false/'
wf_variant shell-sh 's#^      - run: bash scripts/gate/build.sh$#&\
        shell: sh#'
wf_variant command-in-block 's#^      - run: bash scripts/gate/build.sh$#      - run: |\
          set +e\
          bash scripts/gate/build.sh#'
# R4: run 값 안의 표현식
wf_root run-expr
wf_add run-expr <<'EOF'
      - run: echo "${{ github.ref_name }}"
EOF
wf_root run-expr-block
wf_add run-expr-block <<'EOF'
      - run: |
          echo hi
          echo "${{ github.ref_name }}"
EOF
wf_root run-expr-comment
wf_add run-expr-comment <<'EOF'
      - run: |
          echo hi
          # ${{ github.ref_name }}
EOF
# 받아야 하는 것: 따옴표로 감싼 값, 평범한 흐름 시퀀스, run 밖의 표현식, defaults 의 shell: bash, 키처럼 보이는 셸 문장이 든
# run 블록 다음의 진짜 단계 키
wf_root allowed
wf_add allowed <<'EOF'
    name: gates (${{ github.ref_name }})
    concurrency:
      group: ci-${{ github.ref }}
      cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}
    strategy:
      matrix:
        os: [macos-15, windows-2025]
    defaults:
      run:
        shell: bash
    env:
      REF: ${{ github.ref_name }}
  other:
    runs-on: ${{ matrix.os }}
    steps:
      - name: Java 21
        uses: actions/setup-java@77dd5b2ce225ea11469928ac317850245a74ae78 # v6.2.0
        with:
          java-version: "21"
          cache: gradle
      - name: cache
        uses: actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0
        with:
          path: |
            ~/.cargo/registry
          key: cargo-${{ runner.os }}-${{ hashFiles('Cargo.lock') }}
      - name: shell text
        run: |
          "k": v
          { x; }
          true && echo ok
          ls ./*
          ---
          if [ -n "$REF" ]; then echo "$REF"; fi
          continue-on-error: true
          if: false
        shell: bash
      - name: next step
        run: echo done
EOF
mkdir -p "$WF_WORK/ci/.github/workflows"
cp "$REPO_DIR/.github/workflows/ci.yml" "$WF_WORK/ci/.github/workflows/ci.yml"

expect_run reject '[UNSUPPORTED_SYNTAX]' '(w18) 따옴표로 감싼 키 "uses":' wf_check quoted-uses
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w19) job 수준 "permissions": {"contents": "write"}' wf_check quoted-perm
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w20) 작은따옴표로 감싼 키 'continue-on-error':" wf_check single-quoted-key
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w21) 목록 항목인 흐름 매핑 - {name: x, run: echo hi}' wf_check flow-item
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w22) 평범한 값만 담지 않은 흐름 시퀀스 [main, 'v*']" wf_check flow-seq
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w23) 앵커 &shared 와 별칭 *shared' wf_check anchor-alias
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w24) 병합 키 <<:' wf_check merge-key
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w25) 태그 !!str' wf_check tag
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w26) 문서 표시 ---' wf_check doc-marker
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w27) 키와 콜론 사이의 공백 'uses :'" wf_check key-space
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w28) 명시적 키 '? ' 와 값 줄 ': '" wf_check explicit-key
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w29) 들여쓰기 숫자가 붙은 블록 스칼라 머리 '|2'" wf_check block-indent
expect_run reject '[PERMISSIONS_WRITE]' "(w30) 따옴표로 감싼 write (contents: 'write')" wf_check quoted-write
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w31) 줄을 넘기는 따옴표 문자열 안의 persist-credentials: false' wf_check multiline-quote
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w32) 큰따옴표 안의 '\\' 이스케이프 (\\x24{{)" wf_check escape-quote
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w33) 들여쓰기의 탭' wf_check tab-indent
expect_run reject '[CHECKOUT_PERSIST]' '(w34) checkout 의 with: 를 env: 로 바꿈' wf_check persist-env
expect_run reject '[CHECKOUT_PERSIST]' "(w35) 'with: |' 문자열 안의 persist-credentials: false" wf_check persist-block
expect_run pass 'workflow-check: OK' '(w36) with: 의 다른 입력(fetch-depth) 다음의 persist-credentials: false' wf_check with-order
expect_run reject '[CONTINUE_ON_ERROR]' '(w37) 팩토리 단계의 continue-on-error: true' wf_check coe-step
expect_run reject '[CONTINUE_ON_ERROR]' '(w38) job 수준 continue-on-error' wf_check coe-job
expect_run reject '[IF_CONDITION]' '(w39) 팩토리 단계의 if: false' wf_check if-step
expect_run reject '[IF_CONDITION]' '(w40) job 수준 if:' wf_check if-job
expect_run reject '[SHELL_NOT_BASH]' '(w41) 팩토리 단계의 shell: sh' wf_check shell-sh
expect_run reject '[FACTORY_COMMAND_MISSING]' "(w42) 'run: |' 블록 안(set +e 뒤)에만 있는 팩토리 명령" wf_check command-in-block
expect_run reject '[RUN_EXPR]' '(w43) run: 줄의 ${{ github.ref_name }}' wf_check run-expr
expect_run reject '[RUN_EXPR]' "(w44) 'run: |' 블록 안의 \${{ github.ref_name }}" wf_check run-expr-block
expect_run reject '[RUN_EXPR]' "(w45) run 블록 안의 '# \${{ github.ref_name }}' 줄" wf_check run-expr-comment
expect_run pass 'workflow-check: OK' '(w46) 받아야 하는 문법: 따옴표 값, 평범한 흐름 시퀀스, run 밖의 표현식, run 블록 안의 키 모양 셸 문장' \
  wf_check allowed
expect_run pass 'workflow-check: OK' '(w47) 저장소의 .github/workflows/ci.yml 바이트 사본' wf_check ci

# ---- P1-01 2회차 (리뷰 R1, R2, R4): LF 가 아닌 줄바꿈과 제어 문자, 단계 밖의 run:, 대소문자가 다른 checkout ----
# wf_bytes NAME OLD_LINE FORMAT: 깨끗한 픽스처에서 OLD_LINE 과 같은 첫 줄을 printf FORMAT 의 출력으로 바꾼다. CR, U+2028 같은
#   바이트는 bash printf 의 이스케이프(\r, 8진 \342)로 쓴다(sed 의 \r 은 도구마다 다르다). FORMAT 에는 % 를 쓰지 않는다.
#   바꿀 줄이 없으면 깨끗한 픽스처가 그대로 남으므로 그 거부 시나리오는 실패한다(조용히 통과하지 않는다).
wf_bytes() {
  local file="$WF_WORK/$1/.github/workflows/ci.yml" l replaced=0
  wf_root "$1"
  while IFS= read -r l; do
    if [ "$replaced" -eq 0 ] && [ "$l" = "$2" ]; then
      printf "$3"
      replaced=1
    else
      printf '%s\n' "$l"
    fi
  done <"$file" >"$file.new"
  mv "$file.new" "$file"
}
# R1: YAML(libyaml)은 CR, U+0085, U+2028, U+2029 에서도 줄을 나눠 그 뒤의 키를 읽지만, awk 에는 한 줄이다.
wf_bytes cr-coe-step '      - run: bash scripts/gate/lint.sh' \
  '      - name: lint\r        continue-on-error: true\n        run: bash scripts/gate/lint.sh\n'
wf_bytes cr-if-job '    runs-on: ubuntu-24.04' '    runs-on: ubuntu-24.04\r    if: false\n'
wf_bytes cr-persist '        with:' '        with: #\r        env:\n'
wf_root cr-block
printf '      - name: block\n        run: |\n          echo hi\r        if: false\n' | wf_add cr-block
wf_root ls-coe-step
printf '      - name: ls\342\200\250        continue-on-error: true\n        run: echo ls\n' | wf_add ls-coe-step
wf_root nel-coe-step
printf '      - name: nel\302\205        continue-on-error: true\n        run: echo nel\n' | wf_add nel-coe-step
wf_root ps-coe-step
printf '      - name: ps\342\200\251        continue-on-error: true\n        run: echo ps\n' | wf_add ps-coe-step
# BWK awk 는 NUL 에서 줄을 끊으므로 NUL 뒤의 CR 은 awk 의 index() 에도 보이지 않는다(파일 단위의 NUL 검사가 잡는다).
wf_root nul-if
printf '      - name: nul\000\r        if: false\n        run: echo nul\n' | wf_add nul-if
# \v(U+000B)는 YAML 에서 쓸 수 없지만 awk 의 [[:space:]] 는 공백으로 읽어 persist-credentials: false 로 센다.
wf_bytes ctl-persist '          persist-credentials: false' '          persist-credentials:\013false\n'
# 줄 끝의 CRLF 는 받는다.
wf_root crlf
LC_ALL=C awk '{ printf "%s\r\n", $0 }' "$WF_WORK/crlf/.github/workflows/ci.yml" >"$WF_WORK/crlf/ci.new"
mv "$WF_WORK/crlf/ci.new" "$WF_WORK/crlf/.github/workflows/ci.yml"
wf_root adjacent-key
wf_add adjacent-key <<'EOF'
      - name: adjacent
        "continue-on-error":true
        run: echo adjacent
EOF
# R2: run: 은 단계(jobs.<job>.steps 의 항목)의 키일 때만 실행된다. 그것만 팩토리 명령으로 세고, 다른 곳의 run: 은 거부한다.
wf_variant env-run 's|^      - run: bash scripts/gate/build.sh$|      - name: build\
        env:\
          run: bash scripts/gate/build.sh\
        run: echo build skipped|'
wf_variant with-run 's|^      - run: bash scripts/gate/build.sh$|      - name: build\
        uses: actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0\
        with:\
          path: x\
          key: y\
          run: bash scripts/gate/build.sh|'
wf_variant defaults-run-value 's|^      - run: bash scripts/gate/build.sh$|      - run: echo build skipped|
s|^jobs:$|defaults:\
  run: bash scripts/gate/build.sh\
jobs:|'
wf_variant dup-run 's|^      - run: bash scripts/gate/lint.sh$|&\
        run: echo lint skipped|'
# 받아야 하는 단계 모양: steps: 와 같은 열의 '- ' 항목, 키 없는 '-' 다음 줄의 run:, 워크플로와 job 의 defaults: run: shell: bash,
# 두 번째 job, run: 앞의 env:
mkdir -p "$WF_WORK/step-forms/.github/workflows"
cat >"$WF_WORK/step-forms/.github/workflows/ci.yml" <<EOF
name: CI
on:
  push:
    branches: [main]
permissions:
  contents: read
defaults:
  run:
    shell: bash
jobs:
  gates:
    runs-on: ubuntu-24.04
    defaults:
      run:
        shell: bash
    steps:
    - name: Checkout
      uses: actions/checkout@$WF_SHA # v7.0.1
      with:
        persist-credentials: false
    - run: bash scripts/gate/build.sh
    -
      run: bash scripts/gate/test.sh
    - name: lint
      run: bash scripts/gate/lint.sh
  integration:
    runs-on: ubuntu-24.04
    steps:
      - name: integration
        env:
          RUN_MODE: full
        run: bash scripts/gate/integration.sh
EOF
# R4: owner/repo 의 대소문자가 달라도 checkout 이다.
wf_variant case-checkout 's|actions/checkout@|Actions/Checkout@|; /^        with:$/d; /^          persist-credentials: false$/d'

expect_run reject '[UNSUPPORTED_SYNTAX]' '(w48) CR 뒤에 숨은 단계의 continue-on-error: true' wf_check cr-coe-step
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w49) CR 뒤에 숨은 job 수준 if: false' wf_check cr-if-job
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w50) checkout 의 'with: #<CR>' 뒤의 env: (persist-credentials 가 env 아래로 간다)" wf_check cr-persist
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w51) 'run: |' 블록 안의 CR 뒤에 숨은 if: false" wf_check cr-block
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w52) U+2028 뒤에 숨은 continue-on-error: true' wf_check ls-coe-step
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w53) U+0085 뒤에 숨은 continue-on-error: true' wf_check nel-coe-step
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w54) U+2029 뒤에 숨은 continue-on-error: true' wf_check ps-coe-step
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w55) NUL 바이트와 CR 뒤에 숨은 if: false' wf_check nul-if
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w56) 제어 문자 \v 가 공백 자리에 있는 persist-credentials:<VT>false' wf_check ctl-persist
expect_run pass 'workflow-check: OK' '(w57) 모든 줄이 CRLF 로 끝나는 깨끗한 픽스처' wf_check crlf
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w58) 콜론이 바로 붙은 따옴표 키 "continue-on-error":true' wf_check adjacent-key
expect_run reject '[RUN_KEY]' '(w59) 단계 env: 아래의 run: (단계의 run 은 echo)' wf_check env-run
expect_run reject '[FACTORY_COMMAND_MISSING]' '(w60) action 입력 with: run: 에만 있는 팩토리 명령' wf_check with-run
expect_run reject '[RUN_KEY]' '(w61) defaults: 아래의 값이 있는 run: (팩토리 명령)' wf_check defaults-run-value
expect_run reject '[RUN_KEY]' '(w62) 한 단계의 두 번째 run:' wf_check dup-run
expect_run pass 'workflow-check: OK' "(w63) 받는 단계 모양: steps: 와 같은 열의 '- ', 키 없는 '-', 두 수준의 defaults run, run 앞의 env" \
  wf_check step-forms
expect_run reject '[CHECKOUT_PERSIST]' '(w64) 대소문자가 다른 Actions/Checkout (with: 없음)' wf_check case-checkout

# ---- P1-01 3회차 (리뷰 C2-R1-R3, C2-R5, YAML 문법 전수 점검): 허용 목록 밖의 줄 모양, 들여쓰기 구조, 중복 키, uses 경로 조각 ----
# 아래 픽스처의 이름은 모두 c3- 로 시작한다(앞의 픽스처와 겹치지 않는다). wf_bytes 의 FORMAT 에서 % 는 \045, ' 는 \047 로 쓴다.
WJ='    runs-on: ubuntu-24.04'
WB='      - run: bash scripts/gate/build.sh'
WT='      - run: bash scripts/gate/test.sh'
WL='      - run: bash scripts/gate/lint.sh'
WI='      - run: bash scripts/gate/integration.sh'
# C2-R1: YAML 은 값이 있는 키나 '- 값' 다음의 더 깊은 줄을 앞 값에 이어 붙인다(여러 줄에 걸친 평범한 스칼라). libyaml 은
#   'shell: bash' + '-c true {0}' 을 shell 'bash -c true {0}' 으로, 팩토리 run 줄 + '2>/dev/null || true' 를 한 명령으로 읽는다.
wf_bytes c3-cont-defaults-shell 'jobs:' 'defaults:\n  run:\n    shell: bash\n      -c true {0}\njobs:\n'
wf_bytes c3-cont-job-defaults-shell "$WJ" "$WJ"'\n    defaults:\n      run:\n        shell: bash\n          -c true {0}\n'
wf_bytes c3-cont-step-shell "$WB" "$WB"'\n        shell: bash\n          -c true {0}\n'
wf_bytes c3-cont-ortrue "$WB" "$WB"'\n          2>/dev/null || true\n'
wf_bytes c3-cont-desktop-only "$WT" "$WT"'\n          --desktop-only\n'
wf_bytes c3-cont-dash "$WI" "$WI"'\n          - 2>/dev/null || true\n'
wf_bytes c3-cont-dq "$WL" "$WL"'\n          "" || true\n'
# 전수 점검에서 찾은 것: 2회차 점검기가 받았지만 YAML(libyaml)은 다르게 읽거나 오류로 보는 모양
wf_bytes c3-cont-blank "$WB" "$WB"'\n\n          2>/dev/null || true\n'
wf_bytes c3-cont-comment "$WB" "$WB"'\n          # c\n          2>/dev/null || true\n'
wf_bytes c3-cont-item '    branches: [main]' '    branches:\n      - main\n        x\n'
wf_bytes c3-cont-top 'name: CI' 'name: CI\n  x\n'
wf_bytes c3-sq-trailing "$WJ" "$WJ"'\n    timeout-minutes: \04790\047 x\n'
wf_bytes c3-dq-trailing "$WJ" "$WJ"'\n    timeout-minutes: "90" x\n'
wf_bytes c3-sq-pair-after "$WJ" "$WJ"'\n    timeout-minutes: \04790\047 \047\047\n'
wf_root c3-blk-dedent
wf_add c3-blk-dedent <<'EOF'
      - name: block
        run: |
            echo hi
          if: false
EOF
wf_root c3-blk-lead-blank
printf '      - name: block\n        run: |\n              \n          echo hi\n' | wf_add c3-blk-lead-blank
wf_root c3-blk-comment-between
wf_add c3-blk-comment-between <<'EOF'
      - name: block
        run: |
          echo hi
         # c
          continue-on-error: true
EOF
wf_root c3-blk-comment-keycol
wf_add c3-blk-comment-keycol <<'EOF'
      - name: block
        run: |
          echo hi
        # c
          if: false
EOF
wf_bytes c3-indented-dashes "$WJ" "$WJ"'\n    ---\n'
wf_bytes c3-indented-directive "$WJ" "$WJ"'\n    \045YAML 1.2\n'
wf_bytes c3-bom-start 'name: CI' '\357\273\277name: CI\n'
wf_bytes c3-bom-value "$WJ" "$WJ"'\n    timeout-minutes: \357\273\27790\n'
wf_bytes c3-compact-nested '    branches: [main]' '    branches:\n      - - main\n'
wf_bytes c3-bare-dash-nested '    branches: [main]' '    branches:\n      -\n        - main\n'
wf_bytes c3-key-hash "$WJ" "$WJ"'\n    if#x: false\n'
wf_bytes c3-value-at "$WJ" "$WJ"'\n    timeout-minutes: @90\n'
wf_bytes c3-value-backtick "$WJ" "$WJ"'\n    timeout-minutes: `90`\n'
wf_bytes c3-value-percent "$WJ" "$WJ"'\n    timeout-minutes: \04590\n'
wf_bytes c3-value-comma "$WJ" "$WJ"'\n    timeout-minutes: ,90\n'
wf_bytes c3-value-close-bracket "$WJ" "$WJ"'\n    timeout-minutes: ]90\n'
wf_bytes c3-value-close-brace "$WJ" "$WJ"'\n    timeout-minutes: }90\n'
wf_bytes c3-value-dash "$WJ" "$WJ"'\n    timeout-minutes: - 90\n'
wf_bytes c3-value-qmark "$WJ" "$WJ"'\n    timeout-minutes: ? 90\n'
wf_bytes c3-value-colon "$WJ" "$WJ"'\n    timeout-minutes: : 90\n'
wf_bytes c3-value-dash-alone "$WJ" "$WJ"'\n    timeout-minutes: -\n'
wf_bytes c3-key-inner-space "$WJ" "$WJ"'\n    my key: x\n'
wf_bytes c3-key-double-colon "$WJ" "$WJ"'\n    if:: false\n'
wf_bytes c3-key-dash-start "$WJ" "$WJ"'\n    -if: false\n'
wf_bytes c3-tab-after-colon "$WJ" "$WJ"'\n    timeout-minutes:\t90\n'
wf_bytes c3-tab-in-value "$WJ" "$WJ"'\n    timeout-minutes: 9\t0\n'
wf_bytes c3-value-colon-space "$WJ" "$WJ"'\n    env:\n      A: b: c\n'
wf_bytes c3-value-trailing-colon "$WJ" "$WJ"'\n    env:\n      A: b:\n'
wf_root c3-value-quoted-colon
echo '      - run: echo "a: b"' | wf_add c3-value-quoted-colon
wf_bytes c3-keyless-plain "$WJ" "$WJ"'\n    stray\n'
wf_bytes c3-keyless-colon "$WJ" "$WJ"'\n    if:false\n'
wf_bytes c3-keyless-quoted "$WJ" "$WJ"'\n    "stray"\n'
wf_root c3-misaligned
echo '       timeout-minutes: 5' | wf_add c3-misaligned
wf_bytes c3-seq-at-key "$WJ" "$WJ"'\n    - x\n'
wf_root c3-key-at-seq
echo '      timeout-minutes: 5' | wf_add c3-key-at-seq
wf_bytes c3-root-indented 'name: CI' '  name: CI\n'
wf_variant c3-root-seq 's/^name: CI$/- name: CI/'
# C2-R2: 빈 경로 조각, 끝의 /, '.', '..' 조각은 checkout 을 숨길 수 있다(with: 를 지운 사본).
wf_variant c3-co-dblslash 's|actions/checkout@|actions//checkout@|; /^        with:$/d; /^          persist-credentials: false$/d'
wf_variant c3-co-trailslash 's|actions/checkout@|actions/checkout/@|; /^        with:$/d; /^          persist-credentials: false$/d'
wf_variant c3-co-dot 's|actions/checkout@|actions/checkout/.@|; /^        with:$/d; /^          persist-credentials: false$/d'
wf_variant c3-co-dotdot 's|actions/checkout@|actions/x/../checkout@|; /^        with:$/d; /^          persist-credentials: false$/d'
# C2-R3: 같은 매핑의 같은 키. libyaml 은 뒤의 값을 쓴다(두 번째 jobs:, steps:, with:, persist-credentials:, 같은 이름의 job).
wf_root c3-dup-jobs
wf_add c3-dup-jobs <<'EOF'
jobs:
  other:
    runs-on: ubuntu-24.04
    steps:
      - run: echo skipped
EOF
wf_root c3-dup-steps
wf_add c3-dup-steps <<'EOF'
    steps:
      - run: echo skipped
EOF
wf_variant c3-dup-with 's/^          persist-credentials: false$/&\
        with:\
          fetch-depth: 1/'
wf_variant c3-dup-persist 's/^          persist-credentials: false$/&\
          persist-credentials: true/'
wf_root c3-dup-job
wf_add c3-dup-job <<'EOF'
  gates:
    runs-on: ubuntu-24.04
    steps:
      - run: echo skipped
EOF
wf_bytes c3-dup-defaults 'jobs:' 'defaults:\n  run:\n    shell: bash\ndefaults:\n  run:\n    working-directory: x\njobs:\n'
wf_bytes c3-dup-name 'name: CI' 'name: CI\nname: CI2\n'
wf_bytes c3-dup-case "$WJ" "$WJ"'\n    Runs-On: ubuntu-24.04\n'
wf_root c3-dup-steps-indentless
wf_add c3-dup-steps-indentless <<'EOF'
  other:
    runs-on: ubuntu-24.04
    steps:
    - run: echo first
    timeout-minutes: 5
    steps:
    - run: echo second
EOF
# C2-R5: defaults.run 의 working-directory 표현식은 받는다(RUN_EXPR 는 단계의 run 값만 본다).
wf_bytes c3-wd-defaults-expr 'jobs:' 'defaults:\n  run:\n    shell: bash\n    working-directory: ${{ github.ref_name }}\njobs:\n'
# 받아야 하는 모양(3회차): 이 점검기가 다루는 YAML 구성이고, libyaml 도 같은 단계로 읽는다.
wf_root c3-forms
printf '      - name: tab comment\n        run: echo tab\t# c\n' | wf_add c3-forms
wf_add c3-forms <<'EOF'
      - # 주석이 붙은 맨 -
        run: echo bare
      -   name: wide dash
          run: echo wide
      - name: quoted values
        env:
          A: "a #b" # comment
          B: 'it''s'
          C: ""
        run: echo quoted
      - name: block forms
        run: | # header comment
          # first content line looks like a comment
          echo hi

         # less indented comment closes the block
      - name: folded
        run: >-
          echo folded
      - name: subpath action
        uses: actions/cache/save@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0
        with: # comment
           path: x
           key: y
    timeout-minutes: 5
  indentless:
    runs-on: ubuntu-24.04
    strategy:
      matrix:
        os:
        - macos-15
        - windows-2025
    steps:

    # comment between the key and its list
    - name: indentless
      run: echo indentless
    -
      run: echo second
    timeout-minutes: 5
EOF
mkdir -p "$WF_WORK/c3-ci-crlf/.github/workflows"
LC_ALL=C awk '{ printf "%s\r\n", $0 }' "$REPO_DIR/.github/workflows/ci.yml" >"$WF_WORK/c3-ci-crlf/.github/workflows/ci.yml"

expect_run reject '[UNSUPPORTED_SYNTAX]' '(w65) 워크플로 defaults 의 shell: bash 다음 줄 -c true {0} (여러 줄 평범한 스칼라)' wf_check c3-cont-defaults-shell
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w66) job defaults 의 shell: bash 다음 줄 -c true {0}' wf_check c3-cont-job-defaults-shell
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w67) 단계 shell: bash 다음 줄 -c true {0}' wf_check c3-cont-step-shell
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w68) 팩토리 run 줄 다음 줄 '2>/dev/null || true'" wf_check c3-cont-ortrue
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w69) 팩토리 run 줄 다음 줄 '--desktop-only'" wf_check c3-cont-desktop-only
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w70) 팩토리 run 줄 다음 줄 '- 2>/dev/null || true'" wf_check c3-cont-dash
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w71) 팩토리 run 줄 다음 줄 '\"\" || true'" wf_check c3-cont-dq
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w72) 빈 줄 뒤에 이어지는 팩토리 run 값' wf_check c3-cont-blank
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w73) 주석 줄 뒤의 더 깊은 줄' wf_check c3-cont-comment
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w74) 목록 항목 '- main' 다음 줄의 이음 줄" wf_check c3-cont-item
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w75) 최상위 'name: CI' 다음 줄의 이음 줄" wf_check c3-cont-top
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w76) 닫는 작은따옴표 뒤의 글자 ('90' x)" wf_check c3-sq-trailing
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w77) 닫는 큰따옴표 뒤의 글자 ("90" x)' wf_check c3-dq-trailing
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w78) 닫는 작은따옴표 뒤의 '' ('90' '')" wf_check c3-sq-pair-after
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w79) 블록 스칼라 내용(12 열)보다 덜 들여 쓰고 키보다 깊은 if: false' wf_check c3-blk-dedent
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w80) 블록 스칼라 첫 내용 줄보다 공백이 많은 앞쪽 빈 줄' wf_check c3-blk-lead-blank
expect_run reject '[CONTINUE_ON_ERROR]' '(w81) 블록 내용 열보다 덜 들여 쓴 주석 뒤의 continue-on-error: true (주석이 블록을 끝낸다)' wf_check c3-blk-comment-between
expect_run reject '[IF_CONDITION]' '(w82) 블록 키 열의 주석 뒤의 더 깊은 if: false (주석이 블록을 끝낸다)' wf_check c3-blk-comment-keycol
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w83) 들여 쓴 --- 줄' wf_check c3-indented-dashes
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w84) 들여 쓴 %YAML 줄' wf_check c3-indented-directive
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w85) 파일 맨 앞의 BOM' wf_check c3-bom-start
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w86) 값 안의 BOM' wf_check c3-bom-value
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w87) 한 줄에 겹친 목록 '- - main'" wf_check c3-compact-nested
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w88) 맨 '-' 아래의 목록" wf_check c3-bare-dash-nested
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w89) 키 이름 if#x' wf_check c3-key-hash
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w90) @ 로 시작하는 값' wf_check c3-value-at
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w91) 백틱으로 시작하는 값' wf_check c3-value-backtick
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w92) % 로 시작하는 값' wf_check c3-value-percent
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w93) , 로 시작하는 값' wf_check c3-value-comma
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w94) ] 로 시작하는 값' wf_check c3-value-close-bracket
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w95) } 로 시작하는 값' wf_check c3-value-close-brace
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w96) 값 자리의 '- 90'" wf_check c3-value-dash
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w97) 값 자리의 '? 90'" wf_check c3-value-qmark
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w98) 값 자리의 ': 90'" wf_check c3-value-colon
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w99) 값 자리의 '-' 하나" wf_check c3-value-dash-alone
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w100) 공백이 든 키 'my key'" wf_check c3-key-inner-space
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w101) 키 'if:' ('if:: false')" wf_check c3-key-double-colon
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w102) '-' 로 시작하는 키 '-if'" wf_check c3-key-dash-start
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w103) 콜론 뒤의 탭' wf_check c3-tab-after-colon
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w104) 값 안의 탭' wf_check c3-tab-in-value
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w105) 평범한 값 안의 콜론+공백 ('A: b: c')" wf_check c3-value-colon-space
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w106) 평범한 값 끝의 콜론 ('A: b:')" wf_check c3-value-trailing-colon
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w107) 평범한 run 값 안의 콜론+공백 (echo \"a: b\")" wf_check c3-value-quoted-colon
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w108) 키가 없는 줄 'stray'" wf_check c3-keyless-plain
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w109) 키가 없는 줄 'if:false'" wf_check c3-keyless-colon
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w110) 매핑 자리의 따옴표 문자열 "stray"' wf_check c3-keyless-quoted
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w111) 열린 매핑이나 목록의 열과 맞지 않는 들여쓰기 (7 열)' wf_check c3-misaligned
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w112) 값이 있는 키 다음의 같은 열 '- x'" wf_check c3-seq-at-key
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w113) 단계 목록 열(6 열)의 매핑 키' wf_check c3-key-at-seq
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w114) 0 열이 아닌 최상위 (  name: CI)' wf_check c3-root-indented
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w115) 목록인 최상위 (- name: CI)' wf_check c3-root-seq
expect_run reject '[USES_NOT_SHA]' '(w116) 빈 경로 조각 actions//checkout (with: 없음)' wf_check c3-co-dblslash
expect_run reject '[USES_NOT_SHA]' '(w117) 끝의 / actions/checkout/@ (with: 없음)' wf_check c3-co-trailslash
expect_run reject '[USES_NOT_SHA]' "(w118) '.' 조각 actions/checkout/.@ (with: 없음)" wf_check c3-co-dot
expect_run reject '[USES_NOT_SHA]' "(w119) '..' 조각 actions/x/../checkout@ (with: 없음)" wf_check c3-co-dotdot
expect_run reject '[DUPLICATE_KEY]' '(w120) 두 번째 최상위 jobs:' wf_check c3-dup-jobs
expect_run reject '[DUPLICATE_KEY]' '(w121) 한 job 의 두 번째 steps:' wf_check c3-dup-steps
expect_run reject '[DUPLICATE_KEY]' '(w122) checkout 단계의 두 번째 with: (persist-credentials 가 없는 with 가 이긴다)' wf_check c3-dup-with
expect_run reject '[DUPLICATE_KEY]' '(w123) 두 번째 persist-credentials: true' wf_check c3-dup-persist
expect_run reject '[DUPLICATE_KEY]' '(w124) 같은 이름의 두 번째 job gates' wf_check c3-dup-job
expect_run reject '[DUPLICATE_KEY]' '(w125) 두 번째 최상위 defaults:' wf_check c3-dup-defaults
expect_run reject '[DUPLICATE_KEY]' '(w126) 두 번째 최상위 name:' wf_check c3-dup-name
expect_run reject '[DUPLICATE_KEY]' '(w127) 대소문자만 다른 키 runs-on 과 Runs-On' wf_check c3-dup-case
expect_run reject '[DUPLICATE_KEY]' '(w128) 같은 열의 steps 목록 뒤의 두 번째 steps:' wf_check c3-dup-steps-indentless
expect_run pass 'workflow-check: OK' '(w129) defaults.run 의 working-directory: ${{ github.ref_name }} 는 받는다' wf_check c3-wd-defaults-expr
expect_run pass 'workflow-check: OK' "(w130) 받는 모양(3회차): 주석이 붙은 맨 -, 넓은 '-   ', 따옴표 값과 주석, '' 이스케이프, 블록 머리 주석, # 로 시작하는 첫 내용 줄, 덜 들여 쓴 주석이 끝내는 블록, >-, 하위 경로 action, 더 깊은 with: 항목, 같은 열의 목록과 그 뒤의 키, 탭 앞의 주석" \
  wf_check c3-forms
expect_run pass 'workflow-check: OK' '(w131) 저장소 ci.yml 의 CRLF 사본' wf_check c3-ci-crlf

# ---- P1-01 4회차 (리뷰 C3-R1, C3-R3): 대문자가 든 키, 대소문자만 다른 금지 표현과 -latest, 빈 줄·주석 줄의 탭 ----
# 아래 픽스처의 이름은 모두 c4- 로 시작한다. C3-R1: 규칙은 키를 이름으로 찾으므로 'If:', 'Continue-On-Error:' 같은 키가 규칙을
#   피했다(3회차 점검기는 아래 거부 픽스처를 모두 받는다). 대문자가 든 키는 env 변수 이름 말고 모두 KEY_NOT_LOWERCASE 다.
wf_bytes c4-coe-job "$WJ" "$WJ"'\n    Continue-On-Error: true\n'
wf_bytes c4-coe-upper "$WJ" "$WJ"'\n    CONTINUE-ON-ERROR: true\n'
wf_bytes c4-if-step "$WB" "$WB"'\n        If: false\n'
wf_bytes c4-shell-defaults 'jobs:' 'defaults:\n  run:\n    Shell: true {0}\njobs:\n'
wf_variant c4-uses-checkout 's#^        uses: actions/checkout@.*$#        Uses: actions/checkout@v4#; /^        with:$/d; /^          persist-credentials: false$/d'
wf_root c4-run-step
wf_add c4-run-step <<'EOF'
      - Run: echo ${{ github.ref_name }}
EOF
wf_bytes c4-perm-job "$WJ" "$WJ"'\n    Permissions: read-all\n'
# 이름이 env 인 job 의 키는 job 의 키다(env 변수 이름의 예외가 아니다).
wf_root c4-env-job-id
wf_add c4-env-job-id <<'EOF'
  env:
    runs-on: ubuntu-24.04
    If: false
    steps:
      - run: echo env-job
EOF
# 이벤트 이름, 표현식 문맥, 러너 레이블은 소문자로 바꿔 비교한다(거부만 더한다).
wf_variant c4-prt-key 's/^  push:$/  Pull_Request_Target:/'
wf_variant c4-prt-value '/^    branches: \[main\]$/d; s/^  push:$/  - push\
  - Pull_Request_Target/'
wf_bytes c4-secrets "$WJ" "$WJ"'\n    env:\n      TOKEN: ${{ Secrets.TOKEN }}\n'
wf_bytes c4-event "$WJ" "$WJ"'\n    env:\n      TITLE: ${{ GitHub.Event.pull_request.title }}\n'
wf_bytes c4-head-ref "$WJ" "$WJ"'\n    env:\n      REF: ${{ github.HEAD_REF }}\n'
wf_variant c4-latest 's/ubuntu-24.04/ubuntu-LATEST/'
# C3-R3: 블록 스칼라 내용 밖의 빈 줄과 주석 줄의 들여쓰기 자리의 탭(libyaml 은 오류로 본다).
wf_bytes c4-tab-comment "$WB" "$WB"'\n\t# tab comment\n'
wf_bytes c4-tab-blank "$WB" "$WB"'\n  \t\n'
# 받는 모양(4회차): 워크플로, job, 단계의 env: 바로 아래 대문자 변수 이름, 이름이 env 인 job 의 env, 블록 스칼라 내용 안의 탭
mkdir -p "$WF_WORK/c4-env-names/.github/workflows"
cat >"$WF_WORK/c4-env-names/.github/workflows/ci.yml" <<EOF
name: CI
on:
  push:
    branches: [main]
permissions:
  contents: read
env:
  CARGO_TERM_COLOR: always
jobs:
  gates:
    runs-on: ubuntu-24.04
    env:
      RUST_BACKTRACE: 1
    steps:
      - name: Checkout
        uses: actions/checkout@$WF_SHA # v7.0.1
        with:
          persist-credentials: false
      - run: bash scripts/gate/build.sh
      - run: bash scripts/gate/test.sh
      - run: bash scripts/gate/lint.sh
      - run: bash scripts/gate/integration.sh
      - name: env names
        env:
          FOO_BAR: x
        run: |
          echo env-names
  env:
    runs-on: ubuntu-24.04
    env:
      JOB_NAMED_ENV: x
    steps:
      - run: echo env-job
EOF
printf '      - name: tab in block content\n        run: |\n          echo a\n          \techo tab-content\n          \t# tab then hash is content\n          \t\n          echo b\n' | wf_add c4-env-names

expect_run reject '[KEY_NOT_LOWERCASE]' '(w132) job 의 Continue-On-Error: true (대문자가 든 키)' wf_check c4-coe-job
expect_run reject '[KEY_NOT_LOWERCASE]' '(w133) job 의 CONTINUE-ON-ERROR: true' wf_check c4-coe-upper
expect_run reject '[KEY_NOT_LOWERCASE]' '(w134) build.sh 단계의 If: false' wf_check c4-if-step
expect_run reject '[KEY_NOT_LOWERCASE]' '(w135) 워크플로 defaults.run 의 Shell: true {0}' wf_check c4-shell-defaults
expect_run reject '[KEY_NOT_LOWERCASE]' '(w136) with: 없는 Uses: actions/checkout@v4' wf_check c4-uses-checkout
expect_run reject '[KEY_NOT_LOWERCASE]' '(w137) 단계의 Run: echo ${{ github.ref_name }}' wf_check c4-run-step
expect_run reject '[KEY_NOT_LOWERCASE]' '(w138) job 의 Permissions: read-all' wf_check c4-perm-job
expect_run reject '[KEY_NOT_LOWERCASE]' '(w139) 이름이 env 인 job 의 If: false (env 변수 이름의 예외가 아니다)' wf_check c4-env-job-id
expect_run reject '[FORBIDDEN_TRIGGER]' '(w140) on: 의 키 Pull_Request_Target' wf_check c4-prt-key
expect_run reject '[FORBIDDEN_TRIGGER]' '(w141) on: 목록의 값 Pull_Request_Target' wf_check c4-prt-value
expect_run reject '[FORBIDDEN_EXPR]' '(w142) job env 의 ${{ Secrets.TOKEN }}' wf_check c4-secrets
expect_run reject '[FORBIDDEN_EXPR]' '(w143) job env 의 ${{ GitHub.Event.pull_request.title }}' wf_check c4-event
expect_run reject '[FORBIDDEN_EXPR]' '(w144) job env 의 ${{ github.HEAD_REF }}' wf_check c4-head-ref
expect_run reject '[LATEST_RUNNER]' '(w145) ubuntu-LATEST 러너 레이블' wf_check c4-latest
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w146) 탭으로 시작하는 주석 줄' wf_check c4-tab-comment
expect_run reject '[UNSUPPORTED_SYNTAX]' '(w147) 공백과 탭만 있는 줄' wf_check c4-tab-blank
expect_run pass 'workflow-check: OK' '(w148) 받는 모양(4회차): 워크플로·job·단계 env: 의 대문자 변수 이름, 이름이 env 인 job 의 env, 블록 스칼라 내용 안의 탭' \
  wf_check c4-env-names

# ---- P1-02 (리뷰 C5-R1, C5-R2, C5-R4): 시작 env 이름과 한 줄 env:, 색인·공백·문맥 통째·여러 줄 표현식, 블록 안의 내용 열 앞 탭 ----
# 아래 픽스처의 이름은 모두 c5- 로 시작한다. 99040c0 의 점검기는 아래 거부 픽스처를 모두 받는다(exit 0).
# C5-R1: bash 는 BASH_ENV 를 명령 치환까지 펼쳐 스크립트보다 먼저 읽는다(리뷰어 재현: exit 3 인 단계가 실행되지 않고 exit 0).
#   셸·로더·러너 시작 변수 이름은 워크플로, job, 단계의 env: 어디서나 FORBIDDEN_ENV 다(대소문자 무시).
wf_bytes c5-env-top-bashenv 'jobs:' 'env:\n  BASH_ENV: $(printf \045s "exit 0" > "$RUNNER_TEMP/n.sh"; echo "$RUNNER_TEMP/n.sh")\njobs:\n'
wf_bytes c5-env-job-bashenv "$WJ" "$WJ"'\n    env:\n      BASH_ENV: scripts/gate/lib.sh\n'
wf_bytes c5-env-step-bashenv "$WB" "$WB"'\n        env:\n          BASH_ENV: scripts/gate/lib.sh\n'
wf_bytes c5-env-step-lower "$WB" "$WB"'\n        env:\n          bash_env: scripts/gate/lib.sh\n'
c5_env_names="ENV SHELLOPTS POSIXLY_CORRECT PS4 IFS CDPATH GLOBIGNORE EXECIGNORE PATH BASHOPTS LD_PRELOAD DYLD_INSERT_LIBRARIES GITHUB_ENV RUNNER_TEMP ACTIONS_ALLOW_UNSECURE_COMMANDS MSYS"
for c5_name in $c5_env_names; do
  wf_bytes "c5-env-name-$c5_name" "$WB" "$WB"'\n        env:\n          '"$c5_name"': x\n'
done
# Windows 의 대소문자가 섞인 Path (디렉터리 이름은 대소문자를 가리지 않는 파일 시스템에서 PATH 와 겹치지 않게 따로 짓는다)
wf_bytes c5-env-mixed-path "$WB" "$WB"'\n        env:\n          Path: x\n'
# 한 줄 env: 값은 이름 검사가 보지 못하는 이름을 만든다(워크플로, job, 단계).
wf_bytes c5-env-inline-top 'jobs:' 'env: ${{ fromJSON(\047{"BASH_ENV":"x"}\047) }}\njobs:\n'
wf_bytes c5-env-inline-job "$WJ" "$WJ"'\n    env: ${{ fromJSON(\047{"BASH_ENV":"x"}\047) }}\n'
wf_bytes c5-env-inline-step "$WB" "$WB"'\n        env: ${{ fromJSON(\047{"BASH_ENV":"x"}\047) }}\n'
# 받는 모양: 금지 이름을 품기만 한 이름과 주석만 붙은 env:
mkdir -p "$WF_WORK/c5-env-names-ok/.github/workflows"
cat >"$WF_WORK/c5-env-names-ok/.github/workflows/ci.yml" <<EOF
name: CI
on:
  push:
    branches: [main]
permissions:
  contents: read
env:
  NOT_BASH_ENV: x
  MY_LD_PRELOAD: x
jobs:
  gates:
    runs-on: ubuntu-24.04
    env: # job env
      ENVIRONMENT: x
      XPATH: x
    steps:
      - name: Checkout
        uses: actions/checkout@$WF_SHA # v7.0.1
        with:
          persist-credentials: false
      - run: bash scripts/gate/build.sh
      - run: bash scripts/gate/test.sh
      - run: bash scripts/gate/lint.sh
      - run: bash scripts/gate/integration.sh
      - name: reserved words inside longer names
        env:
          XBASH: x
          MY_GITHUB_X: x
          PATH_X: x
          ENV_X: x
          IFS2: x
        run: echo ok
EOF
# C5-R2: job env 값의 색인, 공백, 문맥 통째 쓰기, 허용 목록 밖의 속성, 줄을 넘기는 표현식(99040c0 은 점 표기만 찾았다)
wf_bytes c5-expr-secrets-index "$WJ" "$WJ"'\n    env:\n      T: ${{ secrets[\047GITHUB_TOKEN\047] }}\n'
wf_bytes c5-expr-event-index "$WJ" "$WJ"'\n    env:\n      T: ${{ github[\047event\047][\047pull_request\047][\047title\047] }}\n'
wf_bytes c5-expr-event-spaces "$WJ" "$WJ"'\n    env:\n      T: ${{ github . event . pull_request . title }}\n'
wf_bytes c5-expr-tojson-github "$WJ" "$WJ"'\n    env:\n      T: ${{ toJSON(github) }}\n'
wf_bytes c5-expr-headref-index "$WJ" "$WJ"'\n    env:\n      T: ${{ github[\047head_ref\047] }}\n'
wf_bytes c5-expr-tojson-secrets "$WJ" "$WJ"'\n    env:\n      T: ${{ toJSON(secrets) }}\n'
wf_root c5-expr-secrets-inherit
wf_add c5-expr-secrets-inherit <<EOF
  reusable:
    uses: octo-org/octo-repo/.github/workflows/reusable.yml@$WF_SHA # v1.0.0
    secrets: inherit
EOF
wf_bytes c5-expr-github-star "$WJ" "$WJ"'\n    env:\n      T: ${{ toJSON(github.*) }}\n'
wf_bytes c5-expr-token "$WJ" "$WJ"'\n    env:\n      T: ${{ github.token }}\n'
wf_bytes c5-expr-tojson-event "$WJ" "$WJ"'\n    env:\n      T: ${{ toJSON(github.event) }}\n'
wf_bytes c5-expr-format-github "$WJ" "$WJ"'\n    env:\n      T: ${{ format(\047{0}\047, github) }}\n'
wf_bytes c5-expr-actor "$WJ" "$WJ"'\n    env:\n      T: ${{ github.actor }}\n'
wf_bytes c5-expr-two-line "$WJ" "$WJ"'\n    env:\n      T: |\n        ${{ toJSON(\n        github) }}\n'
# 범위는 첫 "${{" 부터 줄 끝까지다: 한 줄의 둘째 표현식, 따옴표 값 안의 표현식. 접는 블록(>-)은 빈 줄을 지나서도 범위다.
wf_bytes c5-expr-second "$WJ" "$WJ"'\n    env:\n      T: ${{ github.sha }}-${{ github.actor }}\n'
wf_bytes c5-expr-quoted "$WJ" "$WJ"'\n    env:\n      T: "x ${{ github.actor }}"\n'
wf_bytes c5-expr-folded "$WJ" "$WJ"'\n    env:\n      T: >-\n        ${{ toJSON(\n\n        github.event) }}\n'
# 받는 모양: 허용 속성, format 의 인자, 표현식 밖의 GitHub·https://github.com·$GITHUB_OUTPUT·.github/ 경로, 새 블록에서 풀리는 범위
wf_bytes c5-expr-ok "$WJ" "$WJ"'\n    # GitHub: https://github.com\n    env:\n      SHA: ${{ github.sha }}\n      TAG: ${{ format(\047{0}-{1}\047, github.ref_name, runner.os) }}\n      EVENT: ${{ github.event_name }}\n      LIST: |\n        ${{ github.repository }}\n        ${{ github.run_id }}\n      NOTE: |\n        GitHub https://github.com .github/workflows\n'
wf_add c5-expr-ok <<'EOF'
      - name: github words outside expressions
        run: |
          # GitHub: https://github.com
          echo "https://github.com/owner/repo" >>"$GITHUB_OUTPUT"
          ls .github/workflows
EOF
# C5-R4: 'run: |' 블록(내용 열 10) 안의 공백·탭만 있는 줄에서 내용 열보다 앞의 탭(libyaml 은 오류로 본다)
wf_root c5-tab-col0-in-block
printf '      - name: tab only line\n        run: |\n          echo a\n\t\n          echo b\n' | wf_add c5-tab-col0-in-block
wf_root c5-tab-col4-in-block
printf '      - name: tab after four spaces\n        run: |\n          echo a\n    \t\n          echo b\n' | wf_add c5-tab-col4-in-block

expect_run reject '[FORBIDDEN_ENV]' "(w149) 워크플로 env: 의 BASH_ENV: \$(printf ... > \$RUNNER_TEMP/n.sh; echo ...) (리뷰어 재현)" wf_check c5-env-top-bashenv
expect_run reject '[FORBIDDEN_ENV]' '(w150) job env: 의 BASH_ENV' wf_check c5-env-job-bashenv
expect_run reject '[FORBIDDEN_ENV]' '(w151) build.sh 단계 env: 의 BASH_ENV' wf_check c5-env-step-bashenv
expect_run reject '[FORBIDDEN_ENV]' '(w152) 단계 env: 의 소문자 bash_env' wf_check c5-env-step-lower
c5_n=153
for c5_name in $c5_env_names; do
  expect_run reject '[FORBIDDEN_ENV]' "(w$c5_n) 단계 env: 의 $c5_name" wf_check "c5-env-name-$c5_name"
  c5_n=$((c5_n + 1))
done
expect_run reject '[FORBIDDEN_ENV]' '(w169) 단계 env: 의 대소문자가 섞인 Path' wf_check c5-env-mixed-path
expect_run reject '[FORBIDDEN_ENV]' "(w170) 워크플로의 한 줄 env: \${{ fromJSON(...) }}" wf_check c5-env-inline-top
expect_run reject '[FORBIDDEN_ENV]' "(w171) job 의 한 줄 env: \${{ fromJSON(...) }}" wf_check c5-env-inline-job
expect_run reject '[FORBIDDEN_ENV]' "(w172) 단계의 한 줄 env: \${{ fromJSON(...) }}" wf_check c5-env-inline-step
expect_run pass 'workflow-check: OK' '(w173) 받는 env 이름: NOT_BASH_ENV, MY_LD_PRELOAD, ENVIRONMENT, XPATH, XBASH, MY_GITHUB_X, PATH_X, ENV_X, IFS2 와 주석만 붙은 env:' \
  wf_check c5-env-names-ok
expect_run reject '[FORBIDDEN_EXPR]' "(w174) job env 의 \${{ secrets['GITHUB_TOKEN'] }}" wf_check c5-expr-secrets-index
expect_run reject '[FORBIDDEN_EXPR]' "(w175) job env 의 \${{ github['event']['pull_request']['title'] }}" wf_check c5-expr-event-index
expect_run reject '[FORBIDDEN_EXPR]' "(w176) job env 의 \${{ github . event . pull_request . title }}" wf_check c5-expr-event-spaces
expect_run reject '[FORBIDDEN_EXPR]' "(w177) job env 의 \${{ toJSON(github) }}" wf_check c5-expr-tojson-github
expect_run reject '[FORBIDDEN_EXPR]' "(w178) job env 의 \${{ github['head_ref'] }}" wf_check c5-expr-headref-index
expect_run reject '[FORBIDDEN_EXPR]' "(w179) job env 의 \${{ toJSON(secrets) }}" wf_check c5-expr-tojson-secrets
expect_run reject '[FORBIDDEN_EXPR]' '(w180) 재사용 워크플로 job 의 secrets: inherit' wf_check c5-expr-secrets-inherit
expect_run reject '[FORBIDDEN_EXPR]' "(w181) job env 의 \${{ toJSON(github.*) }}" wf_check c5-expr-github-star
expect_run reject '[FORBIDDEN_EXPR]' "(w182) job env 의 \${{ github.token }}" wf_check c5-expr-token
expect_run reject '[FORBIDDEN_EXPR]' "(w183) job env 의 \${{ toJSON(github.event) }}" wf_check c5-expr-tojson-event
expect_run reject '[FORBIDDEN_EXPR]' "(w184) job env 의 \${{ format('{0}', github) }}" wf_check c5-expr-format-github
expect_run reject '[FORBIDDEN_EXPR]' "(w185) job env 의 \${{ github.actor }} (허용 목록 밖의 속성)" wf_check c5-expr-actor
expect_run reject '[FORBIDDEN_EXPR]' "(w186) env 블록 스칼라에서 두 줄에 걸친 \${{ toJSON( / github) }}" wf_check c5-expr-two-line
expect_run reject '[FORBIDDEN_EXPR]' "(w187) 한 줄의 둘째 표현식 \${{ github.sha }}-\${{ github.actor }}" wf_check c5-expr-second
expect_run reject '[FORBIDDEN_EXPR]' "(w188) 큰따옴표 값 안의 \${{ github.actor }}" wf_check c5-expr-quoted
expect_run reject '[FORBIDDEN_EXPR]' "(w189) 접는 블록(>-)에서 빈 줄을 사이에 둔 \${{ toJSON( / github.event) }}" wf_check c5-expr-folded
expect_run pass 'workflow-check: OK' "(w190) 받는 표현식과 낱말: github.sha, format('{0}-{1}', github.ref_name, runner.os), github.event_name, 블록의 github.repository·run_id, 표현식 밖의 GitHub·https://github.com·\$GITHUB_OUTPUT·.github/" \
  wf_check c5-expr-ok
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w191) 'run: |' 블록(내용 열 10) 안의 0 열 탭만 있는 줄" wf_check c5-tab-col0-in-block
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w192) 'run: |' 블록(내용 열 10) 안의 공백 네 개와 탭만 있는 줄" wf_check c5-tab-col4-in-block

# ---- P1-02 실행 B (Queen 결정): Windows 명령 찾기 env 이름과 블록 스칼라의 첫 내용 줄 앞 빈 줄의 탭 ----
# 99040c0 와 실행 A 의 점검기는 아래 거부 픽스처를 모두 받는다(exit 0).
# PATHEXT 와 COMSPEC 은 Windows 러너(windows-2025)의 명령 찾기(확장자)와 명령 해석기를 바꾼다. Windows 는 ComSpec 으로 쓴다.
wf_bytes c5-env-name-PATHEXT "$WB" "$WB"'\n        env:\n          PATHEXT: .JS;.EXE\n'
wf_bytes c5-env-name-COMSPEC "$WB" "$WB"'\n        env:\n          COMSPEC: x\n'
wf_bytes c5-env-job-comspec "$WJ" "$WJ"'\n    env:\n      ComSpec: x\n'
wf_bytes c5-env-top-pathext 'jobs:' 'env:\n  PathExt: .JS\njobs:\n'
# 첫 내용 줄 앞(내용 열을 아직 모른다)의 빈 줄에 있는 탭: libyaml 은 열과 상관없이 오류로 본다.
wf_root c5-tab-lead-col0
printf '      - name: tab before the first content line\n        run: |\n\t\n          echo a\n' | wf_add c5-tab-lead-col0
wf_root c5-tab-lead-col4
printf '      - name: four spaces and a tab before the first content line\n        run: |\n    \t\n          echo a\n' | wf_add c5-tab-lead-col4
wf_root c5-tab-lead-col10
printf '      - name: tab at the later content column before the first content line\n        run: |\n          \t\n          echo a\n' | wf_add c5-tab-lead-col10
# 받는 모양: 금지 이름을 품기만 한 이름, 공백만 있는 앞쪽 빈 줄, 내용 열을 안 뒤 내용 열의 탭
wf_bytes c5-runb-ok "$WB" "$WB"'\n        env:\n          PATHEXT_X: x\n          MY_COMSPEC: x\n          COMSPECS: x\n'
printf '      - name: blank lines\n        run: |\n    \n          echo a\n          \t\n          echo b\n' | wf_add c5-runb-ok

expect_run reject '[FORBIDDEN_ENV]' '(w193) 단계 env: 의 PATHEXT' wf_check c5-env-name-PATHEXT
expect_run reject '[FORBIDDEN_ENV]' '(w194) 단계 env: 의 COMSPEC' wf_check c5-env-name-COMSPEC
expect_run reject '[FORBIDDEN_ENV]' '(w195) job env: 의 ComSpec (Windows 의 표기)' wf_check c5-env-job-comspec
expect_run reject '[FORBIDDEN_ENV]' '(w196) 워크플로 env: 의 PathExt' wf_check c5-env-top-pathext
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w197) 'run: |' 블록의 첫 내용 줄 앞에 0 열 탭만 있는 빈 줄" wf_check c5-tab-lead-col0
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w198) 'run: |' 블록의 첫 내용 줄 앞에 공백 네 개와 탭만 있는 빈 줄" wf_check c5-tab-lead-col4
expect_run reject '[UNSUPPORTED_SYNTAX]' "(w199) 'run: |' 블록의 첫 내용 줄 앞에 공백 열 개(뒤의 내용 열)와 탭만 있는 빈 줄" wf_check c5-tab-lead-col10
expect_run pass 'workflow-check: OK' '(w200) 받는 모양(실행 B): PATHEXT_X, MY_COMSPEC, COMSPECS, 공백만 있는 앞쪽 빈 줄, 내용 열을 안 뒤 내용 열의 탭' \
  wf_check c5-runb-ok

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
