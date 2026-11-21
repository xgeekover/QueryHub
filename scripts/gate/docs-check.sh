#!/usr/bin/env bash
# 문서 점검 (DOC-1, P0-D). lint.sh 가 모든 범위에서 gate_step 으로 실행한다. docs/TEST_PLAN.md "문서 점검 절차" 1-2 의 자동화다.
#
# 1. 명세 원문 보존: docs/REQUIREMENTS.md 에 '<!-- SPEC-VERBATIM-BEGIN -->' 줄과 '<!-- SPEC-VERBATIM-END -->' 줄이 정확히 한 번씩
#    있고 BEGIN 이 먼저 나와야 한다. 두 표시 사이의 블록(TEST_PLAN.md 의 awk 추출과 같은 명령)은 689 줄이고 SHA-256 이 아래
#    기준값과 같아야 한다.
# 2. 상대 링크: README.md, CHANGELOG.md, docs/**/*.md 의 인라인 링크 [글](대상) 에서 대상의 '#' 앞 경로가 링크한 파일의 디렉터리
#    기준으로 있어야 한다. 펜스 코드 블록(``` 또는 ~~~)과 인라인 코드(`...`) 안은 보지 않는다. http:, https:, mailto: 와 '#' 로
#    시작하는 대상은 확인하지 않는다(한국어 제목의 앵커도 확인하지 않는다). 깨진 링크마다 'file:line: target' 한 줄을 출력한다.
#    '/' 로 시작하는 대상과 빈 대상은 링크한 파일 기준으로 풀 수 없으므로 깨진 링크로 다룬다.
#    저장소 루트 안에 가둔다(P1-01): 있는지 보기 전에 '디렉터리/대상' 을 글자 그대로 정리한다(빈 조각과 '.' 은 건너뛰고 '..' 은
#    한 조각을 뺀다). 루트 위로 올라가는 링크는 그 파일이 있어도 깨진 링크로 세고 'file:line: target (저장소 루트 밖)' 을
#    출력한다. 루트 안에 머무는 '..', './', 루트 안의 심볼릭 링크 디렉터리는 받는다. GitHub 이 상대 링크를 푸는 방식과 같다.
#    한계: 인라인 링크만 본다(참조형 링크 [글][id] 는 보지 않는다). 앵커(#제목)는 확인하지 않는다. 글자 그대로 정리하므로 루트
#    밖을 가리키는 저장소 안의 심볼릭 링크는 잡지 못한다(지금 저장소에는 심볼릭 링크가 없다).
# 실패 닫힘: REQUIREMENTS.md, README.md, CHANGELOG.md, docs/ 가 없거나 읽을 수 없거나, sha256sum 과 shasum 이 모두 없으면 실패한다.
# bash 3.2, POSIX 도구만 쓴다(lib.sh).
#
# 사용법: bash scripts/gate/docs-check.sh [--root DIR]
#   --root DIR: 저장소 대신 DIR 을 검사한다(selftest.sh 의 픽스처용). 기준 해시와 줄 수는 바꿀 수 없다.

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SPEC_FILE=docs/REQUIREMENTS.md
SPEC_BEGIN='<!-- SPEC-VERBATIM-BEGIN -->'
SPEC_END='<!-- SPEC-VERBATIM-END -->'
SPEC_LINES=689
SPEC_SHA256=93a4ef5db049cb03292ea1a95bec174d7b32555cdf99f58582e73be83d3ccc7b

root="$REPO_ROOT"
if [ "$#" -eq 2 ] && [ "$1" = --root ]; then
  root="$2"
elif [ "$#" -ne 0 ]; then
  echo "사용법: bash scripts/gate/docs-check.sh [--root DIR]" >&2
  echo "docs-check: FAIL [USAGE] 받을 수 없는 인자: $*" >&2
  exit 2
fi
if ! cd "$root" 2>/dev/null; then
  echo "docs-check: FAIL [USAGE] 검사할 디렉터리로 갈 수 없다: $root" >&2
  exit 2
fi

problems=0
fail_item() {
  echo "  FAIL [$1] $2"
  problems=$((problems + 1))
}

# TEST_PLAN.md "문서 점검 절차" 1 의 추출과 같은 명령이다.
spec_block() {
  awk '/^<!-- SPEC-VERBATIM-BEGIN -->$/{f=1;next} /^<!-- SPEC-VERBATIM-END -->$/{f=0} f' "$SPEC_FILE"
}

check_spec() {
  local begin_count end_count begin_line end_line lines actual
  if [ ! -f "$SPEC_FILE" ] || [ ! -r "$SPEC_FILE" ]; then
    fail_item MISSING_FILE "$SPEC_FILE 이 없거나 읽을 수 없다"
    return 0
  fi
  begin_count="$(grep -c -x -F -e "$SPEC_BEGIN" "$SPEC_FILE" || true)"
  end_count="$(grep -c -x -F -e "$SPEC_END" "$SPEC_FILE" || true)"
  if [ "$begin_count" != 1 ] || [ "$end_count" != 1 ]; then
    fail_item SPEC_MARKERS "$SPEC_FILE: BEGIN 표시 줄 ${begin_count:-?}개, END 표시 줄 ${end_count:-?}개 (각각 정확히 1개여야 한다)"
    return 0
  fi
  begin_line="$(grep -n -x -F -e "$SPEC_BEGIN" "$SPEC_FILE" | cut -d: -f1)"
  end_line="$(grep -n -x -F -e "$SPEC_END" "$SPEC_FILE" | cut -d: -f1)"
  if [ "$begin_line" -ge "$end_line" ]; then
    fail_item SPEC_ORDER "$SPEC_FILE: END 표시($end_line 줄)가 BEGIN 표시($begin_line 줄)보다 앞에 있다"
    return 0
  fi
  lines="$(spec_block | wc -l | tr -d ' ')"
  if [ "$lines" != "$SPEC_LINES" ]; then
    fail_item SPEC_LINES "$SPEC_FILE: 명세 원문 블록이 $lines 줄이다 ($SPEC_LINES 줄이어야 한다)"
  fi
  if ! actual="$(spec_block | gate_sha256)"; then
    fail_item NO_SHA256_TOOL "명세 원문 블록의 SHA-256 을 계산하지 못했다"
    return 0
  fi
  if [ "$actual" != "$SPEC_SHA256" ]; then
    fail_item SPEC_HASH "$SPEC_FILE: 명세 원문 블록의 sha256 이 $actual 이다 ($SPEC_SHA256 이어야 한다. 원문은 고치지 않는다)"
    return 0
  fi
  if [ "$lines" = "$SPEC_LINES" ]; then
    echo "  ok   $SPEC_FILE: 명세 원문 블록 $lines 줄 ($begin_line-$end_line 줄 사이), sha256 $actual"
  fi
}

