# QueryHub 아키텍처

이 문서는 QueryHub 의 구조, 모듈 규칙, Desktop 과 Runtime 의 분리, 기술 스택, Phase 계획을 한곳에 적는다. 결정의 이유는 [ADR](ADR/README.md)에, 요구사항은 [REQUIREMENTS.md](REQUIREMENTS.md)에, 남은 문제는 [BACKLOG.md](BACKLOG.md)에 있다.

상태 표기는 모든 문서가 같다: 구현됨 (P0-x), 일부 구현, 계획 (Phase n), 미실측, 미검증. 「설계 §n」은 사용자가 2026-11-15 에 승인한 설계의 n절이다. 아래 코드 경로는 저장소 루트 기준이다.

## 전체 구조

```
[Desktop Mode]                              [Server Mode]
Tauri 2 셸 (Rust)                            java -jar (Docker 는 계획, Phase 8)
 ├─ WebView: React UI ──HTTP──┐                   │
 └─ 런타임 감독(기동·중지·헬스) │                   │
        └─ 자식 프로세스 ──────▼───────────────────▼
                    QueryHub Runtime (Spring Boot, 같은 JAR, HTTP 포트 1개)
                     ├─ 관리 API   /admin/api/v1/**  ← UI (관리자 토큰 Bearer, P1-03)
                     ├─ 데이터 API /api/v1/**        ← 외부 시스템 (계획, Phase 3·4. API Key 는 Phase 4)
                     ├─ Metadata 저장소 (내장 SQLite <data-dir>/queryhub.db, Flyway. P1-06)
                     └─ Business DB × N (MSSQL · Oracle · MariaDB · PostgreSQL, 계획 Phase 1)
```

- 런타임은 Spring Boot 실행 JAR 하나다(`runtime/app`, `bootJar` 결과 `queryhub-runtime.jar`). 두 모드의 차이는 Spring 프로파일 `desktop`, `server` 의 설정뿐이다(설계 §1). 구현됨 (P0-A).
- 프로파일을 지정하지 않으면 `desktop` 이다(`runtime/app/src/main/resources/application.yml` 의 `spring.profiles.default`). 공통 기본 바인딩 주소도 `127.0.0.1` 이고, 모든 인터페이스(`0.0.0.0:8080`)로 넓히는 것은 `application-server.yml` 뿐이다. `desktop` 은 `127.0.0.1:18580` 이다(`application-desktop.yml`). 구현됨 (P0-A).
- `desktop` 과 `server` 를 함께 켜거나 둘 다 없으면 포트를 열기 전에 기동이 실패한다(`runtime/app/src/main/java/com/queryhub/app/config/RuntimeModeResolver.java`, `@Lazy(false)` 라서 `spring.main.lazy-initialization=true` 여도 같다). 구현됨 (P0-A).
- UI 는 Tauri IPC 가 아니라 관리 API(HTTP)로 런타임 정보를 읽는다. IPC 는 수명 주기 커맨드 네 개뿐이다(아래 「Desktop 과 Runtime」). 같은 UI 가 원격 Server 런타임에 붙는 범위는 Phase 6 에서 정한다(계획).
- Rust 셸의 책임은 런타임 프로세스 수명 주기다. P1-04 부터는 마스터 키 파일을 처음 한 번 만들고 시작마다 읽어 런타임에 넘기는 일도 한다([ADR-023](ADR/ADR-023-master-key-delivery.md)). P1-06 부터는 데이터 디렉터리 `<앱 로컬 데이터 디렉터리>/data` 를 준비해 경로를 넘긴다. 그 안의 SQLite 파일은 런타임이 만들고 확인한다([ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md)). OS 키체인은 지금 쓰지 않는다(2026-11-23 사용자 결정, 아래 「설계와 다른 구현」 11). 파일 저장 대화상자는 계획(Phase 6)이다. 셸에 비즈니스 로직을 두지 않는다.

### 단일 포트와 경로 분리 (D3)

- 포트는 `server.port` 하나다. 관리 API 는 `/admin/api/v1/**`, 데이터 API 는 `/api/v1/**` 로 경로만 다르다([ADR-002](ADR/ADR-002-single-http-port.md), 일부 구현).
- 두 API 의 코드는 서로의 경로와 인증을 참조하지 않는다. ArchUnit R5 가 `adapter.web.admin` 과 `adapter.web.api` 의 분리를 검사한다(구현됨, P0-A). 나중에 포트를 나누면 설정 변경으로 끝나게 하려는 것이다.
- 「API 서버 실행·중지」는 런타임 안의 스위치다. 꺼져 있으면 `/api/v1/**` 가 503 `SERVICE_STOPPED` 를 돌려주고 관리 API 는 계속 동작한다(계획, Phase 3·4). 「Runtime 시작·중지」는 JVM 프로세스 단위다(구현됨, P0-C).
- Desktop 에서 바인딩 주소를 넓혀도 관리 API 는 관리자 토큰을 요구하고, desktop 프로파일은 소켓 원격 주소가 루프백인 관리 요청만 받는다(`queryhub.admin.allow-remote=false`, 구현됨 P1-03, [ADR-021](ADR/ADR-021-admin-token-bearer.md)). 넓은 주소로의 바인딩 자체를 거부하는 바인딩 가드는 계획이다([BACKLOG.md](BACKLOG.md) P103-1). 지금의 공격면은 [SECURITY.md](SECURITY.md)에 있다.

엔드포인트와 응답 형식은 [API_SPECIFICATION.md](API_SPECIFICATION.md)에 있다.

## Desktop 과 Runtime

