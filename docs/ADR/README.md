# 아키텍처 결정 기록 (ADR)

QueryHub 의 기술 결정을 결정 하나에 파일 하나로 남긴다. 다른 문서는 결정의 이유를 되풀이하지 않고 여기에 링크한다.

## 상태 규칙

결정 상태(`상태`)와 구현 상태(`구현`)는 따로 적는다. 결정이 승인됐어도 구현은 계획일 수 있고, 구현됐어도 결정은 제안일 수 있다.

| 상태 | 뜻 |
|---|---|
| 승인됨 | 사용자가 결정했거나 승인했다. ADR 은 사용자의 문구와 날짜를 인용하거나, 2026-11-15 에 승인된 설계의 해당 절을 든다. |
| 제안 | 계획이나 리뷰 중에 정해졌고 구현도 됐을 수 있지만, 사용자가 명시적으로 결정하지 않았다. 사용자가 받아들이면 승인됨으로 바꾸고 그 문구와 날짜를 `근거`에 더한다. |
| 대체됨(ADR-nnn) | 뒤의 ADR 이 이 결정을 바꿨다. 이 파일은 지우지 않는다. |
| 폐기 | 더는 따르지 않는 결정이다. |

- 커밋이나 병합을 승인한 것은 그 코드를 승인한 것이다. ADR 에 적은 결정을 승인한 것으로 치지 않는다.
- `구현` 값: 구현됨 (P0-x, 커밋) | 일부 구현 | 계획 (Phase n). 일부 구현이면 무엇이 구현됐고 무엇이 계획인지 같은 줄에 적는다.
- 「설계 §n」은 사용자가 2026-11-15 에 승인한 설계의 n절을 말한다. 그 내용은 [ARCHITECTURE.md](../ARCHITECTURE.md)와 이 ADR 들로 옮겨져 있다. 사용자 결정 D1–D4 의 원문 인용은 [REQUIREMENTS.md](../REQUIREMENTS.md)의 AR-0 에 있다.
- 남은 문제(BACKLOG ID)는 [BACKLOG.md](../BACKLOG.md)에 있다.

## 목록

