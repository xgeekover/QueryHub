# QueryHub 보안

명세 9절의 보안 정책, 지금 구현된 통제, 알려진 빈 곳을 적는다. 결정의 이유는 ADR 에, 후속 과제는 [BACKLOG.md](BACKLOG.md)에 있다. P1-03(2698115 (2026-11-22))부터 관리 API 와 health 밖의 actuator 는 관리자 토큰(Bearer)을 요구한다(아래 「관리자 토큰」). 데이터 API 의 API Key 는 Phase 4 다.

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
| 8 | DB 비밀번호 암호화 저장 | 업무 DB 비밀번호는 AES-256-GCM, 마스터 키는 셸이 만드는 키 파일(Desktop, 설계의 OS 키체인 대신, [ADR-023](ADR/ADR-023-master-key-delivery.md)) 또는 환경변수·시크릿 파일(Server)([ADR-006](ADR/ADR-006-password-aes-gcm-api-key-hmac.md), 형식은 [ADR-024](ADR/ADR-024-secret-cipher-format-and-rotation.md)). 키와 암호는 P1-04 에서 구현했고, 비밀번호 저장은 연결 저장 태스크다. Metadata DB 비밀번호는 저장하지 않고 환경변수로만 받는다([ADR-011](ADR/ADR-011-metadata-secrets-env-only.md), 구현됨) | 일부 구현 (P1-04: 마스터 키와 암호. 업무 DB 비밀번호 저장은 계획) | 1 |
| 9 | API별 접근 권한 관리 | `api_key_permission` 으로 API 별 권한([ERD.md](ERD.md)) | 계획 | 4, 7 |
| 10 | 요청·응답 로그의 민감정보 마스킹 | 요청·응답 로그에 비밀 값과 SQL 원문을 기본으로 남기지 않는다. 지금 구현된 것: 오류 응답과 기동 메시지에 비밀 값을 넣지 않고, Desktop 셸 출력의 비밀번호, 관리자 토큰, 마스터 키를 가린다(아래) | 일부 구현 (P0-B, P0-C, P1-03, P1-04) | 7 |
| 11 | Query Timeout | `Statement.setQueryTimeout` | 계획 | 4, 7 |
| 12 | API Timeout | API 별 `timeoutSeconds`, 초과 시 504 `QUERY_TIMEOUT` | 계획 | 4, 7 |
| 13 | 최대 조회 건수 제한 | `Statement.setMaxRows(maxRows + 1)`([ADR-005](ADR/ADR-005-streaming-single-path.md)) | 계획 | 4 |
| 14 | 동시 실행 수 제한 | 동시 실행 한도 초과 시 429 `RATE_LIMITED` | 계획 | 7 |
| 15 | Connection Pool 제한 | 연결마다 `maxPoolSize`([ERD.md](ERD.md)). Metadata 풀은 Boot 기본 크기다 | 계획 | 1 |
| 16 | API 호출 Rate Limit | 속도 한도 초과 시 429 `RATE_LIMITED` | 계획 | 7 |
| 17 | 오류 발생 시 DB 상세 정보 외부 노출 금지 | 오류 응답에 예외 메시지, 클래스 이름, 스택 트레이스, SQL, DB 정보를 넣지 않는다. 상세는 `requestId` 로 서버 로그에서 찾는다 | 구현됨 (P0-A). 데이터 API 의 `QUERY_FAILED` 는 Phase 4 에서 다시 검증 | 0, 4 |

설계 §7 은 여기에 「로컬 런타임의 브라우저 공격면」을 더한다: 관리 API 는 쿠키가 아닌 Bearer 토큰, Host 헤더 허용 목록, 루프백 기본 바인딩과 바인딩 가드. 루프백 기본 바인딩은 P0-A 에서, Bearer 토큰, Host 허용 목록, desktop 관리 API 의 루프백 전용(`queryhub.admin.allow-remote`, 소켓의 원격 주소로 판정)은 P1-03 에서 구현됐다([ADR-021](ADR/ADR-021-admin-token-bearer.md)). 이전 문서는 이 루프백 전용 판정을 바인딩 가드라고 불렀다. 넓은 주소로의 바인딩 자체를 거부하는 바인딩 가드는 아직 없다(아래 「알려진 빈 곳」, [BACKLOG.md](BACKLOG.md) P103-1).

## 구현된 통제