| 항목 | Desktop (설계 §1.2) | 지금 (P0-C) | Server |
|---|---|---|---|
| 실행 | 번들된 JRE(jlink) + JAR 을 셸이 자식 프로세스로 실행 | 외부 JDK 17 이상과 저장소의 JAR 을 자식 프로세스로 실행. JRE·JAR 묶기는 계획 (Phase 8) | `java -jar`, Docker 는 계획 (Phase 8) |
| 포트 | 설정값, 기본 바인딩 127.0.0.1 | `127.0.0.1:18580` 고정([ADR-015](ADR/ADR-015-desktop-fixed-port-and-cors.md), 제안) | 설정값(기본 8080), 기본 바인딩 0.0.0.0 |
| 관리자 토큰 | 실행마다 셸이 만들어 런타임과 WebView 에 전달 | 셸이 실행마다 OS 난수 생성기로 만들어 환경변수 `QUERYHUB_ADMIN_TOKEN` 과 Tauri 커맨드 `admin_token` 으로 전달 (구현됨, P1-03, [ADR-021](ADR/ADR-021-admin-token-bearer.md)) | `QUERYHUB_ADMIN_TOKEN` 또는 시크릿 파일 `QUERYHUB_ADMIN_TOKEN_FILE` (구현됨, P1-03) |
| Metadata 저장소 | (설계: MariaDB. 첫 실행 화면에서 입력, 비밀번호는 OS 키체인) | 내장 SQLite. 셸이 `<앱 로컬 데이터 디렉터리>/data` 를 준비해(unix 0700) `QUERYHUB_DATA_DIR` 로 넘기고, 런타임이 그 안의 `queryhub.db`(0600)를 만들고 다시 쓴다. 문제가 있으면 `data_dir_unavailable`. 접속 화면과 키체인은 필요 없다 (구현됨, P1-06, [ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md), 아래 「설계와 다른 구현」 13) | 내장 SQLite. `QUERYHUB_DATA_DIR` 이나 외부 설정 파일의 `queryhub.data-dir` 이 필수다 (구현됨, P1-06) |
| 마스터 키 | OS 키체인 | 키체인을 쓰지 않는다. 셸이 첫 실행에 앱 로컬 데이터 디렉터리의 `secrets/master.key` 를 만들고(unix 0700/0600, 있는 파일은 덮어쓰지 않는다) 시작마다 읽어 환경변수 `QUERYHUB_MASTER_KEY` 로 넘긴다. 없거나 틀리면 `master_key_unavailable` (구현됨, P1-04, [ADR-023](ADR/ADR-023-master-key-delivery.md), 아래 「설계와 다른 구현」 11) | `QUERYHUB_MASTER_KEY` 또는 시크릿 파일 `QUERYHUB_MASTER_KEY_FILE`. 없거나 틀리면 기동 거부 (구현됨, P1-04) |

### 셸의 런타임 감독 (구현됨, P0-C)

셸(`desktop/src-tauri/src/runtime/`)은 런타임을 `<java> -jar <jar> --spring.profiles.active=desktop --server.port=18580` 로 띄운다(`launch.rs` 의 `child_args`). Tauri sidecar(`bundle.externalBin`)는 쓰지 않는다([ADR-001](ADR/ADR-001-jvm-child-process.md)). UI 는 셸 커맨드 네 개(`runtime_status`, `runtime_start`, `runtime_stop`, `runtime_restart`, `desktop/src-tauri/src/commands.rs`)로 수명 주기만 다루고, 셸은 `/admin/api/**` 를 부르지 않는다. 앱은 시작하자마자 런타임을 자동으로 시작한다.

| 상태 | 화면 | 시작 | 중지 | 재시작 |
|---|---|---|---|---|
| `stopped` | 중지됨 | 가능 | | |
| `starting` | 시작 중 | | 가능 | |
| `running` | 실행 중 | | 가능 | 가능 |
| `failed` | 실패 | 가능 | | 가능 |

수치는 `desktop/src-tauri/src/runtime/supervisor.rs` 의 `SupervisorConfig::desktop` 과 `desktop/src/runtime/useRuntimeStatus.ts` 에 있다.

- `starting` → `running`: `GET /actuator/health/readiness` 가 200 일 때(`readiness_interval` 500 ms 간격). 90 초(`startup_timeout`) 안에 안 되면 런타임을 죽이고 `failed(startup_timeout)`.
- `running` 동안 `GET /actuator/health` 를 5 초(`health_interval`)마다 확인해 health(정상·비정상)를 따로 보인다. 상태는 `running` 그대로다.
- 중지 요청 없이 런타임이 끝나면 `failed(exited)` 와 종료 코드, 출력 꼬리를 보인다. 자동 재시작은 하지 않는다.
- 실패 종류: `java_not_found`, `java_too_old`(주 버전 17 미만, `java.rs` 의 `MIN_MAJOR`), `jar_not_found`, `port_in_use`(셸이 `127.0.0.1:<포트>` 에 바인딩해 보고 이어서 300 ms 루프백 연결 시험을 해 판정한다. `launch.rs` 의 `port_is_free`. 메시지는 `lsof -nP -iTCP:18580 -sTCP:LISTEN` 으로 확인하라고 안내한다), `data_dir_unavailable`(P1-06: 데이터 디렉터리가 상대 경로·UTF-8 이 아님·심볼릭 링크·디렉터리가 아님·다른 소유자·그룹·기타 권한 비트. `chmod 700` 을 안내한다), `master_key_unavailable`(P1-04), `spawn_failed`, `exited`, `startup_timeout`. 사전 점검 순서는 java, JAR, 포트, 데이터 디렉터리, 마스터 키다(키가 마지막이라 데이터 디렉터리가 틀리면 키 파일을 만들지 않는다). P1-05 까지 있던 `metadata_settings_missing` 은 P1-06 에서 없어졌다. 메시지는 한국어이고 변수 이름과 경로만 말한다.
- 포트는 18580 고정이다(`supervisor.rs` 의 `DESKTOP_PORT`). 운영 CSP 의 `connect-src` 와 데이터 API 사용자에게 고정 주소가 필요하므로 빈 포트를 찾지 않는다([ADR-015](ADR/ADR-015-desktop-fixed-port-and-cors.md)).
- 런타임 출력: 셸은 출력을 링 버퍼에 최대 200줄(`output_lines`), 줄마다 최대 2048바이트(`output_line_bytes`)까지 두고 출력에 그대로 나온 관리자 토큰과 마스터 키 값을 `********` 로 가린다(`output.rs` 의 `mask_secrets`, `MASK`. P1-05 까지는 Metadata DB 비밀번호도 가렸다. 데이터 디렉터리 경로는 비밀 값이 아니라 가리지 않는다. 인코딩되거나 변형된 형태는 가리지 않는다). 런타임이 실패하면 실패 카드가 마지막 40줄(`status_tail_lines`, `failure.output_tail`)을 보인다. 로그 창은 계획이다([REQUIREMENTS.md](REQUIREMENTS.md) AR-1 OM-1, Phase 6).
- `running` 이면 런타임 정보 카드(이름, 버전, 모드, 시작 시각, 가동 시간)를 `GET /admin/api/v1/runtime` 에서 10 초(`INFO_POLL_MS`)마다 읽는다. 상태는 셸 커맨드로 시작 중 1 초, 그 밖에는 3 초 간격으로 읽는다. 브라우저에서 개발 서버만 열면(Tauri 밖) 안내만 보이고 셸을 부르지 않는다.
- 디버그 빌드는 상태 전이(예: `runtime: starting -> failed(exited) ...`)와 가린 런타임 출력을 셸의 stderr 에 찍는다. 셸은 환경을 로그에 남기지 않고 로그 파일을 쓰지 않는다.

