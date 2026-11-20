#!/usr/bin/env bash
# GitHub Actions 워크플로 점검 (P0-D). lint.sh 가 모든 범위에서 gate_step 으로 실행한다.
#
# .github/workflows/*.yml 과 *.yaml 을 본다(GitHub 은 하위 디렉터리를 읽지 않는다). 하나도 없으면 건너뛰지 않고 실패한다.
# YAML 을 해석하지 않고 줄 단위로 본다(bash 3.2, POSIX 도구만, lib.sh). 어긴 곳마다 'file:line: [CODE] 설명' 을 출력한다.
#   NO_WORKFLOWS / NOT_REGULAR / EMPTY_WORKFLOW: 워크플로 파일이 없거나, 일반 파일이 아니거나, 비었다.
#   USES_NOT_SHA / USES_NO_TAG_COMMENT: 모든 uses: 는 owner/repo[/path]@<소문자 16진수 40자> 다음에 '# <태그>' 주석이 있어야 한다.
#   PERMISSIONS_MISSING / PERMISSIONS_NOT_READ: 최상위 permissions 블록이 정확히 하나 있고 그 항목은 'contents: read' 하나다.
#   PERMISSIONS_JOB_LEVEL: job 이나 단계 수준의 permissions 를 두지 않는다.
#   PERMISSIONS_WRITE: write 권한(': write', write-all)을 주지 않는다.
#   FORBIDDEN_TRIGGER: pull_request_target 이 어디에도(주석 포함) 없다.
#   FORBIDDEN_EXPR: 'secrets.', 'github.event.', 'github.head_ref' 가 어디에도(주석 포함) 없다.
#   CHECKOUT_PERSIST: actions/checkout 을 쓰는 단계마다 'persist-credentials: false' 가 있다.
#   LATEST_RUNNER: 주석 밖에 '-latest' 러너 레이블이 없다(러너 이미지가 예고 없이 바뀐다).
#   FACTORY_COMMAND_MISSING: 팩토리 게이트 명령 네 줄 'bash scripts/gate/{build,test,lint,integration}.sh' 가 인자 없이
#     그대로(run: 줄이나 run 블록의 한 줄로) 워크플로 어딘가에 있다.
# 이 점검은 YAML 문법이나 표현식, action 입력 이름, 러너 레이블의 존재를 확인하지 않는다(그것은 첫 실행에서만 알 수 있다).
#
# 사용법: bash scripts/gate/workflow-check.sh [--root DIR]
#   --root DIR: 저장소 대신 DIR 을 검사한다(selftest.sh 의 픽스처용).

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

root="$REPO_ROOT"
if [ "$#" -eq 2 ] && [ "$1" = --root ]; then
  root="$2"
elif [ "$#" -ne 0 ]; then
  echo "사용법: bash scripts/gate/workflow-check.sh [--root DIR]" >&2
  echo "workflow-check: FAIL [USAGE] 받을 수 없는 인자: $*" >&2
  exit 2
fi
if ! cd "$root" 2>/dev/null; then
  echo "workflow-check: FAIL [USAGE] 검사할 디렉터리로 갈 수 없다: $root" >&2
  exit 2
fi

WORKFLOW_DIR=.github/workflows
FACTORY_COMMANDS="build test lint integration"