| 통제 | 내용 | 코드 |
|---|---|---|
| 루프백 기본 바인딩과 모드 판정 | 공통 기본값과 desktop 은 `127.0.0.1`, 모든 인터페이스는 `server` 프로파일만. 프로파일이 정확히 하나가 아니면 포트를 열기 전에 실패 | `runtime/app/src/main/resources/application.yml`, `application-desktop.yml`, `application-server.yml`, `runtime/app/src/main/java/com/queryhub/app/config/RuntimeModeResolver.java` |
| actuator 노출 제한 | `health` 만 공개, `show-details: never`. 나머지 actuator 는 404 | `application.yml` 의 `management.endpoints.web.exposure.include` |
| 내부 정보 없는 오류 응답 | 공통 오류 형식, Boot 기본 오류 속성 `include-stacktrace: never` 등 | `runtime/adapter-web/src/main/java/com/queryhub/adapter/web/common/error/GlobalExceptionHandler.java`, `QueryHubErrorController.java`, `application.yml` 의 `spring.web.error` |
| Metadata 비밀 값 가드 | 아래 「Metadata DB 비밀 값 규칙」([ADR-011](ADR/ADR-011-metadata-secrets-env-only.md)) | `runtime/app/src/main/java/com/queryhub/app/config/metadata/MetadataDatabaseAutoConfiguration.java` |
| Flyway 연결 고정 | 마이그레이션은 검사한 메인 풀로만([ADR-012](ADR/ADR-012-flyway-connection-pin.md)) | `MetadataFlywayConnectionDetails.java` |
| LOCAL INFILE 차단과 드라이버 버전 | URL 에 `allowLocalInfile=false`, Connector/J 3.5.11(GHSA-fhx5-2xqw-2cgm, [ADR-018](ADR/ADR-018-mariadb-connector-j-override.md)) | `MetadataDatabaseProperties.java` 의 `URL_OPTIONS`, `runtime/gradle/libs.versions.toml` |
| 비밀 값은 환경변수로만 | 셸은 Metadata DB 변수와 관리자 토큰(`QUERYHUB_ADMIN_TOKEN`)을 자식 런타임에 환경변수로만 넘기고 명령행 인자에는 넣지 않는다. 런타임은 프로세스에 보이는 출처(명령행, `-D`, 그 두 곳의 `spring.application.json`)의 토큰과 Metadata DB 비밀 값을 거부한다 | `desktop/src-tauri/src/runtime/launch.rs` 의 `child_env`, `child_args`, `runtime/app/src/main/java/com/queryhub/app/config/ProcessVisibleSources.java` |
| 자식 환경 허용 목록 | 아래 「Desktop 셸의 자식 환경」([ADR-013](ADR/ADR-013-desktop-child-env-allowlist.md)) | `launch.rs` 의 `INHERITED_NAMES`, `INHERITED_PREFIX`, `child_env` |
| 출력 가림 | 셸이 보관하는 출력(200줄)과 실패 카드의 마지막 40줄, 디버그 stderr 에서 출력에 그대로 나온 비밀번호와 관리자 토큰 값을 `********` 로 바꾼다(여러 번의 읽기에 나뉘어 와도 줄 단위로 모아 가린다). 인코딩되거나 변형된 형태(URL 인코딩, JSON 이스케이프 등)는 가리지 않는다 | `desktop/src-tauri/src/runtime/output.rs` 의 `mask_secrets`, `MASK`, `supervisor.rs` 의 `output_lines`, `status_tail_lines` |
| 관리 API CORS 범위 | desktop 프로파일에서만, Tauri 출처 두 개, GET 과 요청 헤더 `Authorization` 만, 자격 증명 없음, P1-03 부터 관리 보안 체인 안. 출처 값이 잘못되면 기동 실패. 규칙 전체는 [API_SPECIFICATION.md](API_SPECIFICATION.md)([ADR-015](ADR/ADR-015-desktop-fixed-port-and-cors.md)) | `runtime/adapter-web/src/main/java/com/queryhub/adapter/web/admin/cors/AdminCorsConfiguration.java`, `AdminCorsProperties.java` |
| 관리자 토큰 | 아래 「관리자 토큰」([ADR-021](ADR/ADR-021-admin-token-bearer.md), [ADR-022](ADR/ADR-022-admin-token-implementation.md)) | `runtime/adapter-web/src/main/java/com/queryhub/adapter/web/admin/security/`, `runtime/app/src/main/java/com/queryhub/app/config/admin/`, `desktop/src-tauri/src/runtime/token.rs`, `desktop/src/runtime/adminApi.ts` |
| desktop 관리 API 의 루프백 전용과 Host 허용 목록 | 아래 「관리자 토큰」 | `AdminNetworkGuardFilter.java`, `LoopbackAddresses.java`, `HostHeaderPolicy.java` |
| WebView CSP | 운영 CSP 는 `connect-src 'self' ipc: http://ipc.localhost http://127.0.0.1:18580` 이고 `'unsafe-eval'` 이 없다. `frame-src 'none'`, `object-src 'none'`, `form-action 'none'` | `desktop/src-tauri/tauri.conf.json` 의 `app.security.csp` |
| Tauri 커맨드 권한 | `build.rs` 의 앱 매니페스트가 커맨드 다섯 개를 등록하고, main 창(로컬 출처, `remote` 없음)에 `allow-runtime-status`, `allow-runtime-start`, `allow-runtime-stop`, `allow-runtime-restart`, `allow-admin-token` 만 준다(core 권한, 플러그인 권한 없음). cargo 시험이 매니페스트, `generate_handler!`, capability 파일이 같은지 확인한다 | `desktop/src-tauri/capabilities/default.json`, `desktop/src-tauri/build.rs` |
| 테스트 JVM 격리 | 테스트 JVM 은 셸의 `QUERYHUB_*` 환경변수를 물려받지 않는다 | `runtime/build.gradle` |
| 개발용 DB | 포트는 `127.0.0.1` 에만 열고, root 비밀번호는 무작위다. 비밀 값은 git 이 무시하는 `deploy/.env` 에 둔다([DEPLOYMENT.md](DEPLOYMENT.md)) | `deploy/compose.dev.yml` |

### 관리자 토큰 (구현됨, P1-03, 2698115)

[BACKLOG.md](BACKLOG.md) P0A-I1 을 닫는다. 요청과 응답의 규칙은 [API_SPECIFICATION.md](API_SPECIFICATION.md)의 「관리자 토큰 인증」, 설정은 [DEPLOYMENT.md](DEPLOYMENT.md)의 「관리자 토큰」에 있다.