자식 환경 허용 목록과 가림 규칙은 [SECURITY.md](SECURITY.md), 변수와 실행 절차는 [DEPLOYMENT.md](DEPLOYMENT.md)에 있다.

### 런타임이 멈추는 방식 (구현됨, P0-C)

- **앱 종료**(창 닫기, Cmd+Q): `RunEvent::Exit` 에서 셸이 런타임에 SIGTERM 을 보내고(Spring 의 정상 종료), 10 초(`stop_grace`) 안에 끝나지 않으면 SIGKILL 한다(`desktop/src-tauri/src/lib.rs`). 중지·재시작 버튼도 같다.
- **셸이 비정상 종료**(SIGKILL, 크래시)되면 셸은 아무것도 할 수 없다. 대신 런타임의 부모 감시가 `QUERYHUB_DESKTOP_PARENT_PID` 의 프로세스가 끝난 것을 감지해(JDK 가 최대 5 초 간격으로 확인하고 PID 재사용도 확인한다) INFO 로그 한 줄을 남기고 종료한다(exit 0). 30 초(`HALT_AFTER`) 안에 끝나지 않으면 `Runtime.halt`(종료 코드 3). 값이 잘못되었으면(숫자가 아님, 0 이하, 살아 있지 않은 PID, 런타임 자신의 PID) 포트를 열기 전에 종료 코드 2 로 끝난다. 변수가 없으면(server 모드, 손으로 실행한 `java -jar`) 감시하지 않는다(`runtime/app/src/main/java/com/queryhub/app/desktop/ParentProcessWatch.java`, `QueryHubApplication.java` 의 `EXIT_INVALID_PARENT_WATCH`, [ADR-014](ADR/ADR-014-runtime-parent-watch.md)).
- **미검증**: Windows 와 Linux(정상 종료 신호가 없는 Windows 의 즉시 종료, `java.exe` 콘솔 창, Windows 출처 `http://tauri.localhost`, WebKitGTK). CI 의 Windows 작업(지금은 실패한다)과 Phase 8 에서 확인한다([BACKLOG.md](BACKLOG.md) PLAT-1, CI-1).

## 모듈과 의존 규칙

의존은 한 방향이다: `app → adapter-* → application → domain` (설계 §3). 구현됨 (P0-A, P0-B).

| 모듈 | 패키지 | 선언할 수 있는 의존 |
|---|---|---|
| domain | `com.queryhub.domain` | 없음 (Spring 을 포함한 어떤 프레임워크도 쓰지 않는다) |
| application | `com.queryhub.application` | `:domain` 만 (Spring 웹·JDBC 를 모른다) |
| adapter-web | `com.queryhub.adapter.web` | `:application`, `:domain`, 외부 라이브러리. 다른 어댑터는 안 된다 |
| adapter-crypto | `com.queryhub.adapter.crypto` | `:application` 만. JDK 만 쓰고 외부 라이브러리는 없다. 다른 어댑터는 안 된다. `javax.crypto` 는 이 모듈만 쓴다(ArchUnit R10). 구현됨 (P1-04) |
| adapter-metadata | `com.queryhub.adapter.metadata` | `:application`, `:domain`, 외부 라이브러리(Spring JDBC, Flyway, SQLite 드라이버 sqlite-jdbc 는 실행 시에만, P1-06). 다른 어댑터는 안 된다. 저장소를 여는 순수 Java 코드는 `com.queryhub.adapter.metadata.store` 에 있다 |
| app | `com.queryhub.app` | 모든 모듈 (유일한 조립 지점) |
| adapter-dbprovider | `com.queryhub.adapter.dbprovider` | `:application`(api), HikariCP. 업무 DB 연결의 공통 JDBC 코드: Provider SPI(`JdbcProviderSpi`), `Driver.connect`, 옵션 허용 목록, 마감, 연결마다 읽기 전용 풀. Provider 에 의존하지 않는다(ArchUnit R11). 구현됨 (P1-07, [ADR-027](ADR/ADR-027-database-provider.md)) |
| provider-mariadb, provider-postgresql | `com.queryhub.adapter.dbprovider.provider.{mariadb,postgresql}` | `:adapter-dbprovider`(api)와 그 DB 의 JDBC 드라이버 하나. 드라이버 클래스는 여기서만 쓴다(R6). 서로 의존하지 않는다(R11). 구현됨 (P1-07) |
| provider-oracle, provider-mssql, adapter-export | | 계획 (P1-10, P1-11, Phase 5) |

모듈은 필요해지는 Phase 에 만든다. Controller 에 비즈니스 로직을 두지 않는다(명세 14절).

규칙은 두 겹으로 강제한다.

