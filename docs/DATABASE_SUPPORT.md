# QueryHub 데이터베이스 지원

두 종류의 DB 를 다룬다. **Metadata DB** 는 QueryHub 자신의 설정과 정의를 두는 MariaDB 이고 구현됐다(P0-B). **업무 DB** 는 API 가 조회하는 DW·운영 DB 이며 계획(Phase 1 부터)이다. 테이블은 [ERD.md](ERD.md), 접속 변수와 개발용 DB 는 [DEPLOYMENT.md](DEPLOYMENT.md), 비밀 값 규칙은 [SECURITY.md](SECURITY.md)에 있다.

## Metadata DB (구현됨, P0-B)

결정: 두 모드 모두 MariaDB 하나([ADR-009](ADR/ADR-009-mariadb-metadata-db.md)). 접근은 Spring JDBC([ADR-007](ADR/ADR-007-spring-jdbc-metadata.md)).

| 항목 | 내용 | 근거 |
|---|---|---|
| 서버 | MariaDB 10.11 이상. 정렬 규칙 `utf8mb4_uca1400_ai_ci`, `utf8mb4_nopad_bin` 이 필요하다 | `V1__create_app_setting.sql` |
| 검증한 버전 | **MariaDB 12.3 (`mariadb:12.3` = 12.3.3)뿐이다.** 10.11·11.x 는 미검증 | 통합 테스트 이미지 |
| 드라이버 | MariaDB Connector/J 3.5.11 (Boot 4.1.2 BOM 의 3.5.10 을 덮어쓴다, GHSA-fhx5-2xqw-2cgm) | `runtime/gradle/libs.versions.toml` 의 `mariadb-connector-j`, `runtime/build.gradle` 의 `ext['mariadb.version']`, [ADR-018](ADR/ADR-018-mariadb-connector-j-override.md) |
| 풀 | HikariCP, 풀 이름 `queryhub-metadata`, 연결 제한 10 초(`connection-timeout: 10000`). 그 밖은 Boot 기본값 | `runtime/app/src/main/resources/application.yml` |
| 권한 | 기동 때 Flyway 가 마이그레이션하므로 DDL 권한(CREATE, ALTER, INDEX 등)이 필요하다 | [ERD.md](ERD.md) 마이그레이션 규칙 |
| 데이터베이스 | 빈 데이터베이스. `baseline-on-migrate` 를 켜지 않는다 | `application.yml` 의 `spring.flyway` |
| 전송 | **평문 연결이다.** 원격 DB 용 TLS 옵션은 계획(Phase 1 보안 또는 Phase 6)이다. 신뢰할 수 있는 네트워크에서만 원격 DB 를 쓴다 | `MetadataDatabaseProperties.URL_OPTIONS` 에 TLS 옵션 없음 |

### JDBC URL (구현됨)

런타임이 접속 변수로 URL 을 만든다(`runtime/app/src/main/java/com/queryhub/app/config/metadata/MetadataDatabaseProperties.java` 의 `URL_OPTIONS`, `MetadataJdbcConnectionDetails.java`).

```
jdbc:mariadb://<host>:<port>/<name>?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true&allowLocalInfile=false
```

- 세션 시간대는 항상 UTC 다. 서버 기본 시간대가 무엇이든 `DATETIME(6)` 에 같은 UTC 값이 저장된다.
- LOCAL INFILE 은 항상 꺼진다. 서버가 허용해도 드라이버가 파일 요청을 거부한다.
- 호스트와 이름에 URL 구분 문자를 허용하지 않으므로(호스트 이름·IPv4·`[IPv6]`, 이름 `[A-Za-z0-9_]{1,64}`) 옵션을 바꿀 수 없다. 변수 규칙 전체는 [DEPLOYMENT.md](DEPLOYMENT.md)에 있다.
- 통합 테스트의 MariaDB 컨테이너는 서버 기본값을 일부러 latin1 / latin1_swedish_ci / +09:00 으로 두어, 런타임이 서버 기본값에 기대지 않는다는 것을 확인한다([TEST_PLAN.md](TEST_PLAN.md)).

### 기동 동작 (구현됨)