- **범위:** `/admin/**`(대소문자 무관)와 health 세 경로 밖의 `/actuator/**` 는 `Authorization: Bearer <토큰>` 을 요구한다. 쿠키와 세션은 없다. health 세 경로와 데이터 API 는 토큰을 보지 않는다.
- **비교:** 받은 값과 설정 값의 SHA-256 다이제스트를 `MessageDigest.isEqual` 로 비교한다(ArchUnit R9). 없는 토큰과 틀린 토큰의 401 응답은 `requestId` 말고 같다.
- **실패 닫힘:** 토큰이 없거나, `QUERYHUB_ADMIN_TOKEN` 과 `QUERYHUB_ADMIN_TOKEN_FILE` 이 둘 다 있거나, 파일이나 형식이 틀리거나, 프로세스에 보이는 출처(명령행, `-D`, 그 두 곳의 `spring.application.json`)에서 오면 포트를 열기 전에 기동을 거부한다. 메시지는 이름만 말한다.
- **desktop 의 네트워크 검사:** 관리 요청은 소켓 원격 주소가 루프백(127.0.0.0/8, `::1`, `::ffff:127.0.0.0/104`, DNS 없이 판정)이고 `Host` 가 허용 목록(`localhost`, `127.0.0.1`, `[::1]`, `queryhub.admin.allowed-hosts`)에 있을 때만 받는다. 아니면 403 이고, 형식이 깨진 Host 일부는 Tomcat 의 평문 400 이다(P103-4). `allow-remote=false` 이면 소켓 원격 주소를 바꾸는 설정을 기동에서 거부한다: `server.forward-headers-strategy` 가 `none` 이 아니거나, `server.tomcat.remoteip.*` 속성 중 하나라도 값이 있거나, 만들어진 Tomcat 파이프라인에 RemoteIpValve 나 허용 목록 밖의 valve 가 있으면 포트를 열기 전에 멈춘다. 이 조건에서 `X-Forwarded-For`, `X-Forwarded-Proto`, `Forwarded` 는 루프백 판정에 쓰이지 않는다. 보안 필터 앞의 서블릿 필터는 허용 목록으로 검사하고 `ForwardedHeaderFilter` 는 `allow-remote=true` 일 때만 받는다(P1-03 3회차, 아래 「관리 체인의 기동 검사」). server 프로파일은 원격 요청을 받고 `allowed-hosts` 를 설정할 때만 Host 를 검사한다.
- **desktop 셸:** 셸 프로세스마다 한 번 OS 난수 생성기(`getrandom`)로 256 비트를 받아 64 자 16진수 토큰을 만든다. 만들지 못하면 setup 이 실패하고 런타임을 띄우지 않는다. 런타임에는 자식 환경변수로만 넘기고(인자와 `java -version` 의 환경에는 없다), UI 에는 Tauri 커맨드 `admin_token`(main 창의 로컬 출처만)으로 넘긴다. 셸 자신의 환경에 있는 토큰 변수는 넘기지 않는다. `AdminToken` 의 Debug·Display, `RuntimeStatus`, `Failure` 에는 값이 없고, 자식 출력의 토큰은 가린다.
- **UI:** 토큰을 메모리에만 두고 `Authorization` 헤더로만 보낸다. localStorage, sessionStorage, 화면, 로그에 남기지 않는다(vitest 가 저장소 호출과 화면을 확인한다). 토큰이 없으면 요청하지 않는다. 401 과 403 은 토큰을 담지 않은 고정 문장이다.
- **actuator 경로의 전제:** 관리 체인은 `/actuator` 접두사와 기본 서블릿 경로만 덮는다. `management.endpoints.web.base-path` 가 `/actuator` 가 아니거나, `management.server.port` 로 actuator 를 다른 포트에 열거나, `spring.mvc.servlet.path` 가 `/` 가 아니면 두 프로파일 모두 기동을 거부한다. P1-03 2회차에 1회차 JAR 이 `spring.mvc.servlet.path=/qh` 에서 관리 API 를 토큰 없이 답한 것을 확인하고 막았다.
- **관리 체인의 기동 검사 (P1-03 3회차):** 2회차 리뷰가 같은 종류의 우회 둘(`spring.security.filter.dispatcher-types=error` 에서 토큰 없이 200, `management.endpoints.web.path-mapping.*` 로 env 를 `/actuator/health` 로 옮겨 토큰 값까지 노출)을 재현했다. 그래서 세 겹으로 검사한다. (1) 속성: 두 프로파일 모두 dispatcher-types 가 Boot 기본 다섯 가지를 다 갖지 않을 때, `management.endpoints.web.path-mapping.*` 에 값이 있을 때, `management.endpoint.health.probes.add-additional-paths=true` 나 `management.endpoint.health.group.*.additional-path` 가 있을 때, `server.tomcat.accesslog.pattern` 에 `Authorization` 이 들어가거나 `spring.mvc.log-request-details=true` 일 때 기동을 거부한다. (2) 필터 매핑: 포트를 열기 전에 `springSecurityFilterChain` 이 `/*` 와 dispatcher 다섯 가지로 매핑된 DelegatingFilterProxy 이고(4회차) 그 앞에 허용 목록 밖의 필터가 없는지 본다. (3) 자기 점검: 포트를 연 직후, readiness 가 ACCEPTING_TRAFFIC 이 되기 전에 런타임이 자기 포트로 토큰 없는 요청을 보낸다. `/admin/api/v1/runtime`, `/actuator/env`, `/actuator/beans` 가 체인의 401(Bearer)이나 403 이 아니거나, health 세 경로가 health 문서나 404 가 아니면 포트를 닫고 0 이 아닌 코드로 끝난다. 점검은 토큰을 보내지도 적지도 않는다. 4회차: 응답은 JSON 으로 읽으므로 들여쓰기 같은 출력 설정과 상관없다. 실패 이유(경로와 상태)는 먼저 ERROR 로그에 남고, 컨텍스트 정리가 멈춰도(메타데이터 DB 가 멈춘 경우) 15 초 뒤 종료 코드 1 로 강제 종료한다. health 경로가 5 초 안에 답하지 않으면 시간 초과로 따로 말한다. 한계: 점검하는 동안(대개 수 ms ~ 수십 ms) 포트는 열려 있고, 점검은 여섯 경로만 본다. 내장 TLS(`server.ssl.*`, SSL 번들 포함)는 속성 검사가 포트를 열기 전에 거부하고(4회차), 다른 길로 TLS 커넥터가 생기면 점검이 거부한다. TLS 는 앞단 리버스 프록시에서 끝낸다. 사용자 결정 「받아들임 (권장)」(2026-11-22): "TLS는 앞단 프록시에서 처리하는 것을 전제로 합니다. 거부 시점을 포트가 열리기 전으로 당기고, 배포 문서에 명시합니다. 런타임 자체 HTTPS 지원은 BACKLOG에 남겨 Phase 8(배포)에서 다시 봅니다." 런타임 자체 HTTPS 는 [BACKLOG.md](BACKLOG.md) P103-7 이다. 속성 목록과 판정은 Boot 4.1.2 의 설정 메타데이터를 기준으로 정리했다(P1-03 3회차 개발 보고의 점검표).
- **접근 로그:** 접근 로그는 `Authorization` 헤더를 적으면 안 된다. 런타임은 `server.tomcat.accesslog.pattern` 에 `Authorization` 이 들어가면 기동을 거부한다. `spring.mvc.log-request-details=true`(DispatcherServlet 의 TRACE 로그가 헤더 값을 적는다)도 거부한다. 로그 수준을 DEBUG·TRACE 로 올리면 Tomcat(`org.apache.coyote.http11`)처럼 받은 요청을 그대로 적는 로거가 있으므로 운영 환경의 웹 계층 로그 수준은 INFO 이상으로 둔다. 리버스 프록시 같은 런타임 밖의 로그는 운영자가 따로 확인해야 한다.
- **행동 변화:** StrictHttpFirewall 이 `;`, `//`, `/./` 같은 경로를 400 으로 거부하고, 관리 체인 경로의 퍼센트 인코딩은 모두 400 이다. 일부 깨진 경로와 형식이 깨진 Host 는 Tomcat 이 애플리케이션보다 먼저 평문 400 으로 거부한다(공통 오류 형식과 `X-Request-Id` 없음, 애플리케이션 코드 실행 없음). `/actuator` 탐색과 health 하위 경로는 더는 제공하지 않는다. 거부된 CORS 요청은 여전히 Spring 의 403 본문이다.
- **검증 범위:** macOS 에서 runtime 시험, 통합 시험, cargo test, vitest 로 확인했다. Windows·Linux 의 셸은 미검증이다(PLAT-1). CI 는 정의됨·미실행이다(CI-1).