1. **Gradle** (`runtime/build.gradle`): 모듈 선언 때문에 금지된 import 는 컴파일되지 않는다. 또 빌드 스크립트의 검사가 모든 모듈의 `api`, `implementation`, `compileOnly`, `compileOnlyApi`, `runtimeOnly` 선언과 `compileClasspath`·`runtimeClasspath` 에 상속되는 모든 설정(사용자 정의 설정 포함)의 선언을 확인해, 규칙을 넓히는 선언이 있으면 어떤 태스크도 실행하기 전에 빌드를 실패시킨다. 파일 의존성(`files(...)`, `fileTree(...)`)은 `app` 이외의 모든 모듈에서 거부한다. 테스트 설정(`testImplementation` 등)은 검사하지 않는다. 모듈은 `settings.gradle` 에 최상위로 include 한 `:domain`, `:application`, `:app`, `:adapter-*`, `:provider-*` 중 하나여야 하고(중첩 프로젝트는 거부한다), `runtime/<이름>/` 에 자기 `build.gradle` 이 있어야 한다(`build.gradle.kts` 는 쓰지 않는다). 남은 우회로 두 개(소비용 구성에 붙인 의존, `resolutionStrategy.dependencySubstitution`)는 [BACKLOG.md](BACKLOG.md) P0A-N3 이다. 클래스 수준에서는 ArchUnit 이 막는다.
2. **ArchUnit** (`runtime/app/src/test/java/com/queryhub/architecture/`): R1 계층, R2 어댑터 간 독립, R3 domain 은 `java..` 만, R4 application 은 웹·HTTP·JDBC·Boot·서블릿·어댑터 금지, R5 관리 API(`adapter.web.admin`)와 데이터 API(`adapter.web.api`) 분리, R6 JDBC 드라이버 클래스(`org.mariadb.jdbc..`, `org.postgresql..`, `oracle.jdbc..`, `com.microsoft.sqlserver..`, P1-06 부터 `org.sqlite..` 등)는 `adapter.dbprovider.provider..` 만(Metadata 저장소 코드는 SQLite 드라이버를 클래스 이름 문자열로만 불러온다), R7 모든 클래스는 네 계층 중 하나에, R8 저장 기술(`org.springframework.jdbc..`, `org.flywaydb..`, `javax.sql..`, `com.zaxxer.hikari..`)은 `adapter.metadata..`, `adapter.dbprovider..`, `app..` 만(`java.sql` 은 허용), R9 관리자 토큰 검증기는 `String.equals` 류와 `Arrays.equals` 를 쓰지 않는다(P1-03), R10 `javax.crypto..` 는 `adapter.crypto..` 만(P1-04), R11 Provider 패키지(`adapter.dbprovider.provider.<db>`)끼리 서로 의존하지 않고 공통 코드(`adapter.dbprovider..`)는 Provider 에 의존하지 않는다(P1-07). P1-07 부터 R8 은 `org.springframework.boot.jdbc..`, `org.springframework.boot.flyway..` 도 다룬다(P0B-R5). Gradle 규칙은 어떤 `adapter-*` 든 Spring JDBC 를 선언하게 두므로 R8 이 두 번째 방어선이다. `ArchitectureRulesSelfTest` 가 고의 위반 픽스처로 R1~R11 이 실제로 실패하는지 확인한다(자체 검증의 빈 곳은 [BACKLOG.md](BACKLOG.md) P0A-I2).

### application 계층의 포트 (설계 §3.1)

| 포트 | 책임 | 상태 |
|---|---|---|
| `AppSettingRepository` | 설정 키-값 저장소. `JdbcAppSettingRepository`(`adapter-metadata`)가 구현한다 | 구현됨 (P0-B) |
| `GetRuntimeInfoUseCase` | 런타임 이름, 버전, 모드, 시작 시각, 가동 시간 | 구현됨 (P0-A) |
| `DatabaseProvider`, `ConnectionPools` (`application.connection`) | DB 별 연결 테스트(`DatabaseProvider`)와 연결 id 마다 읽기 전용 풀(`ConnectionPools`). JDBC 형식을 쓰지 않는다. `TestConnectionUseCase` 가 종류로 Provider 를 고른다. `JdbcDatabaseProvider`·`HikariConnectionPools`(`adapter-dbprovider`)가 구현한다([ADR-027](ADR/ADR-027-database-provider.md)). 스트리밍 설정, 페이지네이션 구문, JDBC 타입 변환은 아직 없다 | 일부 구현 (P1-07: 연결 테스트, 풀, 오류 분류. 나머지는 P1-12 와 Phase 2–4) |
| `QueryExecutor` | 쿼리를 실행해 행을 콜백(`RowConsumer`)으로 넘긴다. List 를 반환하지 않는다 | 계획 (Phase 1, 4) |
| `ResultWriter` | 형식별 구현체. 컬럼 정보·행·종료 메타를 `OutputStream` 에 바로 쓴다 | 계획 (Phase 4, 5) |
| `SqlInspector` | 파라미터 추출, SELECT 전용 검증([ADR-004](ADR/ADR-004-select-only-defense.md)) | 계획 (Phase 2) |
| `SecretCipher` (`application.secret`) | 비밀 값 암호화(`encrypt`, `decrypt`, `needsReencryption`, `reencrypt`, 문맥 `SecretContext`). `AesGcmSecretCipher`(`adapter-crypto`)가 구현한다([ADR-006](ADR/ADR-006-password-aes-gcm-api-key-hmac.md), [ADR-024](ADR/ADR-024-secret-cipher-format-and-rotation.md)) | 구현됨 (P1-04). 연결 저장에서 쓰는 것은 다음 태스크 |
| `ApiKeyHasher` | API Key 해시([ADR-006](ADR/ADR-006-password-aes-gcm-api-key-hmac.md)) | 계획 (Phase 4) |
| `RouteRegistry` | (HTTP Method, 경로) → API Definition. 등록·수정 즉시 반영([ADR-003](ADR/ADR-003-single-dispatcher-route-registry.md)) | 계획 (Phase 3) |

데이터 API 요청 한 건의 처리 순서(계획, Phase 3·4·7): 라우트 조회 → 인증·권한 → 파라미터 검증·변환 → 동시성·속도 제한 → 쿼리 실행(읽기 전용, timeout, maxRows, fetchSize) → 스트리밍 출력 → 이력 기록.

## 기술 스택

사용 중인 버전 (구현됨, P1-06 까지):

