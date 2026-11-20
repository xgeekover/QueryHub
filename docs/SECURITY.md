# QueryHub 보안

명세 9절의 보안 정책, 지금 구현된 통제, 알려진 빈 곳을 적는다. 결정의 이유는 ADR 에, 후속 과제는 [BACKLOG.md](BACKLOG.md)에 있다. 지금(f84c628)은 **인증이 없다.** 관리자 토큰은 Phase 1, API Key 는 Phase 4 다.

## 명세 9절 정책과 설계 대응

| # | 명세 9절 정책 | 설계 대응 (설계 §7) | 상태 | Phase |
|---|---|---|---|---|
| 1 | 기본적으로 SELECT 전용 SQL만 실행 | 3중 방어: 저장 시 SQL 파서로 단일 SELECT 문인지 검증(파싱 실패는 거부), 실행 시 읽기 전용 트랜잭션, 읽기 전용 DB 계정 권장과 쓰기 권한 경고([ADR-004](ADR/ADR-004-select-only-defense.md)) | 계획 | 2 (파서), 1 (읽기 전용 트랜잭션) |
| 2 | INSERT, UPDATE, DELETE, DDL 기본 차단 | 1 과 같은 3중 방어. DML·DDL·다중문 거부가 Phase 2 통과 기준이다 | 계획 | 2 |
| 3 | 허용된 SQL만 실행하는 Allowlist | 저장되고 활성화된 SQL 만 실행한다. 요청이 SQL 을 보내는 경로가 없다 | 계획 | 2, 3, 4 |
| 4 | 읽기 전용 계정 사용 권장 | 연결 테스트에서 쓰기 권한이 있으면 경고한다(특히 SQL Server, [DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)) | 계획 | 1 |
| 5 | SQL Injection 방지 | 파라미터는 PreparedStatement 바인딩만. 문자열 치환 금지. 인젝션 문자열이 값으로만 바인딩됨을 4개 DB 에서 확인 | 계획 | 2 |
| 6 | API Key 인증 | `X-API-Key` 헤더([API_SPECIFICATION.md](API_SPECIFICATION.md)) | 계획 | 4 |
| 7 | API Key 원문 미저장, Hash 저장 | HMAC-SHA-256 해시 + prefix 저장, 원문은 생성 응답에서 한 번만([ADR-006](ADR/ADR-006-password-aes-gcm-api-key-hmac.md)) | 계획 | 4 |
| 8 | DB 비밀번호 암호화 저장 | 업무 DB 비밀번호는 AES-256-GCM, 마스터 키는 OS 키체인(Desktop) 또는 환경변수(Server)([ADR-006](ADR/ADR-006-password-aes-gcm-api-key-hmac.md)). Metadata DB 비밀번호는 저장하지 않고 환경변수로만 받는다([ADR-011](ADR/ADR-011-metadata-secrets-env-only.md), 구현됨) | 계획 (업무 DB) | 1 |
| 9 | API별 접근 권한 관리 | `api_key_permission` 으로 API 별 권한([ERD.md](ERD.md)) | 계획 | 4, 7 |
| 10 | 요청·응답 로그의 민감정보 마스킹 | 요청·응답 로그에 비밀 값과 SQL 원문을 기본으로 남기지 않는다. 지금 구현된 것: 오류 응답과 기동 메시지에 비밀 값을 넣지 않고, Desktop 셸 출력의 비밀번호를 가린다(아래) | 일부 구현 (P0-B, P0-C) | 7 |
| 11 | Query Timeout | `Statement.setQueryTimeout` | 계획 | 4, 7 |
| 12 | API Timeout | API 별 `timeoutSeconds`, 초과 시 504 `QUERY_TIMEOUT` | 계획 | 4, 7 |
| 13 | 최대 조회 건수 제한 | `Statement.setMaxRows(maxRows + 1)`([ADR-005](ADR/ADR-005-streaming-single-path.md)) | 계획 | 4 |
| 14 | 동시 실행 수 제한 | 동시 실행 한도 초과 시 429 `RATE_LIMITED` | 계획 | 7 |
| 15 | Connection Pool 제한 | 연결마다 `maxPoolSize`([ERD.md](ERD.md)). Metadata 풀은 Boot 기본 크기다 | 계획 | 1 |
| 16 | API 호출 Rate Limit | 속도 한도 초과 시 429 `RATE_LIMITED` | 계획 | 7 |
| 17 | 오류 발생 시 DB 상세 정보 외부 노출 금지 | 오류 응답에 예외 메시지, 클래스 이름, 스택 트레이스, SQL, DB 정보를 넣지 않는다. 상세는 `requestId` 로 서버 로그에서 찾는다 | 구현됨 (P0-A). 데이터 API 의 `QUERY_FAILED` 는 Phase 4 에서 다시 검증 | 0, 4 |