### 마스터 키 (구현됨, P1-04, 037385b (2026-11-23))

업무 DB 비밀번호를 암호화하는 256 비트 키다([ADR-023](ADR/ADR-023-master-key-delivery.md)). 형식과 교체는 [ADR-024](ADR/ADR-024-secret-cipher-format-and-rotation.md)에 있다. 비밀번호를 저장하는 기능은 아직 없다(연결 저장은 다음 태스크다).

- 두 프로파일 모두 키가 없거나 틀리면 포트를 열기 전에 기동을 거부한다. server 는 `QUERYHUB_MASTER_KEY` 나 `QUERYHUB_MASTER_KEY_FILE` 중 하나로 받는다. 명령행 인자, `-D`, 그 두 곳의 `spring.application.json` 에서 온 키는 거부한다. 관리자 토큰과 같은 값과 바이트가 모두 같은 키도 거부한다. 거부 메시지는 변수와 속성 이름만 말한다.
- 키는 로그, 오류 응답, 예외 메시지, `toString`, actuator 출력에 나오지 않는다(`MasterKeyLeakTest`, `MasterKeySourceTest`, `AesGcmSecretCipherTest`, `MasterKeyTest`). 기동 로그에는 키 id(kid)만 남는다.
- desktop 셸은 키 파일을 만들고 읽어 런타임 자식의 환경변수로만 넘긴다. argv, `java -version` 의 환경, 상태 JSON, WebView 에는 넣지 않고, 자식 출력에서는 가린다.
- **키체인과 비교한 위협 모델.** 설계는 desktop 의 키를 OS 키체인에 두려 했다. 사용자가 2026-11-23 에 지금은 키체인을 쓰지 않기로 했다. 그래서 desktop 의 키는 키체인보다 약하게 보호된다.
  - 키 파일을 지키는 것은 파일 권한뿐이다. unix 에서 셸은 디렉터리 0700, 파일 0600 과 소유자를 확인하고, 맞지 않으면 거부한다. 있는 파일의 권한을 저절로 바꾸지는 않는다.
  - Windows 는 `%LOCALAPPDATA%` 사용자 프로필의 기본 ACL 에만 기댄다. 셸은 권한과 소유자를 확인하지 않는다(미검증, PLAT-1).
  - 같은 사용자로 도는 모든 프로세스, 관리자와 root 는 키 파일을 읽을 수 있다. 실행 중에는 런타임 자식의 환경변수에서도 읽을 수 있다(macOS `ps -E`, Linux `/proc/<pid>/environ`. 관리자 토큰과 같다).
  - 데이터 디렉터리의 백업(예: Time Machine)과 복사본에는 키 파일이 들어 있다. 같은 백업에 Metadata DB 덤프도 있으면 암호화한 비밀번호를 풀 수 있다. 키 파일과 Metadata DB 백업은 따로 보관한다([DEPLOYMENT.md](DEPLOYMENT.md) 「마스터 키」).
  - 키체인처럼 앱마다 접근 동의를 받는 단계가 없다.
- 런타임 JVM 의 Environment 에 키 문자열이 남으므로 힙 덤프에는 키가 들어 있다. actuator 는 health 만 노출하고, 관리 체인이 `/actuator/**` 를 지킨다.

### Metadata DB 비밀 값 규칙 (구현됨, P0-B)

접속 정보는 환경변수 `QUERYHUB_METADATA_DB_*` 로만 받는다. 변수 표는 [DEPLOYMENT.md](DEPLOYMENT.md)에 있다.