| 영역 | 사용 중 | 출처 |
|---|---|---|
| 런타임 | Java 17 toolchain(로컬 설치 JDK 자동 감지, 내려받지 않음. foojay 없음), Spring Boot 4.1.2 BOM, `io.spring.dependency-management` 1.1.7, Spring Web MVC, Actuator, Spring JDBC, HikariCP | `runtime/gradle/libs.versions.toml`, `runtime/build.gradle`, `runtime/gradle.properties` ([ADR-010](ADR/ADR-010-spring-boot-4-java-21.md), [ADR-025](ADR/ADR-025-java-17-baseline.md)) |
| 빌드 | Gradle wrapper 9.7.1, Groovy DSL, 버전 카탈로그, 기본 패키지 `com.queryhub`, Spotless 8.11.0(google-java-format) | `runtime/gradle/wrapper/gradle-wrapper.properties`, `libs.versions.toml` |
| Metadata 저장소 | 내장 SQLite: `org.xerial:sqlite-jdbc` 3.53.4.0(Boot BOM 관리, 덮어쓰기 없음, Apache-2.0, SQLite 는 public domain), Flyway core 12.4.0 의 SQLite 지원(Flyway 모듈을 더하지 않았다). P1-05 까지의 MariaDB, `flyway-mysql`, Connector/J 는 P1-06 에서 뺐다 | `libs.versions.toml` 의 `sqlite-jdbc`, `runtime/adapter-metadata/build.gradle` ([ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md), [DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)) |
| 업무 DB 드라이버 | MariaDB Connector/J 3.5.11([ADR-018](ADR/ADR-018-mariadb-connector-j-override.md) 덮어쓰기, LGPL-2.1-or-later), pgjdbc 42.7.14(Boot BOM, BSD-2-Clause). 각 Provider 모듈의 `implementation` 이다. 연결 풀은 HikariCP 7.0.2(BOM) | `libs.versions.toml`, `runtime/provider-*/build.gradle` ([ADR-027](ADR/ADR-027-database-provider.md), [DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)) |
| 테스트 | JUnit 5, AssertJ, Spring Boot Test, ArchUnit 1.5.1, Testcontainers 2.0.5(P1-06 에서 뺐고 P1-07 의 업무 DB 통합 시험이 다시 더했다: `testcontainers-junit-jupiter`, `-mariadb`, `-postgresql`), Logback(시험만, 로그 누출 확인), Vitest 5.1.0, Testing Library, `cargo test` | `libs.versions.toml`, `desktop/package.json` |
| Desktop | Tauri 2.11.5(`tauri-build` 2.6.3, `@tauri-apps/api` 2.11.1, `@tauri-apps/cli` 2.11.4), React 19.3.1, TypeScript 5.9.3, Vite 8.3.8, Tailwind CSS 4.3.5, shadcn/ui, pnpm 11.9.0 | `desktop/src-tauri/Cargo.toml`, `desktop/package.json` |

계획인 라이브러리 (설계 §2): Spring Security, springdoc-openapi와 TS 클라이언트 생성([ADR-008](ADR/ADR-008-openapi-ts-client.md)), JSqlParser, 업무 DB 드라이버(mssql-jdbc, ojdbc17, pgjdbc), Jackson XML, Apache Commons CSV, Apache POI SXSSF, TanStack Query·Table, Monaco Editor, Recharts, Playwright. 해당 Phase 에서 버전 카탈로그에 넣는다.

- Boot BOM 이 관리하는 버전은 덮어쓰지 않는다. 예외는 Connector/J 하나다([ADR-018](ADR/ADR-018-mariadb-connector-j-override.md)). P1-06 부터 그 드라이버는 DB Provider 태스크까지 어떤 클래스패스에도 없고 덮어쓰기 줄만 남아 있다. BOM 밖 라이브러리는 버전 카탈로그에 고정한다.
- Tauri 는 2.11 계열로 정확히 고정하고 `Cargo.lock` 은 `--locked` 로만 쓴다. 고정 검사는 [TEST_PLAN.md](TEST_PLAN.md)에 있다.
- GraalVM native image 는 쓰지 않는다([ADR-001](ADR/ADR-001-jvm-child-process.md)).

## 성능 원칙 (계획)

설계 §8, 명세 10절. 업무 DB 조회가 생기는 Phase 1·4·5 에서 구현한다.

- 결과를 List 에 모두 적재한 뒤 변환하지 않는다. 조회는 `RowConsumer` 스트리밍 한 경로만 쓴다([ADR-005](ADR/ADR-005-streaming-single-path.md)).
- fetchSize, 스트리밍 ResultSet, CSV·JSON·XLSX(SXSSF) 스트리밍 출력. 행 수 제한은 `Statement.setMaxRows(maxRows + 1)` 로 건다.
- XLSX 는 1,048,576 행 한도를 넘으면 422 `ROW_LIMIT_EXCEEDED` 로 거부한다. 분할 파일은 후속 과제다.
- DECIMAL 과 2^53 을 넘는 정수는 JSON 에서 문자열로 내보내는 옵션을 둔다.
- DB 별 스트리밍 조건은 [DATABASE_SUPPORT.md](DATABASE_SUPPORT.md), 성능 테스트는 [TEST_PLAN.md](TEST_PLAN.md)에 있다.

## Phase 계획

모든 태스크는 팩토리 공통 게이트(요구사항, build, 단위 테스트, 통합 테스트, 정적 분석, 보안 리뷰, 코드 리뷰, diff 검사)를 통과해야 한다. 아래는 Phase 마다 더하는 범위와 통과 기준이다(설계 §9). 운영 지표 OM-1..OM-7 의 정의는 [REQUIREMENTS.md](REQUIREMENTS.md) AR-1 에 있다.