설계 §7 은 여기에 「로컬 런타임의 브라우저 공격면」을 더한다: 관리 API 는 쿠키가 아닌 Bearer 토큰, Host 헤더 허용 목록, 루프백 기본 바인딩과 바인딩 가드. 루프백 기본 바인딩은 구현됐고(아래), 토큰, Host 허용 목록, 바인딩 가드는 계획(Phase 1, [BACKLOG.md](BACKLOG.md) P0A-I1)이다. 바인딩 가드는 desktop 프로파일의 관리 API 가 루프백에서 온 요청만 받게 하는 것이다(`queryhub.admin.allow-remote`, 소켓의 원격 주소로 판정, [API_SPECIFICATION.md](API_SPECIFICATION.md)). 지금 런타임에는 이 가드가 없다(아래 「알려진 빈 곳」).

## 구현된 통제

| 통제 | 내용 | 코드 |
|---|---|---|
| 루프백 기본 바인딩과 모드 판정 | 공통 기본값과 desktop 은 `127.0.0.1`, 모든 인터페이스는 `server` 프로파일만. 프로파일이 정확히 하나가 아니면 포트를 열기 전에 실패 | `runtime/app/src/main/resources/application.yml`, `application-desktop.yml`, `application-server.yml`, `runtime/app/src/main/java/com/queryhub/app/config/RuntimeModeResolver.java` |
| actuator 노출 제한 | `health` 만 공개, `show-details: never`. 나머지 actuator 는 404 | `application.yml` 의 `management.endpoints.web.exposure.include` |
| 내부 정보 없는 오류 응답 | 공통 오류 형식, Boot 기본 오류 속성 `include-stacktrace: never` 등 | `runtime/adapter-web/src/main/java/com/queryhub/adapter/web/common/error/GlobalExceptionHandler.java`, `QueryHubErrorController.java`, `application.yml` 의 `spring.web.error` |
| Metadata 비밀 값 가드 | 아래 「Metadata DB 비밀 값 규칙」([ADR-011](ADR/ADR-011-metadata-secrets-env-only.md)) | `runtime/app/src/main/java/com/queryhub/app/config/metadata/MetadataDatabaseAutoConfiguration.java` |
| Flyway 연결 고정 | 마이그레이션은 검사한 메인 풀로만([ADR-012](ADR/ADR-012-flyway-connection-pin.md)) | `MetadataFlywayConnectionDetails.java` |
| LOCAL INFILE 차단과 드라이버 버전 | URL 에 `allowLocalInfile=false`, Connector/J 3.5.11(GHSA-fhx5-2xqw-2cgm, [ADR-018](ADR/ADR-018-mariadb-connector-j-override.md)) | `MetadataDatabaseProperties.java` 의 `URL_OPTIONS`, `runtime/gradle/libs.versions.toml` |
| 비밀 값은 환경변수로만 | 셸은 Metadata DB 변수를 자식 런타임에 환경변수로만 넘기고 명령행 인자에는 넣지 않는다 | `desktop/src-tauri/src/runtime/launch.rs` 의 `child_env`, `child_args` |
| 자식 환경 허용 목록 | 아래 「Desktop 셸의 자식 환경」([ADR-013](ADR/ADR-013-desktop-child-env-allowlist.md)) | `launch.rs` 의 `INHERITED_NAMES`, `INHERITED_PREFIX`, `child_env` |
| 출력 가림 | 셸이 보관하는 출력(200줄)과 실패 카드의 마지막 40줄, 디버그 stderr 에서 출력에 그대로 나온 비밀번호 값을 `********` 로 바꾼다. 인코딩되거나 변형된 형태(URL 인코딩, JSON 이스케이프 등)는 가리지 않는다 | `desktop/src-tauri/src/runtime/output.rs` 의 `mask_secret`, `MASK`, `supervisor.rs` 의 `output_lines`, `status_tail_lines` |
| 관리 API CORS 범위 | desktop 프로파일에서만, Tauri 출처 두 개, GET 만, 자격 증명 없음. 출처 값이 잘못되면 기동 실패. 규칙 전체는 [API_SPECIFICATION.md](API_SPECIFICATION.md)([ADR-015](ADR/ADR-015-desktop-fixed-port-and-cors.md)) | `runtime/adapter-web/src/main/java/com/queryhub/adapter/web/admin/cors/AdminCorsConfiguration.java`, `AdminCorsProperties.java` |
| WebView CSP | 운영 CSP 는 `connect-src 'self' ipc: http://ipc.localhost http://127.0.0.1:18580` 이고 `'unsafe-eval'` 이 없다. `frame-src 'none'`, `object-src 'none'`, `form-action 'none'` | `desktop/src-tauri/tauri.conf.json` 의 `app.security.csp` |
| Tauri 커맨드 권한 | `build.rs` 의 앱 매니페스트가 커맨드 네 개를 등록하고, main 창에 `allow-runtime-status`, `allow-runtime-start`, `allow-runtime-stop`, `allow-runtime-restart` 만 준다(core 권한, 플러그인 권한 없음) | `desktop/src-tauri/capabilities/default.json`, `desktop/src-tauri/build.rs` |
| 테스트 JVM 격리 | 테스트 JVM 은 셸의 `QUERYHUB_*` 환경변수를 물려받지 않는다 | `runtime/build.gradle` |
| 개발용 DB | 포트는 `127.0.0.1` 에만 열고, root 비밀번호는 무작위다. 비밀 값은 git 이 무시하는 `deploy/.env` 에 둔다([DEPLOYMENT.md](DEPLOYMENT.md)) | `deploy/compose.dev.yml` |

