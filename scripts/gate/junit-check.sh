#!/usr/bin/env bash
# JUnit XML 결과 판정기.
#
# 사용법: junit-check.sh [--require-each] [--newer FILE] DIR...
#
# DIR 마다 깊이 1 의 TEST-*.xml 을 모아 <testsuite 시작 태그의 tests, skipped, failures, errors 속성을 합산한다.
# 다음 중 하나라도 해당하면 0 이 아닌 값으로 끝난다:
#   [NO_RESULTS]           어떤 DIR 에도 TEST-*.xml 이 없다
#   [ZERO_TESTS]           실행된 테스트(tests - skipped) 합계가 0 이다
#   [FAILURES]/[ERRORS]    failures 또는 errors 합계가 0 보다 크다
#   [SKIPPED]              skipped 합계가 0 보다 크다 (건너뛴 테스트로는 통과할 수 없다)
#   [STALE]                --newer FILE 보다 오래된 XML 이 있다 (이전 실행의 결과)
#   [INVALID]              tests 속성이 있는 <testsuite 태그를 찾지 못한 XML 이 있다, 또는 결과 파일을 읽거나 해석하지 못했다
#                          (일반 파일이 아님, awk 가 0 이 아닌 값으로 끝남, 합계 출력이 음이 아닌 정수 6 개가 아님)
#   [MODULE_WITHOUT_TESTS] --require-each 일 때 XML 이 없거나 실행된 테스트가 0 건인 DIR 이 있다
# 사용법 오류는 2 로 끝난다.
#
# 이식성: bash 3.2 와 POSIX awk(macOS 의 BSD awk, Linux 의 mawk/gawk)만 쓴다.
# 표준 출력·오류(CDATA) 안의 글자는 세지 않는다. 그래서 로그에 찍힌 "<testsuite tests=..." 가 결과를 바꾸지 못한다.

set -euo pipefail

usage() {
  echo "usage: junit-check.sh [--require-each] [--newer FILE] DIR..." >&2
  exit 2
}

require_each=0
newer=''
while [ $# -gt 0 ]; do
  case "$1" in
    --require-each)
      require_each=1
      shift
      ;;
    --newer)
      [ $# -ge 2 ] || usage
      newer="$2"
      shift 2
      ;;
    --)
      shift
      break
      ;;
    -*)
      usage
      ;;
    *)
      break
      ;;
  esac
done
[ $# -ge 1 ] || usage

if [ -n "$newer" ] && [ ! -e "$newer" ]; then
  echo "junit-check: --newer 기준 파일이 없다: $newer" >&2
  exit 2
fi

# 파일 하나의 합계를 "suites tests skipped failures errors invalid" 로 출력한다.
# 속성은 앞에 공백이 있을 때만 인정한다(다른 속성 이름의 일부와 섞이지 않게).
count_xml() {
  awk '
    function num(tag, name,    re) {
      re = "[ \t]" name "=\"[0-9]+\""
      if (match(tag, re) == 0) {
        return -1
      }
      return substr(tag, RSTART + length(name) + 3, RLENGTH - length(name) - 4) + 0
    }
    function add(tag,    v) {
      suites++
      v = num(tag, "tests")
      if (v < 0) {
        invalid++
      } else {
        tests += v
      }
      v = num(tag, "skipped")
      if (v > 0) {
        skipped += v
      }
      v = num(tag, "failures")
      if (v > 0) {
        failures += v
      }
      v = num(tag, "errors")
      if (v > 0) {
        errors += v
      }
    }
    # "<testsuite" 뒤에 공백, 탭, ">" 또는 줄 끝이 오는 위치("<testsuites" 는 제외). 없으면 0.
    function suite_start(s,    off, i, ch) {
      off = 0
      while ((i = index(s, "<testsuite")) > 0) {
        ch = substr(s, i + 10, 1)
        if (ch == "" || ch == " " || ch == "\t" || ch == ">") {
          return off + i
        }
        off += i + 9
        s = substr(s, i + 10)
      }
      return 0
    }
    BEGIN {
      suites = 0; tests = 0; skipped = 0; failures = 0; errors = 0; invalid = 0
      incdata = 0; intag = 0; tag = ""
    }
    {
      line = $0
      while (length(line) > 0) {
        if (incdata) {
          i = index(line, "]]>")
          if (i == 0) {
            line = ""
          } else {
            line = substr(line, i + 3)
            incdata = 0
          }
          continue
        }
        if (intag) {
          i = index(line, ">")
          if (i == 0) {
            tag = tag " " line
            line = ""
          } else {
            tag = tag " " substr(line, 1, i - 1)
            line = substr(line, i + 1)
            intag = 0
            add(tag)
          }
          continue
        }
        c = index(line, "<![CDATA[")
        p = suite_start(line)
        if (p > 0 && (c == 0 || p < c)) {
          rest = substr(line, p + 10)
          i = index(rest, ">")
          if (i == 0) {
            intag = 1
            tag = rest
            line = ""
          } else {
            add(substr(rest, 1, i - 1))
            line = substr(rest, i + 1)
          }
          continue
        }
        if (c > 0) {
          line = substr(line, c + 9)
          incdata = 1
          continue
        }
        line = ""
      }
    }
    END {
      if (intag) {
        invalid++
      }
      if (suites == 0) {
        invalid++
      }
      printf "%d %d %d %d %d %d\n", suites, tests, skipped, failures, errors, invalid
    }
  ' "$1"
}

# count_xml 출력이 음이 아닌 정수 6 개(공백 하나로 구분, 앞자리 0 없음)인지 확인한다.
# 빈 출력, 음수, 숫자가 아닌 글자, 개수가 다른 출력은 모두 거부한다. 해석하지 못한 파일을 0 으로 더하지 않기 위해서다.
valid_counts() {
  local value
  case "$1" in
    '' | *[!0-9' ']* | ' '* | *' ' | *'  '*)
      return 1
      ;;
  esac
  # 글자는 숫자와 공백뿐이므로 단어 분리만 일어난다(경로 확장은 끈다).
  set -f
  # shellcheck disable=SC2086
  set -- $1
  set +f
  [ "$#" -eq 6 ] || return 1
  for value in "$@"; do
    case "$value" in
      0 | [1-9] | [1-9]*[0-9]) ;;
      *)
        return 1
        ;;
    esac
  done
  return 0
}

