# ADR-011 Metadata DB 비밀 값은 환경변수로만 받고, 프로세스에 보이는 출처는 거부한다

- 상태: 대체됨(ADR-026)
- 날짜: 2026-11-18
- 구현: 구현됨 (P0-B, f913239). P1-06 에서 지웠다(아래 주)

> 2026-11-23 주: [ADR-026](ADR-026-sqlite-embedded-metadata-store.md) 으로 Metadata 저장소가 내장 SQLite 가 되어 접속 정보와 비밀번호가 없어졌다. P1-06 은 `QUERYHUB_METADATA_DB_*` 변수, `queryhub.metadata.db.password`·`spring.datasource.hikari.password`·`jdbc-url`·`data-source-properties` 거부를 지웠다. 프로세스에 보이는 출처 검사(`runtime/app/src/main/java/com/queryhub/app/config/ProcessVisibleSources.java`)는 관리자 토큰([ADR-021](ADR-021-admin-token-bearer.md))과 마스터 키([ADR-023](ADR-023-master-key-delivery.md))를 위해 남는다. SQLite 설정을 `data-source-properties` 로 바꾸는 것은 이제 출처와 상관없이 거부하고 풀 연결의 PRAGMA 를 확인한다(ADR-026 결정 9). 아래 본문은 P0-B 때의 기록으로 남긴다.

## 맥락

명령행 인자와 JVM 시스템 속성(`-D`)은 프로세스 목록(`ps`)에 보인다. P0-B 코드 리뷰 1회차(지적 R1)는 비밀번호 가드가 `queryhub.metadata.db.password` 한 키만 보고 있어서, `spring.application.json` 이나 `spring.datasource.hikari.*` 키로 비밀번호를 넘기면 가드를 지나간다는 것을 재현했다.

## 결정

- Metadata DB 접속 정보는 `QUERYHUB_METADATA_DB_HOST`, `_PORT`, `_NAME`, `_USERNAME`, `_PASSWORD` 환경변수로 받는다. 변수 표는 [DEPLOYMENT.md](../DEPLOYMENT.md)에 있다.
- 프로세스에 보이는 출처는 명령행 인자(`commandLineArgs`), JVM 시스템 속성(`systemProperties`), 그리고 그 둘 중 하나로 넘어온 `spring.application.json` 이다.
- 이 출처가 다음 키 중 하나를 정하면 웹 서버가 열리기 전에 기동을 멈춘다(지연 초기화에서도 같다): `queryhub.metadata.db.password`, `spring.datasource.hikari.password`, `spring.datasource.hikari.jdbc-url`, `spring.datasource.hikari.data-source-properties` 아래의 모든 키. 키 이름은 relaxed binding 으로 비교한다.
- 오류 메시지는 쓸 변수(`QUERYHUB_METADATA_DB_PASSWORD`)나 문제의 키 이름만 말하고 값은 말하지 않는다.
- OS 환경변수로 온 `SPRING_APPLICATION_JSON` 과 환경변수 경로는 허용한다. 설정 파일은 이 가드의 범위가 아니다.

## 결과

- 구현됨 (P0-B): `runtime/app/src/main/java/com/queryhub/app/config/metadata/MetadataDatabaseAutoConfiguration.java` (`PROTECTED_HIKARI_KEYS` 와 `data-source-properties` 접두).
- Boot 4.1.2 에서 `hikari.password` 와 `hikari.jdbc-url` 은 바인딩 뒤에 검사한 연결 정보로 다시 덮인다. 그래도 값이 `ps` 에 보이므로 방어적으로 거부한다.
- 남은 문제([BACKLOG](../BACKLOG.md)): `hikari.catalog` 와 `connection-init-sql` 을 막을지(P0B-H1), `spring.autoconfigure.exclude` 로 이 자동 구성을 빼면 가드도 함께 사라지는 문제(P0B-R14), 무시되지만 거부되지 않는 `spring.flyway.*`·`spring.datasource.url`·`spring.datasource.password`(P0B-R15).

## 근거

- 승인된 설계 §1.2: 「비밀 값(토큰, 마스터 키, DB 비밀번호)은 환경변수로 넘기고 명령행 인자에 넣지 않는다(프로세스 목록에 보인다).」
- 사용자 결정, 2026-11-18 (P0-B 코드 리뷰 1회차): 「지금 고칠까요?」에 「지금 고침 (권장)」으로 답해 R1 을 병합 전에 고치기로 했다. 막을 출처와 키의 범위는 그 수정의 요구사항으로 Queen 이 정했다.