| Phase | 범위 | 통과 기준 | 상태 |
|---|---|---|---|
| 0 기반 | 모듈 골격, Gradle, Flyway, 공통 오류·응답, 게이트 스크립트, CI 정의, Desktop 셸 스파이크(D4) | 새 clone 에서 build·test·lint 가 exit 0, ArchUnit 의존 규칙 통과, 런타임 기동 후 health 200, Tauri 에서 런타임 기동·중지 | 완료(P0-D, d0b0f0f, 2026-11-20). CI 는 Phase 0 에서는 정의만 했고 2026-11-23 부터 실행된다(아래 표, [Phase 0 보고](PHASE0-REPORT.md), [BACKLOG.md](BACKLOG.md) CI-1) |
| 1 DB 연결 | Provider 4종, 연결 CRUD·테스트, 풀, 비밀번호 암호화, 관리자 토큰 | 4개 DB 실제 컨테이너에서 연결·SELECT·타입 변환·쓰기 거부 통과. 비밀번호 평문이 DB·로그·응답에 없음 | 진행 중: P1-03 관리자 토큰, P1-04 비밀번호 암호, P1-07 Provider 2종(MariaDB, PostgreSQL)의 연결 시험과 읽기 전용 풀(bb0cd8c (2026-11-23)). 다음은 P1-08 저장소 V2(`database_connection`, 암호화한 비밀번호) → P1-09 관리 API 와 CORS → P1-10 Oracle → P1-11 MSSQL(드라이버만, 통합 시험 생략) → P1-12 Phase 1 마무리(SELECT, 타입, 네 DB 의 쓰기 거부, 쓰기 권한 경고, P0B-H1·R14·R15, P103-1, CI-2) |
| 2 SQL 저장소 | SQL CRUD, 파라미터 파서·정의, SELECT 전용 검증, 테스트 실행, 버전 이력 | 명세 12절 Parameter Test 7종 통과. 인젝션 문자열이 값으로만 바인딩됨을 4개 DB 에서 확인. DML·DDL·다중문 거부 | 계획 |
| 3 API 관리 | API CRUD, 매핑, 라우트 레지스트리, 활성·비활성 | 중복·예약 경로 거부, 수정 즉시 라우팅 반영 | 계획 |
| 4 API 런타임 | 동적 실행, JSON·XML, GET·POST, API Key, 오류 처리 | 명세 12절 API Test 8종 통과. 오류 응답에 DB 메시지·스택 없음 | 계획 |
| 5 Export | CSV·XLSX·HTML, 스트리밍 | 10만 행 추출이 제한된 힙(예: 256 MB)에서 통과. 한글·NULL·날짜·숫자 왕복 검증 | 계획 |
| 6 Desktop | 10개 메뉴 UI, Tauri 연동(설계의 Metadata DB 첫 실행 화면과 그 비밀번호의 OS 키체인은 P1-06 의 내장 SQLite 로 필요 없어졌다, [ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md)), Dashboard 의 **운영 지표 화면**(OM-1..OM-5: 로그 창, 처리 중 요청·대기열, 스레드, 레이턴시, JVM 힙) | Playwright E2E(연결 등록부터 추출까지), 타입체크·lint 통과. 운영 지표 화면은 Phase 7 전에 수집되지 않은 패널을 「아직 수집하지 않음」으로 보인다 | 계획 |
| 7 모니터링·보안 | 이력, 대시보드, 키 관리, rate limit, 동시성 제한, 감사, 마스킹, **운영 지표 수집과 관리 API 엔드포인트**(OM-1..OM-7: Micrometer, 관리자 토큰, actuator 미노출, 지표·로그 창 보안) | 한도·마스킹 테스트 통과, 보안 리뷰 high 0건. 지표와 로그 창에 SQL 원문·파라미터 값·비밀 값이 없음(OM-7) | 계획 |
| 8 배포·안정화 | 설치본, JAR, Docker, 문서, 성능·보안 테스트, 번들 JRE(jlink), 서명·공증 | 1천·1만·10만 건과 동시 요청 결과 문서화, Docker 이미지 기동 확인, 문서 완비 | 계획 |

- 운영 지표의 화면(Phase 6)이 수집(Phase 7)보다 먼저다. 이 순서는 Phase 6 을 계획할 때 다시 검토한다(AR-1).
- 각 Phase 완료 때 구현 기능, 테스트 결과, 남은 문제를 [CHANGELOG.md](../CHANGELOG.md)와 Phase 보고에 적는다(명세 13절). Phase 0 보고는 [PHASE0-REPORT.md](PHASE0-REPORT.md)다.
- macOS 에서 검증할 수 없는 Windows·Linux 경로는 CI 에 맡기고 「미검증」으로 적는다. Mock 테스트만으로 실제 DB 호환성을 통과 처리하지 않는다(명세 14절).

### Phase 0 태스크

| 태스크 | 내용 | 커밋 | 상태 |
|---|---|---|---|
| P0-A | 런타임 골격(Gradle 멀티 모듈, Boot 앱, health, requestId, 공통 오류·응답), ArchUnit, 정적 분석, 게이트 스크립트, 저장소 기본 파일 | ee00165 | 완료 |
| P0-B | 메타데이터 기반: Flyway + MariaDB, `app_setting`, Testcontainers 통합 테스트, 개발용 compose, `integration` 게이트 | f913239 | 완료 |
| P0-C | Desktop 셸 스파이크: Vite + React + Tailwind + shadcn 골격, Tauri 에서 런타임 기동·중지·헬스 표시, 부모 감시 | f84c628 | 완료 |
| 문서 기준선 | `docs/`, `CHANGELOG.md`, README 정리 (T-20261119-074437-3980) | 51141df | 완료 |
| P0-D | CI 워크플로 정의(`.github/workflows/ci.yml`, 정의됨·미실행), 게이트의 `--desktop-only`·`docs-check.sh`·`workflow-check.sh`·`selftest.sh --no-skip`, Phase 0 보고, 문서 갱신 (T-20261120-181159-c278) | d0b0f0f | 완료 |

태스크 사이에는 사람의 검토와 커밋이 들어간다. 테스트 수는 [CHANGELOG.md](../CHANGELOG.md)와 [TEST_PLAN.md](TEST_PLAN.md)에 있다.

## 위험 요소

설계 §11 의 위험과 지금의 상태다.

