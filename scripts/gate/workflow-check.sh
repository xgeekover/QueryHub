#!/usr/bin/env bash
# GitHub Actions 워크플로 점검 (P0-D, P1-01, P1-02). lint.sh 가 모든 범위에서 gate_step 으로 실행한다.
#
# .github/workflows/*.yml 과 *.yaml 을 본다(GitHub 은 하위 디렉터리를 읽지 않는다). 하나도 없으면 건너뛰지 않고 실패한다.
# YAML 파서가 아니다. 줄 단위로 보고(bash 3.2, POSIX 도구만, lib.sh), 줄 단위로 다룰 수 없는 문법은 받지 않고 거부한다(실패 닫힘).
# 어긴 곳마다 'file:line: [CODE] 설명' 을 출력한다.
#   NO_WORKFLOWS / NOT_REGULAR / EMPTY_WORKFLOW: 워크플로 파일이 없거나, 일반 파일이 아니거나, 비었다.
#   UNSUPPORTED_SYNTAX: 이 점검기가 다루지 않는 문법이나 문자가 있다.
#     모든 줄(블록 스칼라 내용, 주석, 빈 줄 포함): 줄 끝 CRLF 의 CR 을 뗀 뒤에도 남은 CR, U+0085(NEL), U+2028, U+2029. YAML 은
#     이 문자에서도 줄을 나누지만 awk 는 LF 에서만 나누므로, 그 뒤의 키는 어떤 규칙에도 보이지 않는다. 탭이 아닌 제어 문자
#     (U+0001-U+001F, U+007F)와 BOM(U+FEFF, 파일 맨 앞 포함)도 거부한다(YAML 에서 쓸 수 없거나 [[:space:]] 가 일부를 공백으로
#     읽는다). NUL 바이트는 awk 에 따라(BWK awk) 그 뒤를 읽지 못하므로 awk 밖에서 파일마다 찾고 'file:0:' 으로 알린다.
#     블록 스칼라 내용 밖의 줄(' #' 뒤 주석을 뗀 뒤): 따옴표로 감싼 키(콜론이 바로 붙은 '"k":v' 포함),
#     흐름 매핑 '{', 평범한 값([A-Za-z0-9_./-])만 한 줄에 담지 않은 흐름 시퀀스 '[', 앵커 '&', 별칭 '*', 병합 키 '<<:', 태그 '!',
#     0 열의 문서 표시 '---', '...' 와 지시자 '%', 키와 콜론 사이의 공백('uses :'), 명시적 키 '? ' 와 값 줄 ': ',
#     |, |-, |+, >, >-, >+ 가 아닌 블록 스칼라 머리(들여쓰기 숫자), 줄을 넘기는 따옴표 문자열, 큰따옴표 안의 '\' 이스케이프,
#     들여쓰기의 탭. 블록 스칼라 내용 밖의 빈 줄과 주석 줄에서도 들여쓰기 자리의 탭을 거부한다(P1-01 리뷰 3회차 C3-R3).
#     허용 목록(P1-01 리뷰 2회차 C2-R1 뒤): 블록 스칼라 내용 밖의 줄은 아래 모양 가운데 하나여야 하고, 그 밖의 줄은
#       UNSUPPORTED_SYNTAX 다. '키:', '키: 값', '- 키:', '- 키: 값', '- 값', '-'. 키는 따옴표 없는 [A-Za-z0-9_-] 이고 첫 글자는
#       '-' 가 아니다. 값은 한 줄에서 끝나는 평범한 값, 한 줄에서 닫히는 따옴표 문자열, 평범한 값만 담은 한 줄 흐름 시퀀스,
#       블록 스칼라 머리다. 그래서 다음을 거부한다: 키가 없는 줄, 탭(공백 자리 어디든), 한 줄에 겹친 목록 항목('- - x'), 평범한
#       값을 시작할 수 없는 문자(',', ']', '}', '%', '@', 백틱)와 값 자리의 '- ', '? ', ': ', 평범한 값 안의 ': ' 와 끝의 ':',
#       닫는 따옴표 뒤의 글자.
#     들여쓰기 구조(같은 회차): 앞 구조 줄보다 깊게 들여 쓴 줄은 앞 줄이 값이 빈 키나 맨 '-' 일 때만 받는다(자식). 값이 있는
#       키나 '- 값' 다음의 더 깊은 줄은 YAML 에서 앞 값에 이어 붙는 여러 줄 평범한 스칼라('shell: bash' 다음 줄의 '-c true'
#       는 shell 을 'bash -c true' 로 바꾼다)이므로 거부한다. 덜 들여 쓴 줄은 열린 매핑이나 목록의 열에 맞아야 한다. 같은
#       열의 '- ' 목록은 값이 빈 키 바로 다음에서만 받는다. 맨 '-' 아래의 '-'(목록 안의 목록)와 0 열이 아닌 최상위도 거부한다.
#     블록 스칼라(같은 회차): 내용 열은 첫 내용 줄의 열이다. 그보다 덜 들여 쓰고 키보다 깊은, 주석이 아닌 줄은 거부한다. 그
#       열보다 덜 들여 쓴 주석 줄은 YAML 에서 블록을 끝내므로 여기서도 블록을 닫는다(그 뒤의 더 깊은 줄은 위 규칙이 거부한다).
#       첫 내용 줄보다 공백이 많은 앞쪽 빈 줄도 거부한다. 내용 열을 안 뒤의 공백·탭만 있는 줄에서 내용 열보다 앞에 있는 탭도
#       거부한다(P1-02, C5-R4. libyaml 은 들여쓰기 자리의 탭을 오류로 본다). 내용 열과 그 뒤의 탭은 문자열이라 받는다. 첫 내용
#       줄 앞의 공백·탭만 있는 빈 줄(내용 열을 아직 모른다)에 있는 탭은 열과 상관없이 거부한다(P1-02. libyaml 은 내용 열을 정하기
#       전의 탭을 모두 오류로 본다).
#   DUPLICATE_KEY: 같은 매핑(워크플로, job, 단계, with:, env: 등 어디든)에 같은 키가 두 번 있다(대소문자는 가리지 않는다).
#     libyaml 은 뒤의 값을 쓴다(다른 파서는 다를 수 있다): 두 번째 jobs:, steps:, with:, persist-credentials:, 같은 이름의 job 이
#     앞의 것을 가린다.
#   KEY_NOT_LOWERCASE: 키에 대문자(A-Z)가 있다(P1-01 리뷰 3회차 C3-R1). 아래 규칙은 키를 이름으로 찾는데 GitHub 이 키의
#     대소문자를 가리는지 확인하지 않았으므로, 'If:', 'Continue-On-Error:', 'Shell:', 'Uses:' 처럼 대소문자만 다른 키는 규칙을
#     피하지 못하게 모두 거부한다. 예외는 워크플로, job, 단계의 env: 바로 아래 키(환경 변수 이름)다(그 이름은 FORBIDDEN_ENV 가
#     본다). container·services 의 env, matrix, with: 아래의 키와 job id 는 소문자여야 한다.
#   FORBIDDEN_ENV: 워크플로, job, 단계의 env: 바로 아래 변수 이름(대소문자 무시)이 bash 로 시작하거나(BASH_ENV, BASHOPTS,
#     BASH_COMPAT, BASH_XTRACEFD, BASH_FUNC_* 등), LD_, DYLD_, GITHUB_, RUNNER_, ACTIONS_, MSYS 로 시작하거나, ENV, SHELLOPTS,
#     POSIXLY_CORRECT, PS4, IFS, CDPATH, GLOBIGNORE, EXECIGNORE, PATH, PATHEXT, COMSPEC 이다(P1-02, 리뷰 C5-R1). 셸, 동적 로더,
#     러너가 단계를 시작하고 실행하는 방식과 Windows 의 명령 찾기를 바꾸는 이름이다: bash 는 BASH_ENV 를 명령 치환까지 펼쳐
#     스크립트보다 먼저 읽으므로 'BASH_ENV: $(...)' 한 줄이 게이트 네 단계를 실행하지 않고 exit 0 으로 끝낸다. PATH 는
#     'run: bash scripts/gate/...' 의 bash 를 고르고, PATHEXT 와 COMSPEC 은 Windows 러너(windows-2025)에서 명령 이름에 붙일
#     확장자와 명령 해석기를 바꾼다. ACTIONS_ALLOW_UNSECURE_COMMANDS 는
#     set-env 명령을 되살린다. Windows 의 이름은 대소문자를 가리지 않아 소문자로 비교한다.
#     같은 세 자리의 env: 에 한 줄 값(예: 'env: ${{ fromJSON(...) }}')을 두는 것도 거부한다: 위 검사가 보지 못하는 이름을 만든다.
#     env: 는 '이름: 값' 줄의 블록 매핑으로만 쓴다(주석만 붙은 'env: # ...' 는 받는다).
#   USES_NOT_SHA / USES_NO_TAG_COMMENT: 모든 uses: 는 owner/repo[/path]@<소문자 16진수 40자> 다음에 '# <태그>' 주석이 있어야 한다.
#     경로 조각은 비지 않고 '.' 이나 '..' 가 아니다('actions//checkout', 'actions/checkout/' 처럼 checkout 을 숨기는 모양을 거부).
#   PERMISSIONS_MISSING / PERMISSIONS_NOT_READ: 최상위 permissions 블록이 정확히 하나 있고 그 항목은 'contents: read' 하나다.
#   PERMISSIONS_JOB_LEVEL: job 이나 단계 수준의 permissions 를 두지 않는다.
#   PERMISSIONS_WRITE: write 권한(': write', 따옴표로 감싼 write, write-all)을 주지 않는다.
#   FORBIDDEN_TRIGGER: pull_request_target 이 어디에도(주석 포함, 대소문자 무시) 없다.
#   FORBIDDEN_EXPR: 'secrets.', 'github.event.', 'github.head_ref' 가 어디에도(주석 포함, 대소문자 무시) 없다. P1-02(리뷰 C5-R2)
#     부터 색인·공백·문맥 통째 쓰기도 거부한다. 식별자 secrets(앞뒤가 [a-z0-9_-] 가 아닌 것: secrets['X'], toJSON(secrets),
#     job 의 'secrets: inherit')는 어디에도(주석 포함, 대소문자 무시) 없다. 표현식 범위 안의 식별자 github(앞이 [a-z0-9_.-], 뒤가
#     [a-z0-9_-] 가 아닌 것)는 바로 뒤에 '.' 과 허용 속성이 식별자 경계까지 와야 한다. 허용 속성: ref, ref_name, ref_type, sha,
#     repository, repository_owner, run_id, run_number, run_attempt, workflow, job, event_name, server_url, api_url. 그래서
#     github['event'], 'github . event', toJSON(github), github.*, format('{0}', github), github.token, github.actor 를 거부한다.
#     표현식 범위: 줄(주석 포함)의 첫 '${{' 부터 줄 끝까지다(따옴표는 해석하지 않는다. 한 줄의 둘째 표현식, 따옴표 값 안의
#     표현식, 표현식 뒤의 글자도 범위다). 블록 스칼라(|, >) 내용에서는 '${{' 가 든 내용 줄 다음의 같은 블록 내용 줄 전체가
#     범위다(줄을 넘기는 표현식). 블록 밖에서 줄을 넘기는 값은 UNSUPPORTED_SYNTAX 다(여러 줄 평범한 스칼라, 줄을 넘기는 따옴표
#     문자열). 범위 밖의 GitHub, https://github.com, $GITHUB_OUTPUT, .github/ 경로는 받는다. 범위 안에서는 문자열 안의 github
#     (format('github'))와 https://github.com 도 거부한다(실패 닫힘).
#   CHECKOUT_PERSIST: actions/checkout 을 쓰는 단계마다(owner/repo 의 대소문자는 가리지 않는다) 그 단계의 with: 블록 바로 아래
#     항목으로 'persist-credentials: false' 가 있다(env: 아래, 더 깊은 곳, 'with: |' 문자열 안, 다른 단계에 있는 것은 세지 않는다).
#   CONTINUE_ON_ERROR / IF_CONDITION: continue-on-error: 와 if: 키를 어디에도(워크플로, job, 단계) 두지 않는다. 값은 보지 않는다.
#     실패를 덮거나 단계·job 을 건너뛰게 해 게이트가 막지 못한다(건너뛴 job 은 실행을 실패시키지 않는다). 대소문자만 다른 키
#     (Continue-On-Error:, If:)는 KEY_NOT_LOWERCASE 가 거부한다.
#   SHELL_NOT_BASH: shell: 값은 정확히 bash 다(true 로 시작하는 사용자 셸 템플릿은 스크립트를 실행하지 않는다).
#   RUN_KEY: run: 키는 단계의 키, 곧 jobs.<job>.steps 목록 항목의 바로 아래 키로만 두고 한 단계에 하나만 둔다. env:, with:(action
#     입력), matrix, outputs 같은 다른 곳의 run: 과 한 단계의 두 번째 run: 은 거부한다. 예외는 워크플로와 job 의 defaults: 바로
#     아래에 있는 값이 빈 run:(그 아래 shell: bash) 하나다.
#   RUN_EXPR: 단계의 run 값(run: 줄과 그보다 깊게 들여 쓴 다음 줄 모두, 주석처럼 보이는 줄 포함)에 '${{' 가 없다. GitHub 은
#     셸이 보기 전에 표현식을 펼친다. name:, runs-on:, concurrency, with:, env:, defaults: 값의 표현식은 받는다.
#   LATEST_RUNNER: 주석 밖에 '-latest' 러너 레이블이 없다(대소문자 무시. 러너 이미지가 예고 없이 바뀐다).
#   FACTORY_COMMAND_MISSING: 팩토리 게이트 명령 네 줄 'bash scripts/gate/{build,test,lint,integration}.sh' 가 인자 없이
#     단계의 한 줄짜리 run 값('run: bash scripts/gate/<이름>.sh', 앞의 '- ' 는 받는다)으로 워크플로 어딘가에 있다. 블록 스칼라
#     안의 줄은 세지 않는다(set +e, '|| true', 'exit 0' 으로 무력화할 수 있다). 단계 밖의 run:(env:, with: 아래)도 세지 않는다.
#     그 run 줄 다음의 더 깊은 줄('2>/dev/null || true', '--desktop-only')은 위의 들여쓰기 구조 규칙이 거부한다.
# 이 점검은 action 입력 이름, 표현식의 뜻, 러너 레이블의 존재, 트리거와 브랜치 필터, working-directory 를 확인하지 않는다(그것은
# 첫 실행에서만 알 수 있거나 범위 밖이다). env 이름은 FORBIDDEN_ENV 의 이름만 거부한다(P1-02): HOME, TMPDIR, LANG·LC_*, 도구
# 옵션 변수(JAVA_TOOL_OPTIONS, NODE_OPTIONS, GRADLE_OPTS, RUSTC_WRAPPER 같은 것)로 도구를 바꿔치기하는 것은 확인하지 않고,
# container·services 의 env 도 보지 않는다. GitHub 이 GITHUB_* 같은 예약 이름과 한 줄 env:, 색인·공백 표현식을 어떻게 다루는지는
# 확인하지 않았다(CI-1. 새 규칙은 거부만 더한다). 표현식은 secrets 와 github 문맥만 본다: inputs, vars, needs, steps(출력),
# matrix, env, job, runner, strategy 문맥의 값은 보지 않는다. 표현식 범위는 ASCII 로 쓴 '${{' 와 github 만 본다(GitHub 의
# 표현식 해석기는 확인하지 않았다). 단계 명령이 무엇을 하는지도 보지 않는다: 앞 단계가 게이트 스크립트를 고쳐 쓰거나
# $GITHUB_ENV·$GITHUB_PATH·$GITHUB_OUTPUT 에 써서(예: BASH_ENV 설정) 뒤 단계를 바꾸는 것을 잡지 못하고,
# container: 와 services: 도 보지 않는다(P1-01 리뷰 3회차 C3-R2). 줄 단위 점검기이므로 YAML 파서와 같다고
# 보장하지 않는다: libyaml(Ruby Psych)과 비교한 구성만 확인했고, GitHub 의 파서는 확인하지 않았다. U+0085 가 아닌 C1 제어
# 문자와 올바르지 않은 UTF-8 은 보지 않는다(줄 나눔을 바꾸지 않는다).
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