total_files=0
total_tests=0
total_skipped=0
total_failures=0
total_errors=0
total_stale=0
total_invalid=0
modules_without_tests=''

for dir in "$@"; do
  files=0
  tests=0
  skipped=0
  failures=0
  errors=0
  stale=0
  invalid=0
  if [ -d "$dir" ]; then
    for xml in "$dir"/TEST-*.xml; do
      # 일치하는 파일이 없으면 패턴 문자열 그대로 남는다. 그것만 건너뛴다(깨진 심볼릭 링크는 건너뛰지 않는다).
      [ -e "$xml" ] || [ -L "$xml" ] || continue
      files=$((files + 1))
      if [ ! -f "$xml" ]; then
        invalid=$((invalid + 1))
        echo "junit-check: 결과 파일이 일반 파일이 아니다(디렉터리, 깨진 링크 등): $xml"
        continue
      fi
      if [ -n "$newer" ] && [ -z "$(find "$xml" -prune -newer "$newer" -print)" ]; then
        stale=$((stale + 1))
        echo "junit-check: 오래된 결과 파일(--newer 기준보다 이전): $xml"
      fi
      # awk 의 종료 코드를 확인하려고 출력을 먼저 변수에 받는다. 읽지 못했거나 해석하지 못한 파일은 0 건으로 더하지 않고
      # INVALID 로 센다(그렇지 않으면 같은 모듈의 다른 XML 때문에 OK 가 된다).
      if ! counts="$(count_xml "$xml")"; then
        invalid=$((invalid + 1))
        echo "junit-check: 결과 파일을 읽거나 해석하지 못했다 (awk 실패): $xml"
        continue
      fi
      if ! valid_counts "$counts"; then
        invalid=$((invalid + 1))
        echo "junit-check: 결과 파일의 합계를 해석하지 못했다 (출력: '$counts'): $xml"
        continue
      fi
      read -r x_suites x_tests x_skipped x_failures x_errors x_invalid <<<"$counts"
      if [ "$x_invalid" -gt 0 ]; then
        invalid=$((invalid + 1))
        echo "junit-check: tests 속성이 있는 <testsuite 태그를 찾지 못했다 (suites=$x_suites): $xml"
      fi
      tests=$((tests + x_tests))
      skipped=$((skipped + x_skipped))
      failures=$((failures + x_failures))
      errors=$((errors + x_errors))
    done
  fi
  executed=$((tests - skipped))
  echo "junit-check: $dir files=$files tests=$tests executed=$executed skipped=$skipped failures=$failures errors=$errors stale=$stale"
  if [ "$files" -eq 0 ] || [ "$executed" -le 0 ]; then
    modules_without_tests="$modules_without_tests $dir"
  fi
  total_files=$((total_files + files))
  total_tests=$((total_tests + tests))
  total_skipped=$((total_skipped + skipped))
  total_failures=$((total_failures + failures))
  total_errors=$((total_errors + errors))
  total_stale=$((total_stale + stale))
  total_invalid=$((total_invalid + invalid))
done

total_executed=$((total_tests - total_skipped))
echo "junit-check: TOTAL dirs=$# files=$total_files tests=$total_tests executed=$total_executed skipped=$total_skipped failures=$total_failures errors=$total_errors stale=$total_stale"

problems=0
report() {
  echo "junit-check: FAIL [$1] $2"
  problems=$((problems + 1))
}

if [ "$total_files" -eq 0 ]; then
  report NO_RESULTS "결과 XML(TEST-*.xml)이 하나도 없다"
fi
if [ "$total_executed" -le 0 ]; then
  report ZERO_TESTS "실행된 테스트가 0건이다 (zero tests executed)"
fi
if [ "$total_failures" -gt 0 ]; then
  report FAILURES "실패한 테스트가 있다 (failures=$total_failures)"
fi
if [ "$total_errors" -gt 0 ]; then
  report ERRORS "오류가 난 테스트가 있다 (errors=$total_errors)"
fi
if [ "$total_skipped" -gt 0 ]; then
  report SKIPPED "건너뛴 테스트가 있다 (skipped=$total_skipped). 건너뛴 테스트로는 통과할 수 없다"
fi
if [ "$total_stale" -gt 0 ]; then
  report STALE "이번 실행보다 오래된 결과 파일이 있다 (stale=$total_stale)"
fi
if [ "$total_invalid" -gt 0 ]; then
  report INVALID "읽을 수 없는 결과 파일이 있다 (invalid=$total_invalid)"
fi
if [ "$require_each" -eq 1 ] && [ -n "$modules_without_tests" ]; then
  report MODULE_WITHOUT_TESTS "실행된 테스트가 없는 모듈 결과 디렉터리:$modules_without_tests"
fi

if [ "$problems" -gt 0 ]; then
  exit 1
fi
echo "junit-check: OK"
