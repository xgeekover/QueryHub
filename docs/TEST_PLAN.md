# QueryHub 테스트 계획

테스트 계층, 게이트 스크립트와 거짓 통과 방어, CI 워크플로(정의됨·미실행), 지금까지의 테스트 수, 명세 12절 기준의 Phase 배치, 미검증 플랫폼, 문서 점검 절차를 적는다. 게이트가 결과를 직접 판정한다는 결정은 [ADR-019](ADR/ADR-019-gate-scripts-judge-results.md)에 있다.

## 테스트 계층

| 계층 | 도구 | 위치 | 상태 |
|---|---|---|---|
| domain·application 단위 | JUnit 5, AssertJ | `runtime/domain/src/test`, `runtime/application/src/test` | 구현됨 (P0-A) |
| adapter 단위·슬라이스 | JUnit 5, Spring Boot Test(MockMvc) | `runtime/adapter-web/src/test`, `runtime/adapter-metadata/src/test` | 구현됨 (P0-A, P0-B) |
| 조립·설정 | Spring Boot Test (오류 형식, CORS, 프로파일, Metadata 설정, 부모 감시) | `runtime/app/src/test` | 구현됨 (P0-A, P0-B, P0-C) |
| 아키텍처 | ArchUnit R1-R8 과 고의 위반 픽스처 | `runtime/app/src/test/java/com/queryhub/architecture/` ([ARCHITECTURE.md](ARCHITECTURE.md)) | 구현됨 (P0-A, P0-B) |
| 통합 | Testcontainers + 실제 MariaDB(`mariadb:12.3`), 실행 JAR 하위 프로세스(`BootJarIntegrationTest`, `DesktopParentWatchIntegrationTest`) | `runtime/app/src/integrationTest` | 구현됨 (P0-B, P0-C) |
| UI 단위 | Vitest, Testing Library, jsdom | `desktop/src/**/*.test.ts(x)` | 구현됨 (P0-C) |
| Rust 셸 | `cargo test` (가짜 자식과 임시 HTTP 서버) | `desktop/src-tauri/src/**` | 구현됨 (P0-C) |
| 사람의 인수 확인 | 화면 확인 절차 | 태스크 보고 | P0-C 에서 수행 |
| E2E | Playwright | | 계획 (Phase 6) |
| 업무 DB 통합 | Testcontainers, DB 4종 | | 계획 (Phase 1) |
| 성능 | 아래 「명세 12절」 Performance Test | | 계획 (Phase 8) |

- 사람의 인수 확인(P0-C AC-19): 개발 모드 앱과 서명 없는 디버그 번들에서 「실행 중」, health 정상, 런타임 정보 카드(가동 시간 증가), 중지·시작·재시작, 잘못된 DB 포트로 띄웠을 때 비밀번호 없는 실패 카드를 확인했다. 번들에서는 이것으로 `tauri://localhost` 의 CSP 와 CORS 가 WKWebView 에서 동작함을 확인했다. 클릭은 사람의 지시로 자동화 도구가 했고 사람이 캡처를 검토했다.
- 단위 테스트는 Docker 없이 돈다. 테스트 JVM 은 셸의 `QUERYHUB_*` 환경변수를 물려받지 않는다(`runtime/build.gradle`). 그래서 `deploy/.env` 를 source 한 셸에서도 결과가 같다.

## 게이트 스크립트

저장소 루트에서 실행한다(스크립트는 어느 cwd 에서 실행해도 저장소 루트 기준으로 동작한다). 모두 성공하면 exit 0 이다.