- **비밀번호는 명령행 인자(`--queryhub.metadata.db.password=...`)나 JVM 시스템 속성(`-D...`)으로 넘기지 않는다.** 프로세스 목록에 보이기 때문이다. 그렇게 넘기면 런타임은 기동을 거부하고 `QUERYHUB_METADATA_DB_PASSWORD` 를 쓰라고 안내한다(값은 출력하지 않는다).
- 명령행 인자나 `-D` 로 넘긴 `spring.application.json`(`SPRING_APPLICATION_JSON`) 안의 비밀번호도 같이 거부한다. OS 환경변수 `SPRING_APPLICATION_JSON` 은 허용한다.
- 비밀 값이나 드라이버 옵션을 담는 `spring.datasource.hikari.password`, `spring.datasource.hikari.jdbc-url`, `spring.datasource.hikari.data-source-properties.*` 도 명령행 인자, `-D`, 그 두 곳의 `spring.application.json` 으로 넘기면 거부한다. 메시지는 키 이름만 말하고 `QUERYHUB_METADATA_DB_*` 를 쓰라고 안내한다. Spring Boot 4.1.2 는 바인딩 뒤에 Hikari 의 `jdbc-url`·`password` 를 검사한 접속 정보로 되돌리므로 이 둘은 실제 접속에 쓰이지 않지만, 값이 프로세스 목록에 보이므로 방어적으로 거부한다. 그 밖의 `spring.datasource.hikari.*` 설정(풀 크기 등)은 운영자 설정이다.
- Flyway 는 항상 검사한 접속 정보로 만든 연결 풀로 마이그레이션한다. `spring.flyway.url`, `spring.flyway.user`, `spring.flyway.password` 는 어디서 넘겨도(명령행, `-D`, `spring.application.json`, 환경변수, 설정 파일) 접속에 쓰이지 않는다. 마이그레이션 사용자는 `QUERYHUB_METADATA_DB_USERNAME` 이다.
- 기동 실패 메시지는 문제가 있는 변수 이름만 말하고 비밀번호 값을 넣지 않는다.

### Desktop 셸의 자식 환경 (구현됨, P0-C)

- 셸은 `java -version` 확인과 런타임을 빈 환경에서 띄우고, 자기 환경에서 허용 목록의 변수만 물려준다: `PATH`, `HOME`, `USER`, `LOGNAME`, `LANG`, `LC_*`, `TZ`, `TMPDIR`. Windows 는 `PATH`, `SYSTEMROOT`, `WINDIR`, `TEMP`, `TMP`, `USERPROFILE`, `APPDATA`, `LOCALAPPDATA`, `PATHEXT`, `COMPUTERNAME` 이고 대소문자를 무시한다(미검증).
- `java -version` 은 이 허용 목록의 변수만 받는다(`launch.rs` 의 `inherited_env`). Metadata DB 변수(비밀번호 포함), 관리자 토큰 `QUERYHUB_ADMIN_TOKEN`, 마스터 키 `QUERYHUB_MASTER_KEY`(P1-04), `QUERYHUB_DESKTOP_PARENT_PID` 는 런타임 자식의 환경에만 더한다(`child_env`). 셸 자신의 환경에 있는 `QUERYHUB_ADMIN_TOKEN`, `QUERYHUB_ADMIN_TOKEN_FILE` 은 넘기지 않는다.
- 목적: Spring Boot 는 OS 환경변수를 `application-desktop.yml` 보다 앞세우므로, 셸의 환경(`SERVER_ADDRESS`, `MANAGEMENT_*`, `LOGGING_*`, `DEBUG`, `SPRING_*`, `JAVA_TOOL_OPTIONS` 같은 JVM 옵션 변수 등)이 런타임의 바인딩 주소, 엔드포인트 노출, 로깅, 프로파일을 바꾸지 못하게 한다. 허용 목록의 이름은 Spring 속성에 바인딩되지 않는다. 이것이 P0-C 리뷰 1회차 지적 R1 의 수정이다.
- 셸은 환경을 로그에 남기지 않고 로그 파일을 쓰지 않는다.
- `java -version` 사전 점검의 환경은 P1-03 부터 가짜 java 가 남긴 환경 파일로 확인한다: 관리자 토큰, Metadata DB 비밀번호, 부모 PID 가 없다([BACKLOG.md](BACKLOG.md) P0C-C2-2 닫힘, [TEST_PLAN.md](TEST_PLAN.md)).

### CI 워크플로 (정의됨·미실행, P0-D)

