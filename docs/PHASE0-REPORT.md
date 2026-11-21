# Phase 0 보고

Phase 0(기반)의 결과, 통과 기준과 근거, 태스크별 요약, 사람의 결정, 남은 문제, 미검증 항목, 다음 단계를 적는다(명세 13절). 날짜는 커밋 날짜다. 테스트 수는 각 태스크의 마지막 게이트 실행에서 센 값이고 [CHANGELOG.md](../CHANGELOG.md), [TEST_PLAN.md](TEST_PLAN.md)의 수와 같다.

## 요약

- Phase 0 은 다섯 태스크(P0-A, P0-B, P0-C, 문서 기준선, P0-D)로 했다. 다섯 태스크가 모두 커밋됐고 **Phase 0 은 완료됐다**(마지막 P0-D 는 d0b0f0f, 2026-11-20).
- 승인된 설계 §9 의 Phase 0 통과 기준 네 개는 모두 macOS 로컬에서 확인했다. Windows·Linux 는 확인하지 못했다.
- CI 워크플로(`.github/workflows/ci.yml`)는 **정의됨·미실행**이다. 원격 저장소가 없고 push 는 사람이 하므로 한 번도 실행되지 않았다([BACKLOG.md](BACKLOG.md) CI-1, [ADR-020](ADR/ADR-020-ci-runs-gate-scripts.md)).
- 구현된 것: Gradle 멀티 모듈 런타임(Spring Boot 4.1.2, Java 21)과 의존 규칙, 공통 응답·오류 형식, health, Metadata DB(MariaDB)와 Flyway, Testcontainers 통합 테스트, Tauri 2.11 Desktop 셸 스파이크, 결과를 직접 판정하는 게이트 스크립트, 설계 문서와 ADR 20건, CI 정의.
- 남은 문제는 열린 BACKLOG 항목 19개다(아래 「남은 문제」). 닫은 항목은 5개이고 모두 P0-D 에서 닫았다.

## 통과 기준과 근거

설계 §9 의 Phase 0 통과 기준이다([ARCHITECTURE.md](ARCHITECTURE.md) 「Phase 계획」). 「AC」는 각 태스크의 인수 기준 번호다.

| # | 통과 기준 | 근거 | 상태 |
|---|---|---|---|
| 1 | 새 clone 에서 build·test·lint 가 exit 0 | 커밋할 파일만 복사한 새 사본에서 게이트를 돌렸다: P0-A AC-01(build·test·lint), P0-B AC-21 과 P0-C AC-09(integration 포함 네 게이트, 게이트 뒤 `A` 줄만). 읽는 법은 [TEST_PLAN.md](TEST_PLAN.md) 「새 사본 검사의 읽는 법」이다. P0-D 는 같은 방법을 AC-14 로 테스트 단계에서 확인한다. CI 의 `gates` 작업이 새 checkout 에서 같은 일을 하도록 정의했다 | 확인됨 (macOS 로컬). CI 의 새 clone 은 미검증 |
| 2 | ArchUnit 의존 규칙 통과 | `test.sh` 가 실행하는 `ArchitectureTest`(R1-R8)와 고의 위반 픽스처로 규칙이 실제로 실패하는지 보는 `ArchitectureRulesSelfTest`: P0-A AC-07, P0-B AC-19. P0-D 의 게이트에서도 runtime 단위 300건에 들어 있다. Gradle 선언 검사가 첫째 방어선이다([ARCHITECTURE.md](ARCHITECTURE.md) 「모듈과 의존 규칙」) | 확인됨 (macOS 로컬) |
| 3 | 런타임 기동 후 health 200 | P0-A AC-08 `HealthEndpointTest`(실제 Tomcat, 인증 없이 200 UP)와 실행 JAR 수동 확인. P0-B AC-13 기동 스모크와 AC-17 실행 JAR 하위 프로세스 테스트(실제 MariaDB 12.3, `integration.sh`). P0-C AC-16 런타임 통합 테스트. P0-D 의 `integration.sh` 는 23건 | 확인됨 (macOS 로컬, MariaDB 12.3) |
| 4 | Tauri 에서 런타임 기동·중지 | P0-C 의 `cargo test`(셸 감독: 가짜 자식과 임시 HTTP 서버), AC-17 의 macOS 수동 확인(health 200, 인자, kill -9, 실패 카드), AC-19 사람의 인수 확인(개발 모드 앱과 서명 없는 디버그 번들, [TEST_PLAN.md](TEST_PLAN.md) 「테스트 계층」). P0-D 의 `cargo test` 는 32건 | 확인됨 (macOS 로컬). Windows·Linux 는 미검증(PLAT-1) |

## 태스크별 요약

