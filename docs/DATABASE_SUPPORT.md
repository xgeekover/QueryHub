# QueryHub 데이터베이스 지원

두 종류의 DB 를 다룬다. 둘은 서로 다른 것이다.

- **Metadata 저장소**는 QueryHub 자신의 설정과 정의를 두는 내부 저장소다. P1-06 부터 두 모드 모두 **내장 SQLite** 다([ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md)). 사용자가 따로 설치하거나 접속할 DB 가 없다. P1-05 까지는 외부 MariaDB 였다([ADR-009](ADR/ADR-009-mariadb-metadata-db.md), 대체됨).
- **업무 DB** 는 API 가 조회하는 DW·운영 DB 다(MSSQL, Oracle, MariaDB, PostgreSQL). DB Provider 로 붙인다. P1-07 부터 MariaDB·PostgreSQL Provider 의 연결 시험과 읽기 전용 풀이 구현됐다([ADR-027](ADR/ADR-027-database-provider.md)). Oracle 은 P1-10, SQL Server 는 P1-11 이다. **MariaDB 는 업무 DB 로서 지원한다.** metadata 저장소 선택지로만 남기지 않았다(사용자 결정 2026-11-23 「3. 권장하는 반식으로 진행」).

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
| 파일 | `<data-dir>/queryhub.db` 와 `-wal`, `-shm`(롤백 저널 `-journal` 은 새 DB 나 롤백 모드 사본을 처음 WAL 로 바꿀 때만 잠깐 생긴다). 데이터 디렉터리는 `QUERYHUB_DATA_DIR` 이고 필수다. 로컬 디스크만 쓴다 | [DEPLOYMENT.md](DEPLOYMENT.md) 「데이터 디렉터리」 |
| 풀 | HikariCP, 풀 이름 `queryhub-metadata`, `maximum-pool-size: 4`(운영자가 바꿀 수 있다), 연결 제한 10 초(`connection-timeout: 10000`) | `runtime/app/src/main/resources/application.yml` |
| 동시 쓰기 | 한 번에 쓰기 하나(SQLite). WAL 이라 읽기는 쓰기와 함께 돈다. `transaction_mode=IMMEDIATE` 가 트랜잭션 시작 때 쓰기 잠금을 잡고, 다른 쓰기는 `busy_timeout` 5 초 동안 기다린다. 5 초 넘게 막히면 그 쓰기는 `DataAccessException`(`SQLITE_BUSY`)으로 실패한다. `IMMEDIATE` 에서는 읽기 전용 트랜잭션도 쓰기 잠금을 잡는다(P106-6) | 단위 시험 `SqliteAppSettingRepositoryTest` (8 스레드 × 50 쓰기, 1 초·5 초 넘는 잠금) |
| 권한과 전송 | 계정, 비밀번호, 네트워크 연결이 없다. 저장소를 지키는 것은 데이터 디렉터리와 파일의 권한이다(unix 0700/0600) | [SECURITY.md](SECURITY.md) 「Metadata 저장소 파일」 |

### JDBC URL 과 연결 설정 (구현됨, P1-06)

런타임이 URL 을 만든다(`runtime/adapter-metadata/src/main/java/com/queryhub/adapter/metadata/store/StoreLayout.java` 의 `URL_OPTIONS` 와, P1-07 부터 그 밖에서 붙이는 `open_mode=2`). 운영자가 URL 을 주는 설정은 없다. `open_mode=2` 는 읽기·쓰기이고 만들기가 없다. 파일은 런타임이 늘 먼저 만들므로, 실행 중에 사라진 `queryhub.db` 를 다음 새 연결이 빈 DB 로 만들지 않고 실패한다(P1-06 리뷰 N5).

```
jdbc:sqlite:<절대 경로>/queryhub.db?journal_mode=WAL&synchronous=FULL&foreign_keys=true&busy_timeout=5000&transaction_mode=IMMEDIATE&open_mode=2
```

