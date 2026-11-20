# ADR-009 Metadata DB 는 두 모드 모두 MariaDB

- 상태: 승인됨
- 날짜: 2026-11-15
- 구현: 일부 구현 — MariaDB 연결과 Flyway 는 구현됨 (P0-B, f913239). Desktop 의 앱 설정 입력은 계획 (Phase 6), Phase 0 의 임시 방식은 [ADR-017](ADR-017-desktop-metadata-settings-from-env.md)

## 맥락

명세 3절은 Metadata Database 로 MariaDB 와 Flyway 를 든다. Desktop 에서는 내장 DB(H2)를 쓰자는 제안이 있었다.

## 결정

- Metadata DB 는 Desktop Mode 와 Server Mode 모두 MariaDB 하나다. 내장 DB 안은 반려됐다.
- 스키마는 Flyway 로 관리한다.
- Desktop 은 사용자가 앱 설정에 입력한 MariaDB(로컬 또는 원격)에 접속한다. 첫 실행 때 접속 정보를 받는 화면을 보이고 비밀번호는 OS 키체인에 둔다. Server 는 환경변수로 지정한다.
- Metadata DB 에 연결할 수 없으면 런타임은 기동에 실패한다.

## 결과

- 구현됨 (P0-B): `runtime/adapter-metadata` 모듈, `runtime/adapter-metadata/src/main/resources/db/migration/V1__create_app_setting.sql`, `QUERYHUB_METADATA_DB_*` 환경변수로 받는 연결, 연결이나 인증이 실패하면 웹 서버가 열리기 전에 기동을 멈추는 검사. 지원 서버는 MariaDB 10.11 이상이고 검증한 버전은 12.3 뿐이다([DATABASE_SUPPORT.md](../DATABASE_SUPPORT.md)).
- 계획 (Phase 6): Desktop 의 첫 실행 화면과 키체인. 그때까지 Desktop 은 셸 환경변수를 쓴다([ADR-017](ADR-017-desktop-metadata-settings-from-env.md)).
- 치르는 값: Desktop 이 외부 MariaDB 에 의존한다(설계 §11 7번). Metadata DB 가 없으면 앱을 쓸 수 없으므로 첫 실행 화면과 오류 안내가 중요하다.

## 근거

- 사용자 결정 D2, 2026-11-15: 「2. MariaDB로 단일로 진행해줘.」 ([REQUIREMENTS.md](../REQUIREMENTS.md) AR-0)
- 승인된 설계 §1.2 와 §10 의 ADR 009.