```sh
bash scripts/gate/build.sh        # main·test·integrationTest 를 -Xlint:all -Werror 로 컴파일하고 실행 JAR 을 만든다
                                  # (assemble testClasses integrationTestClasses)
bash scripts/gate/test.sh         # 단위 테스트를 실제로 실행하고 JUnit XML 로 판정한다 (Docker 불필요)
bash scripts/gate/lint.sh         # 게이트 스크립트 문법(bash -n), 문서 점검(docs-check.sh), 워크플로 점검(workflow-check.sh),
                                  # Spotless 형식 검사, 컴파일 (spotlessCheck compileJava compileTestJava compileIntegrationTestJava)
bash scripts/gate/integration.sh  # 통합 테스트(실제 MariaDB, Testcontainers)를 실행하고 JUnit XML 로 판정한다 (Docker 필요)
```

팩토리 게이트 명령은 위 네 줄 그대로다(인자 없음). `build.sh`, `test.sh`, `lint.sh` 는 선택 인자 `--desktop-only` 하나를 받는다(아래 「게이트 범위」).

판정기는 `scripts/gate/junit-check.sh`(JUnit XML), `scripts/gate/cargo-test-check.sh`(cargo test 출력)이고, `scripts/gate/selftest.sh` 가 픽스처로 판정기 자체를 먼저 검증한다. 공통 함수(범위 선택, Docker 사전 점검 포함)는 `scripts/gate/lib.sh` 에 있다. `scripts/gate/docs-check.sh` 와 `scripts/gate/workflow-check.sh` 는 lint 의 저장소 단계다(P0-D).

### test.sh 의 거짓 통과 방어 (구현됨, P0-A)

`test.sh` 는 종료 코드만 보는 게이트가 거짓으로 통과하지 않도록 다음을 막는다.

- 먼저 `selftest.sh` 로 판정기(`junit-check.sh`) 자체를 검증한다. 실패하면 중단한다. root 로 돌면 권한 000 파일을 읽을 수 있어 일부 시나리오가 SKIP 된다. `selftest.sh --no-skip` 은 건너뛴 시나리오가 하나라도 있으면 그 수를 말하고 exit 1 이다. `test.sh` 와 `integration.sh` 는 인자 없이 부르고, CI 의 `gates` 작업이 root 가 아닌지 확인한 뒤 `--no-skip` 으로 따로 실행한다(정의됨·미실행). 판정기의 합계 형식 거부 분기(`junit-check.sh` 의 `valid_counts`)는 진짜 awk 로는 닿지 않으므로, PATH 맨 앞의 가짜 awk 가 틀린 합계(빈 출력, 앞자리 0, 숫자가 아닌 글자, 값 5 개, 음수, 공백 두 칸)를 찍게 한 시나리오 (i8) 이 `[INVALID]` 와 그 분기의 메시지를 요구하고, 같은 장치의 올바른 합계는 통과해야 한다. 형식이 틀린 `tests` 속성(빈 값, 숫자가 아님)은 (i7) 이 다룬다([BACKLOG.md](BACKLOG.md) P0A-N1 완료).
- `runtime/` 아래 `build.gradle` 을 가진 모든 모듈을 찾는다(모든 깊이의 `build/`, `.gradle/` 은 건너뛴다). 탐색(`find`)이 실패하거나 모듈이 하나도 없으면 테스트를 실행하기 전에 실패한다. `settings.gradle` 에 include 된 프로젝트에 자기 `build.gradle` 이 없으면 모듈 의존 규칙이 빌드를 실패시키므로, 탐색에서 빠지는 모듈이 없다. 새 모듈도 자동으로 포함된다. 심볼릭 링크인 모듈 디렉터리는 탐색에서 빠진다(P0A-N2).
- `cleanTest test --continue --no-build-cache` 로 항상 테스트를 다시 실행한다. `cleanTest` 는 UP-TO-DATE 로 0건 실행이 되는 일을, `--no-build-cache` 는 사용자 홈이나 CI 에서 빌드 캐시를 켜 두었을 때 Test 태스크를 캐시에서 복원(FROM-CACHE)해 실행 없이 결과 XML 이 생기는 일을 막는다.
- 모듈마다 실행된 테스트가 1건 이상인지 확인한다.
- 실패·오류가 있으면, 건너뛴 테스트(`@Disabled` 등)가 하나라도 있으면, 이번 실행보다 오래된 결과 파일이 있으면, 읽거나 해석할 수 없는 결과 파일(`[INVALID]`: 읽기 권한 없음, 일반 파일이 아님, `tests` 속성 없음이나 형식이 틀린 `tests` 값, 닫히지 않은 태그, XML 이 아님, 음이 아닌 정수 6 개가 아닌 합계)이 있으면 실패한다.
- 성공하면 모듈마다 한 줄(`tests`, `executed`, `skipped`, `failures`, `errors`)과 합계 줄을 출력하고, 마지막 줄은 `GATE PASS: test` 다.