- `.github/workflows/ci.yml` 은 비밀 값이 필요 없다. 최상위 권한은 `contents: read` 하나이고 작업별 권한이 없다. 모든 checkout 이 `persist-credentials: false` 라 작업 트리에 자격 증명이 남지 않는다.
- `pull_request_target` 을 쓰지 않고, `secrets.`, `github.event.`, `github.head_ref` 표현을 쓰지 않는다. P1-02(P1-01 리뷰 5회차 C5-R2)부터 workflow-check 는 식별자 `secrets`(`secrets['X']`, `toJSON(secrets)`, job 의 `secrets: inherit`)를 주석을 포함한 어디서나 거부하고, 표현식 범위(줄의 첫 `${{` 부터 줄 끝까지. 블록 스칼라에서는 `${{` 가 든 내용 줄 다음의 같은 블록 내용 줄 전체) 안의 `github` 는 바로 뒤에 `.` 과 허용 속성(`ref`, `ref_name`, `ref_type`, `sha`, `repository`, `repository_owner`, `run_id`, `run_number`, `run_attempt`, `workflow`, `job`, `event_name`, `server_url`, `api_url`)이 올 때만 받는다(`FORBIDDEN_EXPR`). 그래서 색인(`github['event']`), 공백(`github . event`), 문맥 통째 쓰기(`toJSON(github)`, `github.*`, `format('{0}', github)`), `github.token`, `github.actor` 처럼 허용 목록 밖의 속성, 여러 줄에 걸친 표현식으로 `github.event`·`github.head_ref`·`github.token` 에 닿는 길을 막는다. 범위 안에서는 문자열 안의 `github` 도 거부한다(실패 닫힘). action 은 40자 커밋 SHA 와 `# 태그` 주석으로 고정한다.
- `scripts/gate/workflow-check.sh` 가 lint 단계에서 이 규칙을 점검한다. 워크플로는 한 번도 실행되지 않았다([BACKLOG.md](BACKLOG.md) CI-1, [ADR-020](ADR/ADR-020-ci-runs-gate-scripts.md)).
- workflow-check 는 줄 단위 점검이고 YAML 파서가 아니다. 그래서 줄 단위로 다룰 수 없는 문법은 받지 않고 거부한다(P1-01, P0-D 리뷰 R1-R4): 따옴표로 감싼 키(`"uses":`, 콜론이 바로 붙은 `"k":v` 포함), 흐름 매핑(`{ }`), 평범한 값만 담지 않은 흐름 시퀀스, 앵커·별칭·병합 키, 태그, 문서 표시와 지시자, `key :`, 명시적 키, 들여쓰기 숫자가 붙은 블록 머리, 줄을 넘기는 따옴표 문자열, 큰따옴표 안의 `\` 이스케이프, 들여쓰기의 탭은 `UNSUPPORTED_SYNTAX` 다. 따옴표로 감싼 `write` 도 `PERMISSIONS_WRITE` 다.
- LF 가 아닌 줄바꿈 문자도 `UNSUPPORTED_SYNTAX` 다(P1-01 리뷰 1회차 R1): 줄 끝 CRLF 의 CR 을 뗀 뒤에도 남은 CR, U+0085(NEL), U+2028, U+2029. YAML 은 이 문자에서도 줄을 나누지만 awk 는 LF 에서만 나누므로, 받으면 그 뒤에 둔 키(`continue-on-error:`, job 의 `if: false`, `with:` 대신 `env:` 등)가 모든 규칙을 피한다. 그래서 블록 스칼라 내용, 주석, 빈 줄을 가리지 않고 모든 줄에서 거부한다. 탭이 아닌 제어 문자(U+0001-U+001F, U+007F)와 NUL 바이트(BWK awk 는 NUL 뒤를 읽지 못한다)도 거부한다. awk 는 `LC_ALL=C` 로 돌려 바이트로 비교한다.
- P1-01 리뷰 2회차(C2-R1)와 YAML 문법 전수 점검 뒤로는 구조 줄을 허용 목록으로 본다. 블록 스칼라 내용 밖의 줄은 `키:`, `키: 값`, `- 키:`, `- 키: 값`, `- 값`, `-` 가운데 하나여야 하고, 그 밖의 줄은 `UNSUPPORTED_SYNTAX` 다. 키는 따옴표 없는 `[A-Za-z0-9_-]` 이고, 값은 한 줄에서 끝나는 평범한 값, 한 줄에서 닫히는 따옴표 문자열, 평범한 값만 담은 흐름 시퀀스, 블록 머리다. 들여쓰기도 본다: 값이 있는 키나 `- 값` 다음의 더 깊은 줄은 YAML 이 앞 값에 이어 붙이므로(여러 줄에 걸친 평범한 스칼라) 거부한다. 받으면 `shell: bash` 다음 줄의 `-c true {0}` 이 libyaml 에서 shell 을 `bash -c true {0}` 으로 바꿔 게이트 스크립트가 실행되지 않고, 팩토리 run 줄 다음 줄의 `2>/dev/null || true` 나 `--desktop-only` 가 그 명령을 바꾼다. 열린 매핑이나 목록의 열과 맞지 않는 들여쓰기, 키가 없는 줄, 탭, `- - x`, 맨 `-` 아래의 목록, 0 열이 아닌 최상위, 평범한 값을 시작할 수 없는 문자와 값 자리의 `- `·`? `·`: `, 평범한 값 안의 `: `, 닫는 따옴표 뒤의 글자, BOM 도 거부한다. 블록 스칼라는 첫 내용 줄의 열을 내용 열로 보고, 그보다 덜 들여 쓴 주석 줄이 블록을 끝낸다(libyaml 과 같다). 내용 열보다 덜 들여 쓰고 키보다 깊은, 주석이 아닌 줄은 거부한다.
- 같은 매핑에 같은 키(대소문자는 가리지 않는다)가 두 번 있으면 `DUPLICATE_KEY` 다(C2-R3). libyaml 은 뒤의 값을 쓰므로, 받으면 두 번째 `jobs:`·`steps:`·`with:`·`persist-credentials:` 나 같은 이름의 job 이 팩토리 명령이나 `persist-credentials: false` 가 든 앞의 것을 가린다. `uses:` 의 경로 조각은 비지 않고 `.`·`..` 가 아니어야 한다(C2-R2). `actions//checkout@…` 와 `actions/checkout/@…` 는 checkout 찾기를 피하므로 `USES_NOT_SHA` 로 거부한다.
- 키는 소문자로 쓴다(`KEY_NOT_LOWERCASE`, P1-01 리뷰 3회차 C3-R1). 규칙은 키를 이름(`if`, `continue-on-error`, `shell`, `run`, `uses`, `with`, `persist-credentials`, `permissions`, `jobs`, `steps`, `defaults`, `env`)으로 찾는데, GitHub 이 키의 대소문자를 가리는지는 확인하지 않았다. 가리지 않는다면 `If: false`, `Continue-On-Error: true`, `Shell: true {0}`, `with:` 없는 `Uses: actions/checkout@v4` 가 규칙을 피해 게이트를 무력화하거나 자격 증명을 남긴다. 그래서 대문자가 든 키는 모두 거부한다(키는 허용 목록의 키 줄에만 있으므로 모든 키가 이 검사를 지난다). 예외는 워크플로, job, 단계의 `env:` 바로 아래 변수 이름이다. 이름이 `env` 인 job 의 키, `container:`·`services:` 의 `env`, matrix 와 `with:` 의 키, job id 는 소문자여야 한다. 같은 까닭으로 `pull_request_target`, `secrets.`, `github.event.`, `github.head_ref`, `-latest` 도 대소문자를 가리지 않고 찾는다. 블록 스칼라 내용 밖의 빈 줄과 주석 줄의 들여쓰기 자리의 탭도 `UNSUPPORTED_SYNTAX` 다(C3-R3, libyaml 은 오류로 본다). P1-02(C5-R4)부터 블록 스칼라 안의 공백·탭만 있는 줄에서 내용 열보다 앞의 탭과, 첫 내용 줄 앞의 빈 줄에 있는 탭(열과 상관없이)도 `UNSUPPORTED_SYNTAX` 다(libyaml 0.2.1 이 거부하는 것을 Ruby Psych 로 확인했다).
- 예외로 대문자를 받는 `env:` 변수 이름 가운데 셸, 동적 로더, 러너가 단계를 시작하고 실행하는 방식을 바꾸는 이름은 `FORBIDDEN_ENV` 다(P1-02, P1-01 리뷰 5회차 C5-R1, 대소문자 무시): bash 로 시작하는 이름(`BASH_ENV`, `BASHOPTS`, `BASH_FUNC_*` 등), `LD_`·`DYLD_`·`GITHUB_`·`RUNNER_`·`ACTIONS_`·`MSYS` 로 시작하는 이름, `ENV`, `SHELLOPTS`, `POSIXLY_CORRECT`, `PS4`, `IFS`, `CDPATH`, `GLOBIGNORE`, `EXECIGNORE`, `PATH`, `PATHEXT`, `COMSPEC`. bash 는 `BASH_ENV` 를 명령 치환까지 펼쳐 스크립트보다 먼저 읽으므로 워크플로 `env:` 의 `BASH_ENV: $(...)` 한 줄이 게이트 네 단계를 실행하지 않고 exit 0 으로 끝낼 수 있다(리뷰어가 로컬 bash 로 재현). `PATH` 는 `run: bash scripts/gate/...` 의 bash 를 고르고, `PATHEXT`·`COMSPEC` 은 Windows 러너(windows-2025)의 명령 찾기와 명령 해석기를 바꾸며, `ACTIONS_ALLOW_UNSECURE_COMMANDS` 는 set-env 명령을 되살린다. 워크플로·job·단계의 한 줄 `env:` 값(`env: ${{ fromJSON(...) }}`)도 이 검사가 보지 못하는 이름을 만들므로 거부한다. 금지 이름을 품기만 한 이름(`NOT_BASH_ENV`, `MY_LD_PRELOAD`, `XPATH`, `PATHEXT_X`)은 받는다.
- 게이트를 무력화하는 키도 거부한다: `continue-on-error:`(`CONTINUE_ON_ERROR`)와 `if:`(`IF_CONDITION`)는 워크플로·job·단계 어디서나 값과 상관없이, `shell:` 은 정확히 `bash` 가 아니면(`SHELL_NOT_BASH`) 거부한다. 단계의 run 값(run: 줄과 그보다 깊은 줄, 주석 모양 줄 포함)의 `${{` 는 셸에 들어가기 전에 펼쳐지므로 `RUN_EXPR` 로 거부한다.
- `run:` 은 단계(`jobs.<job>.steps` 의 항목)의 키로만 두고 한 단계에 하나만 둔다(`RUN_KEY`, P1-01 리뷰 1회차 R2). `env:` 변수나 action 입력(`with:`), matrix 아래의 `run:` 은 실행되지 않으므로 팩토리 명령으로 세지 않고 거부한다. 단계 밖에서 받는 것은 워크플로와 job 의 `defaults:` 바로 아래 값이 빈 `run:`(그 아래 `shell: bash`) 하나다. `persist-credentials: false` 는 checkout 단계(owner/repo 의 대소문자는 가리지 않는다)의 `with:` 바로 아래만 세고, 팩토리 명령은 블록 안의 줄이 아닌 단계의 한 줄짜리 `run:` 값만 센다.
- 잡지 못하는 것: action 입력 이름이 맞는지, 표현식의 뜻(`name:`, `runs-on:`, `concurrency`, `with:`, `env:`, `defaults:` 값의 표현식은 받는다), 러너 레이블이 있는지, 트리거와 브랜치 필터, `working-directory`, `FORBIDDEN_ENV` 밖의 env 이름(`HOME`, `TMPDIR`, `LANG`·`LC_*`, `JAVA_TOOL_OPTIONS`·`NODE_OPTIONS`·`GRADLE_OPTS`·`RUSTC_WRAPPER` 같은 도구 옵션 변수)으로 도구를 바꿔치기하는 것과 `container:`·`services:` 의 env. 표현식은 `secrets` 와 `github` 문맥만 본다: `inputs`, `vars`, `needs`, `steps`, `matrix`, `env`, `job`, `runner`, `strategy` 문맥의 값은 보지 않는다. 단계 명령이 무엇을 하는지도 보지 않는다: 앞 단계가 게이트 스크립트를 고쳐 쓰거나 `$GITHUB_ENV`·`$GITHUB_PATH`·`$GITHUB_OUTPUT` 에 써서(예: `BASH_ENV` 설정) 뒤 단계를 바꾸는 것, `container:` 와 `services:` 는 잡지 못한다(P1-01 리뷰 3회차 C3-R2). GitHub 이 색인·공백 표현식, 한 줄 `env:`, `GITHUB_*` 같은 예약 이름을 어떻게 다루는지도 확인하지 않았다(CI-1. P1-02 의 규칙은 거부만 더한다). YAML 을 GitHub 와 같게 읽는지도 확인하지 않았다: 전수 점검은 libyaml 0.2.1(Ruby Psych)과 견준 구성에서만 했고 GitHub 의 파서로는 하지 않았다(CI-1). U+0085 가 아닌 C1 제어 문자와 올바르지 않은 UTF-8 은 보지 않는다(줄 나눔을 바꾸지 않는다). 그러니 이 점검을 통과해도 이런 방법으로는 게이트 단계를 무력화할 수 있다. 이것은 리뷰와 첫 CI 실행(CI-1)이 본다. `docs-check.sh` 는 링크를 저장소 루트 안에 가두지만 글자 그대로의 정리라서 루트 밖을 가리키는 저장소 안의 심볼릭 링크는 잡지 못한다([TEST_PLAN.md](TEST_PLAN.md) 「lint 의 저장소 단계」).