| 태스크 | 팩토리 태스크 | 커밋 | 날짜 | 개발 회차 | 자동 재시도 | 테스트 |
|---|---|---|---|---|---|---|
| P0-A 런타임 골격 | T-20261115-233911-3446 | ee00165 | 2026-11-17 | 2 | 1/2 | runtime 단위 107 |
| P0-B 메타데이터 기반 | T-20261117-204259-6611 | f913239 | 2026-11-18 | 3 | 2/2 | runtime 단위 234, 통합 21 |
| P0-C Desktop 셸 스파이크 | T-20261118-095453-0662 | f84c628 | 2026-11-19 | 2 | 1/2 | runtime 단위 300, 통합 23, vitest 30, cargo test 30 |
| 문서 기준선 | T-20261119-074437-3980 | 51141df | 2026-11-20 | 2 | 1/2 | 코드 변경 없음 |
| P0-D CI 정의와 Phase 0 마무리 | T-20261120-181159-c278 | d0b0f0f | 2026-11-20 | 1 | 0/2 | runtime 단위 300, 통합 23, vitest 30, cargo test 32, selftest 93 시나리오 (macOS 로컬) |

- 개발 회차는 개발 단계를 거친 횟수이고, 자동 재시도는 팩토리의 실패 뒤 재시도 횟수(한도 2)다.
- **P0-A:** Gradle 9.7.1 멀티 모듈(domain, application, adapter-web, app), 선언 의존 검사와 ArchUnit, 공통 응답·오류, X-Request-Id, desktop·server 프로파일, health, 게이트 스크립트와 JUnit 판정기.
- **P0-B:** `adapter-metadata`, Flyway V1(`app_setting`), Metadata DB 연결과 기동 검사, 비밀 값의 출처 제한([ADR-011](ADR/ADR-011-metadata-secrets-env-only.md)), Flyway 고정([ADR-012](ADR/ADR-012-flyway-connection-pin.md)), Testcontainers 통합 테스트와 `integration.sh`, 개발용 compose.
- **P0-C:** Tauri 2.11.5 셸, 런타임 자동 시작·중지·재시작과 헬스 표시, 자식 환경 허용 목록([ADR-013](ADR/ADR-013-desktop-child-env-allowlist.md)), 런타임의 부모 감시([ADR-014](ADR/ADR-014-runtime-parent-watch.md)), 게이트의 desktop 단계와 cargo test 판정기.
- **문서 기준선:** `docs/` 의 설계 문서, ADR-001..ADR-019, BACKLOG, `CHANGELOG.md`, README 정리.
- **P0-D:** CI 워크플로 정의(정의됨·미실행), 게이트의 `--desktop-only`, `docs-check.sh`(DOC-1), `workflow-check.sh`, `selftest.sh --no-skip` 과 junit-check 의 합계 형식 거부 분기 시나리오(P0A-N1), stdout 만 읽는 Docker 사전 점검(P0B-R4), Windows `Path` 처리(P0C-C2-4, 미검증), ADR-020, 이 보고. `#[cfg(windows)]` 테스트 1건은 macOS 에서 컴파일되지 않아 cargo test 32 에 들어 있지 않다.

## 사람의 결정

모두 사용자가 내린 결정이다. 커밋이나 병합의 승인은 그 코드의 승인이고, ADR 에 적은 결정의 승인으로 치지 않는다([ADR 목록](ADR/README.md)의 상태 규칙).

| 날짜 | 결정 | 저장소의 근거 |
|---|---|---|
| 2026-11-15 | 설계를 승인하고 결정 D1–D4 를 내렸다 | [REQUIREMENTS.md](REQUIREMENTS.md) AR-0 |
| 2026-11-17 | 개발용 compose 의 예시 파일 이름을 `deploy/env.example` 로 정했다 | [ADR-016](ADR/ADR-016-env-example-naming.md) |
| 2026-11-18 | P0-B 리뷰 지적 R1(프로세스에 보이는 출처의 비밀 값)과 R10(Flyway 연결)을 병합 전에 지금 고치기로 했다 | [ADR-011](ADR/ADR-011-metadata-secrets-env-only.md), [ADR-012](ADR/ADR-012-flyway-connection-pin.md) |
| 2026-11-18 | P0-C 리뷰 지적 R1–R4 를 지금 고치기로 했다. R1 의 수정이 자식 환경 허용 목록이다 | [ADR-013](ADR/ADR-013-desktop-child-env-allowlist.md) |
| 2026-11-19 | P0-C 의 UI 인수 확인(AC-19). 사용자는 원격에서, Queen 이 앱을 조작하는 동안 캡처를 검토했다 | [TEST_PLAN.md](TEST_PLAN.md) 「테스트 계층」 |
| 2026-11-19 | 런타임 운영 지표 요구사항(OM-1..OM-7, 화면 Phase 6, 수집 Phase 7)을 승인했다 | [REQUIREMENTS.md](REQUIREMENTS.md) AR-1 |
| 2026-11-19 | P0-C 커밋(f84c628)을 승인했다: 「1. 진행해주세요」 | [ADR-015](ADR/ADR-015-desktop-fixed-port-and-cors.md) 「근거」 |
| 2026-11-20 | 문서 기준선 커밋(51141df)을 승인했다: 「1. 승인」 | [CHANGELOG.md](../CHANGELOG.md) |
| 2026-11-20 | 「P0-D 리뷰 R1 지금 고칠까요?」에 「후속으로 넘김」 이라고 답했다. 그래서 P0-D 리뷰 지적 R1–R7 은 후속 태스크 P1-01 로 넘어갔다 | [BACKLOG.md](BACKLOG.md) 「완료」 P0D-R1..P0D-R7 |
| 2026-11-20 | P0-D 커밋(d0b0f0f)을 승인했다: 「1. 승인」 | [CHANGELOG.md](../CHANGELOG.md) |