### integration.sh (구현됨, P0-B)

`runtime/app/src/integrationTest` 의 통합 테스트만 실행한다(단위 테스트는 실행하지 않는다). **Docker 가 필요하고, Docker 가 없으면 건너뛰지 않고 실패한다.**

- `test.sh` 와 같은 판정을 쓴다: `selftest.sh` 먼저, `cleanIntegrationTest integrationTest --continue --no-build-cache`, 그리고 `junit-check.sh --require-each --newer`(0건, 실패·오류, 건너뜀, 오래된 결과, 해석할 수 없는 결과를 거부한다).
- `src/integrationTest` 가 있는 모듈을 자동으로 찾는다. 하나도 없거나 `runtime/app` 이 없으면 실패한다.
- Gradle 을 실행하기 전에 `lib.sh` 의 `docker_preflight` 가 `docker info` 로 데몬을 확인한다(최대 60 초). 서버 버전은 stdout 에서만 읽고 숫자로 시작하는 한 줄이어야 한다. stderr 는 trap 으로 지우는 임시 파일에 두었다가 실패 때 보인다. 성공하면 `Docker 서버 버전: <버전>` 을 출력한다. `selftest.sh` 가 가짜 docker 로 stderr 경고만 있는 exit 0, exit 1, stdout `WARNING: x`, 두 줄을 거부하고 `29.5.2` 를 받는 것을 확인한다([BACKLOG.md](BACKLOG.md) P0B-R4 완료). `integration.sh` 는 인자를 받지 않는다.
- MariaDB 컨테이너(`mariadb:12.3`)는 Testcontainers 가 테스트 JVM 마다 하나 띄우고, 끝나면 Ryuk 가 지운다. 서버 기본값은 일부러 latin1 / latin1_swedish_ci / +09:00 으로 두어, 런타임이 서버 기본값에 기대지 않는다는 것을 확인한다. 컨테이너 포트가 모든 호스트 인터페이스에 열리는 것은 P0B-R6 이다(Phase 1).
- 걸리는 시간: 개발 머신(이미지가 이미 있을 때)에서 컨테이너 기동을 포함해 1 분 안팎이다. Gradle 태스크 제한은 10 분이다. 마지막 줄은 `GATE PASS: integration (..., <초>s)` 다.
- 통합 테스트가 다루는 것(P0-B, P0-C): 기동, 스키마, LOCAL INFILE 거부, 저장소 왕복, 기동 실패, Flyway 고정, JAR 하위 프로세스.

### 게이트의 desktop 단계 (구현됨, P0-C)

| 게이트 | runtime 단계 뒤에 더한 단계 |
|---|---|
| `build.sh` | 필수 도구(node, pnpm, cargo), desktop 존재 확인, `pnpm --dir desktop install --frozen-lockfile`, `pnpm --dir desktop run build`(tsc --noEmit, vite build) 와 `dist/index.html` 확인, `cargo build --locked` |
| `test.sh` | 필수 도구, 존재 확인, 설치, vitest(이전 XML 을 지우고 실행, `junit-check.sh --require-each --newer` 로 판정), `cargo test --locked`(출력을 임시 파일에 담아 `cargo-test-check.sh --require-lib` 로 판정). 앞 단계가 실패해도 모든 단계를 실행하고, 모든 종료 코드가 0 일 때만 마지막 줄이 `GATE PASS: test` 다 |
| `lint.sh` | (runtime 단계 앞에 저장소 단계 `docs-check.sh`, `workflow-check.sh` 를 모든 범위에서 실행한다, P0-D) 필수 도구, 존재 확인, Tauri 버전 고정 검사, 설치, `pnpm --dir desktop run typecheck`, `pnpm --dir desktop run lint`(eslint `--max-warnings 0`), `cargo fmt --check`, `cargo clippy --locked --all-targets -- -D warnings` |