# NUL 바이트: BWK awk 는 문자열을 NUL 에서 끊으므로 그 뒤의 CR 같은 줄바꿈 문자도 awk 에는 보이지 않는다. 그래서 awk 밖에서
# 파일마다 찾는다(tr, cmp: POSIX). 지운 사본이 원본과 다르면 NUL 이 있다.
for file in "${files[@]}"; do
  if ! LC_ALL=C tr -d '\000' <"$file" | cmp -s - "$file"; then
    echo "$file:0: [UNSUPPORTED_SYNTAX] NUL 바이트가 있다 (YAML 에서 쓸 수 없고, awk 에 따라 그 뒤를 읽지 못한다)"
    problems=$((problems + 1))
  fi
done

# awk 는 macOS BWK awk, mawk, gawk 에서 같게 동작하는 것만 쓴다(match, substr, index, split, sub, gsub, tolower, sprintf,
# [[:space:]]). LC_ALL=C 로 돌린다: 그러면 세 awk 모두 문자열을 바이트로 다루므로, "\302\205" 같은 8진 이스케이프는 바이트이고
# index() 는 바이트를 비교하며, [[:space:]] 는 ASCII 공백(공백, \t, \n, \v, \f, \r)뿐이다. 입력이 올바른 UTF-8 이 아니어도 같다.
# 프로그램 안에 작은따옴표를 쓸 수 없으므로 sq 로 넘긴다. 팩토리 명령(네 줄)도 awk 가 센다: 블록 스칼라 안인지 알아야 한다.
set +e
LC_ALL=C awk -v sq="'" -v factory="$FACTORY_COMMANDS" -v wfdir="$WORKFLOW_DIR" '
  BEGIN {
    hex = ""
    for (i = 0; i < 40; i++) hex = hex "[0-9a-f]"
    # 탭(9), LF(10), CR(13, 따로 본다)이 아닌 C0 제어 문자와 DEL. LC_ALL=C 에서 sprintf("%c", n) 은 그 값의 바이트 하나다.
    nctl = 0
    for (i = 1; i < 32; i++) if (i != 9 && i != 10 && i != 13) ctl[++nctl] = sprintf("%c", i)
    ctl[++nctl] = sprintf("%c", 127)
    # owner/repo[/path]: 조각마다 한 글자 이상이다(빈 조각과 끝의 / 는 맞지 않는다). ".", ".." 조각은 따로 거부한다.
    pinned = "^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]+[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+@" hex "[[:space:]]+#[[:space:]]*[^[:space:]]+[[:space:]]*$"
    sha_without_comment = "@" hex "[[:space:]]*$"
    quote = "[\"" sq "]"
    quoted_write = ":[[:space:]]*" quote "?write" quote "?([[:space:]]|$)"
    plain_flow_seq = "^\\[[[:space:]]*[A-Za-z0-9_./-]+([[:space:]]*,[[:space:]]*[A-Za-z0-9_./-]+)*[[:space:]]*\\]$"
    factory_line = "^[[:space:]]*(-[[:space:]]+)?run:[[:space:]]+bash scripts/gate/[A-Za-z0-9_-]+\\.sh[[:space:]]*$"
    ncmd = split(factory, cmd, " ")
    for (i = 1; i <= ncmd; i++) want[cmd[i]] = 1
    # 표현식 범위 안에서 github 뒤에 올 수 있는 속성(P1-02, C5-R2). 이 밖의 속성, 색인, 공백, 문맥 통째 쓰기는 거부한다.
    ngh = split("ref ref_name ref_type sha repository repository_owner run_id run_number run_attempt workflow job event_name server_url api_url", ghp, " ")
    for (i = 1; i <= ngh; i++) gh_allowed[ghp[i]] = 1
    bad = 0
  }
  function report(ln, code, msg) {
    printf "%s:%d: [%s] %s\n", cur_file, ln, code, msg
    bad++
  }
  function unsupported(what) {
    report(FNR, "UNSUPPORTED_SYNTAX", "줄 단위 점검기가 다루지 않는 YAML 문법이다: " what " (블록 형식의 평범한 키와 값으로 쓴다)")
  }
  # 허용 목록의 평범한 값: 첫 글자가 YAML 표시자가 아니고(- ? : 는 뒤에 공백이 아닌 글자가 오면 받는다), 콜론+공백과 끝의
  # 콜론이 없다. 블록 머리, 흐름, 앵커·별칭, 태그, 따옴표로 시작하는 값은 그 규칙들이 따로 본다.
  function plain_check(v,    c, c2) {
    c = substr(v, 1, 1)
    if (index("|>[{&*!\"" sq, c)) return
    if (index(",]}%@`#", c)) {
      unsupported("평범한 값을 시작할 수 없는 문자 " c)
      return
    }
    c2 = substr(v, 2, 1)
    if (index("-?:", c) && (c2 == "" || c2 ~ /[[:space:]]/)) {
      unsupported("값 자리의 표시자 " c " (목록 항목, 명시적 키, 값 줄)")
      return
    }
    if (v ~ /:([[:space:]]|$)/) unsupported("평범한 값 안의 콜론+공백 또는 끝의 콜론 (YAML 은 이것을 키로 읽거나 오류로 본다)")
  }
  # 표현식 범위 s(소문자) 안의 식별자 github 마다: 바로 뒤가 "." 과 허용 속성(식별자 경계까지)이어야 한다. 앞이
  # [a-z0-9_.-] 이거나 뒤가 [a-z0-9_-] 인 github 는 다른 이름의 일부다(steps.github, $github_output, .github/). 첫 위반만 알린다.
  function expr_github(s,    t, pos, p, c, prop) {
    t = s
    pos = 0
    while (match(t, /github/)) {
      p = pos + RSTART
      t = substr(t, RSTART + 6)
      pos = p + 5
      c = (p > 1) ? substr(s, p - 1, 1) : ""
      if (c != "" && c ~ /[a-z0-9_.-]/) continue
      c = substr(s, p + 6, 1)
      if (c != "" && c ~ /[a-z0-9_-]/) continue
      prop = ""
      if (c == "." && match(substr(s, p + 7), /^[a-z0-9_-]+/)) prop = substr(s, p + 7, RLENGTH)
      if (!(prop in gh_allowed)) {
        report(FNR, "FORBIDDEN_EXPR", "표현식 안의 github 는 github.<허용 속성> 으로만 쓴다 (허용: ref, ref_name, ref_type, sha, repository, repository_owner, run_id, run_number, run_attempt, workflow, job, event_name, server_url, api_url. 색인 github[...], 공백, toJSON(github), github.*, 그 밖의 속성은 거부한다. 지금: " substr(s, p, 24) ")")
        return
      }
    }
  }
  function close_item() {
    if (item_checkout_line > 0 && !item_persist)
      report(item_checkout_line, "CHECKOUT_PERSIST", "actions/checkout 단계에 persist-credentials: false 가 없다")
    item_indent = -1
    item_checkout_line = 0
    item_persist = 0
    item_key_col = -1
    item_runs = 0
    with_col = -1
    with_child_col = -1
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
    item_key_col = -1; item_runs = 0; with_col = -1; with_child_col = -1
    blk_col = -1; run_col = -1; blk_ind = -1; blk_lead = 0; blk_expr = 0
    sd = 0
    nl = 0; pv_last = -1; pv_child = 0; pv_empty = -1; pv_bare = 0
  }
  {
    line = $0
    sub(/\r$/, "", line)
    # YAML 의 줄바꿈은 LF 말고도 CR 과 (YAML 1.1, libyaml) U+0085, U+2028, U+2029 다. awk 는 LF 에서만 줄을 나누므로 이런 문자
    # 뒤의 키는 어떤 규칙에도 보이지 않는다(블록 스칼라 안의 CR 은 블록을 끝내고 키를 끼워 넣을 수 있다). 그래서 블록 스칼라
    # 내용, 주석, 빈 줄을 가리지 않고 모든 줄에서 거부한다. 줄 끝 CRLF 의 CR 은 위에서 뗐다.
    if (index(line, "\r") || index(line, "\302\205") || index(line, "\342\200\250") || index(line, "\342\200\251"))
      report(FNR, "UNSUPPORTED_SYNTAX", "LF 가 아닌 줄바꿈 문자(CR, U+0085, U+2028, U+2029)가 줄 안에 있다 (YAML 은 여기서도 줄을 나누지만 이 점검기는 LF 에서만 나눈다. 줄은 LF 나 CRLF 로만 끝낸다)")
    # BOM(U+FEFF): YAML 은 파일 맨 앞의 BOM 을 건너뛰고 문서 안의 BOM 은 오류로 보지만, 이 점검기에는 키 앞의 글자다.
    if (index(line, "\357\273\277"))
      report(FNR, "UNSUPPORTED_SYNTAX", "BOM(U+FEFF)이 있다 (파일 맨 앞이어도 받지 않는다. BOM 없는 UTF-8 로 저장한다)")
    for (i = 1; i <= nctl; i++) {
      if (index(line, ctl[i])) {
        report(FNR, "UNSUPPORTED_SYNTAX", "탭이 아닌 제어 문자(U+0001-U+001F, U+007F)가 있다 (YAML 에서 쓸 수 없고, 이 점검기의 [[:space:]] 는 일부를 공백으로 읽는다)")
        break
      }
    }
    # 주석을 포함해 어디에도 없어야 하는 표현. 대소문자는 가리지 않는다(P1-01 리뷰 3회차 C3-R1): GitHub 이 이벤트 이름과 표현식
    # 문맥 이름의 대소문자를 가리는지 확인하지 않았으므로, 소문자로 바꿔 비교해 거부만 더한다.
    lline = tolower(line)
    if (index(lline, "pull_request_target")) report(FNR, "FORBIDDEN_TRIGGER", "pull_request_target 을 쓰지 않는다 (대소문자 무시)")
    if (index(lline, "secrets.")) report(FNR, "FORBIDDEN_EXPR", "secrets. 참조를 쓰지 않는다 (이 워크플로는 비밀 값이 필요 없다. 대소문자 무시)")
    if (index(lline, "github.event.")) report(FNR, "FORBIDDEN_EXPR", "github.event. 표현식을 쓰지 않는다 (외부 입력이 셸에 들어간다. 대소문자 무시)")
    if (index(lline, "github.head_ref")) report(FNR, "FORBIDDEN_EXPR", "github.head_ref 를 쓰지 않는다 (외부 입력이 셸에 들어간다. 대소문자 무시)")
    # 식별자 secrets 는 어떤 모양으로도(secrets[...], secrets [ ... ], toJSON(secrets), secrets.*, job 의 secrets: inherit)
    # 어디에도(주석 포함) 없다(P1-02, C5-R2). 앞뒤가 [a-z0-9_-] 이면 다른 이름의 일부다(no-secrets, secrets_x).
    if (lline ~ /(^|[^a-z0-9_-])secrets([^a-z0-9_-]|$)/)
      report(FNR, "FORBIDDEN_EXPR", "식별자 secrets 를 쓰지 않는다 (색인, 공백, toJSON(secrets), secrets: inherit 도 거부한다. 주석 포함, 대소문자 무시)")

    match(line, /^ */)
    ind = RLENGTH
    # 블록 스칼라와 run 값은 그 키의 열보다 깊게 들여 쓴 줄이다. run 값의 영역은 빈 줄과 주석 줄이 닫지 않는다.
    blank = (line ~ /^[[:space:]]*$/)
    blank_or_comment = (line ~ /^[[:space:]]*(#.*)?$/)
    # 블록 스칼라(YAML): 내용 열 blk_ind 는 첫 내용 줄의 열이다("#" 로 시작해도 내용이다). 키 열 이하의 줄은 블록을 끝낸다.
    # 주석 줄도 그렇고, 내용 열보다 덜 들여 쓴 주석 줄도 블록을 끝낸다(그 뒤의 더 깊은 줄은 아래 들여쓰기 규칙이 거부한다).
    # 내용 열보다 덜 들여 쓰고 키보다 깊은, 주석이 아닌 줄은 거부한다(YAML 은 오류로 본다). 첫 내용 줄보다 공백이 많은 앞쪽
    # 빈 줄은 libyaml 이 내용 열을 그 공백 수로 잡으므로 거부한다.
    if (blk_col >= 0) {
      if (blank) {
        if (blk_ind < 0 && ind > blk_lead) blk_lead = ind
        # 내용 열보다 앞의 탭(P1-02, C5-R4): libyaml 은 들여쓰기 자리의 탭을 오류로 본다. 내용 열의 탭과 그 뒤의 탭은
        # 문자열이다(w148). 첫 내용 줄 앞의 빈 줄(내용 열을 아직 모른다)에서는 libyaml 이 어느 열의 탭이든 오류로 보므로
        # 열과 상관없이 거부한다(P1-02).
        if ((blk_ind < 0 || ind < blk_ind) && line ~ /^ *\t/) {
          if (blk_ind < 0) unsupported("블록 스칼라의 첫 내용 줄 앞에 탭이 있는 빈 줄")
          else unsupported("블록 스칼라 내용 열(" blk_ind " 열)보다 앞에 탭이 있는 빈 줄")
        }
      } else if (ind <= blk_col || (blank_or_comment && blk_ind >= 0 && ind < blk_ind)) {
        blk_col = -1
      } else if (blk_ind < 0) {
        blk_ind = ind
        if (blk_lead > ind) unsupported("블록 스칼라의 첫 내용 줄보다 공백이 많은 앞쪽 빈 줄")
      } else if (ind < blk_ind) {
        unsupported("블록 스칼라 내용(" blk_ind " 열)보다 덜 들여 쓰고 그 키보다 깊은 줄")
      }
    }
    # 표현식 범위(P1-02, C5-R2): 줄(주석 포함, 소문자)의 첫 "${{" 부터 줄 끝까지다. 따옴표는 해석하지 않는다(줄 끝까지 보므로
    # 한 줄의 둘째 "${{" 와 따옴표 값 안의 "${{" 도 범위 안이다). 블록 스칼라(|, >) 내용에서는 "${{" 가 든 내용 줄 다음의 같은
    # 블록 내용 줄 전체가 범위다(줄을 넘기는 표현식). 블록이 끝나거나 새 블록 머리가 오거나 새 파일이면 풀린다. 블록 밖에서는
    # 줄을 넘기는 값(여러 줄 평범한 스칼라, 줄을 넘기는 따옴표 문자열)을 위의 규칙들이 이미 거부한다.
    if (blk_col < 0) blk_expr = 0
    escope = ""
    if (blk_col >= 0 && blk_expr) escope = lline
    else if ((ei = index(lline, "${{")) > 0) escope = substr(lline, ei)
    if (blk_col >= 0 && index(lline, "${{")) blk_expr = 1
    if (escope != "") expr_github(escope)
    if (!blank_or_comment && run_col >= 0 && ind <= run_col) run_col = -1
    # run 값의 표현식은 셸이 보기 전에 펼쳐진다. 주석처럼 보이는 줄도 run 값의 일부다.
    if (run_col >= 0 && ind > run_col && index(line, "${{"))
      report(FNR, "RUN_EXPR", "run 값에 ${{ }} 표현식을 쓰지 않는다 (셸에 들어가기 전에 펼쳐진다. 필요하면 env: 로 넘긴다)")
    # 블록 스칼라 내용 밖의 빈 줄과 주석 줄도 들여쓰기 자리에 탭을 두지 않는다(P1-01 리뷰 3회차 C3-R3). libyaml 은 이런 줄을
    # 오류로 본다(구조 줄의 탭은 아래에서 거부한다). 블록 스칼라 내용 안의 탭은 문자열이므로 보지 않는다.
    if (blank_or_comment && blk_col < 0 && line ~ /^ *\t/) unsupported("빈 줄이나 주석 줄의 들여쓰기 자리의 탭")
    if (blank_or_comment) next
    in_block = (blk_col >= 0)

    code = line
    sub(/[[:space:]]+#.*$/, "", code)

    # 목록 항목(단계) 경계: persist-credentials 는 checkout 과 같은 항목의 with: 안에 있어야 한다.
    if (line ~ /^ *-( |$)/) {
      if (item_indent < 0 || ind <= item_indent) {
        close_item()
        item_indent = ind
        if (match(line, /^ *-[[:space:]]+[^[:space:]]/)) item_key_col = RLENGTH - 1
        else item_key_col = -2
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
      } else {
        # 빈 조각은 pinned 가 막는다. ".", ".." 조각은 경로를 바꿔 다른 action(예: checkout)을 가리킬 수 있다.
        upath = line
        sub(/^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]+/, "", upath)
        sub(/@.*$/, "", upath)
        nseg = split(upath, useg, "/")
        for (i = 1; i <= nseg; i++) {
          if (useg[i] == "." || useg[i] == "..") {
            report(FNR, "USES_NOT_SHA", "uses 경로에 . 이나 .. 조각을 쓰지 않는다 (지금: " upath ")")
            break
          }
        }
      }
      # GitHub 은 owner/repo 의 대소문자를 가리지 않는다고 본다(Actions/Checkout 도 checkout 이다). tolower 는 POSIX awk 다.
      if (tolower(line) ~ /uses:[[:space:]]*actions\/checkout@/) item_checkout_line = FNR
    }

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
    if (code ~ quoted_write || index(code, "write-all"))
      report(FNR, "PERMISSIONS_WRITE", "write 권한을 주지 않는다")
    if (index(tolower(code), "-latest"))
      report(FNR, "LATEST_RUNNER", "-latest 러너 레이블을 쓰지 않는다 (대소문자 무시. ubuntu-24.04, macos-15, windows-2025 처럼 고정한다)")

    # ---- 여기부터는 구조 줄만 본다(블록 스칼라의 내용은 문자열이다) ----
    if (in_block) next

    sub(/[[:space:]]+$/, "", code)
    match(line, /^ *(-[[:space:]]+)*/)
    kcol = RLENGTH
    body = code
    sub(/^[[:space:]]+/, "", body)
    while (body ~ /^-([[:space:]]|$)/) sub(/^-[[:space:]]*/, "", body)
    haskey = 0; key = ""; value = body
    if (match(body, /:([[:space:]]|$)/)) {
      haskey = 1
      key = substr(body, 1, RSTART - 1)
      value = substr(body, RSTART + 1)
      sub(/^[[:space:]]+/, "", value)
    }
    # 따옴표 문자열은 주석을 떼기 전의 줄에서 본다(문자열 안의 " #" 는 주석이 아니다).
    rbody = line
    sub(/^[[:space:]]+/, "", rbody)
    while (rbody ~ /^-([[:space:]]|$)/) sub(/^-[[:space:]]*/, "", rbody)
    q = ""
    if (rbody ~ ("^" quote)) q = rbody
    else if (match(rbody, /:([[:space:]]|$)/)) {
      q = substr(rbody, RSTART + 1)
      sub(/^[[:space:]]+/, "", q)
      if (q !~ ("^" quote)) q = ""
    }

    if (line ~ /^ *\t/) unsupported("들여쓰기의 탭")
    if (code ~ /^(---|\.\.\.)([[:space:]]|$)/ || code ~ /^%/) unsupported("문서 표시(---, ...)나 지시자(%)")
    if (body ~ /^\?([[:space:]]|$)/ || body ~ /^:([[:space:]]|$)/) unsupported("명시적 키(? ) 또는 값 줄(: )")
    # 따옴표로 감싼 키는 콜론이 바로 붙어도("k":v, 흐름 문맥의 JSON 모양 키) 거부한다.
    if (body ~ ("^" quote) && body ~ (quote "[[:space:]]*:")) unsupported("따옴표로 감싼 키")
    if (body !~ ("^" quote) && haskey && key ~ /[[:space:]]$/) unsupported("키와 콜론 사이의 공백")
    if (body ~ /^\{/ || (haskey && value ~ /^\{/)) unsupported("흐름 매핑 { }")
    seq = ""
    if (body ~ /^\[/) seq = body
    else if (haskey && value ~ /^\[/) seq = value
    if (seq != "" && seq !~ plain_flow_seq) unsupported("평범한 값만 담지 않은 흐름 시퀀스 " seq)
    if (body ~ /^[&*]/ || (haskey && value ~ /^[&*]/)) unsupported("앵커(&) 또는 별칭(*)")
    if (body ~ /^<<[[:space:]]*:/) unsupported("병합 키 <<:")
    if (body ~ /^!/ || (haskey && value ~ /^!/)) unsupported("태그(!)")
    if (q != "" && substr(q, 1, 1) == "\"" && index(q, "\\")) unsupported("큰따옴표 문자열 안의 \\ 이스케이프")
    if (q != "") {
      t = substr(q, 2)
      if (substr(q, 1, 1) == "\"") { gsub(/\\./, "", t); closed = index(t, "\"") }
      else { gsub(sq sq, "", t); closed = index(t, sq) }
      if (!closed) unsupported("줄을 넘기는 따옴표 문자열")
    }
    hdr = haskey ? value : body
    if (hdr ~ /^[|>]/) {
      if (hdr !~ /^[|>][-+]?$/) unsupported("블록 스칼라 머리 " hdr " (|, |-, |+, >, >-, >+ 만 다룬다)")
      blk_col = haskey ? kcol : ind
      blk_ind = -1
      blk_lead = 0
      blk_expr = 0
    }

    # 열린 키(값이 빈 키)의 쌓임 pk_col[1..sd], pk_key[1..sd]: 아래 줄들의 부모 키다. 열은 늘 깊어진다. "- " 줄은 그 "-" 열보다
    # 깊은 키를 닫고(steps: 와 같은 열의 "- " 항목도 steps 아래다), 다른 줄은 그 키 열 이상의 키를 닫는다. 이 줄의 키는 맨 끝에 쌓는다.
    if (line ~ /^ *-( |$)/) { while (sd > 0 && pk_col[sd] > ind) sd-- }
    else { while (sd > 0 && pk_col[sd] >= kcol) sd-- }
    # 키 없는 "-" 줄 다음의 첫 더 깊은 줄이 그 항목의 키 열이다.
    if (item_key_col == -2 && item_indent >= 0 && ind > item_indent) item_key_col = ind
    # 단계의 키: jobs.<job>.steps 목록 항목의 바로 아래 키다(jobs 는 0 열의 최상위 키).
    step_key = (item_indent >= 0 && kcol == item_key_col && sd == 3 && pk_col[1] == 0 && pk_key[1] == "jobs" && pk_key[3] == "steps")
    # 환경 변수 이름(KEY_NOT_LOWERCASE 의 예외, C3-R1): 워크플로(env), job(jobs.<job>.env), 단계(jobs.<job>.steps 항목의 env)의
    # env: 바로 아래에 있는 "- " 가 아닌 키다. GitHub 은 이 키를 키워드가 아닌 변수 이름으로 읽는다. 이름이 env 인 job 의 키
    # (jobs.env 아래, sd == 2)와 container·services 의 env 는 예외가 아니다.
    env_name = (line !~ /^ *-( |$)/ && sd > 0 && pk_col[1] == 0 && ((sd == 1 && pk_key[1] == "env") || (sd == 3 && pk_key[1] == "jobs" && pk_key[3] == "env") || (sd == 4 && pk_key[1] == "jobs" && pk_key[3] == "steps" && pk_key[4] == "env")))
    # env: 가 환경 변수 매핑인 자리(P1-02, C5-R1): 워크플로의 키(0 열), job 의 키(jobs.<job> 바로 아래), 단계의 키(step_key).
    env_level = (step_key || (line !~ /^ *-( |$)/ && ((sd == 0 && kcol == 0) || (sd == 2 && pk_col[1] == 0 && pk_key[1] == "jobs"))))

    if (body ~ /^continue-on-error:([[:space:]]|$)/)
      report(FNR, "CONTINUE_ON_ERROR", "continue-on-error 를 두지 않는다 (실패한 단계나 job 이 실행을 실패시키지 않는다)")
    if (body ~ /^if:([[:space:]]|$)/)
      report(FNR, "IF_CONDITION", "if: 조건을 두지 않는다 (단계나 job 을 건너뛰면 게이트가 막지 못한다)")
    if (body ~ /^shell:([[:space:]]|$)/ && value != "bash")
      report(FNR, "SHELL_NOT_BASH", "shell 은 bash 여야 한다 (지금: " value ")")
    if (body ~ /^run:([[:space:]]|$)/) {
      if (step_key) {
        # 단계의 run 만 실행된다: run 값의 영역을 열고 팩토리 명령을 센다. 중복 키는 파서마다 다르게 읽으므로 둘째 run: 은 거부한다.
        if (item_runs++ > 0)
          report(FNR, "RUN_KEY", "한 단계에 run: 이 두 번 있다 (중복 키는 YAML 파서마다 다르게 읽는다)")
        run_col = kcol
        if (index(line, "${{"))
          report(FNR, "RUN_EXPR", "run 값에 ${{ }} 표현식을 쓰지 않는다 (셸에 들어가기 전에 펼쳐진다. 필요하면 env: 로 넘긴다)")
        if (line ~ factory_line) {
          name = line
          sub(/^.*scripts\/gate\//, "", name)
          sub(/\.sh[[:space:]]*$/, "", name)
          if (name in want) found[name] = 1
        }
      } else {
        # 받는 것은 워크플로(0 열)나 job(jobs.<job>) 의 defaults: 바로 아래에 있는 값이 빈 run: 하나다(그 아래 shell: bash).
        defaults_run = (value == "" && line !~ /^ *-( |$)/ && sd > 0 && pk_key[sd] == "defaults" && pk_col[1] == 0 && (sd == 1 || (sd == 3 && pk_key[1] == "jobs")))
        if (!defaults_run)
          report(FNR, "RUN_KEY", "run: 은 단계(jobs.<job>.steps 의 항목)의 키로만 둔다 (env:, with:, matrix 아래의 run 은 실행되지 않으므로 팩토리 명령으로 세지 않는다. defaults: 아래의 값이 빈 run: 만 받는다)")
      }
    }

    # with: 블록: 단계 키 열에 값이 빈 with: 가 열고, 처음 나온 더 깊은 줄의 열이 그 바로 아래 항목의 열이다.
    if (with_col >= 0) {
      if (ind <= with_col) with_col = -1
      else {
        if (with_child_col < 0) with_child_col = ind
        if (ind == with_child_col && code ~ /^ *persist-credentials:[[:space:]]*false$/) item_persist = 1
      }
    }
    if (with_col < 0 && item_indent >= 0 && kcol == item_key_col && body == "with:") {
      with_col = kcol
      with_child_col = -1
    }
    if (haskey && value == "") {
      sd++
      pk_col[sd] = kcol
      pk_key[sd] = key
    }

    # ---- 허용 목록 (P1-01 리뷰 2회차 C2-R1 뒤): 구조 줄은 "키:", "키: 값", "- 키:", "- 키: 값", "- 값", "-" 가운데 하나다 ----
    isdash = (line ~ /^ *-( |$)/)
    akey = (haskey && body !~ ("^" quote))
    if (index(code, "\t") && line !~ /^ *\t/) unsupported("구조 줄의 탭 (들여쓰기와 구분에는 공백만 쓴다)")
    if (line ~ /^ *-[[:space:]]+-([[:space:]]|$)/) unsupported("한 줄에 겹친 목록 항목 - -")
    if (!isdash && !akey) unsupported("키가 없는 줄 (앞 값에 이어지는 여러 줄 스칼라이거나 키 자리의 값이다)")
    if (akey && key !~ /^[A-Za-z0-9_][A-Za-z0-9_-]*$/) unsupported("키 이름 " key " (따옴표 없는 [A-Za-z0-9_-] 만 쓴다)")
    # 키는 소문자다(P1-01 리뷰 3회차 C3-R1). 규칙은 키를 이름(if, continue-on-error, shell, run, uses, with, persist-credentials,
    # permissions, jobs, steps, defaults, env)으로 찾는데, GitHub 이 키의 대소문자를 가리는지 확인하지 않았다. 그래서 대문자가
    # 든 키는 모두 거부한다(If:, Continue-On-Error:, Uses: 가 규칙을 피하지 못한다). 키는 위 허용 목록의 키 줄에만 있으므로 모든
    # 키가 이 검사를 지난다. 예외는 env_name(워크플로, job, 단계의 env: 바로 아래 변수 이름)이다.
    if (akey && key ~ /[A-Z]/ && !env_name)
      report(FNR, "KEY_NOT_LOWERCASE", "키 " key " 에 대문자가 있다 (키는 소문자로 쓴다. 대문자는 워크플로, job, 단계의 env: 바로 아래 변수 이름에만 받는다)")
    # 셸, 동적 로더, 러너가 단계를 시작하거나 실행하는 방식을 바꾸는 환경 변수 이름(P1-02, C5-R1). bash 는 BASH_ENV 를 명령
    # 치환까지 펼쳐 스크립트보다 먼저 읽으므로 한 줄로 모든 bash 단계(게이트 네 단계 포함)를 무력화한다. Windows 의 환경 변수
    # 이름은 대소문자를 가리지 않으므로 소문자로 바꿔 비교한다. bash* 와 ld_, dyld_, github_, runner_, actions_, msys 로
    # 시작하는 이름, 그리고 env, shellopts, posixly_correct, ps4, ifs, cdpath, globignore, execignore, path 를 거부한다.
    # pathext 와 comspec 은 Windows 러너(windows-2025)의 명령 찾기(확장자)와 명령 해석기를 바꾼다(P1-02).
    if (akey && env_name) {
      lk = tolower(key)
      if (lk ~ /^(bash|ld_|dyld_|github_|runner_|actions_|msys)/ || lk ~ /^(env|shellopts|posixly_correct|ps4|ifs|cdpath|globignore|execignore|path|pathext|comspec)$/)
        report(FNR, "FORBIDDEN_ENV", "환경 변수 " key " 를 두지 않는다 (bash 시작·옵션 변수, LD_*·DYLD_* 로더 변수, GITHUB_*·RUNNER_*·ACTIONS_* 러너 변수, MSYS*, PATH, PATHEXT, COMSPEC 은 단계의 셸과 명령을 바꾼다. 대소문자 무시)")
    }
    # 워크플로, job, 단계의 env: 는 블록 매핑이어야 한다(P1-02, C5-R1): 한 줄 값(예: env: ${{ fromJSON(...) }})은 위 이름
    # 검사가 보지 못하는 이름을 만든다.
    if (akey && key == "env" && value != "" && env_level)
      report(FNR, "FORBIDDEN_ENV", "env: 에 한 줄 값을 두지 않는다 (지금: " value ". 워크플로, job, 단계의 env: 는 이름: 값 줄의 블록 매핑으로만 쓴다)")
    if (akey && value != "") plain_check(value)
    else if (isdash && !akey && body != "") plain_check(body)
    # 닫는 따옴표 뒤에는 공백과 주석만 온다(작은따옴표 안의 두 작은따옴표는 따옴표 하나다).
    if (q != "") {
      t = substr(q, 2)
      cl = 0
      if (substr(q, 1, 1) == "\"") cl = index(t, "\"")
      else {
        i = 1
        while ((c1 = substr(t, i, 1)) != "") {
          if (c1 == sq) {
            if (substr(t, i + 1, 1) == sq) { i += 2; continue }
            cl = i
            break
          }
          i++
        }
      }
      if (cl > 0) {
        rest = substr(t, cl + 1)
        if (rest !~ /^[[:space:]]*$/ && rest !~ /^[[:space:]]+#/) unsupported("닫는 따옴표 뒤의 글자 " rest)
      }
    }

    # ---- 들여쓰기 구조 (P1-01 리뷰 2회차 C2-R1 뒤) ----
    # 열린 매핑과 목록: lv_col[1..nl](열은 늘 깊어진다), lv_kind("m" 매핑, "s" 목록), lv_id(매핑 번호, 중복 키용. 파일을 넘어
    # 늘기만 하므로 지우지 않아도 겹치지 않는다). 앞 구조 줄: pv_last(그 줄의 가장 깊은 열: 키 줄은 키 열, 키 없는 "-" 줄은
    # "-" 열), pv_child(아래에 자식을 둘 수 있는가: 값이 빈 키, 맨 "-"), pv_empty(값이 빈 키였으면 그 열: 같은 열의 "- " 목록이
    # 그 값이다), pv_bare(맨 "-" 였는가).
    lv_ok = 1
    if (nl == 0) {
      if (isdash || ind != 0) {
        unsupported("최상위는 0 열에서 시작하는 매핑이어야 한다")
        lv_ok = 0
      } else {
        nl = 1; lv_col[1] = 0; lv_kind[1] = "m"; lv_id[1] = ++nmap
      }
    } else if (ind > pv_last) {
      if (!pv_child) {
        # YAML 은 값이 있는 키나 "- 값" 다음의 더 깊은 줄을 앞 값에 이어 붙인다(여러 줄에 걸친 평범한 스칼라).
        unsupported("앞 줄의 값에 이어지는 더 깊게 들여 쓴 줄 (여러 줄에 걸친 평범한 스칼라. 예: shell: bash 다음 줄의 -c true)")
        lv_ok = 0
      } else if (isdash && pv_bare) {
        unsupported("목록 항목 안의 목록 (맨 - 아래의 -)")
        lv_ok = 0
      } else {
        nl++; lv_col[nl] = ind; lv_kind[nl] = (isdash ? "s" : "m")
        if (!isdash) lv_id[nl] = ++nmap
      }
    } else {
      while (nl > 1 && lv_col[nl] > ind) nl--
      if (lv_col[nl] != ind) {
        unsupported("열린 매핑이나 목록의 열과 맞지 않는 들여쓰기")
        lv_ok = 0
      } else if (isdash && lv_kind[nl] == "m") {
        # 매핑과 같은 열의 "- " 목록은 값이 빈 키 바로 다음에서만 그 키의 값이다.
        if (pv_empty == ind) { nl++; lv_col[nl] = ind; lv_kind[nl] = "s" }
        else {
          unsupported("매핑 키 자리의 목록 항목")
          lv_ok = 0
        }
      } else if (!isdash && lv_kind[nl] == "s") {
        # 같은 열의 목록이 끝나고 그 위 매핑의 키로 돌아간다. 그런 매핑이 없으면 목록 자리의 키다.
        if (nl > 1 && lv_col[nl - 1] == ind && lv_kind[nl - 1] == "m") nl--
        else {
          unsupported("목록 항목 자리의 매핑 키")
          lv_ok = 0
        }
      }
    }
    if (lv_ok && isdash && akey) {
      nl++; lv_col[nl] = kcol; lv_kind[nl] = "m"; lv_id[nl] = ++nmap
    }
    if (lv_ok && akey) {
      dk = lv_id[nl] SUBSEP tolower(key)
      if (dk in seen)
        report(FNR, "DUPLICATE_KEY", "같은 매핑에 키 " key " 가 두 번 있다 (앞: " seen[dk] " 줄. libyaml 은 뒤의 값을 써서 앞의 것을 가리고, 파서마다 다를 수 있다)")
      else seen[dk] = FNR
    }
    if (lv_ok) {
      if (akey) { pv_last = kcol; pv_child = (value == ""); pv_empty = (value == "" ? kcol : -1); pv_bare = 0 }
      else { pv_last = ind; pv_child = (body == ""); pv_empty = -1; pv_bare = (body == "") }
    }
  }
  END {
    if (NR > 0) finish_file()
    for (i = 1; i <= ncmd; i++) {
      if (!(cmd[i] in found)) {
        printf "%s:0: [FACTORY_COMMAND_MISSING] %sbash scripts/gate/%s.sh%s (인자 없음) 줄이 없다. 팩토리 게이트와 같은 명령을 쓴다.\n", wfdir, sq, cmd[i], sq
        bad++
      }
    }
    exit (bad > 0 ? 1 : 0)
  }
' "${files[@]}"
awk_status=$?
set -e
if [ "$awk_status" -ne 0 ]; then
  problems=$((problems + 1))
fi

if [ "$problems" -ne 0 ]; then
  echo "workflow-check: FAIL (파일 ${#files[@]} 개: ${files[*]})"
  exit 1
fi
echo "workflow-check: OK (파일 ${#files[@]} 개: ${files[*]}. SHA 고정, 최상위 contents: read, 금지 표현(식별자 secrets, 허용 속성 밖의 github 표현식 포함) 없음, 셸·로더·러너 시작과 Windows 명령 찾기 env 이름, 한 줄 env: 없음, checkout persist-credentials: false, 모델 밖 문법·LF 가 아닌 줄바꿈·제어 문자 없음, 허용 목록 밖의 줄 모양·여러 줄 평범한 스칼라·맞지 않는 들여쓰기 없음, 중복 키 없음, 키는 소문자(env 변수 이름 말고), continue-on-error·if: 없음, shell: bash, run: 은 단계마다 하나, 단계 run 값에 \${{ 없음, 단계의 한 줄 run 인 팩토리 명령 네 줄)"