- 기동할 때 부트스트랩 연결과, Flyway 전과 뒤에 최대 풀 크기만큼 동시에 빌린 풀 연결에서 `PRAGMA journal_mode` = `wal`, `foreign_keys` = 1, `synchronous` = 2(FULL), `busy_timeout` = 5000 을 확인한다. 하나라도 다르면 포트를 열기 전에 기동을 거부한다. `spring.datasource.hikari.connection-init-sql` 로 바꾼 값도 이 확인이 잡는다.
- `spring.datasource.hikari.data-source-properties` 에 `journal_mode`, `synchronous`, `foreign_keys`, `busy_timeout`, `transaction_mode`, `open_mode`(P1-07. sqlite-jdbc 는 연결 속성을 URL 보다 앞세운다)가 있으면(대소문자, `-`, `.` 무시) 이름만으로 거부한다. 메시지는 이 여섯 이름을 `StorePragmas.MANAGED_KEYS` 에서 만들어 든다.
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
- 들어 있는 네이티브는 **20개**다(P1-06 에서 내려받은 JAR 의 `unzip -l`, 경로 `org/sqlite/native/<OS>/<arch>/`). 실행해 본 것은 **Mac/aarch64**(로컬, macOS arm64)와 **Linux/x86_64**(CI 의 `gates (ubuntu-24.04)` 작업, 2026-11-23, P1-06 코드) 둘이다. 나머지 18개는 모두 **미검증**이다(PLAT-1, [BACKLOG.md](BACKLOG.md) P106-3). `Linux-Android` 네이티브는 이 JAR 에 없다.

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
| Linux | x86_64 | `libsqlitejdbc.so` | 1,097,664 | CI 에서 실행함 (`gates (ubuntu-24.04)` 작업의 단위·통합 시험, 2026-11-23) |
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

- 이 표에 없는 OS·아키텍처에서는 런타임이 기동하지 않는다(위 메시지). 사용자의 세 플랫폼(macOS, Linux, Windows) 가운데 Windows 에서는 실행해 보지 않았다(CI 의 Windows 작업에는 Java 단계가 없다). Linux 는 CI 의 시험만 돌았다.
- Windows 에서는 불러온 DLL 이 열려 있는 동안 그 파일과 디렉터리를 지울 수 없다(미검증).

## 업무 DB (P1-07: MariaDB·PostgreSQL 구현됨, Oracle·SQL Server 계획)

결정은 [ADR-027](ADR/ADR-027-database-provider.md)(제안)이다. P1-07 은 연결 시험과 연결마다 읽기 전용 풀까지다. P1-08a 는 P1-07 리뷰 후속(옵션 값의 허용 목록, pgjdbc 의 클라이언트 인증서, 통합 시험의 Ryuk)이다. 연결 정보 저장(`database_connection`, 암호화한 비밀번호)은 P1-08b, 관리 API 는 P1-09, Oracle 은 P1-10, SQL Server 는 P1-11, SELECT·타입 변환·네 DB 의 쓰기 거부·쓰기 권한 경고는 P1-12 다.

### 드라이버 (P1-07 에서 해석한 JAR)

| 드라이버 | 버전 | JAR 바이트 | 라이선스 | 출처 |
|---|---|---|---|---|
| MariaDB Connector/J `org.mariadb.jdbc:mariadb-java-client` | 3.5.11 ([ADR-018](ADR/ADR-018-mariadb-connector-j-override.md) 의 덮어쓰기) | 793,387 | LGPL-2.1-or-later | POM 의 `<licenses>` |
| pgjdbc `org.postgresql:postgresql` | 42.7.14 (Boot BOM 관리) | 1,221,004 | BSD-2-Clause | 매니페스트 `Bundle-License`, JAR 안의 `META-INF/LICENSE`(PostgreSQL Global Development Group) |
| pgjdbc 의 의존성 `org.checkerframework:checker-qual` | 3.55.1 | 241,885 | MIT | POM |
| pgjdbc JAR 안에 셰이드된 `com.ongres.scram` 의 scram-client·scram-common 3.2, `com.ongres.stringprep` 의 saslprep·stringprep 2.2 | pgjdbc JAR 안 | (pgjdbc 에 포함) | 네 파일 모두 BSD 2-조항 문구(Copyright OnGres, Inc. 2017 은 scram, 2019 는 stringprep) | JAR 안의 `META-INF/licenses/<그룹>/<이름>-<버전>/META-INF/LICENSE`. 캐시의 JAR 을 읽기만 해서 확인했다(P1-07) |

