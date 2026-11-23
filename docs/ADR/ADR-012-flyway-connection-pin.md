# ADR-012 Flyway 는 검사한 메인 DataSource 로만 마이그레이션한다

- 상태: 승인됨
- 날짜: 2026-11-18
- 구현: 구현됨 (P0-B, f913239). P1-06 의 SQLite 저장소에서도 그대로다(39c90e1 (2026-11-23)). P1-07 의 업무 DB 풀은 Spring 빈이 아니고 DataSource 빈은 Metadata 풀 하나뿐이라 이 고정에 닿지 않는다([ADR-027](ADR-027-database-provider.md))

> 2026-11-23 주 (P1-06, [ADR-026](ADR-026-sqlite-embedded-metadata-store.md)): Metadata 저장소가 내장 SQLite 로 바뀌어도 이 결정은 그대로다. `MetadataFlywayConnectionDetails` 가 URL 과 사용자를 비워 두므로 Flyway 는 검사한 메인 Hikari 풀(`jdbc:sqlite:<data-dir>/queryhub.db`, 런타임의 PRAGMA)로만 마이그레이션한다. `spring.flyway.url`·`user` 가 다른 SQLite 파일을 가리켜도 마이그레이션은 검사한 저장소에서 돌고 그 파일은 만들어지지 않는다(단위 시험 `MetadataFlywayConnectionTest` 와 통합 시험 `MetadataStartupIntegrationTest`). 맥락의 호스트·DB 이름·`allowLocalInfile`·UTC 옵션과 결과의 DDL 권한은 MariaDB 때의 이야기다. SQLite 에는 계정이 없다.

## 맥락

P0-B 코드 리뷰 2회차(지적 R10)는 Boot 4.1.2 의 Flyway 자동 구성이 `spring.flyway.url` 이나 `spring.flyway.user` 가 있으면 자기 `SimpleDriverDataSource` 를 따로 만든다는 것을 재현했다. 그러면 마이그레이션 연결이 검사한 호스트와 DB 이름, 고정한 `allowLocalInfile=false` 와 UTC 옵션을 모두 우회하고, `ps` 에 보이는 비밀번호도 쓰인다.

## 결정

- Flyway 는 언제나 런타임이 검사한 메인 DataSource 로 마이그레이션한다. `spring.flyway.url`, `spring.flyway.user`, `spring.flyway.password` 는 출처(명령행, `-D`, JSON, 환경변수, 설정 파일)와 상관없이 연결에 영향을 주지 않는다.
- 방법: `MetadataDatabaseAutoConfiguration` 이 `FlywayConnectionDetails` 빈(`MetadataFlywayConnectionDetails`)을 등록한다. 이 빈의 `getJdbcUrl()` 과 `getUsername()` 은 `null` 을 돌려준다. Boot 4.1.2 의 `FlywayConnectionDetails` 는 `@ConditionalOnMissingBean` 이므로 이 빈이 속성 기반 연결 정보를 대신하고, Flyway 는 메인 DataSource 를 쓴다.
- Flyway 가 켜져 있어도 웹 서버가 열리기 전에 메인 풀이 연결되는지 확인한다.

## 결과

- 구현됨 (P0-B): `runtime/app/src/main/java/com/queryhub/app/config/metadata/MetadataFlywayConnectionDetails.java`. 통합 테스트가 Flyway 고정을 확인한다([TEST_PLAN.md](../TEST_PLAN.md)).
- 마이그레이션을 별도 DDL 계정으로 돌릴 수 없다. 메인 계정에 DDL 권한이 있어야 한다([DATABASE_SUPPORT.md](../DATABASE_SUPPORT.md)).
- 이 키들을 명령행으로 주면 조용히 무시된다. 거부나 경고는 하지 않는다([BACKLOG](../BACKLOG.md) P0B-R15). 자동 구성을 통째로 빼는 경로는 P0B-R14 다.
- Boot 의 내부 구조에 기대는 결정이다. Boot 를 올릴 때 `FlywayConnectionDetails` 의 조건이 그대로인지 다시 확인한다.

## 근거

- 사용자 결정, 2026-11-18 (P0-B 코드 리뷰 2회차): 「지금 고칠까요?」에 「지금 고침 (권장)」으로 답해 R10 을 구조적으로 고치기로 했다. 고치는 방식(`FlywayConnectionDetails` 빈으로 마이그레이션을 검사한 메인 풀에 고정)은 리뷰어의 제안을 바탕으로 그 수정의 요구사항으로 Queen 이 정했다.
- 승인된 설계 §1.2 의 비밀 값 원칙. [ADR-011](ADR-011-metadata-secrets-env-only.md) 과 같은 위협을 다룬다.
