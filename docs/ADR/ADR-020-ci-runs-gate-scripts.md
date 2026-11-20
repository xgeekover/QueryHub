# ADR-020 CI 는 팩토리 게이트와 같은 스크립트를 같은 명령으로 실행한다

- 상태: 제안
- 날짜: 2026-11-20
- 구현: 일부 구현 — 정의됨 (P0-D), 실행은 미검증

## 맥락

- 승인된 설계 §9 의 Phase 0 통과 기준 하나는 「새 clone 에서 build·test·lint 가 exit 0」이고, Phase 0 범위에는 「CI 정의」가 들어 있다.
- 원격 저장소가 없고 push 는 사람이 한다. 그래서 Phase 0 에서는 워크플로를 정의만 할 수 있고 실행해 볼 수 없다([ARCHITECTURE.md](../ARCHITECTURE.md) 「위험 요소」 10).
- 팩토리 게이트는 `bash scripts/gate/build.sh`, `test.sh`, `lint.sh`, `integration.sh` 의 종료 코드로 판정하고, 거짓 통과 방어는 그 스크립트 안에 있다([ADR-019](ADR-019-gate-scripts-judge-results.md)). CI 가 gradle, pnpm, cargo 를 따로 부르면 그 방어가 CI 에서 빠지고 두 판정이 어긋난다.
- Testcontainers 의 `mariadb:12.3` 은 Linux 컨테이너다. macOS 러너에는 Docker 가 없고 Windows 러너는 Windows 컨테이너만 돌린다(러너 이미지의 설명, 미검증).
- 리뷰에서 넘어온 과제: root 로 돌면 selftest 의 시나리오가 SKIP 된다(P0A-N1), 새 사본 검사의 읽는 법(P0B-R7), Windows·Linux Desktop 은 미검증(PLAT-1), Windows 의 `Path` 대소문자(P0C-C2-4), Testcontainers 포트(P0B-R6).

## 결정

- 워크플로는 `.github/workflows/ci.yml` 하나다. main 의 `push`, `pull_request` 와 `workflow_dispatch` 에서 돈다. `pull_request_target` 은 쓰지 않는다. `concurrency` 는 ref 마다 하나이고, `refs/heads/main` 이 아닌 ref 에서만 진행 중인 실행을 취소한다.
- 작업 `gates` (`ubuntu-24.04`): checkout, root 가 아닌지 확인(`test "$(id -u)" -ne 0`), WebKitGTK 패키지, Temurin 21, pnpm 11.9.0, Node 24.16.0, rustup 1.97.1(rustfmt, clippy) 다음에 `bash scripts/gate/selftest.sh --no-skip` 을 돌리고, 팩토리 게이트 명령 네 줄을 인자 없이 글자 그대로 실행한다. 마지막 단계는 `git status --porcelain --untracked-files=all` 을 출력하고 비어 있지 않으면 실패한다(P0B-R7).
- 작업 `desktop` (matrix `macos-15`, `windows-2025`, `fail-fast: false`, 기본 셸 bash): Java, Gradle, Docker 단계 없이 `bash scripts/gate/build.sh --desktop-only`, `bash scripts/gate/test.sh --desktop-only`, `bash scripts/gate/lint.sh --desktop-only` 를 실행한다.
- 통합 테스트(`integration.sh`)는 Linux 컨테이너가 필요하므로 `gates` 작업에서만 돈다. Linux Desktop(WebKitGTK)의 빌드·테스트·lint 도 `gates` 작업이 맡는다.
- 범위 선택(`scripts/gate/lib.sh` 의 `gate_parse_scope`): 인자가 없으면 모든 단계다(팩토리 명령). 정확히 `--desktop-only` 하나면 runtime(Gradle) 단계만 빼고, 시작 안내 `게이트 범위: desktop-only, runtime(Gradle) 단계는 실행하지 않았다 (runtime 단계까지 실행하려면 인자 없이 실행한다)` 와 마지막 줄 `GATE PASS: <이름> (desktop-only, runtime(Gradle) 단계는 실행하지 않았다…)` 로 그 사실을 밝힌다. 그 밖의 인자는 어떤 도구도 실행하기 전에 exit 2 다. 환경변수로는 범위를 고를 수 없다. `integration.sh` 는 인자를 받지 않는다.
- 고정과 권한:
  - action 은 40자 커밋 SHA 와 `# 태그` 주석으로 고정한다. 2026-11-20 에 읽기 전용 `git ls-remote --tags` 로 찾았다: `actions/checkout` v7.0.1, `actions/setup-java` v6.2.0, `pnpm/action-setup` v6.1.1, `actions/setup-node` v7.1.0, `actions/cache` v6.1.0.
  - 러너 레이블은 `-latest` 를 쓰지 않는다. Rust 는 제3자 action 대신 러너의 `rustup` 으로 1.97.1 을 설치하고 기본값으로 둔다.
  - 최상위 `permissions` 는 `contents: read` 하나이고 작업별 권한은 없다. 모든 checkout 이 `persist-credentials: false` 다. 비밀 값이 필요 없고, `secrets.`, `github.event.`, `github.head_ref` 표현을 쓰지 않는다.
  - `scripts/gate/workflow-check.sh` 가 이 규칙과 팩토리 명령 네 줄을 lint 단계(`ci: workflow-check.sh (SHA 고정, 권한, 금지 표현, 팩토리 명령)`)에서 모든 범위로 점검한다. 워크플로 파일이 없으면 건너뛰지 않고 실패한다.
