# ADR-019 게이트 스크립트가 테스트 결과를 직접 판정한다

- 상태: 승인됨
- 날짜: 2026-11-15
- 구현: 구현됨 (P0-A ee00165, P0-B f913239, P0-C f84c628)

## 맥락

품질 게이트는 등록된 명령의 종료 코드만 본다. 실행된 테스트가 0건이거나, 실패가 있는데 종료 코드가 0 이거나, 건너뛴 테스트로 녹색이 되는 거짓 통과는 종료 코드만으로는 잡히지 않는다.

## 결정

- 게이트 명령은 `scripts/gate/build.sh`, `test.sh`, `lint.sh`, `integration.sh` 다. 저장소 루트를 기준으로 동작하고 새 clone 에서 바로 돈다. 산출물은 모두 git 이 무시한다(게이트 뒤 `git status` 가 깨끗해야 한다).
- 스크립트가 결과 파일을 직접 판정한다.
  - `scripts/gate/junit-check.sh`: JUnit XML 이 없거나(`NO_RESULTS`), 실행된 테스트가 0건이거나(`ZERO_TESTS`), 실패·오류(`FAILURES`/`ERRORS`), 건너뜀(`SKIPPED`), 이전 실행의 결과(`STALE`), 읽거나 해석할 수 없는 결과(`INVALID`)가 있으면 실패한다.
  - `scripts/gate/selftest.sh`: `test.sh` 가 판정을 믿기 전에 `junit-check.sh` 가 거짓 통과 픽스처를 모두 거부하는지 확인한다.
  - `scripts/gate/cargo-test-check.sh` (P0-C): `cargo test` 출력에서 결과 없음, 0건, 실패, `#[ignore]`, 이름 필터로 일부만 실행, 라이브러리 테스트 없음, 해석 불가를 거부한다.
  - `scripts/gate/integration.sh` (P0-B): Docker 가 없으면 건너뛰지 않고 실패한다.
- 이후 태스크에서 `scripts/gate/` 와 테스트 설정을 약화시키는 변경은 리뷰에서 범위 위반으로 다룬다.
- `desktop/` 이 생긴 P0-C 에서 같은 스크립트에 프런트엔드와 Rust 단계를 더했다.

## 결과

- 구현됨: P0-A 에서 build·test·lint 와 junit-check·selftest, P0-B 에서 integration, P0-C 에서 desktop 단계와 cargo-test-check. 단계별 방어의 자세한 내용은 [TEST_PLAN.md](../TEST_PLAN.md)에 있다.
- P0-D (d0b0f0f, 2026-11-20): build·test·lint 의 `--desktop-only`, lint 의 `docs-check.sh`(DOC-1)와 `workflow-check.sh`, `selftest.sh --no-skip` 과 junit-check 의 합계 형식 거부 분기(`valid_counts`)를 다루는 시나리오(P0A-N1), stdout 만 읽는 Docker 사전 점검(P0B-R4). CI 가 같은 스크립트를 부르는 결정은 [ADR-020](ADR-020-ci-runs-gate-scripts.md)(제안)이다.
- P1-01 (커밋 전, P0-D 리뷰 후속): `workflow-check.sh` 는 줄 단위로 다룰 수 없는 YAML 문법과 LF 가 아닌 줄바꿈 문자를 받지 않고 거부한다(`UNSUPPORTED_SYNTAX`). 실패를 덮거나 건너뛰게 하는 키(`CONTINUE_ON_ERROR`, `IF_CONDITION`), bash 가 아닌 셸(`SHELL_NOT_BASH`), 단계 밖이나 한 단계에 두 번 있는 `run:`(`RUN_KEY`), run 값 안의 표현식(`RUN_EXPR`)도 거부한다. P1-01 리뷰 2회차부터 구조 줄을 허용 목록과 들여쓰기 구조로 보고(여러 줄에 걸친 평범한 스칼라도 `UNSUPPORTED_SYNTAX`), 같은 매핑의 같은 키(`DUPLICATE_KEY`)와 빈 조각이나 `.`·`..` 가 든 `uses:` 경로(`USES_NOT_SHA`)를 거부한다. checkout 의 `persist-credentials: false` 는 그 단계의 `with:` 바로 아래만 세고, 팩토리 명령은 단계의 한 줄짜리 run 값만 센다. `docs-check.sh` 는 저장소 루트 밖으로 나가는 링크를 깨진 링크로 센다. 판정 범위와 한계는 [TEST_PLAN.md](../TEST_PLAN.md) 「lint 의 저장소 단계」에 있다.
- 판정 스크립트가 검사받는 저장소 안에 있으므로 스크립트 자체의 변경도 리뷰 대상이다.
- 남은 문제([BACKLOG](../BACKLOG.md)): 심볼릭 링크 모듈이 탐색에서 빠진다(P0A-N2). P0A-N1, P0B-R4, DOC-1 은 P0-D 에서 닫았다. CI 에서의 실행은 미검증이다(CI-1).

## 근거

- 승인된 설계 §9.1 (2026-11-15 사용자 승인): 「스크립트가 "실행된 테스트가 0건", "실패·오류가 있는데 exit 0", "건너뛴 테스트로 녹색" 같은 거짓 통과를 직접 막는다(JUnit XML 집계 확인).」