- 제안 상태로 남은 ADR 은 ADR-015, ADR-017, ADR-018, ADR-020 이다. 사용자가 받아들이면 승인됨으로 바꾼다.
- P0-D 리뷰 지적 R1–R7(medium 1, low 4, info 2)은 Phase 0 안에서 고치지 않고 P1-01 로 넘겼다. 그래서 아래 「남은 문제」의 열린 항목 수에 들어 있지 않다. P1-01 이 이를 P0D-R1..P0D-R7 로 닫고, R7 에서 나온 Gradle 의존성 검증을 CI-2 로 연다([BACKLOG.md](BACKLOG.md)).

## 남은 문제

열린 [BACKLOG.md](BACKLOG.md) 항목 19개를 목표별로 묶었다. 목표는 계획이며 바뀔 수 있다.

| 목표 | 항목 |
|---|---|
| Phase 1 | [P0A-N2](BACKLOG.md), [P0A-N3](BACKLOG.md), [P0A-N4](BACKLOG.md), [P0A-I1](BACKLOG.md), [P0A-I2](BACKLOG.md), [P0B-R3](BACKLOG.md), [P0B-R5](BACKLOG.md), [P0B-R6](BACKLOG.md), [P0B-H1](BACKLOG.md), [P0B-R14](BACKLOG.md), [P0B-R15](BACKLOG.md) |
| Phase 6 | [P0C-R5](BACKLOG.md), [P0C-R7](BACKLOG.md), [P0C-C2-1](BACKLOG.md), [P0C-C2-2](BACKLOG.md) |
| Phase 8 | [P0C-R6](BACKLOG.md), [P0C-C2-3](BACKLOG.md) |
| CI-1 첫 실행 / Phase 8 | [PLAT-1](BACKLOG.md) |
| 사람의 첫 push 뒤 | [CI-1](BACKLOG.md) |

- P0-D 에서 닫은 항목: P0A-N1, P0B-R4, P0B-R7, P0C-C2-4, DOC-1. 모두 macOS 로컬에서만 확인했고 CI 에서는 미검증이다(BACKLOG 「완료」 표의 메모).
- P0B-R6(Testcontainers 포트)은 runtime 의 통합 테스트 지원 코드를 고쳐야 해서 Phase 1 로 미뤘다([ADR-020](ADR/ADR-020-ci-runs-gate-scripts.md)).

## 미검증

- **CI:** 워크플로는 한 번도 실행되지 않았다(CI-1). 러너 레이블(`ubuntu-24.04`, `macos-15`, `windows-2025`), action 입력 이름, Git Bash 위의 Windows 단계, Linux WebKitGTK 빌드, ubuntu 의 awk(mawk)에서 새 awk 코드를 알 수 없다.
- **Windows·Linux Desktop:** 빌드, 번들, 자식 환경 허용 목록, 경로 처리, 종료 방식(PLAT-1). Windows 의 `Path` 처리(P0C-C2-4)를 확인하는 `#[cfg(windows)]` 테스트는 한 번도 컴파일되지 않았다.
- **Metadata DB:** MariaDB 12.3 만 검증했다([DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)).
- **사람의 인수 확인:** macOS 에서만 했다(P0-C AC-19, 개발 모드 앱과 서명 없는 디버그 번들).
- **`java -version` 사전 점검의 환경:** 단위 비교와 코드 읽기로만 확인했다(P0C-C2-2).

## 다음 단계

1. (완료) 사람이 P0-D 의 변경을 검토하고 커밋했다(d0b0f0f, 2026-11-20). Phase 0 이 끝났다.
2. 사람이 원격 저장소를 만들고 push 한다.
3. 첫 CI 실행을 확인한다: `gates` 작업과 `desktop` 작업(macos-15, windows-2025)의 결과를 [BACKLOG.md](BACKLOG.md) CI-1 과 PLAT-1 에 적고, 실패가 있으면 새 항목으로 남긴다.
4. 제안 상태의 ADR(ADR-015, ADR-017, ADR-018, ADR-020)을 사용자가 검토한다.
5. Phase 1(DB 연결, Provider 4종, 관리자 토큰)을 계획한다. 목표가 Phase 1 인 BACKLOG 항목 11개를 함께 본다.
