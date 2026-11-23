# QueryHub 데이터베이스 지원

두 종류의 DB 를 다룬다. 둘은 서로 다른 것이다.

- **Metadata 저장소**는 QueryHub 자신의 설정과 정의를 두는 내부 저장소다. P1-06 부터 두 모드 모두 **내장 SQLite** 다([ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md)). 사용자가 따로 설치하거나 접속할 DB 가 없다. P1-05 까지는 외부 MariaDB 였다([ADR-009](ADR/ADR-009-mariadb-metadata-db.md), 대체됨).
- **업무 DB** 는 API 가 조회하는 DW·운영 DB 다(MSSQL, Oracle, MariaDB, PostgreSQL). DB Provider 로 붙이며 계획(Phase 1 의 다음 태스크부터)이다. **MariaDB 는 업무 DB 로서 계속 지원 계획이다.** metadata 저장소 선택지로만 남기지 않았다(사용자 결정 2026-11-23 「3. 권장하는 반식으로 진행」).

테이블은 [ERD.md](ERD.md), 데이터 디렉터리·설정·백업은 [DEPLOYMENT.md](DEPLOYMENT.md), 파일 규칙의 보안 측면은 [SECURITY.md](SECURITY.md)에 있다.

## Metadata 저장소: 내장 SQLite (구현됨, P1-06)

결정: 두 모드 모두 내장 SQLite([ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md)). 접근은 Spring JDBC([ADR-007](ADR/ADR-007-spring-jdbc-metadata.md)).

| 항목 | 내용 | 근거 |
|---|---|---|
| 드라이버 | `org.xerial:sqlite-jdbc` **3.53.4.0**. Spring Boot 4.1.2 BOM 이 관리한다(카탈로그 항목에 버전이 없고 덮어쓰지 않는다). `runtime/adapter-metadata` 의 `runtimeOnly`. 운영 코드는 `org.sqlite` 클래스를 참조하지 않는다(ArchUnit R6) | `runtime/gradle/libs.versions.toml` 의 `sqlite-jdbc`, `runtime/adapter-metadata/build.gradle` |
| 라이선스 | sqlite-jdbc 는 **Apache License 2.0**(POM 의 `<licenses>`, 매니페스트 `Bundle-License`, JAR 안의 `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE`). 원래 Zentus 코드의 `LICENSE.zentus` 도 들어 있다. 번들된 SQLite 라이브러리는 **public domain** 이다(JAR 에 따로 SQLite 라이선스 파일은 없다) | 내려받은 POM 과 JAR (P1-06 개발 기록) |
| 크기 | JAR 12,002,370 바이트(약 12.0 MB). 실행 JAR 의 `BOOT-INF/lib/sqlite-jdbc-3.53.4.0.jar` 로 들어간다. `mariadb-java-client`(793,387 바이트)와 `flyway-mysql`(29,694 바이트)이 빠져 실행 JAR 은 약 11.2 MB 커졌다(P1-06 의 빌드에서 40,215,981 바이트) | 같다 |
| 전이 의존성 | 없다. POM 의 `slf4j-api` 는 optional 이라 해석되지 않는다. 클래스패스의 외부 모듈 변화는 sqlite-jdbc 추가와 `flyway-mysql`·`mariadb-java-client`(통합 테스트는 Testcontainers 와 그 의존성까지) 제거뿐이다 | `:app:dependencies` 전후 비교 |
| SQLite 버전 | sqlite-jdbc 3.53.4.0 에 번들된 SQLite 3.53.x. STRICT 테이블(3.37 이상)과 UPSERT(3.24 이상)를 쓴다 | `V1__create_app_setting.sql` |
| Flyway | `flyway-core` 12.4.0 이 SQLite 지원(`org/flywaydb/core/internal/database/sqlite/`, 플러그인 등록 `SQLiteDatabaseType`)을 담고 있어 Flyway 모듈을 더하지 않았다 | `flyway-core-12.4.0.jar` |
| 파일 | `<data-dir>/queryhub.db` 와 `-wal`, `-shm`. 데이터 디렉터리는 `QUERYHUB_DATA_DIR` 이고 필수다. 로컬 디스크만 쓴다 | [DEPLOYMENT.md](DEPLOYMENT.md) 「데이터 디렉터리」 |
| 풀 | HikariCP, 풀 이름 `queryhub-metadata`, `maximum-pool-size: 4`(운영자가 바꿀 수 있다), 연결 제한 10 초(`connection-timeout: 10000`) | `runtime/app/src/main/resources/application.yml` |
| 동시 쓰기 | 한 번에 쓰기 하나(SQLite). WAL 이라 읽기는 쓰기와 함께 돈다. `transaction_mode=IMMEDIATE` 가 트랜잭션 시작 때 쓰기 잠금을 잡고, 다른 쓰기는 `busy_timeout` 5 초 동안 기다린다. 5 초 넘게 막히면 그 쓰기는 `DataAccessException`(`SQLITE_BUSY`)으로 실패한다. `IMMEDIATE` 에서는 읽기 전용 트랜잭션도 쓰기 잠금을 잡는다(P106-6) | 단위 시험 `SqliteAppSettingRepositoryTest` (8 스레드 × 50 쓰기, 1 초·5 초 넘는 잠금) |
| 권한과 전송 | 계정, 비밀번호, 네트워크 연결이 없다. 저장소를 지키는 것은 데이터 디렉터리와 파일의 권한이다(unix 0700/0600) | [SECURITY.md](SECURITY.md) 「Metadata 저장소 파일」 |

