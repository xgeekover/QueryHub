# ADR-017 Phase 0 Desktop 은 Metadata DB 설정을 셸 환경변수에서 읽는다

- 상태: 제안
- 날짜: 2026-11-19
- 구현: 구현됨 (P0-C, f84c628) — 첫 실행 화면과 OS 키체인은 계획 (Phase 6)

## 맥락

승인된 설계 §1.2 는 Desktop 의 Metadata DB 를 「사용자가 앱 설정에 입력한 MariaDB」로 적고, 첫 실행 화면에서 접속 정보를 받아 비밀번호를 OS 키체인에 두라고 한다. Phase 0 의 셸 스파이크에는 설정 화면이 없다.

## 결정

- Phase 0 의 셸은 자기 프로세스 환경의 `QUERYHUB_METADATA_DB_*` 변수를 읽어 그대로 자식 런타임의 환경변수로 넘긴다. 명령행 인자에는 넣지 않는다.
- 필요한 값이 없으면 런타임을 띄우지 않고 `metadata_settings_missing` 으로 실패한다. 메시지는 빠진 변수 이름만 말하고, 개발 모드에서는 `deploy/.env` 를 읽은 셸에서 앱을 시작하라고 안내한다.
- 런타임 출력에 비밀번호 값이 그대로 나오면 셸이 `********` 로 가린다(`output.rs` 의 `mask_secret`). 인코딩되거나 변형된 형태는 가리지 않는다.

## 결과

- 구현됨 (P0-C): `desktop/src-tauri/src/runtime/launch.rs` 의 Metadata DB 변수 상수와 `settings_missing_failure`.
- 치르는 값: Finder 에서 연 앱(셸 환경이 없는 실행)은 `metadata_settings_missing` 을 보인다. 개발자 외에는 쓰기 어렵다.
- 계획 (Phase 6): 첫 실행 화면, OS 키체인 저장, 앱 설정에서 읽기. 그때 이 ADR 은 대체됨 상태가 된다.

## 근거

- P0-C 계획과 구현에서 정했다. 사용자의 명시적인 결정은 없다(P0-C 커밋 승인은 코드의 승인이다). 사용자가 받아들이면 승인됨으로 바꾼다.
- 관련 결정: [ADR-009](ADR-009-mariadb-metadata-db.md), [ADR-013](ADR-013-desktop-child-env-allowlist.md). 설계와 다른 점은 [ARCHITECTURE.md](../ARCHITECTURE.md)의 「설계와 다른 구현」에 있다.