desktop 단계의 거짓 통과 방어:

- desktop 이 없거나 도구가 없으면 건너뛰지 않고 실패한다.
- vitest: 0 건, 실패, `it.skip`(skipped), 이전 실행의 XML 은 `junit-check.sh` 가 거부한다. `it.only` 는 Vitest 설정 `allowOnly: false` 가, 테스트 파일이 하나도 없으면 `passWithNoTests: false` 가 실패시킨다(`desktop/vite.config.ts`).
- cargo test: `cargo-test-check.sh` 가 결과 없음 `[NO_RESULTS]`, 0 건 `[ZERO_TESTS]`, 실패 `[FAILURES]`, `#[ignore]` `[IGNORED]`, 이름 필터 `[FILTERED]`, lib 단위 테스트 없음 `[LIB_WITHOUT_TESTS]`, 해석할 수 없는 출력이나 결과 없이 끝난 바이너리 `[INVALID]` 를 거부한다. `selftest.sh` 가 시나리오마다 픽스처로 이 판정기를 먼저 검증한다.
- 잠금 파일: `--frozen-lockfile` 과 `cargo --locked` 는 잠금 파일과 어긋나면 실패한다. `Cargo.lock` 은 검증된 다른 프로젝트(NexTerm)의 잠금 파일에서 복사했다. `cargo update` 나 `cargo generate-lockfile` 은 실행하지 않는다.
- Tauri 버전 고정 검사: `@tauri-apps/api`·`@tauri-apps/cli` 가 정확한 2.11.x 이고, `Cargo.toml` 이 `tauri = "=2.11.5"`, `tauri-build = "=2.6.3"` 이고, `Cargo.lock` 의 tauri 가 2.11.5 하나이며 npm 패키지와 major.minor 가 같아야 한다. 어기면 그 항목을 말하고 실패한다(Tauri CLI 는 major.minor 가 다르면 거부하고, 새로 해석하면 2.12 크레이트가 섞인다).
- 셸 감독 테스트는 가짜 자식(sh 스크립트, 경로에 표식 `queryhub-fake-runtime`)과 임시 포트의 작은 HTTP 서버를 쓴다. 실제 런타임과 DB 가 필요 없다. 테스트가 끝난 뒤 `pgrep -fl queryhub-fake-runtime` 은 아무것도 출력하지 않아야 한다. 포트 확인 탐침이 임시 포트 범위에서 드물게 `port_in_use` 를 잘못 낼 수 있는 것은 P0C-C2-1 이고, `java -version` 사전 점검의 환경을 실제 자식으로 확인하지 않은 것은 P0C-C2-2 다.
- 비용: `desktop/src-tauri/target` 은 작업 트리마다 수 GB 다. 처음 cargo build 는 Tauri 의존성을 컴파일한다.

### 게이트 범위 `--desktop-only` (구현됨, P0-D)