### JDBC URL 과 연결 설정 (구현됨, P1-06)

런타임이 URL 을 만든다(`runtime/adapter-metadata/src/main/java/com/queryhub/adapter/metadata/store/StoreLayout.java` 의 `URL_OPTIONS`). 운영자가 URL 을 주는 설정은 없다.

```
jdbc:sqlite:<절대 경로>/queryhub.db?journal_mode=WAL&synchronous=FULL&foreign_keys=true&busy_timeout=5000&transaction_mode=IMMEDIATE
```

- 기동할 때 부트스트랩 연결과, Flyway 전과 뒤에 최대 풀 크기만큼 동시에 빌린 풀 연결에서 `PRAGMA journal_mode` = `wal`, `foreign_keys` = 1, `synchronous` = 2(FULL), `busy_timeout` = 5000 을 확인한다. 하나라도 다르면 포트를 열기 전에 기동을 거부한다. `spring.datasource.hikari.connection-init-sql` 로 바꾼 값도 이 확인이 잡는다.
- `spring.datasource.hikari.data-source-properties` 에 `journal_mode`, `synchronous`, `foreign_keys`, `busy_timeout`, `transaction_mode` 가 있으면(대소문자, `-`, `.` 무시) 이름만으로 거부한다.
- 데이터 디렉터리 경로에 `?` 가 있으면 거부한다(sqlite-jdbc 는 파일 이름의 `?` 뒤를 URL 옵션으로 읽는다).

### 기동 동작 (구현됨, P1-06)

- 순서는 [ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md) 결정 10 이다. 데이터 디렉터리 설정과 규칙(Windows UNC 경로는 아무것도 만들기 전에 거부), 네트워크 파일 시스템, 잠금, 네이티브 디렉터리, DB 파일 머리글(빈 DB 옆에 데이터가 든 `-wal`·`-journal` 이 있으면 거부), **읽기 전용 연결**의 `quick_check`, PRAGMA 를 확인한 뒤 Flyway 가 마이그레이션(지금은 V1 `app_setting`)을 적용한다. 풀 연결마다 연 파일(`PRAGMA database_list`)이 검사한 `queryhub.db` 인지와 PRAGMA 를 Flyway 전과 뒤에 확인한다. `spring.flyway.enabled=false` 여도 앞의 검사와 풀 연결 확인은 돈다(단위 시험 `MetadataStartupFailureTest` 가 나쁜 저장소와 약해진 PRAGMA 로 확인한다). 거부할 때 파일을 바꾸지 않는 범위는 [DEPLOYMENT.md](DEPLOYMENT.md) 「권한과 거부」에 있다.
- 실패는 모두 웹 서버가 포트를 열기 전에 일어나고(`spring.main.lazy-initialization=true` 여도 같다) 종료 코드는 1 이다. 메시지는 한국어로 설정 이름과 경로, 고치는 법을 말한다.
- 새 데이터 디렉터리에서는 디렉터리(0700), `native/`(0700), `queryhub.db`(0600)를 만들고 V1 을 적용한다. 다음 시작은 같은 파일(같은 inode)을 다시 쓰고 마이그레이션을 다시 적용하지 않는다.
- `GET /actuator/health` 는 저장소 상태(`db`)를 포함한다([API_SPECIFICATION.md](API_SPECIFICATION.md)).

### 네이티브 라이브러리 (구현됨, P1-06)

sqlite-jdbc 는 SQLite C 라이브러리를 JAR 안에 OS·아키텍처별로 담고, 첫 연결 때 맞는 것을 파일로 풀어 불러온다(JVM 마다 한 번). 그래서 닫힌 사내망의 장비도 실행 JAR 하나만 받으면 된다.

- 푸는 곳: 런타임이 JVM 시스템 속성 `org.sqlite.tmpdir` 을 `<data-dir>/native`(0700)로 정한다. 기본값인 `java.io.tmpdir` 에는 풀지 않는다. 파일 이름은 `sqlite-<버전>-<uuid>-libsqlitejdbc.<확장자>` 와 `.lck` 다. 옮기려면 `QUERYHUB_SQLITE_NATIVE_DIR`([DEPLOYMENT.md](DEPLOYMENT.md) 「SQLite 네이티브 라이브러리 디렉터리」).
- 불러오지 못하면(원인 사슬의 `UnsatisfiedLinkError` 나 `NativeLibraryNotFoundException`) 포트를 열기 전에 기동을 거부하고, 메시지는 `os.name`/`os.arch`, 네이티브 디렉터리, `QUERYHUB_SQLITE_NATIVE_DIR`(`queryhub.sqlite.native-dir`), 원인 후보(`noexec` 마운트, Windows 백신, 이 플랫폼의 네이티브 없음)를 말한다. 이 메시지를 만드는 분류기는 단위 시험만 있다. 실제 `noexec` 마운트와 Windows 백신은 재현하지 않았다.
- 들어 있는 네이티브는 **20개**다(P1-06 에서 내려받은 JAR 의 `unzip -l`, 경로 `org/sqlite/native/<OS>/<arch>/`). 실행해 본 것은 **Mac/aarch64 하나**(이 Mac mini, macOS arm64)다. 나머지 19개는 모두 **미검증**이다(PLAT-1, [BACKLOG.md](BACKLOG.md) P106-3). `Linux-Android` 네이티브는 이 JAR 에 없다.