| 번호 | 제목 | 상태 | 구현 | 날짜 |
|---|---|---|---|---|
| [ADR-001](ADR-001-jvm-child-process.md) | JVM 런타임을 자식 프로세스로 실행하고 JRE 를 번들한다 | 승인됨 | 일부 구현 (P0-C) | 2026-11-15 |
| [ADR-002](ADR-002-single-http-port.md) | HTTP 포트는 하나, 관리·데이터 API 는 경로로 구분 | 승인됨 | 일부 구현 (P0-A) | 2026-11-15 |
| [ADR-003](ADR-003-single-dispatcher-route-registry.md) | 단일 디스패처와 메모리 라우트 레지스트리 | 승인됨 | 계획 (Phase 3, 4) | 2026-11-15 |
| [ADR-004](ADR-004-select-only-defense.md) | SELECT 전용 3중 방어, 파싱 실패는 거부 | 승인됨 | 계획 (Phase 2) | 2026-11-15 |
| [ADR-005](ADR-005-streaming-single-path.md) | 스트리밍 단일 경로, 행 제한은 setMaxRows | 승인됨 | 계획 (Phase 1, 4, 5) | 2026-11-15 |
| [ADR-006](ADR-006-password-aes-gcm-api-key-hmac.md) | DB 비밀번호는 AES-256-GCM, API Key 는 HMAC-SHA-256 해시 | 승인됨 | 계획 (Phase 1, 4) | 2026-11-15 |
| [ADR-007](ADR-007-spring-jdbc-metadata.md) | 메타데이터 접근은 Spring JDBC (JPA 미사용) | 승인됨 | 구현됨 (P0-B, f913239) | 2026-11-15 |
| [ADR-008](ADR-008-openapi-ts-client.md) | 관리 API 의 OpenAPI 에서 TS 클라이언트 생성 | 승인됨 | 계획 (Phase 1, 6) | 2026-11-15 |
| [ADR-009](ADR-009-mariadb-metadata-db.md) | Metadata DB 는 두 모드 모두 MariaDB | 승인됨 | 일부 구현 (P0-B) | 2026-11-15 |
| [ADR-010](ADR-010-spring-boot-4-java-21.md) | Spring Boot 4.1 + Java 21 | 승인됨 | 구현됨 (P0-A, ee00165) | 2026-11-15 |
| [ADR-011](ADR-011-metadata-secrets-env-only.md) | Metadata DB 비밀 값은 환경변수로만 받고, 프로세스에 보이는 출처는 거부 | 승인됨 | 구현됨 (P0-B, f913239) | 2026-11-18 |
| [ADR-012](ADR-012-flyway-connection-pin.md) | Flyway 는 검사한 메인 DataSource 로만 마이그레이션 | 승인됨 | 구현됨 (P0-B, f913239) | 2026-11-18 |
| [ADR-013](ADR-013-desktop-child-env-allowlist.md) | Desktop 셸은 자식 런타임에 허용 목록의 환경변수만 넘긴다 | 승인됨 | 구현됨 (P0-C, f84c628) | 2026-11-18 |
| [ADR-014](ADR-014-runtime-parent-watch.md) | 런타임이 QUERYHUB_DESKTOP_PARENT_PID 로 부모 셸을 감시 | 승인됨 | 구현됨 (P0-C, f84c628) | 2026-11-15 |
| [ADR-015](ADR-015-desktop-fixed-port-and-cors.md) | Desktop 포트 18580 고정과 Tauri 출처용 관리 API CORS | 제안 | 구현됨 (P0-C, f84c628) | 2026-11-19 |
| [ADR-016](ADR-016-env-example-naming.md) | 개발용 compose 예시 파일 이름은 deploy/env.example | 승인됨 | 구현됨 (P0-B, f913239) | 2026-11-17 |
| [ADR-017](ADR-017-desktop-metadata-settings-from-env.md) | Phase 0 Desktop 은 Metadata DB 설정을 셸 환경변수에서 읽는다 | 제안 | 구현됨 (P0-C, f84c628) | 2026-11-19 |
| [ADR-018](ADR-018-mariadb-connector-j-override.md) | MariaDB Connector/J 3.5.11 로 BOM 버전을 덮어쓴다 | 제안 | 구현됨 (P0-B, f913239) | 2026-11-18 |
| [ADR-019](ADR-019-gate-scripts-judge-results.md) | 게이트 스크립트가 테스트 결과를 직접 판정한다 | 승인됨 | 구현됨 (P0-A, P0-B, P0-C) | 2026-11-15 |
| [ADR-020](ADR-020-ci-runs-gate-scripts.md) | CI 는 팩토리 게이트와 같은 스크립트를 같은 명령으로 실행한다 | 제안 | 일부 구현 — 정의됨 (P0-D), 실행은 미검증 | 2026-11-20 |

제안 상태인 ADR-015, ADR-017, ADR-018, ADR-020 은 사용자가 받아들이면 승인됨으로 바꾼다.

## 템플릿

새 ADR 은 다음 번호로 `ADR-NNN-<english-slug>.md` 파일을 만들고 위 목록에 한 줄을 더한다. 첫 줄은 제목 줄 `# ADR-NNN 제목` 하나이고, 그 아래는 다음과 같다.

```markdown
- 상태: 승인됨 | 제안 | 대체됨(ADR-nnn) | 폐기
- 날짜: YYYY-MM-DD
- 구현: 구현됨 (P0-x, 커밋) | 일부 구현 | 계획 (Phase n)

## 맥락
결정이 필요했던 상황과 제약.

## 결정
정한 것. 코드가 있으면 저장소 상대 경로와 심볼을 적는다.

## 결과
좋아지는 점, 치르는 값, 남은 문제(BACKLOG ID).

## 근거
사용자 결정의 문구와 날짜, 또는 승인된 설계의 절. 제안이면 누가 어느 단계에서 정했는지.
```