- SHA-256: `mariadb-java-client-3.5.11.jar` 42604178b9e57ec08ee0035079961516f086e455d0141f0b22c6eff29fb2fff4, `postgresql-42.7.14.jar` 73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c, `checker-qual-3.55.1.jar` 857658c8bdcbff794949a8d9922f88d63ee3751c203c7cd7d21ea97f9e745288.
- 내려받기: 사람의 결정(2026-11-23)에 따라 PostgreSQL 드라이버와 Testcontainers PostgreSQL 모듈만 받았다(repo.maven.apache.org): `postgresql-42.7.14` 의 JAR·POM, `checker-qual-3.55.1` 의 JAR·`.module`·POM, `testcontainers-postgresql-2.0.5` 의 JAR(14,864 바이트)·POM. Connector/J 3.5.11, HikariCP 7.0.2, 나머지 Testcontainers 2.0.5 모듈은 이미 Gradle 캐시에 있었다. Gradle 의존성 검증은 없다(CI-2, P1-12).
- 클래스패스: `:app` 의 `runtimeClasspath` 는 68 에서 71 모듈(두 드라이버와 checker-qual), `integrationTestRuntimeClasspath` 는 103 에서 122 모듈이다. 실행 JAR 의 `BOOT-INF/lib` 에 두 드라이버 JAR 이 들어가고 `BootJarClassVersionIntegrationTest` 가 확인한다.
- 설치본의 라이선스 고지(LGPL 드라이버, BSD·MIT 고지 파일)는 Phase 8 에서 정한다.

### 연결 동작 (구현됨, P1-07, P1-08a)

| | MariaDB | PostgreSQL |
|---|---|---|
| URL (필드로 만든다) | `jdbc:mariadb://<호스트>:<포트>/<DB>` | `jdbc:postgresql://<호스트>:<포트>/<DB>` |
| 받는 옵션 | `sslMode` 하나. 값은 `disable`, `trust`, `verify-ca`, `verify-full` 가운데 하나(P1-08a, 아래 「옵션 값」) | `sslmode` 하나. 값은 `disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full` 가운데 하나(P1-08a) |
| 고정 속성 (옵션보다 이긴다) | `connectTimeout`·`socketTimeout`(밀리초, connectTimeout), `allowLocalInfile=false`, `readOnlyPropagatesToServer=true`, `permitRedirect=false` | `connectTimeout`·`socketTimeout`(초, 올림, connectTimeout), `loginTimeout`(connectTimeout + 3 초), `readOnlyMode=always`, `sslcert=""`·`sslkey=""`(P1-08a: 클라이언트 인증서·키 파일을 어떤 sslmode 에서도 읽지 않는다, 단위 시험) |
| 읽기 전용 (통합 시험) | 풀 연결의 INSERT 가 SQLState 25006 으로 거부된다. `readOnlyPropagatesToServer` 가 없으면 성공했다 | 풀 연결의 INSERT 가 25006 으로 거부된다. pgjdbc 기본값 `transaction` 에서는 성공했다 |
| 실패 분류 | 1049·1044 → `DATABASE_NOT_FOUND`(1044 는 권한 없음일 수도 있어 메시지가 두 원인을 말한다, P107-10), 1045 → `AUTHENTICATION_FAILED`, 나머지는 표준 분류 | 표준 SQLState 분류 |
| 그 밖에 확인한 것 | 서버가 LOCAL INFILE 을 허용해도(`--local-infile=1`) `LOAD DATA LOCAL INFILE` 이 실패하고 표가 비어 있다 | `.pgpass`·`pg_service.conf` 를 쓰지 않는다(맞는 비밀번호를 담은 파일이 있어도 틀린 비밀번호는 `AUTHENTICATION_FAILED`, `service` 옵션은 거부) |
| 미검증 | `permitRedirect=false`(P107-7) | `verify-ca`·`verify-full` 이 읽는 `~/.postgresql/root.crt` 와 실제 TLS 서버(P107-6. 통합 시험의 서버는 SSL 이 꺼져 있다), 응답 없는 서버의 시간 차이(P107-9) |

- 공통(두 DB): 연결 시험의 마감은 connectTimeout + 2 초이고 결과와 예외는 고정 한국어 메시지만 담는다. 풀은 연결마다 `queryhub-conn-<id>`, 읽기 전용, MBean 없음, 열어도 연결하지 않는다. 기본 한도는 connectTimeout 1–120 초, maxPoolSize 1–20, 비밀번호 1,024 자, 옵션 32 개, 옵션 값 512 자다(사람이 바꿀 수 있다, ADR-027 결정 11).
- socketTimeout 이 connectTimeout 과 같아 긴 업무 쿼리를 끊는다. 업무 쿼리 전에 다시 설계한다(P107-5).

#### 옵션 값 (구현됨, P1-08a)