### Metadata DB 비밀 값 규칙 (구현됨, P0-B)

접속 정보는 환경변수 `QUERYHUB_METADATA_DB_*` 로만 받는다. 변수 표는 [DEPLOYMENT.md](DEPLOYMENT.md)에 있다.

- **비밀번호는 명령행 인자(`--queryhub.metadata.db.password=...`)나 JVM 시스템 속성(`-D...`)으로 넘기지 않는다.** 프로세스 목록에 보이기 때문이다. 그렇게 넘기면 런타임은 기동을 거부하고 `QUERYHUB_METADATA_DB_PASSWORD` 를 쓰라고 안내한다(값은 출력하지 않는다).
- 명령행 인자나 `-D` 로 넘긴 `spring.application.json`(`SPRING_APPLICATION_JSON`) 안의 비밀번호도 같이 거부한다. OS 환경변수 `SPRING_APPLICATION_JSON` 은 허용한다.
- 비밀 값이나 드라이버 옵션을 담는 `spring.datasource.hikari.password`, `spring.datasource.hikari.jdbc-url`, `spring.datasource.hikari.data-source-properties.*` 도 명령행 인자, `-D`, 그 두 곳의 `spring.application.json` 으로 넘기면 거부한다. 메시지는 키 이름만 말하고 `QUERYHUB_METADATA_DB_*` 를 쓰라고 안내한다. Spring Boot 4.1.2 는 바인딩 뒤에 Hikari 의 `jdbc-url`·`password` 를 검사한 접속 정보로 되돌리므로 이 둘은 실제 접속에 쓰이지 않지만, 값이 프로세스 목록에 보이므로 방어적으로 거부한다. 그 밖의 `spring.datasource.hikari.*` 설정(풀 크기 등)은 운영자 설정이다.
- Flyway 는 항상 검사한 접속 정보로 만든 연결 풀로 마이그레이션한다. `spring.flyway.url`, `spring.flyway.user`, `spring.flyway.password` 는 어디서 넘겨도(명령행, `-D`, `spring.application.json`, 환경변수, 설정 파일) 접속에 쓰이지 않는다. 마이그레이션 사용자는 `QUERYHUB_METADATA_DB_USERNAME` 이다.
- 기동 실패 메시지는 문제가 있는 변수 이름만 말하고 비밀번호 값을 넣지 않는다.

