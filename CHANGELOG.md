# 변경 이력

이 파일은 [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) 형식을 따른다. 분류 이름은 한국어로 쓴다: 추가, 변경, 수정, 보안. 아직 릴리스한 버전이 없으므로 모든 항목은 `[Unreleased]` 아래에 있다. 버전 번호 규칙은 첫 릴리스(Phase 8) 때 정한다.

Phase 마다 구현한 기능, 테스트 결과, 남은 문제를 적는다(명세 13절). 항목은 최신이 위에 온다. 날짜는 커밋 날짜(`git log -1 --format=%cs <커밋>`)다. 테스트 수는 그 태스크의 마지막 게이트 실행에서 센 값이다.

## [Unreleased]

### Phase 1 (진행 중)

남은 문제: [docs/BACKLOG.md](docs/BACKLOG.md). P1-01 은 Phase 1 의 범위(DB 연결, Provider, 관리자 토큰)가 아니라 P0-D 리뷰의 후속이다.

#### P1-01 P0-D 리뷰 후속: 점검기의 실패 닫힘 (커밋 전)

##### 추가

- `workflow-check.sh` 의 새 코드(P0D-R1, P0D-R3, P0D-R4): `UNSUPPORTED_SYNTAX`(블록 스칼라 내용 밖의 줄에서 따옴표로 감싼 키, 흐름 매핑, 평범한 값만 담지 않은 흐름 시퀀스, 앵커·별칭·병합 키, 태그, 문서 표시·지시자, `key :`, 명시적 키, 들여쓰기 숫자가 붙은 블록 머리, 줄을 넘기는 따옴표 문자열, 큰따옴표 안의 `\` 이스케이프, 들여쓰기의 탭. 블록 내용과 주석을 포함한 모든 줄에서 LF 가 아닌 줄바꿈 문자 CR·U+0085·U+2028·U+2029, 탭이 아닌 제어 문자, NUL), `CONTINUE_ON_ERROR`, `IF_CONDITION`, `SHELL_NOT_BASH`, `RUN_KEY`(단계 밖의 `run:` 과 한 단계의 둘째 `run:`), `RUN_EXPR`(단계의 run 값 안의 `${{`).
- P1-01 리뷰 2회차(C2-R1-R3)와 YAML 문법 전수 점검: `UNSUPPORTED_SYNTAX` 는 블록 스칼라 내용 밖의 줄을 허용 목록(`키:`, `키: 값`, `- 키:`, `- 키: 값`, `- 값`, `-`)과 들여쓰기 구조로 보고, 여러 줄에 걸친 평범한 스칼라(값이 있는 키나 `- 값` 다음의 더 깊은 줄), 맞지 않는 들여쓰기, 키가 없는 줄, 탭, `- - x`, 평범한 값을 시작할 수 없는 문자, 평범한 값 안의 `: `, 닫는 따옴표 뒤의 글자, BOM, 블록 스칼라 내용 열보다 덜 들여 쓴 줄을 거부한다. 새 코드 `DUPLICATE_KEY` 는 같은 매핑의 같은 키(대소문자 무시)를 거부한다.
- P1-01 리뷰 3회차(C3-R1, C3-R3): 새 코드 `KEY_NOT_LOWERCASE` 는 대문자가 든 키(`If:`, `Continue-On-Error:`, `Shell:`, `Uses:`, `Run:`, `Permissions:` 등)를 거부한다. 규칙이 키를 이름으로 찾으므로 대소문자만 다른 키가 규칙을 피했다. 예외는 워크플로·job·단계 `env:` 바로 아래의 변수 이름이다. `FORBIDDEN_TRIGGER`, `FORBIDDEN_EXPR`, `LATEST_RUNNER` 는 대소문자를 가리지 않고 찾는다. `UNSUPPORTED_SYNTAX` 는 블록 스칼라 내용 밖의 빈 줄과 주석 줄의 들여쓰기 자리의 탭도 거부한다.
- `selftest.sh` 의 새 시나리오 134개: docs-check (d13)-(d15), workflow-check (w18)-(w148). (w48)-(w64) 는 P1-01 리뷰 1회차(R1, R2, R4), (w65)-(w131) 은 2회차(C2-R1-R3, C2-R5)와 YAML 문법 전수 점검, (w132)-(w148) 은 3회차(C3-R1, C3-R3)의 재현이다. 받는 픽스처에는 저장소 `ci.yml` 의 바이트 사본(w47)과 CRLF 사본(w131), CRLF 픽스처(w57), 여러 단계 모양의 픽스처(w63, w130), `defaults.run` 의 `working-directory` 표현식(w129), `env:` 의 대문자 변수 이름(w148)이 있다. 새 거부 시나리오마다 d0b0f0f 의 점검기((w48)-(w64) 는 1회차, (w65)-(w128) 은 2회차, (w132)-(w147) 은 3회차의 점검기)나 그 규칙을 끈 사본에서는 그 시나리오가 실패한다.
- `docs/BACKLOG.md`: CI-2(Gradle 의존성 검증 없음, 목표 Phase 1 의 DB Provider 태스크 전). P101-T1(P1-01 테스트에서 한 번 실패하고 다시 돌리자 통과한 cargo 포트 확인 테스트, 원인은 추정, 목표 Phase 1 의 별도 태스크).

##### 변경

- `workflow-check.sh`: `CHECKOUT_PERSIST` 는 checkout 단계의 `with:` 바로 아래 항목인 `persist-credentials: false` 만 센다(P0D-R2). `FACTORY_COMMAND_MISSING` 은 팩토리 명령을 단계(`jobs.<job>.steps` 의 항목)의 한 줄짜리 `run: bash scripts/gate/<이름>.sh` 값으로만 센다(블록 안의 줄과 `env:`·`with:` 아래의 `run:` 은 세지 않는다, P0D-R3). checkout 은 owner/repo 의 대소문자를 가리지 않고 찾는다. 팩토리 명령 확인을 grep 에서 같은 awk 로 옮겼고, 그 awk 는 `LC_ALL=C` 로 돌려 바이트로 비교한다. `uses:` 의 고정 모양은 빈 경로 조각과 끝의 `/` 를 받지 않고 `.`·`..` 조각도 거부한다(C2-R2). 블록 스칼라는 첫 내용 줄의 열을 내용 열로 보고, 그보다 덜 들여 쓴 주석 줄이 블록을 끝낸다(libyaml 과 같다). 출력 형식(`file:line: [CODE] 설명`, `workflow-check: OK|FAIL`)과 종료 코드는 그대로다.
- 문서: P0-D 를 아직 커밋하지 않은 것으로 적던 곳을 d0b0f0f (2026-11-20) 로 바꾸고 Phase 0 을 완료로 적었다(`README.md`, ADR-019, `docs/ARCHITECTURE.md`, `docs/BACKLOG.md`, `docs/PHASE0-REPORT.md`, `docs/TEST_PLAN.md`). 두 점검기가 잡는 것과 잡지 못하는 것을 `docs/SECURITY.md`, `docs/TEST_PLAN.md`, ADR-020 에 적었다. `docs/BACKLOG.md` 에서 P0D-R1..P0D-R7 을 닫았다.
- 캐시 설명(P0D-R7): `ci.yml` 머리 주석, ADR-020, `docs/TEST_PLAN.md` 가 캐시는 의존성 캐시뿐이고 산출물은 없으며, Gradle 캐시에는 Gradle 이 만든 데이터도 있고 복원한 캐시는 검증하지 않는다(CI-2)고 적는다.

##### 수정

- `launch.rs`, `supervisor.rs` 의 Windows 주석이 「P0-D CI」 대신 첫 CI 실행(CI-1, PLAT-1)을 가리킨다(P0D-R6).

##### 보안

- `workflow-check.sh` 는 줄 단위 점검기가 다루지 않는 YAML 문법(따옴표로 감싼 키, 흐름 매핑 등)을 `UNSUPPORTED_SYNTAX` 로 거부한다(P0D-R1). 모든 줄의 LF 가 아닌 줄바꿈 문자(CR, U+0085, U+2028, U+2029), 탭이 아닌 제어 문자와 NUL 도 거부한다. 이런 것을 받으면 그 뒤의 키가 SHA 고정·권한 규칙과 아래 규칙을 피한다. 따옴표로 감싼 `write` 도 `PERMISSIONS_WRITE` 로 거부한다.
- `workflow-check.sh` 는 `continue-on-error:`, `if:`, bash 가 아닌 `shell:` 을 워크플로·job·단계 어디서나 거부하고(P0D-R3), 단계의 run 값 안의 `${{` 를 `RUN_EXPR` 로(P0D-R4), 단계 밖이나 한 단계에 두 번 있는 `run:` 을 `RUN_KEY` 로 거부한다. 팩토리 명령은 단계의 한 줄짜리 run 값만 센다.
- `workflow-check.sh` 는 값이 있는 키나 `- 값` 다음의 더 깊은 줄을 `UNSUPPORTED_SYNTAX` 로 거부한다(C2-R1). YAML 은 이 줄을 앞 값에 이어 붙이므로, 받으면 `shell: bash` 다음 줄의 `-c true {0}` 이 shell 을 `bash -c true {0}` 으로 바꾸고(libyaml 기준. 게이트 스크립트가 실행되지 않는다), 팩토리 run 줄 다음 줄의 `2>/dev/null || true` 나 `--desktop-only` 가 그 명령을 바꾼다. 같은 매핑의 같은 키는 `DUPLICATE_KEY` 로 거부한다(C2-R3. libyaml 은 뒤의 값을 쓰므로 두 번째 `with:` 나 `persist-credentials: true` 가 앞의 `persist-credentials: false` 를 가린다). `actions//checkout` 처럼 checkout 찾기를 피하는 `uses:` 경로는 `USES_NOT_SHA` 로 거부한다(C2-R2).
- 이 점검기는 YAML 파서가 아니고 `working-directory`, env 나 `PATH` 로 도구를 바꿔치기하는 것, 트리거와 브랜치 필터, action 입력 이름, 표현식의 뜻을 확인하지 않는다. 단계 명령이 무엇을 하는지도 보지 않는다: 앞 단계가 게이트 스크립트를 고쳐 쓰거나 `$GITHUB_ENV`·`$GITHUB_PATH` 에 써서(예: `BASH_ENV` 설정) 뒤 단계를 바꾸는 것, `container:` 와 `services:` 는 잡지 못한다(P1-01 리뷰 3회차 C3-R2). YAML 을 GitHub 와 같게 읽는지도 확인하지 않았다(전수 점검은 libyaml 0.2.1 과 견준 구성에서만 했다). 그래서 점검을 통과해도 이런 방법으로는 게이트 단계를 무력화할 수 있다. 한계는 [docs/SECURITY.md](docs/SECURITY.md)와 [docs/TEST_PLAN.md](docs/TEST_PLAN.md) 「lint 의 저장소 단계」에 있다.
- `docs-check.sh` 는 상대 링크를 저장소 루트 안에 가둔다. 루트 위로 올라가는 링크는 그 파일이 있어도 깨진 링크다(P0D-R5). 루트 밖을 가리키는 저장소 안의 심볼릭 링크는 잡지 못한다.

테스트 (macOS 로컬의 게이트 실행): runtime 단위 300, vitest 30, cargo test 32, 통합 23. 모두 통과, 건너뜀 0. selftest 227 시나리오(P0-D 의 93, 새로 134), SKIP 0. 점검기의 awk 는 macOS `/usr/bin/awk` 에서만 돌렸다(mawk, gawk 는 CI-1 전까지 미검증). CI 는 실행되지 않았다(CI-1).

### Phase 0 (완료, d0b0f0f)

남은 문제: [docs/BACKLOG.md](docs/BACKLOG.md). Phase 0 보고(통과 기준, 사람의 결정, 미검증 항목): [docs/PHASE0-REPORT.md](docs/PHASE0-REPORT.md)

#### P0-D CI 정의와 Phase 0 마무리 — d0b0f0f (2026-11-20)

##### 추가

- `.github/workflows/ci.yml` (**정의됨·미실행**: 원격 저장소가 없어 한 번도 실행되지 않았다, [BACKLOG](docs/BACKLOG.md) CI-1). `gates` 작업(ubuntu-24.04)은 새 checkout 에서 root 가 아닌지 확인하고 `selftest.sh --no-skip` 과 팩토리 게이트 네 개를 인자 없이 돌린 뒤 작업 트리가 깨끗한지 본다. `desktop` 작업(macos-15, windows-2025)은 build·test·lint 를 `--desktop-only` 로 돌린다([ADR-020](docs/ADR/ADR-020-ci-runs-gate-scripts.md), 제안).
- `scripts/gate/docs-check.sh` (DOC-1): 명세 원문 블록(689줄, SHA-256)과 `README.md`, `CHANGELOG.md`, `docs/**/*.md` 의 상대 링크를 검사한다. `lint.sh` 가 모든 범위에서 실행한다.
- `scripts/gate/workflow-check.sh`: 워크플로의 SHA 고정, 권한, 금지 표현, checkout 의 `persist-credentials: false`, 팩토리 명령 네 줄을 검사한다. 워크플로 파일이 없으면 실패한다. `lint.sh` 가 모든 범위에서 실행한다.
- 게이트 범위: `build.sh`, `test.sh`, `lint.sh` 의 선택 인자 `--desktop-only`. runtime(Gradle) 단계만 빼고 시작 안내와 마지막 `GATE PASS` 줄에 그 사실을 적는다. 그 밖의 인자는 어떤 도구도 실행하기 전에 exit 2 이고, `integration.sh` 는 인자를 받지 않는다. 인자 없는 실행의 단계와 출력 형식은 그대로다.
- `selftest.sh --no-skip` (P0A-N1)과 새 시나리오 58개: 범위 파서, 가짜 docker 로 Docker 사전 점검, docs-check 와 workflow-check 의 픽스처, junit-check 의 형식이 틀린 `tests` 속성(빈 값, 숫자가 아님)과 합계 형식 거부 분기(`valid_counts`, 가짜 awk 가 틀린 합계를 찍는다, P0A-N1).
- `desktop/src-tauri/src/runtime/launch.rs` 의 테스트 3건: 설정 없음 메시지의 두 문구, 대소문자 무시 모드에서만 `Path` 를 찾는 `env_value`(두 건 모두 모든 플랫폼), Windows 의 `Path` 에서 `java.exe` 찾기(`#[cfg(windows)]`, 미검증).
- `docs/ADR/ADR-020-ci-runs-gate-scripts.md`(제안)와 `docs/PHASE0-REPORT.md`.

##### 변경

- `launch.rs`: Metadata DB 설정이 없을 때의 메시지가 `docs/DEPLOYMENT.md` 와 하위 셸 명령 `(set -a; . deploy/.env; set +a; pnpm --dir desktop tauri dev)` 를 가리킨다.
- `docs/BACKLOG.md`: P0A-N1, P0B-R4, P0B-R7, P0C-C2-4, DOC-1 을 닫았다. P0B-R6 은 Phase 1 로 미뤘고, PLAT-1 의 목표를 「CI-1 첫 실행 / Phase 8」로 바꿨고, CI-1 을 더했다(ID 24개).
- `docs/TEST_PLAN.md`(CI 절, 게이트 범위, lint 의 저장소 단계, 새 사본 검사, 문서 점검 절차, 테스트 수), `docs/ARCHITECTURE.md`, `docs/SECURITY.md`, `docs/DEPLOYMENT.md`, `docs/ADR/README.md`, ADR-013, ADR-017, ADR-019, `README.md`. 문서 기준선의 「커밋 전」을 51141df (2026-11-20) 로 바꿨다.

##### 수정

- Windows 에서 `resolve_java` 가 `PATH` 를 대소문자 구분 없이 찾는다(`launch.rs` 의 `env_value`, 모든 플랫폼에서 컴파일하고 `cfg!(windows)` 로 고른다, P0C-C2-4). Windows 에서는 실행해 보지 않았다(미검증).
- Docker 사전 점검(`lib.sh` 의 `docker_preflight`)이 서버 버전을 stdout 에서만 읽고 숫자로 시작하는 한 줄만 받는다. stderr 는 실패 때만 보인다(P0B-R4).
- ADR-013 의 `java -version` 사전 점검 환경 설명을 코드(`inherited_env`)와 `docs/SECURITY.md` 에 맞췄다. 아래 P0-C 보안 항목의 가림 문구를 「출력에 그대로 나온 비밀번호」로 고쳤다.

##### 보안

- CI 워크플로는 비밀 값이 필요 없다. 최상위 권한은 `contents: read` 하나이고, 모든 checkout 이 `persist-credentials: false` 이며, action 은 40자 커밋 SHA 로 고정했다. `pull_request_target`, `secrets.`, `github.event.`, `github.head_ref` 를 쓰지 않는다. `workflow-check.sh` 가 lint 단계에서 이 규칙을 점검한다. 이 줄 단위 점검기는 P1-01 전까지 따옴표로 감싼 키와 흐름 매핑을 보지 못했다(P0D-R1, 위의 P1-01).
- 게이트 범위는 명시한 인자로만 고른다. 환경변수로는 게이트의 단계를 줄일 수 없다.

테스트 (macOS 로컬의 게이트 실행): runtime 단위 300, vitest 30, cargo test 32, 통합 23. 모두 통과, 건너뜀 0. selftest 93 시나리오, SKIP 0. `#[cfg(windows)]` 테스트 1건은 macOS 에서 컴파일되지 않아 미검증이다. CI 는 실행되지 않았다(CI-1).

#### 문서 기준선 — 51141df (2026-11-20)

##### 추가

- `docs/REQUIREMENTS.md`: 2026-11-15 명세 원문을 표시 사이에 그대로 보존하고, 승인된 변경을 추가 요구사항으로 적었다. AR-0 은 설계 결정 D1–D4, AR-1 은 2026-11-19 에 승인된 런타임 운영 지표(OM-1..OM-7, 계획: 화면 Phase 6, 수집 Phase 7)다.
- `docs/ADR/`: 상태 규칙, 템플릿, 목록과 ADR-001..ADR-019. 001–010 은 승인된 설계의 결정이고, 011–019 는 Phase 0 에서 정한 결정이다(015, 017, 018 은 제안).
- `docs/BACKLOG.md`: P0-A, P0-B, P0-C 리뷰에서 넘어온 후속 과제와 플랫폼·문서 과제.
- `docs/ARCHITECTURE.md`, `docs/ERD.md`, `docs/API_SPECIFICATION.md`, `docs/DATABASE_SUPPORT.md`, `docs/SECURITY.md`, `docs/DEPLOYMENT.md`, `docs/TEST_PLAN.md`: 명세 13절의 설계 문서. 지금 구현된 동작은 f84c628 의 코드에서 확인해 적었고, 나머지는 계획으로 적었다. `docs/ARCHITECTURE.md` 의 Phase 계획은 운영 지표의 화면을 Phase 6, 수집을 Phase 7 에 두고, 「설계와 다른 구현」 표가 승인된 설계와 달라진 10곳을 ADR 과 함께 적는다.
- 이 파일 `CHANGELOG.md`.

##### 변경

- `README.md`: 자세한 설명을 `docs/` 로 옮기고 소개, 현재 상태, 저장소 구조, 빠른 시작, 문서 목록만 남겼다.

테스트: 코드, 게이트 스크립트, 설정은 바뀌지 않았다.

#### P0-C Desktop 셸 스파이크 — f84c628 (2026-11-19)

##### 추가

- `desktop/`: pnpm 프로젝트(React 19, Vite 8, Tailwind 4, shadcn/ui)와 Tauri 2.11.5 크레이트. 버전은 정확히 고정했고, `Cargo.lock` 은 `--locked` 로만 쓴다.
- Rust 런타임 감독: java 21 이상과 런타임 JAR 찾기, 포트 확인(bind 와 연결 탐침), 기동·중지·재시작과 헬스 확인, 중지 때 SIGTERM 뒤 SIGKILL, 앱 종료 때 런타임 종료. Tauri 명령 네 개(`runtime_status`, `runtime_start`, `runtime_stop`, `runtime_restart`).
- Desktop UI: 상태, 헬스, 시작·중지·재시작 버튼, `GET /admin/api/v1/runtime` 의 런타임 정보 카드, 가린 출력 끝부분을 보이는 실패 카드.
- 런타임: `QUERYHUB_DESKTOP_PARENT_PID` 로 부모 셸을 감시해 앱이 죽으면 런타임도 끝난다. 값이 잘못되면 종료 코드 2 로 기동을 멈춘다([ADR-014](docs/ADR/ADR-014-runtime-parent-watch.md)).
- 게이트: build·test·lint 에 desktop 단계(frozen install, 타입 검사, eslint, vitest 와 junit-check, cargo build·test·fmt·clippy `--locked`), Tauri 버전 고정 검사, `scripts/gate/cargo-test-check.sh` 와 그 자체 검사. 서명하지 않은 디버그 번들 확인 절차.

##### 변경

- `README.md`: Desktop 준비물(JDK 21), 변수, 실행 절차, 게이트, 서명 없는 번들 확인.

##### 보안

- 자식 런타임에는 허용 목록의 환경변수만 넘긴다. 셸의 환경으로 런타임의 바인딩, 노출, 로깅, 프로파일을 바꿀 수 없다([ADR-013](docs/ADR/ADR-013-desktop-child-env-allowlist.md)).
- Metadata DB 설정은 환경변수로만 넘기고 명령행 인자에 넣지 않는다. 출력에 그대로 나온 비밀번호는 `********` 로 가린다([ADR-017](docs/ADR/ADR-017-desktop-metadata-settings-from-env.md)).
- 관리 API CORS 는 Tauri WebView 출처만, GET 만, 자격 증명 없이, desktop 프로파일에서만 허용한다. 출처 값이 잘못되면 기동을 멈춘다. WebView 는 엄격한 CSP 를 쓴다([ADR-015](docs/ADR/ADR-015-desktop-fixed-port-and-cors.md)).

테스트: runtime 단위 300, vitest 30, cargo test 30, 통합 23. 모두 통과, 건너뜀 0.

#### P0-B 메타데이터 기반 — f913239 (2026-11-18)

##### 추가

- `runtime/adapter-metadata` 모듈: application 계층에만 의존하고, `JdbcAppSettingRepository` 가 `AppSettingRepository` 포트를 구현한다([ADR-007](docs/ADR/ADR-007-spring-jdbc-metadata.md)).
- Flyway `V1__create_app_setting.sql`: utf8mb4, `utf8mb4_nopad_bin` 키, `DATETIME(6)`. 서버의 문자 집합, 정렬 규칙, 시간대와 무관하다. 마이그레이션 이름과 버전을 지키는 테스트.
- Metadata DB 연결: `QUERYHUB_METADATA_DB_*` 환경변수를 `queryhub.metadata.db.*` 에 바인딩한다. 값이 없거나 틀리거나, DB 에 닿지 않거나 인증이 실패하면 웹 서버가 열리기 전에 기동을 멈춘다. 메시지에는 비밀번호를 넣지 않는다([ADR-009](docs/ADR/ADR-009-mariadb-metadata-db.md)).
- 통합 테스트: Testcontainers 와 공유 `mariadb:12.3` 컨테이너. 기동, 스키마, LOCAL INFILE 거부, 저장소 왕복, 기동 실패, Flyway 고정, JAR 하위 프로세스 테스트.
- 게이트: `scripts/gate/integration.sh` (Docker 가 없으면 건너뛰지 않고 실패, junit-check 판정).
- 개발용 compose: `deploy/compose.dev.yml` 과 `deploy/env.example` (git 이 무시하는 `deploy/.env` 로 복사해서 쓴다, [ADR-016](docs/ADR/ADR-016-env-example-naming.md)).

##### 변경

- `build.sh` 와 `lint.sh` 가 통합 테스트 소스도 다룬다. 단위 테스트는 Docker 없이 돌고 물려받은 `QUERYHUB_*` 를 무시한다.
- `.gitignore`: 예시 파일 이름을 `deploy/env.example` 로 정하면서 쓰이지 않게 된 `!.env.example` 부정 패턴을 뺐다([ADR-016](docs/ADR/ADR-016-env-example-naming.md)).
- `README.md`: 모듈 목록, 의존 규칙, 환경변수 표, 비밀 값 처리, 통합 게이트, 개발용 compose.

##### 보안

- 프로세스에 보이는 출처(명령행 인자, JVM 시스템 속성, 그 둘로 넘어온 `spring.application.json`)의 Metadata 비밀번호와 Hikari `password`, `jdbc-url`, `data-source-properties` 키를 거부한다. 메시지는 키 이름만 말한다([ADR-011](docs/ADR/ADR-011-metadata-secrets-env-only.md)).
- Flyway 를 검사한 풀에 고정했다. `spring.flyway.url`, `user`, `password` 로 마이그레이션 연결을 돌릴 수 없다([ADR-012](docs/ADR/ADR-012-flyway-connection-pin.md)).
- MariaDB Connector/J 를 3.5.11 로 고정했다(GHSA-fhx5-2xqw-2cgm). 런타임 URL 에 `allowLocalInfile=false` 를 넣는다([ADR-018](docs/ADR/ADR-018-mariadb-connector-j-override.md)).

테스트: 단위 234, 통합 21. 모두 통과, 건너뜀 0.

#### P0-A 런타임 골격 — ee00165 (2026-11-17)

##### 추가

- `runtime/`: Gradle 9.7.1 멀티 모듈(domain, application, adapter-web, app), Java 21 toolchain, Spring Boot 4.1.2 BOM, 버전 카탈로그, 체크섬을 고정한 wrapper. 빌드 시점의 선언 의존 규칙이 Clean Architecture 방향을 강제한다([ADR-010](docs/ADR/ADR-010-spring-boot-4-java-21.md)).
- 공통 응답과 오류: 성공·오류 형식, HTTP 상태가 정해진 `ErrorCode`, 400/404/405/415/500 의 같은 형식 처리.
- 요청 ID: 유효한 `X-Request-Id` 는 그대로 쓰고 없으면 만든다. 모든 응답 헤더와 `meta.requestId` 에 싣는다.
- 프로파일: `desktop`(127.0.0.1:18580, 기본)과 `server`(0.0.0.0:8080). 모드 프로파일이 정확히 하나가 아니면 기동에 실패한다. health 프로브와 `GET /admin/api/v1/runtime`.
- ArchUnit: 계층, 슬라이스, domain·application 순수성, admin/api 분리, 패키지 위치 규칙과 일부러 위반하는 픽스처.
- 품질: Spotless 검사와 `javac -Xlint:all -Werror`. `scripts/gate/build.sh`, `test.sh`, `lint.sh`. `test.sh` 는 0건, 실패, 건너뜀, 이전 실행, 읽을 수 없는 JUnit 결과를 거부하고 selftest 로 판정기를 먼저 검사한다([ADR-019](docs/ADR/ADR-019-gate-scripts-judge-results.md)).
- 저장소 기본 파일: `.gitignore`, `.gitattributes`, `.editorconfig`, 한국어 `README.md`.

##### 보안

- 오류 응답에 스택 트레이스와 내부 메시지를 넣지 않는다. actuator 는 `health` 만 공개한다.
- desktop 프로파일은 기본으로 루프백(127.0.0.1)에만 바인딩한다.

테스트: 단위 107 (adapter-web 29, app 63, application 4, domain 11). 모두 통과, 건너뜀 0. 통합 게이트는 아직 없었다.