- 기동할 때 Flyway 가 마이그레이션(지금은 V1 `app_setting`)을 적용한다. `spring.flyway.enabled=false` 로 Flyway 를 끄면 마이그레이션 대신 연결 하나를 열어 확인한다(`MetadataDatabaseStartupCheck.java`). 어느 쪽이든 DB 에 닿지 않으면 기동이 실패한다.
- 기동 실패는 웹 서버가 포트를 열기 전에 일어난다(`spring.main.lazy-initialization=true` 여도 같다): 필수 변수가 없거나 비어 있음, 값이 규칙에 어긋남(메시지는 문제가 있는 변수 이름을 모두 말한다), DB 에 닿지 않음(연결 제한 10 초), 자격 증명 거부.
- `GET /actuator/health` 는 DB 상태(`db`)를 포함한다. DB 에 닿지 않으면 503 `DOWN` 이다([API_SPECIFICATION.md](API_SPECIFICATION.md)).

## 업무 DB (계획, Phase 1)

설계 §6 의 표다. 아래 동작은 문헌으로만 확인했으며 **미실측**이다. Phase 1 통합 테스트에서 DB 마다 「쓰기 시도가 거부되는가」, 「대량 조회가 힙에 쌓이지 않는가」로 실증한다.

| | PostgreSQL | MariaDB | Oracle | SQL Server |
|---|---|---|---|---|
| 드라이버 (라이선스) | pgjdbc (BSD) | Connector/J (LGPL-2.1) | ojdbc17 (Oracle FUTC) | mssql-jdbc (MIT) |
| 스트리밍 조건 | autoCommit off + fetchSize | fetchSize | fetchSize 상향 (기본 10) | 기본 동작(adaptive buffering) |
| 읽기 전용 강제 | 읽기 전용 트랜잭션 | 읽기 전용 트랜잭션 | `SET TRANSACTION READ ONLY` | 트랜잭션 수준 수단 없음 |
| 페이지네이션 | LIMIT/OFFSET | LIMIT/OFFSET | OFFSET…FETCH (12c+) | OFFSET…FETCH, ORDER BY 필수 (2012+) |
| 주요 주의점 | timestamptz, jsonb, 배열 | TINYINT(1) 이 boolean, `Instant` 바인딩이 JVM 시간대를 탄다 | DATE 에 시간 포함, 빈 문자열이 NULL, IN 1,000개 한도(23ai 이전) | 암호화 기본 켜짐, 파라미터 2,100개 한도 |
| arm64 테스트 이미지 | 있음 | 있음 | 있음 (gvenzl/oracle-free 23.5+) | 없음 — Rosetta 에뮬레이션 |
| 상태 | 계획, 미실측 | 계획, 미실측 | 계획, 미실측 | 계획, 미실측 |

- **읽기 전용:** SELECT 전용 정책은 파서, 읽기 전용 트랜잭션, 읽기 전용 계정 권장의 3중 방어다([ADR-004](ADR/ADR-004-select-only-defense.md)). SQL Server 는 읽기 전용을 트랜잭션으로 강제할 수 없으므로 계정 권한과 파서에 의존하고, 연결 테스트에서 쓰기 권한이 있으면 경고한다.
- **스트리밍과 행 제한:** 조회는 스트리밍 한 경로만 쓴다. 행 수 제한은 SQL 재작성 대신 `Statement.setMaxRows(maxRows + 1)` 로 건다(초과 여부를 알 수 있다). 사용자 SQL 을 감싸는 재작성은 SQL Server 의 ORDER BY 제약에서 깨진다. 페이지네이션은 API 별 선택 기능으로 DB 별 구문을 적용한다([ADR-005](ADR/ADR-005-streaming-single-path.md)).
- **드라이버와 모듈:** 드라이버 의존은 `provider-{mssql,oracle,mariadb,postgresql}` 모듈에만 둔다(ArchUnit R6, [ARCHITECTURE.md](ARCHITECTURE.md)).
- **드라이버 라이선스 (계획, Phase 8):** Oracle·MariaDB 드라이버를 설치본에 넣으려면 라이선스 고지 파일이 필요하다.
- **로컬 제약:** 호스트 메모리 16 GB, Docker VM 약 8 GB. DB 별 통합 테스트는 순차로 돌린다. SQL Server 컨테이너의 에뮬레이션 동작과 게이트 제한 시간 안에 들어가는지는 Phase 1 첫 작업에서 실측한다.