1. **Tauri + JVM 번들의 3개 OS 패키징·서명.** P0-C 스파이크는 macOS 의 서명 없는 디버그 번들까지 확인했다. JRE 묶기, 설치본, 서명, Windows·Linux 는 계획(Phase 8)이고 미검증이다(PLAT-1).
2. **SQL 파서의 방언 커버리지.** 파싱 실패는 거부가 기본이다. DB 별 SQL 모음으로 테스트한다(Phase 2).
3. **SQL Server 의 읽기 전용 미강제.** 계정 권한 점검과 파서에 의존한다([DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)).
4. **스트리밍 도중 오류.** 응답을 시작한 뒤에는 HTTP 상태를 바꿀 수 없다([API_SPECIFICATION.md](API_SPECIFICATION.md)).
5. **XLSX 행 한도와 임시 파일.**
6. **단일 포트에서 외부 바인딩 시 관리 API 노출.** 관리자 토큰과 루프백 전용 관리 API 로 줄이지만 포트 분리보다 약하다. 사용자가 나중에 분리를 결정할 수 있다([ADR-002](ADR/ADR-002-single-http-port.md)).
7. **Desktop 이 외부 MariaDB 에 의존 (P1-06 에서 해소).** Metadata 저장소는 이제 내장 SQLite 다([ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md)). 대신 SQLite 의 위험이 생겼다: 네이티브 라이브러리(Mac/aarch64 밖은 미검증, PLAT-1), 한 번에 쓰기 하나, 로컬 디스크만, 호스트 하나(여러 서버가 함께 쓰지 못한다, [BACKLOG.md](BACKLOG.md) P106-1), 운영자가 하는 백업([DEPLOYMENT.md](DEPLOYMENT.md) 「백업·복구」).
8. **숫자 정밀도**(위 「성능 원칙」).
9. **통합 테스트 시간과 메모리.** 호스트 16 GB, Docker VM 약 8 GB. DB 별 통합 테스트는 순차로 돌린다([DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)).
10. **CI.** push 는 사람이 한다. 워크플로(`.github/workflows/ci.yml`)는 P0-D 에서 정의만 했고, 2026-11-23 부터 공개 저장소(github.com/xgeekover/QueryHub)에서 push 로 실행된다. Linux(`gates`)와 macOS(`desktop`) 작업은 통과했고, Windows 작업은 `test.sh` 의 자체 검증 i6 에서 실패한다([BACKLOG.md](BACKLOG.md) CI-1, [ADR-020](ADR/ADR-020-ci-runs-gate-scripts.md)). P1-07 코드(bb0cd8c)도 같았다(실행 45194878756 의 2회차. 1회차의 Linux 실패는 Maven Central 의 429 응답이었다). P1-07a(커밋 전)가 i6 를 고쳤고, Windows 는 그 push 뒤의 CI 실행까지 미검증이다.

## 설계와 다른 구현

승인된 설계와 다르게 구현한 곳이다. 이유는 ADR 에 있고, 「다시 볼 때」에 설계대로 되돌리거나 설계를 고친다.