TLS 모드 옵션은 값도 허용 목록이다([ADR-027](ADR/ADR-027-database-provider.md) 결정 3). 이름과 값은 대소문자까지 정확히 같아야 한다. 목록 밖의 값은 연결하기 전에 `OPTION_REJECTED` 로 거부되고, 메시지는 옵션 이름만 말한다(「허용되지 않은 접속 옵션입니다: <옵션 이름>」, 값은 메시지·결과·로그에 없다). P1-07 까지는 값을 보지 않아, 틀린 값이 드라이버 안에서 실패해 `UNREACHABLE`(pgjdbc 의 08001)이나 `CONNECTION_FAILED`(Connector/J 의 `IllegalArgumentException`)라는 틀린 안내가 되었다.

| | MariaDB `sslMode` (Connector/J 3.5.11) | PostgreSQL `sslmode` (pgjdbc 42.7.14) |
|---|---|---|
| 받는 값 | `disable`, `trust`, `verify-ca`, `verify-full` | `disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full` |
| 드라이버는 받지만 일부러 거부하는 모양 | `allow`, 별칭 `DISABLED`, `0`, `false`, `REQUIRED`, `VERIFY_CA`, `VERIFY_IDENTITY`, `1`, `true`, 대소문자 변형(예: `Trust`, `Disable`) | 대소문자 변형(pgjdbc 는 대소문자를 무시한다. 예: `REQUIRE`, `Prefer`, `VERIFY-FULL`) |
| 그 밖에 거부하는 것 | 오타와 앞뒤 공백(예: `verify_full`, `verify-full `), 빈 값 | 오타와 앞뒤 공백(예: `requir`, `verify_full`, `require `), 빈 값. 옵션 이름 `sslcert`·`sslkey`(고정 속성이라 대소문자와 상관없이)와 `sslrootcert`(허용 목록 밖) |

- 별칭을 받지 않는 까닭: 별칭은 드라이버 버전마다 다르고, 옵션 이름도 이미 대소문자를 구분한다. 저장된 값이 드라이버를 바꾼 뒤 다른 뜻이 되지 않게 한 가지 철자만 받는다.
- Provider 가 값 집합 없이(또는 빈 집합으로) 옵션을 허용하면 공통 코드가 `IllegalStateException` 을 던진다(값을 검사하지 않는 옵션은 둘 수 없다).
- `sslrootcert` 는 고정하지 않았다: 빈 값으로 고정하면 verify-ca·verify-full 이 그 경로를 열다가 실패한다. 두 모드는 런타임 사용자의 `~/.postgresql/root.crt` 를 읽는다(P107-6).

### 시험한 이미지와 메모리 (P1-07, macOS arm64 로컬)

| 이미지 (arm64, 로컬에 있던 것) | 서버 | 쓰는 시험 | 메모리 |
|---|---|---|---|
| `mariadb:12.3` (103,246,639 바이트) | MariaDB 12.3.3 | `MariaDbProviderIntegrationTest` 11 (P1-07 은 10) | 약 125 MiB (run D 의 통합 게이트 중 `docker stats`) |
| `postgres:16-alpine` (113,895,262 바이트) | PostgreSQL 16.15 (이미지의 `PG_VERSION`) | `PostgreSqlProviderIntegrationTest` 10 (P1-07 은 9) | 재지 못했다(시험이 짧아 `docker stats` 가 잡기 전에 끝났다) |
| `testcontainers/ryuk:0.14.0` (2,124,447 바이트) | — | Testcontainers 의 정리 컨테이너. P1-08a 부터 통합 시험에서 끈다(8080/tcp 를 모든 인터페이스에 열었다, P107-11) | 약 11.5 MiB (P1-07 run D 의 게이트 때) |

- 세 이미지는 모두 이미 로컬에 있었고 받은 것(`docker pull`)은 없다. CI 의 `gates` 작업에서는 이 이미지(Linux 컨테이너)로 통합 시험이 통과했다(P1-07 코드 bb0cd8c, 실행 45194878756 의 2회차, 2026-11-23).
- 컨테이너는 시험 클래스마다 하나씩, 차례로 뜬다. run D 의 통합 게이트 전체는 75 초였다. 컨테이너 포트는 127.0.0.1 에만 묶는다. P1-08a 부터 Ryuk 없이 돌고, 세션 검사가 세션의 모든 컨테이너가 127.0.0.1 에만 열리는지 본다. 강제로 죽은 실행이 남긴 컨테이너는 사람이 세션 라벨로 지운다([TEST_PLAN.md](TEST_PLAN.md) 「integration.sh」).
- 호스트 메모리 16 GB, Docker VM 약 8 GB(`docker stats` 의 한도 7.75 GiB). SQL Server 는 arm64 이미지가 없어 P1-11 에서 통합 시험을 생략한다(사람의 결정 「Oracle만, MSSQL은 테스트 생략」).