# 링크한 파일 FILE<TAB>줄 번호<TAB>대상 을 한 줄씩 출력한다. 펜스 코드 블록과 인라인 코드는 지우고 본다.
extract_links() {
  awk '
    FNR == 1 { fence = "" }
    {
      line = $0
      sub(/\r$/, "", line)
      if (fence != "") {
        if (line ~ ("^[[:space:]]*" fence)) fence = ""
        next
      }
      if (line ~ /^[[:space:]]*```/) { fence = "```"; next }
      if (line ~ /^[[:space:]]*~~~/) { fence = "~~~"; next }
      gsub(/``[^`]*``/, "", line)
      gsub(/`[^`]*`/, "", line)
      while (match(line, /\]\([^)]*\)/)) {
        target = substr(line, RSTART + 2, RLENGTH - 3)
        line = substr(line, RSTART + RLENGTH)
        sub(/^[[:space:]]+/, "", target)
        space = index(target, " ")
        if (space > 0) target = substr(target, 1, space - 1)
        if (target ~ /^<.*>$/) target = substr(target, 2, length(target) - 2)
        printf "%s\t%d\t%s\n", FILENAME, FNR, target
      }
    }
  ' "$@"
}

# path_within_root PATH: PATH(루트 기준 상대 경로)를 글자 그대로 정리해 루트 위로 올라가면 1 을 돌려준다. 파일 시스템은 보지 않는다.
path_within_root() {
  local rest="$1" seg depth=0
  while [ -n "$rest" ]; do
    case "$rest" in
      */*)
        seg="${rest%%/*}"
        rest="${rest#*/}"
        ;;
      *)
        seg="$rest"
        rest=
        ;;
    esac
    case "$seg" in
      '' | .) ;;
      ..)
        depth=$((depth - 1))
        if [ "$depth" -lt 0 ]; then
          return 1
        fi
        ;;
      *) depth=$((depth + 1)) ;;
    esac
  done
  return 0
}

check_links() {
  local files=() file list links tab line target path dir suffix checked=0 external=0 broken=0
  for file in README.md CHANGELOG.md; do
    if [ -f "$file" ] && [ -r "$file" ]; then
      files+=("$file")
    else
      fail_item MISSING_FILE "$file 이 없거나 읽을 수 없다"
    fi
  done
  if [ ! -d docs ]; then
    fail_item MISSING_FILE "docs/ 디렉터리가 없다"
  elif ! list="$(find docs -type f -name '*.md' | LC_ALL=C sort)"; then
    fail_item MISSING_FILE "docs/ 아래의 *.md 를 나열하지 못했다 (find 실패)"
  else
    while IFS= read -r file; do
      [ -n "$file" ] || continue
      files+=("$file")
    done <<EOF
$list
EOF
  fi
  if [ "${#files[@]}" -eq 0 ]; then
    fail_item MISSING_FILE "링크를 확인할 문서가 하나도 없다"
    return 0
  fi
  if ! links="$(extract_links "${files[@]}")"; then
    fail_item MISSING_FILE "문서에서 링크를 읽지 못했다 (awk 실패)"
    return 0
  fi
  tab="$(printf '\t')"
  while IFS="$tab" read -r file line target; do
    [ -n "$file" ] || continue
    case "$target" in
      http:* | https:* | mailto:* | '#'*)
        external=$((external + 1))
        continue
        ;;
    esac
    checked=$((checked + 1))
    path="${target%%#*}"
    case "$file" in
      */*) dir="${file%/*}" ;;
      *) dir=. ;;
    esac
    suffix=
    case "$path" in
      '' | /*) ;;
      *)
        if ! path_within_root "$dir/$path"; then
          suffix=' (저장소 루트 밖)'
        elif [ -e "$dir/$path" ]; then
          continue
        fi
        ;;
    esac
    echo "$file:$line: $target$suffix"
    broken=$((broken + 1))
  done <<EOF
$links
EOF
  if [ "$broken" -ne 0 ]; then
    fail_item BROKEN_LINK "깨진 상대 링크 $broken 개 (위의 file:line: target. 확인한 상대 링크 $checked 개, 문서 ${#files[@]} 개)"
  else
    echo "  ok   상대 링크 $checked 개가 모두 있다 (문서 ${#files[@]} 개. 외부·'#' 링크 $external 개는 확인하지 않는다)"
  fi
}

check_spec
check_links
if [ "$problems" -ne 0 ]; then
  echo "docs-check: FAIL ($problems 개 항목)"
  exit 1
fi
echo "docs-check: OK (명세 원문 블록과 상대 링크)"