### Desktop 셸의 자식 환경 (구현됨, P0-C)

- 셸은 `java -version` 확인과 런타임을 빈 환경에서 띄우고, 자기 환경에서 허용 목록의 변수만 물려준다: `PATH`, `HOME`, `USER`, `LOGNAME`, `LANG`, `LC_*`, `TZ`, `TMPDIR`. Windows 는 `PATH`, `SYSTEMROOT`, `WINDIR`, `TEMP`, `TMP`, `USERPROFILE`, `APPDATA`, `LOCALAPPDATA`, `PATHEXT`, `COMPUTERNAME` 이고 대소문자를 무시한다(미검증).
- `java -version` 은 이 허용 목록의 변수만 받는다(`launch.rs` 의 `inherited_env`). Metadata DB 변수(비밀번호 포함)와 `QUERYHUB_DESKTOP_PARENT_PID` 는 런타임 자식의 환경에만 더한다(`child_env`).
- 목적: Spring Boot 는 OS 환경변수를 `application-desktop.yml` 보다 앞세우므로, 셸의 환경(`SERVER_ADDRESS`, `MANAGEMENT_*`, `LOGGING_*`, `DEBUG`, `SPRING_*`, `JAVA_TOOL_OPTIONS` 같은 JVM 옵션 변수 등)이 런타임의 바인딩 주소, 엔드포인트 노출, 로깅, 프로파일을 바꾸지 못하게 한다. 허용 목록의 이름은 Spring 속성에 바인딩되지 않는다. 이것이 P0-C 리뷰 1회차 지적 R1 의 수정이다.
- 셸은 환경을 로그에 남기지 않고 로그 파일을 쓰지 않는다.
- `java -version` 사전 점검의 환경은 단위 비교와 코드 읽기로만 확인했다([BACKLOG.md](BACKLOG.md) P0C-C2-2, [TEST_PLAN.md](TEST_PLAN.md)).

## 알려진 빈 곳