| OS | 아키텍처 | 파일 | 바이트 | 상태 |
|---|---|---|---|---|
| Mac | aarch64 | `libsqlitejdbc.dylib` | 1,124,176 | 실행함 (P1-06 단위·통합·실행 JAR 자식 프로세스 시험, Queen 의 desktop 수동 확인) |
| Mac | x86_64 | `libsqlitejdbc.dylib` | 1,288,092 | 미검증 (PLAT-1) |
| Linux | aarch64 | `libsqlitejdbc.so` | 1,113,360 | 미검증 (PLAT-1) |
| Linux | arm | `libsqlitejdbc.so` | 994,056 | 미검증 (PLAT-1) |
| Linux | armv6 | `libsqlitejdbc.so` | 985,884 | 미검증 (PLAT-1) |
| Linux | armv7 | `libsqlitejdbc.so` | 981,784 | 미검증 (PLAT-1) |
| Linux | ppc64 | `libsqlitejdbc.so` | 1,330,224 | 미검증 (PLAT-1) |
| Linux | riscv64 | `libsqlitejdbc.so` | 776,872 | 미검증 (PLAT-1) |
| Linux | x86 | `libsqlitejdbc.so` | 985,112 | 미검증 (PLAT-1) |
| Linux | x86_64 | `libsqlitejdbc.so` | 1,097,664 | 미검증 (PLAT-1) |
| Linux-Musl | aarch64 | `libsqlitejdbc.so` | 1,117,208 | 미검증 (PLAT-1) |
| Linux-Musl | x86 | `libsqlitejdbc.so` | 1,091,960 | 미검증 (PLAT-1) |
| Linux-Musl | x86_64 | `libsqlitejdbc.so` | 1,104,256 | 미검증 (PLAT-1) |
| FreeBSD | aarch64 | `libsqlitejdbc.so` | 1,220,120 | 미검증 (PLAT-1) |
| FreeBSD | x86 | `libsqlitejdbc.so` | 976,996 | 미검증 (PLAT-1) |
| FreeBSD | x86_64 | `libsqlitejdbc.so` | 1,081,280 | 미검증 (PLAT-1) |
| Windows | aarch64 | `sqlitejdbc.dll` | 1,125,376 | 미검증 (PLAT-1) |
| Windows | armv7 | `sqlitejdbc.dll` | 832,000 | 미검증 (PLAT-1) |
| Windows | x86 | `sqlitejdbc.dll` | 953,856 | 미검증 (PLAT-1) |
| Windows | x86_64 | `sqlitejdbc.dll` | 1,014,784 | 미검증 (PLAT-1) |

- 이 표에 없는 OS·아키텍처에서는 런타임이 기동하지 않는다(위 메시지). 사용자의 세 플랫폼(macOS, Linux, Windows) 가운데 macOS arm64 말고는 실행해 보지 않았다.
- Windows 에서는 불러온 DLL 이 열려 있는 동안 그 파일과 디렉터리를 지울 수 없다(미검증).

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
- **드라이버와 모듈:** 드라이버 의존은 `provider-{mssql,oracle,mariadb,postgresql}` 모듈에만 둔다(ArchUnit R6, [ARCHITECTURE.md](ARCHITECTURE.md)). P1-06 부터 MariaDB Connector/J 는 어떤 클래스패스에도 없다. Provider 태스크가 다시 더할 때 `runtime/build.gradle` 의 덮어쓰기(3.5.11, [ADR-018](ADR/ADR-018-mariadb-connector-j-override.md))가 적용된다.
- **드라이버 라이선스 (계획, Phase 8):** Oracle·MariaDB 드라이버를 설치본에 넣으려면 라이선스 고지 파일이 필요하다.
- **통합 테스트:** 업무 DB 통합 테스트는 실제 컨테이너(Testcontainers)를 쓴다. P1-06 에서 Testcontainers 를 뺐으므로 Provider 태스크가 다시 더한다. `integration.sh` 의 Docker 사전 점검은 그래서 남겨 두었다.
- **로컬 제약:** 호스트 메모리 16 GB, Docker VM 약 8 GB. DB 별 통합 테스트는 순차로 돌린다. SQL Server 컨테이너의 에뮬레이션 동작과 게이트 제한 시간 안에 들어가는지는 Phase 1 첫 작업에서 실측한다.