### 네 DB 의 비교 (설계 §6)

아래 표의 스트리밍, 페이지네이션, 타입 주의점은 문헌으로만 확인했으며 **미실측**이다. P1-12 와 Phase 2–5 의 통합 테스트에서 DB 마다 「쓰기 시도가 거부되는가」, 「대량 조회가 힙에 쌓이지 않는가」로 실증한다.

| | PostgreSQL | MariaDB | Oracle | SQL Server |
|---|---|---|---|---|
| 드라이버 (라이선스) | pgjdbc 42.7.14 (BSD-2-Clause) | Connector/J 3.5.11 (LGPL-2.1-or-later) | ojdbc17 (Oracle FUTC, 내려받기는 P1-10 에서 사람이 답한다) | mssql-jdbc (MIT) |
| 스트리밍 조건 | autoCommit off + fetchSize | fetchSize | fetchSize 상향 (기본 10) | 기본 동작(adaptive buffering) |
| 읽기 전용 강제 | 읽기 전용 트랜잭션. P1-07: `readOnlyMode=always` 와 풀 readOnly 로 INSERT 거부를 실측 | 읽기 전용 트랜잭션. P1-07: `readOnlyPropagatesToServer=true` 와 풀 readOnly 로 INSERT 거부를 실측 | `SET TRANSACTION READ ONLY` | 트랜잭션 수준 수단 없음 |
| 페이지네이션 | LIMIT/OFFSET | LIMIT/OFFSET | OFFSET…FETCH (12c+) | OFFSET…FETCH, ORDER BY 필수 (2012+) |
| 주요 주의점 | timestamptz, jsonb, 배열 | TINYINT(1) 이 boolean, `Instant` 바인딩이 JVM 시간대를 탄다 | DATE 에 시간 포함, 빈 문자열이 NULL, IN 1,000개 한도(23ai 이전) | 암호화 기본 켜짐, 파라미터 2,100개 한도 |
| arm64 테스트 이미지 | 있음 (`postgres:16-alpine`, 시험함) | 있음 (`mariadb:12.3`, 시험함) | 있음 (gvenzl/oracle-free 23.5+) | 없음 — 통합 시험 생략 (P1-11) |
| 상태 | 연결 시험·읽기 전용 풀 구현됨 (P1-07). 조회는 계획 | 연결 시험·읽기 전용 풀 구현됨 (P1-07). 조회는 계획 | 계획 (P1-10) | 계획 (P1-11, 드라이버만) |

- **읽기 전용:** SELECT 전용 정책은 파서, 읽기 전용 트랜잭션, 읽기 전용 계정 권장의 3중 방어다([ADR-004](ADR/ADR-004-select-only-defense.md)). SQL Server 는 읽기 전용을 트랜잭션으로 강제할 수 없으므로 계정 권한과 파서에 의존하고, 연결 테스트에서 쓰기 권한이 있으면 경고한다(계획, P107-4).
- **스트리밍과 행 제한:** 조회는 스트리밍 한 경로만 쓴다. 행 수 제한은 SQL 재작성 대신 `Statement.setMaxRows(maxRows + 1)` 로 건다(초과 여부를 알 수 있다). 사용자 SQL 을 감싸는 재작성은 SQL Server 의 ORDER BY 제약에서 깨진다. 페이지네이션은 API 별 선택 기능으로 DB 별 구문을 적용한다([ADR-005](ADR/ADR-005-streaming-single-path.md)).
- **드라이버와 모듈:** 드라이버 의존은 `provider-{mssql,oracle,mariadb,postgresql}` 모듈에만 둔다(ArchUnit R6, [ARCHITECTURE.md](ARCHITECTURE.md)). P1-07 에서 `provider-mariadb`(Connector/J 3.5.11)와 `provider-postgresql`(pgjdbc 42.7.14)이 생겼다.
- **통합 테스트:** 업무 DB 통합 테스트는 실제 컨테이너(Testcontainers 2.0.5)를 쓴다. P1-06 에서 뺐고 P1-07 에서 다시 더했다. `integration.sh` 의 Docker 사전 점검이 그래서 필요하다. 다른 서버 버전은 미검증이다(P107-8).
