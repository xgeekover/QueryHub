# ADR-007 메타데이터 접근은 Spring JDBC (JPA 미사용)

- 상태: 승인됨
- 날짜: 2026-11-15
- 구현: 구현됨 (P0-B, f913239)

## 맥락

명세 3절의 백엔드 스택은 Spring JDBC 를 쓴다. 메타데이터 스키마는 단순하고, 업무 DB 조회도 JDBC 로 한다.

## 결정

- 메타데이터 저장소는 Spring JDBC(`JdbcClient`, `NamedParameterJdbcTemplate`)로 구현한다. JPA 와 Hibernate 는 쓰지 않는다.
- 저장소 구현은 `adapter-metadata` 모듈에 두고 application 계층의 포트(`*Repository`)를 구현한다.

## 결과

- 구현됨 (P0-B): `runtime/adapter-metadata/src/main/java/com/queryhub/adapter/metadata/setting/JdbcAppSettingRepository.java` 가 `JdbcClient` 로 `AppSettingRepository` 포트를 구현한다.
- ArchUnit R8 이 JDBC 드라이버와 Spring JDBC 를 영속성 어댑터 안에 묶어 둔다. R8 이 `org.springframework.boot.jdbc..` 와 `org.springframework.boot.flyway..` 는 다루지 않는다([BACKLOG](../BACKLOG.md) P0B-R5).
- SQL 을 직접 쓰므로 스키마 변경 때 쿼리도 함께 고쳐야 한다. 마이그레이션 규칙은 [ERD.md](../ERD.md)에 있다.

## 근거

- 승인된 설계 §2 와 §10 의 ADR 007 (명세 스택, 단순한 스키마).