| # | 승인된 설계 (절) | 구현 | 이유 | ADR | 다시 볼 때 |
|---|---|---|---|---|---|
| 1 | §13 「예시는 `.env.example` 로만 둔다」 | 예시 파일은 `deploy/env.example` (이름 앞에 점 없음) | 도구와 검사가 `.env*` 를 비밀 파일로 다뤄, 커밋하고 읽어야 하는 예시가 그 패턴을 피해야 했다 | [ADR-016](ADR/ADR-016-env-example-naming.md) (승인됨) | 없음 |
| 2 | §1.2 Desktop 포트 「설정값(충돌이 적은 기본값)」 | `127.0.0.1:18580` 고정. 셸이 항상 `--server.port=18580` 을 넘기고 빈 포트를 찾지 않는다 | 운영 CSP `connect-src` 와 데이터 API 사용자에게 고정 주소가 필요하다 | [ADR-015](ADR/ADR-015-desktop-fixed-port-and-cors.md) (제안) | Phase 6 (설정 화면) |
| 3 | §1.2 Desktop 의 Metadata DB 는 앱 설정과 첫 실행 화면에서 받고 비밀번호는 OS 키체인 | P0-C 부터 P1-05 까지 셸 환경변수 `QUERYHUB_METADATA_DB_*` 를 읽었다. **P1-06 에서 없어졌다**: 셸은 데이터 디렉터리만 넘기고 런타임이 내장 SQLite 를 쓴다(13) | 화면과 키체인은 Phase 6 범위였다. 스파이크에는 환경변수로 충분했다 | [ADR-017](ADR/ADR-017-desktop-metadata-settings-from-env.md) (대체됨(ADR-026)) | 없음 (13 이 대신한다) |
| 4 | §2 「Boot BOM 이 관리하는 것은 덮어쓰지 않는다」 | MariaDB Connector/J 만 BOM 의 3.5.10 대신 3.5.11 (`runtime/build.gradle` 의 `ext['mariadb.version']`). P1-06 에서는 드라이버가 클래스패스에 없었고, P1-07 에서 업무 DB 드라이버로 다시 들어왔다(3.5.11) | GHSA-fhx5-2xqw-2cgm. Provider 태스크가 드라이버를 다시 더할 때 3.5.11 을 받게 한다 | [ADR-018](ADR/ADR-018-mariadb-connector-j-override.md) (제안) | BOM 이 3.5.11 이상을 담을 때 예외를 지운다 |
| 5 | §9.1 「(이후 `integration.sh`)」 | P0-B 에서 `scripts/gate/integration.sh` 를 만들었다. Docker 가 필요하고 Docker 가 없으면 건너뛰지 않고 실패한다. `integrationTest` 소스 세트는 `runtime/app` 에 있다 | 실제 MariaDB 없이는 Metadata DB 동작을 통과 처리할 수 없었다(명세 14절). P1-06 부터 통합 테스트는 실제 SQLite 파일과 실행 JAR 자식 프로세스로 돌고 Docker 를 쓰지 않는다. Docker 사전 점검은 남겼고 P1-07 의 업무 DB 통합 시험이 Testcontainers 로 Docker 를 다시 쓴다([ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md) 결정 14) | [ADR-019](ADR/ADR-019-gate-scripts-judge-results.md) (승인됨), [ADR-020](ADR/ADR-020-ci-runs-gate-scripts.md) (제안) | CI-1 (CI 는 `gates` 작업에서만 Docker 로 `integration.sh` 를 돌린다. 2026-11-23 의 실행에서 통과했다, P1-06 코드) |
| 6 | §1.2 「비밀 값…은 환경변수로 넘기고 명령행 인자에 넣지 않는다」. Boot 4.1.2 의 동작에 대한 대응은 설계에 없다 | Boot 4.1.2 의 별도 DataSource Flyway 경로를 막고 검사한 풀에 고정했다(P1-06 의 SQLite 저장소에서도 그대로). Hikari `jdbc-url`·`password` 거부(ADR-011)는 Metadata 비밀번호가 없어져 P1-06 에서 지웠다. 대신 SQLite 설정을 바꾸는 `data-source-properties` 는 출처와 상관없이 거부하고 풀 연결의 PRAGMA 를 확인한다 | `spring.flyway.url` 등으로 마이그레이션 연결을 돌릴 수 있었다. 비밀 값이 명령행에 보이면 안 된다(§1.2) | [ADR-012](ADR/ADR-012-flyway-connection-pin.md) (승인됨), [ADR-011](ADR/ADR-011-metadata-secrets-env-only.md) (대체됨(ADR-026)) | Phase 1 (P0B-H1, P0B-R14, P0B-R15) |
| 7 | §1.2 Desktop 은 번들된 JRE(jlink) + JAR | 외부 JDK 17 이상(`QUERYHUB_DESKTOP_JAVA`, `JAVA_HOME`, `PATH` 순, 17 미만은 `java_too_old`, [ADR-025](ADR/ADR-025-java-17-baseline.md))과 디버그 빌드의 저장소 JAR | 스파이크 범위. JRE·JAR 묶기는 설치본과 함께 한다 | [ADR-001](ADR/ADR-001-jvm-child-process.md) (승인됨, 일부 구현) | Phase 8 |
| 8 | 설계에 없음 (§1.1 은 경로 분리만 말한다) | desktop 프로파일에서 관리 API 의 CORS 를 Tauri 출처 `tauri://localhost`, `http://tauri.localhost` 에만, GET 과 요청 헤더 `Authorization` 만 허용한다. P1-03 부터 관리 Spring Security 체인 안에서 처리한다 | 앱 번들의 WebView 가 `http://127.0.0.1:18580` 의 관리 API 를 직접 읽어야 하고, 관리자 토큰을 `Authorization` 헤더로 보낸다 | [ADR-015](ADR/ADR-015-desktop-fixed-port-and-cors.md) (제안), [ADR-022](ADR/ADR-022-admin-token-implementation.md) (제안) | 없음 (관리자 토큰은 P1-03 에서 더했다) |
| 9 | §5.3 「헬스 체크는 인증 없이」, readiness 는 언급 없음 | `GET /actuator/health` 는 Metadata 저장소(`db`, P1-06 부터 내장 SQLite)를 포함한다. readiness 는 저장소를 포함하지 않는다 | P0-B 는 Metadata DB 상태를 health 에 더했고, readiness 에 넣을지는 정하지 않았다. Desktop 감독은 readiness 로 `starting` → `running` 을 판정한다 | 없음 | 계획 (readiness 에 DB 를 넣을지 정한다) |
| 10 | §12 「설계 문서 기준선」은 P0-A 의 일부 | 별도 태스크(문서 기준선, T-20261119-074437-3980)로 했다 | P0-A 는 코드와 게이트에 집중했고 README 가 유일한 문서였다 | 없음 | 없음 |
| 11 | §1.2 와 §7: desktop 의 마스터 키는 OS 키체인에 둔다 | desktop 은 키체인을 쓰지 않는다. 셸이 앱 로컬 데이터 디렉터리의 `secrets/master.key` 를 처음 한 번 만들고(unix 0700/0600, 덮어쓰지 않는다) 시작마다 읽어 `QUERYHUB_MASTER_KEY` 로 런타임에 넘긴다. macOS, Linux, Windows 가 같은 코드다 | 사용자 결정 2026-11-23: 「MacOS에서뿐만 아니고, 리눅스/윈도우 환경에서도 동작시킬 수 있어야 해.」 「키체인이 필수가 아니라면, 사내 폐쇄망에서 사용할 거라 지금 당장은 키체인을 사용 안하고 싶습니다.」 키 파일은 파일 권한만 지킨다([SECURITY.md](SECURITY.md) 「마스터 키」) | [ADR-023](ADR/ADR-023-master-key-delivery.md) (제안, 키체인 제외는 사용자 결정) | 키체인 통합을 다시 정할 때(BACKLOG P104-2). Linux·Windows 는 미검증(PLAT-1, CI-1) |
| 12 | §0, §2: Java 21 (사용자 결정 D1) | Spring Boot 4.1.2 는 그대로이고 Java 17 로 컴파일·테스트·실행한다. Gradle toolchain 은 정확히 17 이고 로컬에 설치된 JDK 만 쓴다(foojay 없음, `org.gradle.java.installations.auto-download=false`). desktop 셸은 Java 17 이상을 받는다. CI 는 Temurin 17 을 준비한다(2026-11-23 부터 실행) | 사용자 결정 2026-11-23: 「Java OpenJDK 17 환경에서 사용할 수 있어야 해.」 그리고 Queen 의 제안에 「1. 진행」. 닫힌 사내망에는 OpenJDK 17 이 있고 JDK 를 내려받을 수 없다([REQUIREMENTS.md](REQUIREMENTS.md) AR-2) | [ADR-025](ADR/ADR-025-java-17-baseline.md) (승인됨) | 없음 |
| 13 | 명세 3절 「Metadata Database: MariaDB, Flyway」, 설계 §1.2·§10 과 사용자 결정 D2(두 모드 모두 MariaDB, 내장 DB 반려) | Metadata 저장소는 두 모드 모두 내장 SQLite 다(`org.xerial:sqlite-jdbc` 3.53.4.0, Flyway core 의 SQLite 지원). 파일은 `<data-dir>/queryhub.db` 이고 데이터 디렉터리는 `QUERYHUB_DATA_DIR`(환경변수, 외부 설정 파일 또는 명령행)다. desktop 셸은 `<앱 로컬 데이터 디렉터리>/data` 를 넘긴다. MariaDB 는 metadata 선택지로 남지 않고 업무 DB 로만 계획이다 | 사용자 결정 2026-11-23: 「Metadata DB는 꼭 필요한가요? SQLLite 등의 내부DB나 설정파일 기반으로 동작할 수 있어야 할 것 같아요.」, 그리고 Queen 의 선택지에 「2. SQLite 내장」, 「3. 권장하는 반식으로 진행」([REQUIREMENTS.md](REQUIREMENTS.md) AR-2). 닫힌 사내망에서 외부 DB 없이 써야 한다 | [ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md) (승인됨, ADR-009 를 대체) | 여러 서버가 저장소 하나를 함께 써야 할 때(P106-1). Mac/aarch64 밖의 네이티브와 Windows·Linux 는 미검증(PLAT-1, CI-1) |