| 호출 | 범위 |
|---|---|
| `bash scripts/gate/build.sh` (인자 없음, 팩토리 명령) | 모든 단계. 출력과 마지막 줄 `GATE PASS: <이름>` 의 형식은 P0-D 전과 같다 |
| `bash scripts/gate/build.sh --desktop-only` (`test.sh`, `lint.sh` 도 같다) | runtime(Gradle) 단계만 뺀다. `==> [gate] runtime:` 줄이 없고, 시작 안내는 `게이트 범위: desktop-only, runtime(Gradle) 단계는 실행하지 않았다 (runtime 단계까지 실행하려면 인자 없이 실행한다)`, 마지막 줄은 `GATE PASS: <이름> (desktop-only, runtime(Gradle) 단계는 실행하지 않았다…)` 다 |
| 그 밖의 인자(`--bogus`, 인자 둘 이상, 대소문자가 다른 값, 빈 문자열) | 어떤 도구도 실행하기 전에 `GATE FAIL: <이름>: 받을 수 없는 인자 …` 로 exit 2 |
| `bash scripts/gate/integration.sh` 에 인자를 주면 | 인자를 받지 않으므로 exit 2 |

- 범위는 인자로만 고른다. 환경변수 `GATE_SCOPE` 를 내보내도 인자 없는 실행은 모든 단계다.
- `test.sh --desktop-only` 는 실행하지 않은 gradle 과 junit-check 를 판정에서 빼고 마지막 줄에 `gradle=not run`, `junit-check=not run` 을 적는다. 0 으로 세지 않는다.
- `selftest.sh` 가 파서(인자 없음은 all, `--desktop-only` 는 desktop-only, 그 밖은 오류)와 잘못된 인자에서 가짜 도구가 한 번도 불리지 않는 것을 확인한다.

### lint 의 저장소 단계 (구현됨, P0-D)

| 단계 (lint 출력의 이름) | 점검 | 실패 닫힘 |
|---|---|---|
| `docs: docs-check.sh (명세 원문 해시, 상대 링크)` | 아래 「문서 점검 절차」 1-2: `docs/REQUIREMENTS.md` 의 표시 사이 블록(689줄, SHA-256 기준값)과 `README.md`, `CHANGELOG.md`, `docs/**/*.md` 의 상대 인라인 링크(펜스 블록과 인라인 코드 밖, `#` 앞 경로가 링크한 파일 기준으로 있어야 한다). 깨진 링크마다 `file:line: target` | 파일이 없거나 `sha256sum` 과 `shasum` 이 모두 없으면 실패 |
| `ci: workflow-check.sh (SHA 고정, 권한, 금지 표현, 팩토리 명령)` | `.github/workflows/*.yml`·`*.yaml`: `uses:` 는 40자 SHA 와 `# 태그` 주석, 최상위 `permissions` 의 `contents: read` 와 쓰기 권한 없음, 작업별 권한 없음, `pull_request_target`·`secrets.`·`github.event.`·`github.head_ref` 없음, 모든 checkout 의 `persist-credentials: false`, `-latest` 러너 없음, 팩토리 명령 네 줄(인자 없음) | 워크플로 파일이 하나도 없으면 건너뛰지 않고 실패 |

- `selftest.sh` 가 두 점검기를 픽스처로 먼저 검증한다: docs-check 는 바뀐 바이트 하나, END 없음, BEGIN 두 번, END 가 BEGIN 앞, 깨진 링크, 저장소 루트에서만 풀리는 링크를 거부하고 펜스 블록 안의 링크, https 링크, 있는 파일의 `#` 조각 링크를 받는다. workflow-check 는 `@v4` 태그 고정, 7자 SHA, `# 태그` 없는 SHA, 최상위 권한 없음, 쓰기 권한, `pull_request_target`, `secrets.`, `${{ github.event.`, persist-credentials 없는 checkout, 빠진 팩토리 명령을 거부하고 깨끗한 픽스처를 받는다.
- 한계: docs-check 는 인라인 링크만 보고(참조형 링크는 보지 않는다) 앵커를 확인하지 않는다. workflow-check 는 줄 단위 점검이고 YAML 검증기가 아니다.

### 개발 중 실행과 산출물

개발 중에는 필요한 모듈만 실행한다.

```sh
sh runtime/gradlew -p runtime spotlessApply            # 형식 자동 수정 (google-java-format)
sh runtime/gradlew -p runtime :domain:test             # 한 모듈의 테스트
sh runtime/gradlew -p runtime :app:test --tests 'com.queryhub.architecture.*'
```