## 알려진 빈 곳

| 내용 | BACKLOG | 목표 |
|---|---|---|
| 바인딩 가드가 없다. JAR 을 desktop 프로파일로 직접 실행하면서 바인딩 주소를 넓혀도(예: `--server.address=0.0.0.0`) 기동을 거부하지 않는다. 관리 API 는 관리자 토큰과 루프백 검사(소켓 원격 주소)로 막히지만, health 와 앞으로의 데이터 API 는 그 주소에서 열린다. 셸이 띄운 런타임은 주소 인자를 받지 않고 `SERVER_ADDRESS` 도 물려받지 않는다(「Desktop 셸의 자식 환경」) | P103-1 | Phase 1 |
| 관리자 토큰은 런타임 자식의 환경변수에 있으므로 같은 사용자의 프로세스와 root 는 읽을 수 있다(macOS `ps -E`, Linux `/proc/<pid>/environ`). 설계 §1.2 와 [ADR-011](ADR/ADR-011-metadata-secrets-env-only.md)이 받아들인 경로다 | — | 정하지 않음 |
| WebView 의 최상위 탐색을 막지 않는다(`on_navigation` 없음). `admin_token` 권한은 `remote` 키가 없는 capability 라 앱에 묶인 로컬 출처에만 주어진다(Tauri 2 의 규칙. 외부 페이지로 탐색해 시험하지는 않았다) | P103-2 | Phase 6 |
| 서블릿 컨테이너가 먼저 거부하는 깨진 요청 대상(`%2F`, `%00`, `{`·`}`, 절대 형식 등)과 깨진 Host(대괄호 없는 IPv6(`::1`), zone id(`[::1%25lo0]`), userinfo(`user@localhost`), 숫자가 아닌 포트(`localhost:abc`), Host 헤더 둘, HTTP/1.1 의 Host 없음)는 Tomcat 의 평문 400 이고 공통 오류 형식과 `X-Request-Id` 가 없다. 애플리케이션 코드는 실행되지 않는다 | P103-4 | 정하지 않음 |
| Windows·Linux 의 셸(토큰 생성, 자식 환경, capability)은 미검증이다 | PLAT-1 | CI-1 첫 실행 |
| desktop 의 마스터 키 파일은 파일 권한만 지킨다. 같은 사용자의 프로세스, 관리자와 root, 데이터 디렉터리의 백업과 복사본이 키를 읽을 수 있다(위 「마스터 키」) | P104-2 | 정하지 않음 (키체인 통합을 다시 정할 때) |
| Windows·Linux 의 키 파일 경로, `create_new`, 권한 동작은 미검증이다. Windows 는 키 파일의 DACL 을 따로 좁히지 않는다 | PLAT-1, P104-7 | CI-1 첫 실행 |
| 이전 키 입력과 일괄 재암호화가 없다. `QUERYHUB_MASTER_KEY` 를 바꾸면 기존 암호문이 `UNKNOWN_KEY` 가 된다(지금은 저장된 암호문이 없다) | P104-4 | 연결 저장 태스크 |
| `spring.datasource.hikari.catalog` 와 `connection-init-sql` 을 프로세스에 보이는 출처에서 막을지 정하지 않았다. catalog 는 연결 DB 를, connection-init-sql 은 UTC 세션 설정을 바꿀 수 있다 | P0B-H1 | Phase 1 |
| `spring.autoconfigure.exclude` 로 `MetadataDatabaseAutoConfiguration` 을 빼면 가드, 검사한 연결 정보, Flyway 고정이 함께 사라진다 | P0B-R14 | Phase 1 |
| `spring.flyway.url`·`user`·`password` 와 `spring.datasource.url`·`password` 를 명령행이나 `-D` 로 주면 무시되지만 거부도 경고도 없다. 값은 프로세스 목록에 보인다 | P0B-R15 | Phase 1 |
| Metadata DB 연결이 평문이다. 원격 DB 용 TLS 는 계획이다([DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)) | — | Phase 1 또는 6 |
| Testcontainers 가 테스트용 MariaDB 포트를 모든 호스트 인터페이스에 연다(테스트가 도는 약 20 초). P0-D 는 runtime 을 바꾸지 않아 미뤘다([ADR-020](ADR/ADR-020-ci-runs-gate-scripts.md)) | P0B-R6 | Phase 1 |
| `.gitignore` 의 `*.key`, `*.p12`, `*.pem` 이 경로 고정 없이 걸려 정상 파일도 커밋에서 말없이 빠진다 | P0A-N4 | Phase 1 |
| `desktop/pnpm-workspace.yaml` 의 `minimumReleaseAgeExclude` 에 `lucide-react@1.74.0` 예외가 남아 있다 | P0C-R5 | Phase 6 |
| `JAVA_HOME` 은 java 를 고르는 데만 쓰고 자식에게 넘기지 않는다. 고른 java 가 macOS 의 `/usr/bin/java` 스텁이면 기본 JDK 가 뜬다 | P0C-C2-3 | Phase 8 |
| 거부된 CORS 요청은 공통 오류 형식이 아니라 Spring 의 403 본문(`Invalid CORS request`)이다. P1-03 에서 다시 보고 그대로 두었다 | — | 정하지 않음 |

## 운영 지표의 보안 제약 (계획)

[REQUIREMENTS.md](REQUIREMENTS.md) AR-1 의 OM-6, OM-7 이다. 화면은 Phase 6, 수집과 엔드포인트는 Phase 7 이다.

- **OM-6:** 지표는 런타임 안에서 Micrometer 미터를 읽어 관리 API 엔드포인트 하나로만 내보낸다. 관리자 토큰(Phase 1)을 요구한다. actuator 의 `metrics`, `prometheus` 엔드포인트는 열지 않고 노출은 `health` 하나로 둔다(`application.yml`). actuator 를 외부로 여는 것은 P0-C 리뷰 지적 R1 에서 막은 경로다([ADR-013](ADR/ADR-013-desktop-child-env-allowlist.md)).
- **OM-7:** 지표 이름, 태그, 로그 창에 SQL 원문, 파라미터 값, 비밀 값, API Key 를 넣지 않는다. 태그 값의 종류 수는 API ID 와 풀 이름처럼 개수가 정해진 것으로 제한한다. 로그 창은 계속 가린 채 보이고 Phase 7 의 마스킹 정책(위 표 10번)을 따른다.