| 내용 | BACKLOG | 목표 |
|---|---|---|
| `GET /admin/api/v1/runtime` 에 인증이 없다. server 프로파일에서는 0.0.0.0 에서 열리고, desktop 에서는 Host 허용 목록이 없어 DNS 리바인딩으로 읽힐 수 있다. 응답은 민감하지 않은 런타임 정보뿐이다 | P0A-I1 | Phase 1 |
| 관리자 토큰이 생길 때까지 이 머신의 모든 Tauri 앱이 같은 출처 `tauri://localhost` 로 관리 API 의 GET 응답을 읽을 수 있다. Phase 0 의 관리 API 에는 민감한 정보가 없다 | P0A-I1 | Phase 1 |
| server 프로파일은 모든 인터페이스에 바인딩하고 인증이 없으므로 신뢰할 수 있는 네트워크에서만 실행한다 | P0A-I1 | Phase 1 |
| 바인딩 가드가 없다. JAR 을 desktop 프로파일로 직접 실행하면서 바인딩 주소를 넓히면(예: `--server.address=0.0.0.0`) 관리 API 도 그 주소에서 인증 없이 열린다. 셸이 띄운 런타임은 주소 인자를 받지 않고 `SERVER_ADDRESS` 도 물려받지 않는다(「Desktop 셸의 자식 환경」). 계획된 가드는 desktop 관리 API 의 루프백 전용(`queryhub.admin.allow-remote`, 소켓의 원격 주소로 판정)이다 | P0A-I1 | Phase 1 |
| `spring.datasource.hikari.catalog` 와 `connection-init-sql` 을 프로세스에 보이는 출처에서 막을지 정하지 않았다. catalog 는 연결 DB 를, connection-init-sql 은 UTC 세션 설정을 바꿀 수 있다 | P0B-H1 | Phase 1 |
| `spring.autoconfigure.exclude` 로 `MetadataDatabaseAutoConfiguration` 을 빼면 가드, 검사한 연결 정보, Flyway 고정이 함께 사라진다 | P0B-R14 | Phase 1 |
| `spring.flyway.url`·`user`·`password` 와 `spring.datasource.url`·`password` 를 명령행이나 `-D` 로 주면 무시되지만 거부도 경고도 없다. 값은 프로세스 목록에 보인다 | P0B-R15 | Phase 1 |
| Metadata DB 연결이 평문이다. 원격 DB 용 TLS 는 계획이다([DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)) | — | Phase 1 또는 6 |
| Testcontainers 가 테스트용 MariaDB 포트를 모든 호스트 인터페이스에 연다(테스트가 도는 약 20 초) | P0B-R6 | P0-D |
| `.gitignore` 의 `*.key`, `*.p12`, `*.pem` 이 경로 고정 없이 걸려 정상 파일도 커밋에서 말없이 빠진다 | P0A-N4 | Phase 1 |
| `desktop/pnpm-workspace.yaml` 의 `minimumReleaseAgeExclude` 에 `lucide-react@1.74.0` 예외가 남아 있다 | P0C-R5 | Phase 6 |
| `JAVA_HOME` 은 java 를 고르는 데만 쓰고 자식에게 넘기지 않는다. 고른 java 가 macOS 의 `/usr/bin/java` 스텁이면 기본 JDK 가 뜬다 | P0C-C2-3 | Phase 8 |
| 거부된 CORS 요청은 공통 오류 형식이 아니라 Spring 의 403 `Invalid CORS request` 다 | — | Phase 1 |

## 운영 지표의 보안 제약 (계획)

[REQUIREMENTS.md](REQUIREMENTS.md) AR-1 의 OM-6, OM-7 이다. 화면은 Phase 6, 수집과 엔드포인트는 Phase 7 이다.

- **OM-6:** 지표는 런타임 안에서 Micrometer 미터를 읽어 관리 API 엔드포인트 하나로만 내보낸다. 관리자 토큰(Phase 1)을 요구한다. actuator 의 `metrics`, `prometheus` 엔드포인트는 열지 않고 노출은 `health` 하나로 둔다(`application.yml`). actuator 를 외부로 여는 것은 P0-C 리뷰 지적 R1 에서 막은 경로다([ADR-013](ADR/ADR-013-desktop-child-env-allowlist.md)).
- **OM-7:** 지표 이름, 태그, 로그 창에 SQL 원문, 파라미터 값, 비밀 값, API Key 를 넣지 않는다. 태그 값의 종류 수는 API ID 와 풀 이름처럼 개수가 정해진 것으로 제한한다. 로그 창은 계속 가린 채 보이고 Phase 7 의 마스킹 정책(위 표 10번)을 따른다.