빌드·테스트 산출물은 모두 git 이 무시하는 경로(`runtime/.gradle/`, 각 모듈의 `build/`, `desktop/node_modules`, `desktop/dist`, `desktop/src-tauri/target`)에 생긴다. 게이트 뒤 `git status --short` 가 바뀌지 않아야 한다. 게이트 스크립트와 테스트 설정은 품질 판정 기준이므로, 계획되지 않은 변경은 리뷰에서 범위 위반으로 다룬다. ArchUnit 자체 검증의 빈 곳(R5 의 api→admin 방향, R6)은 P0A-I2 다.

### 새 사본 검사의 읽는 법 (P0B-R7)

설계 §9 의 Phase 0 통과 기준은 「새 clone 에서 build·test·lint 가 exit 0」이다.

- **로컬 새 사본 (지금의 근거):** 커밋할 파일(`git ls-files -z --cached --others --exclude-standard`)을 빈 디렉터리에 복사하고 `git init -q`, `git add -A` 만 한 사본(커밋하지 않음)에서 네 게이트를 차례로 돌린다. 이 사본에서는 `git status --short` 가 staged `A` 줄을 늘 보여 준다. **`A` 줄 말고 아무것도 없어야 통과다**(`??` 나 ` M` 줄이 생기면 게이트가 추적되지 않는 산출물을 남긴 것이다).
- **CI 의 새 clone (정의됨·미실행, P0-D):** `gates` 작업이 새 checkout 에서 네 게이트를 인자 없이 돌린 뒤 `git status --porcelain --untracked-files=all` 을 출력하고, 비어 있지 않으면 실패한다. 커밋된 checkout 이라 `A` 줄이 없고 출력이 비어야 한다. 이 단계가 처음 실행될 때까지 로컬 새 사본이 기준이다([BACKLOG.md](BACKLOG.md) P0B-R7 완료, CI-1).

## CI (정의됨·미실행, P0-D)

`.github/workflows/ci.yml` 은 정의만 했고 한 번도 실행되지 않았다. 원격 저장소가 없고 push 는 사람이 한다([BACKLOG.md](BACKLOG.md) CI-1). 결정과 이유는 [ADR-020](ADR/ADR-020-ci-runs-gate-scripts.md)(제안)에 있다. 아래는 모두 **미검증**이다.

| 작업 | 러너 | 단계 (차례대로) |
|---|---|---|
| `gates` | `ubuntu-24.04` | checkout(`persist-credentials: false`), `test "$(id -u)" -ne 0`, WebKitGTK 패키지(`libwebkit2gtk-4.1-dev libgtk-3-dev libayatana-appindicator3-dev librsvg2-dev patchelf`), Temurin 21, pnpm 11.9.0, Node 24.16.0, rustup 1.97.1(rustfmt, clippy), cargo registry 캐시, `bash scripts/gate/selftest.sh --no-skip`, `bash scripts/gate/build.sh`, `bash scripts/gate/test.sh`, `bash scripts/gate/lint.sh`, `bash scripts/gate/integration.sh`, 깨끗한 작업 트리 확인 |
| `desktop` | matrix `macos-15`, `windows-2025` (`fail-fast: false`, 셸 bash) | checkout, pnpm, Node, rustup, cargo registry 캐시, `bash scripts/gate/build.sh --desktop-only`, `bash scripts/gate/test.sh --desktop-only`, `bash scripts/gate/lint.sh --desktop-only`. Java, Gradle, Docker 단계는 없다 |