- root 아님과 `--no-skip`(P0A-N1): `selftest.sh --no-skip` 은 건너뛴 시나리오가 하나라도 있으면 그 수를 말하고 exit 1 이다. 인자 없는 `selftest.sh` 는 그대로이고 `test.sh`, `integration.sh` 는 인자 없이 부른다. 그래서 권한 000 시나리오가 SKIP 될 수 있는 Windows 의 desktop 작업은 막히지 않는다.
- 캐시는 내려받은 것만 담는다: Gradle(`setup-java` 의 `cache: gradle`), pnpm store(`setup-node` 의 `cache: pnpm`, `cache-dependency-path: desktop/pnpm-lock.yaml`), `~/.cargo/registry/index`, `~/.cargo/registry/cache`, `~/.cargo/git/db`(`actions/cache`, 키는 `runner.os`, `1.97.1`, `Cargo.lock` 해시). `target`, `node_modules`, `dist`, `build`, `test-results` 는 캐시하지 않는다. 매 실행이 새 빌드이고, 이전 실행의 결과 파일이 판정에 섞이지 않는다.
- P0B-R6(Testcontainers 가 MariaDB 포트를 모든 호스트 인터페이스에 연다)은 P0-D 에서 바꾸지 않고 Phase 1 로 미룬다. 바꾸려면 `runtime/` 의 통합 테스트 지원 코드를 고쳐야 하는데 P0-D 는 runtime 을 바꾸지 않는다. CI 러너는 작업마다 새로 만드는 VM 이고 테스트용 계정만 쓴다(러너의 설명, 미검증).

## 결과

- 로컬 팩토리와 CI 가 같은 판정 코드를 쓴다. 0건, 실패, 건너뜀, 이전 결과를 거부하는 방어가 CI 에도 그대로 적용된다.
- 새 checkout 뒤의 깨끗한 작업 트리 확인이 P0B-R7 의 「`A` 줄만 있어야 통과」 읽는 법을 대신한다. 로컬 새 사본 검사는 CI 가 처음 돌기 전까지 기준으로 남는다([TEST_PLAN.md](../TEST_PLAN.md)).
- Windows·Linux Desktop 이 처음으로 실행될 자리가 생겼다(PLAT-1). 하지만 아직 한 번도 실행되지 않았다([BACKLOG](../BACKLOG.md) CI-1).
- 치르는 값: 산출물을 캐시하지 않으므로 매 실행이 느리다. `desktop` 작업은 runtime 단계를 돌리지 않으므로 macOS·Windows 에서 Gradle 빌드는 CI 가 확인하지 않는다. `workflow-check.sh` 는 줄 단위 점검이고 YAML 이나 표현식 검증기가 아니다.
- 미검증(첫 실행 전에는 알 수 없다): 러너 레이블, action 입력 이름, Git Bash 위의 Windows 단계(가짜 실행 파일과 심볼릭 링크를 쓰는 selftest 시나리오 포함), Linux WebKitGTK 빌드, ubuntu 의 awk(mawk)에서 새 awk 코드, Windows 에서만 컴파일되는 `#[cfg(windows)]` 테스트.
- 남은 문제: CI-1, PLAT-1, P0B-R6.

## 근거

- 제안: P0-D 의 요구사항·계획 단계에서 Planner 와 Queen 이 정했다. 사용자는 이 결정을 명시적으로 받아들이지 않았다. 커밋 승인은 결정 승인으로 치지 않는다([ADR 목록](README.md)의 상태 규칙).
- 승인된 설계 §9 (통과 기준 「새 clone 에서 build·test·lint 가 exit 0」과 Phase 0 범위의 「CI 정의」)와 §9.1 (게이트 스크립트가 결과를 판정한다, [ADR-019](ADR-019-gate-scripts-judge-results.md)).
