#!/usr/bin/env bash
# junit-check.sh 자체 검증. test.sh 가 판정을 믿기 전에 먼저 실행한다.
#
# 임시 디렉터리(mktemp -d, trap 으로 삭제)에 JUnit XML 픽스처를 만들고, 거짓 통과 시나리오는 모두 거부되고
# 깨끗한 픽스처는 통과하는지 확인한다. 기대와 다른 결과가 하나라도 있으면 1 로 끝난다.
# 픽스처의 표준 출력(CDATA)에는 가짜 "<testsuite tests=99" 를 넣어 둔다. 판정기가 그것을 세면 (b) 가 통과해 버린다.

set -euo pipefail

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

if [ "$unmet" -ne 0 ]; then
  echo "selftest: FAIL ($unmet 개 시나리오가 기대와 다르다)"
  exit 1
fi
if [ "$skipped_scenarios" -ne 0 ]; then
  echo "selftest: PASS (실행한 시나리오는 모두 기대대로 동작한다. 이 환경에서 만들 수 없어 건너뛴 시나리오 $skipped_scenarios 개)"
  exit 0
fi
echo "selftest: PASS (junit-check.sh 가 모든 시나리오에서 기대대로 동작한다)"