- **같은 명령:** `gates` 작업의 네 줄은 팩토리 게이트 명령과 글자 그대로 같고 인자가 없다. 게이트 스크립트 밖에서 gradle, pnpm, cargo 를 부르지 않는다. 그러므로 junit-check, cargo-test-check, selftest 의 거짓 통과 방어도 로컬과 같다. `workflow-check.sh` 가 이 네 줄이 있는지 lint 단계에서 확인한다.
- **통합 테스트는 Linux 에서만:** `mariadb:12.3` 은 Linux 컨테이너다. macOS 러너에는 Docker 가 없고 Windows 러너는 Windows 컨테이너만 돌린다. 그래서 `integration.sh` 는 `gates` 작업에만 있다.
- **root 아님과 `--no-skip`:** `gates` 작업은 root 가 아닌지 먼저 확인하고 selftest 를 `--no-skip` 으로 돌린다. `desktop` 작업은 `test.sh` 안의 인자 없는 selftest 만 돌리므로, Git Bash 에서 권한 000 시나리오가 SKIP 되어도 막히지 않는다(미검증).
- **캐시:** 내려받은 것만 담는다. Gradle(`setup-java` 의 `cache: gradle`), pnpm store(`setup-node` 의 `cache: pnpm`, `cache-dependency-path: desktop/pnpm-lock.yaml`), `~/.cargo/registry/index`, `~/.cargo/registry/cache`, `~/.cargo/git/db`(키: `runner.os`, `1.97.1`, `Cargo.lock` 해시). `target`, `node_modules`, `dist`, `build`, `test-results` 는 캐시하지 않으므로 매 실행이 새 빌드다.
- **트리거와 권한:** main 의 push·pull_request 와 수동 실행(`workflow_dispatch`). 최상위 권한 `contents: read` 하나, 비밀 값 없음([SECURITY.md](SECURITY.md)).
- **첫 실행 전에는 알 수 없는 것:** 러너 레이블, action 입력 이름, Git Bash 위의 Windows 단계(가짜 실행 파일과 심볼릭 링크를 쓰는 selftest 시나리오 포함), Linux WebKitGTK 빌드, ubuntu 의 awk(mawk)에서 새 awk 코드, `#[cfg(windows)]` 테스트.

## 테스트 수

각 태스크의 마지막 게이트 실행에서 센 값이다. 모두 통과, 건너뜀 0. 같은 수가 [CHANGELOG.md](../CHANGELOG.md)에 있다.

| 태스크 | 커밋 | runtime 단위 | 통합 | vitest | cargo test |
|---|---|---|---|---|---|
| P0-A | ee00165 | 107 (adapter-web 29, app 63, application 4, domain 11) | 게이트 없음 | — | — |
| P0-B | f913239 | 234 | 21 | — | — |
| P0-C | f84c628 | 300 | 23 | 30 | 30 |
| 문서 기준선 | 51141df | 코드 변경 없음 | 코드 변경 없음 | 코드 변경 없음 | 코드 변경 없음 |
| P0-D | 커밋 전 | 300 | 23 | 30 | 32 |

- P0-D 의 수는 macOS 로컬의 게이트 실행에서 센 값이다. cargo test 의 새 테스트 2건은 모든 플랫폼에서 돈다. `#[cfg(windows)]` 테스트 1건은 macOS 에서 컴파일되지 않아 32 에 들어 있지 않고 미검증이다. `selftest.sh` 는 93 시나리오(P0-D 전 35, 새로 58)를 SKIP 0 으로 통과한다. CI 의 수는 없다(미실행).

## 명세 12절 테스트 기준과 Phase

| 명세 12절 | 항목 | Phase | 상태 |
|---|---|---|---|
| Database Test | MSSQL, Oracle, MariaDB, PostgreSQL 연결 및 SELECT (실제 컨테이너, 쓰기 거부와 타입 변환 포함) | 1 | 계획. Metadata DB(MariaDB 12.3)의 연결은 P0-B 통합 테스트가 다루지만 업무 DB 테스트가 아니다 |
| Parameter Test | 정상, 필수 누락, 잘못된 타입, NULL, 기본값, SQL Injection 시도, 컬렉션 (7종, 인젝션은 4개 DB) | 2 | 계획 |
| API Test | GET, POST, 잘못된 URL, 비활성 API, 인증 실패, 파라미터 검증 실패, SQL 오류, Timeout (8종) | 4 | 계획. 잘못된 URL 의 공통 404 는 P0-A 테스트가 다룬다 |
| Export Test | JSON, XML, CSV, XLSX, HTML, 한글, NULL, 날짜 및 숫자, 대용량 (10만 행, 제한된 힙 예: 256 MB) | 5 | 계획 |
| Performance Test | 1,000건, 10,000건, 100,000건, 동시 요청, Connection Pool 한계, 메모리 사용량, 응답 시간 | 8 | 계획. 결과는 문서로 남긴다 |