problems=0
files=()
for file in "$WORKFLOW_DIR"/*.yml "$WORKFLOW_DIR"/*.yaml; do
  # 맞는 파일이 없으면 glob 이 그대로 남는다. 깨진 링크도 여기서 잡는다.
  if [ ! -e "$file" ] && [ ! -L "$file" ]; then
    continue
  fi
  if [ ! -f "$file" ] || [ ! -r "$file" ]; then
    echo "$file:0: [NOT_REGULAR] 읽을 수 있는 일반 파일이 아니다"
    problems=$((problems + 1))
  elif [ ! -s "$file" ]; then
    echo "$file:0: [EMPTY_WORKFLOW] 빈 워크플로 파일이다"
    problems=$((problems + 1))
  else
    files+=("$file")
  fi
done
if [ "${#files[@]}" -eq 0 ]; then
  echo "$WORKFLOW_DIR:0: [NO_WORKFLOWS] 검사할 워크플로 파일(*.yml, *.yaml)이 없다. 건너뛰지 않고 실패한다."
  echo "workflow-check: FAIL"
  exit 1
fi

set +e
awk '
  BEGIN {
    hex = ""
    for (i = 0; i < 40; i++) hex = hex "[0-9a-f]"
    pinned = "^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]+[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@" hex "[[:space:]]+#[[:space:]]*[^[:space:]]+[[:space:]]*$"
    sha_without_comment = "@" hex "[[:space:]]*$"
    bad = 0
  }
  function report(ln, code, msg) {
    printf "%s:%d: [%s] %s\n", cur_file, ln, code, msg
    bad++
  }
  function close_item() {
    if (item_checkout_line > 0 && !item_persist)
      report(item_checkout_line, "CHECKOUT_PERSIST", "actions/checkout 단계에 persist-credentials: false 가 없다")
    item_indent = -1
    item_checkout_line = 0
    item_persist = 0
  }
  function finish_file() {
    close_item()
    if (top_perm == 0)
      report(1, "PERMISSIONS_MISSING", "최상위 permissions 블록이 없다 (contents: read 한 항목만 둔다)")
    else if (top_perm > 1)
      report(perm_line, "PERMISSIONS_MISSING", "최상위 permissions 가 " top_perm " 번 나온다 (한 번이어야 한다)")
    else if (perm_entries == 0 && !perm_inline)
      report(perm_line, "PERMISSIONS_NOT_READ", "최상위 permissions 블록에 항목이 없다 (contents: read 를 둔다)")
  }
  FNR == 1 {
    if (NR > 1) finish_file()
    cur_file = FILENAME
    top_perm = 0; perm_line = 0; perm_entries = 0; perm_inline = 0; in_perm = 0
    item_indent = -1; item_checkout_line = 0; item_persist = 0
  }
  {
    line = $0
    sub(/\r$/, "", line)
    # 주석을 포함해 어디에도 없어야 하는 표현
    if (index(line, "pull_request_target")) report(FNR, "FORBIDDEN_TRIGGER", "pull_request_target 을 쓰지 않는다")
    if (index(line, "secrets.")) report(FNR, "FORBIDDEN_EXPR", "secrets. 참조를 쓰지 않는다 (이 워크플로는 비밀 값이 필요 없다)")
    if (index(line, "github.event.")) report(FNR, "FORBIDDEN_EXPR", "github.event. 표현식을 쓰지 않는다 (외부 입력이 셸에 들어간다)")
    if (index(line, "github.head_ref")) report(FNR, "FORBIDDEN_EXPR", "github.head_ref 를 쓰지 않는다 (외부 입력이 셸에 들어간다)")
    if (line ~ /^[[:space:]]*(#.*)?$/) next

    code = line
    sub(/[[:space:]]+#.*$/, "", code)
    match(line, /^ */)
    ind = RLENGTH

    # 목록 항목(단계) 경계: persist-credentials 는 checkout 과 같은 항목 안에 있어야 한다.
    if (line ~ /^ *-( |$)/) {
      if (item_indent < 0 || ind <= item_indent) {
        close_item()
        item_indent = ind
      }
    } else if (item_indent >= 0 && ind <= item_indent) {
      close_item()
    }

    if (line ~ /(^|[^A-Za-z0-9_-])uses:/) {
      if (line !~ pinned) {
        if (line ~ sha_without_comment)
          report(FNR, "USES_NO_TAG_COMMENT", "SHA 뒤에 # <태그> 주석이 없다")
        else
          report(FNR, "USES_NOT_SHA", "uses 는 owner/repo@<소문자 16진수 40자> # <태그> 여야 한다")
      }
      if (line ~ /uses:[[:space:]]*actions\/checkout@/) item_checkout_line = FNR
    }
    if (code ~ /^[[:space:]]+persist-credentials:[[:space:]]*false[[:space:]]*$/) item_persist = 1

    if (line ~ /^permissions:/) {
      top_perm++
      perm_line = FNR
      in_perm = 1
      rest = code
      sub(/^permissions:[[:space:]]*/, "", rest)
      if (rest != "") {
        perm_inline = 1
        report(FNR, "PERMISSIONS_NOT_READ", "최상위 permissions 는 contents: read 한 항목의 블록이어야 한다 (지금: " rest ")")
      }
    } else if (ind == 0) {
      in_perm = 0
    } else if (in_perm) {
      entry = code
      sub(/^[[:space:]]+/, "", entry)
      sub(/[[:space:]]+$/, "", entry)
      perm_entries++
      if (entry != "contents: read")
        report(FNR, "PERMISSIONS_NOT_READ", "최상위 permissions 의 항목은 contents: read 하나여야 한다 (지금: " entry ")")
    }
    if (line ~ /^[[:space:]]+(-[[:space:]]+)?permissions:/)
      report(FNR, "PERMISSIONS_JOB_LEVEL", "job 이나 단계 수준의 permissions 를 두지 않는다 (최상위 contents: read 만 쓴다)")
    if (code ~ /:[[:space:]]*write([[:space:]]|$)/ || index(code, "write-all"))
      report(FNR, "PERMISSIONS_WRITE", "write 권한을 주지 않는다")
    if (index(code, "-latest"))
      report(FNR, "LATEST_RUNNER", "-latest 러너 레이블을 쓰지 않는다 (ubuntu-24.04, macos-15, windows-2025 처럼 고정한다)")
  }
  END {
    if (NR > 0) finish_file()
    exit (bad > 0 ? 1 : 0)
  }
' "${files[@]}"
awk_status=$?
set -e
if [ "$awk_status" -ne 0 ]; then
  problems=$((problems + 1))
fi

# 팩토리와 같은 명령: 네 게이트 명령이 인자 없이 그대로 있어야 한다. [[:space:]] 는 CR 도 받는다.
for name in $FACTORY_COMMANDS; do
  if ! grep -Eq "^[[:space:]]*(-[[:space:]]+)?(run:[[:space:]]+)?bash scripts/gate/$name\\.sh[[:space:]]*$" "${files[@]}"; then
    echo "$WORKFLOW_DIR:0: [FACTORY_COMMAND_MISSING] 'bash scripts/gate/$name.sh' (인자 없음) 줄이 없다. 팩토리 게이트와 같은 명령을 쓴다."
    problems=$((problems + 1))
  fi
done

if [ "$problems" -ne 0 ]; then
  echo "workflow-check: FAIL (파일 ${#files[@]} 개: ${files[*]})"
  exit 1
fi
echo "workflow-check: OK (파일 ${#files[@]} 개: ${files[*]}. SHA 고정, 최상위 contents: read, 금지 표현 없음, checkout persist-credentials: false, 팩토리 명령 네 줄)"