- Mock 테스트만으로 실제 DB 호환성을 통과 처리하지 않는다(명세 14절).
- 운영 지표(OM-1..OM-7)의 테스트: 지표와 로그 창에 SQL 원문·파라미터 값·비밀 값이 없음, 태그 수 제한, actuator `metrics`·`prometheus` 미노출(계획, Phase 7, [REQUIREMENTS.md](REQUIREMENTS.md) AR-1).

## 미검증 플랫폼

- Windows·Linux 의 Desktop 셸(빌드, 번들, 자식 환경 허용 목록, 경로 처리)은 macOS 에서 검증할 수 없어 **미검증**이다([BACKLOG.md](BACKLOG.md) PLAT-1, 목표 CI-1 첫 실행 / Phase 8). Windows 의 `Path` 대소문자 처리는 P0-D 에서 고쳤고(P0C-C2-4), 그 `#[cfg(windows)]` 테스트는 Windows CI 에서만 돈다(미검증).
- macOS 에서 Windows 타깃으로 Tauri 앱 crate 를 `cargo check` 하면 `llvm-rc` 가 없어 멈춘다. Windows 전용 코드는 로컬에서 검증되지 않는다.
- Metadata DB 는 MariaDB 12.3 만 검증했다([DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)).

## 문서 점검 절차

1-2 는 P0-D 부터 `lint.sh` 의 `docs: docs-check.sh (명세 원문 해시, 상대 링크)` 단계가 모든 범위에서 실행한다([BACKLOG.md](BACKLOG.md) DOC-1 완료). 아래 수동 명령은 참고로 남긴다. 3-5 는 문서를 고친 태스크가 직접 확인한다.

1. **명세 원문 보존 (`docs-check.sh`):** `docs/REQUIREMENTS.md` 의 표시 사이 블록이 바뀌지 않았는지 해시로 확인한다. 블록은 689줄이고 해시는 `93a4ef5db049cb03292ea1a95bec174d7b32555cdf99f58582e73be83d3ccc7b` 다. 각 표시는 한 번씩, BEGIN 이 END 보다 앞에 있어야 한다.

   ```sh
   awk '/^<!-- SPEC-VERBATIM-BEGIN -->$/{f=1;next} /^<!-- SPEC-VERBATIM-END -->$/{f=0} f' docs/REQUIREMENTS.md | shasum -a 256
   ```

2. **상대 링크 (`docs-check.sh`):** `README.md`, `CHANGELOG.md`, `docs/**/*.md` 의 상대 링크 대상(`#` 앞 경로)이 링크한 파일의 디렉터리 기준으로 존재하는지 확인한다. 한국어 제목의 앵커는 확인하지 않는다.
3. **범위:** 문서 태스크라면 `git status --porcelain --untracked-files=all` 에 문서 파일만 있어야 한다.
4. **ID 일관성:** 문서에 나오는 `ADR-0nn` 은 `docs/ADR/` 에 파일이 있고, BACKLOG 형식 ID 는 [BACKLOG.md](BACKLOG.md)에 행이 있으며, ADR 의 `상태` 는 [ADR 목록](ADR/README.md)과 같아야 한다.
5. **비밀 값과 로컬 경로:** 비밀 값, 실제 비밀번호, 로컬 절대 경로, 사용자 이름이 없어야 한다.
