# QueryHub API 명세

런타임은 HTTP 포트 하나에서 관리 API(`/admin/api/v1/**`)와 데이터 API(`/api/v1/**`)를 경로로 나눈다([ADR-002](ADR/ADR-002-single-http-port.md)). 앞부분은 f84c628 에 **구현된** 것이고, 「계획」 절은 설계 §5 의 초안이다. 포트와 바인딩은 [ARCHITECTURE.md](ARCHITECTURE.md), 보안 통제는 [SECURITY.md](SECURITY.md)에 있다.

## 구현된 API (P0-A, P0-B)

### 엔드포인트

| 메서드·경로 | 응답 |
|---|---|
| `GET /actuator/health` | 200 `{"status":"UP", ...}` (인증 없음, 상세 정보 없음). P0-B 부터 Metadata DB(`db`) 상태를 포함한다. DB 에 닿지 않으면 503 `DOWN` |
| `GET /actuator/health/liveness` | 200 `{"status":"UP"}` |
| `GET /actuator/health/readiness` | 200 `{"status":"UP"}`. Metadata DB 를 포함하지 않는다(넣을지는 계획) |
| `GET /admin/api/v1/runtime` | 200 런타임 상태 (아래 예) |
| 그 밖의 모든 경로 (다른 actuator 엔드포인트, `/api/v1/**` 포함) | 404 `NOT_FOUND` |

- actuator 는 `health` 만 공개한다(`runtime/app/src/main/resources/application.yml` 의 `management.endpoints.web.exposure.include: health`, `show-details: never`, `probes.enabled: true`).
- 정적 리소스를 제공하지 않는다(`spring.web.resources.add-mappings: false`). 없는 경로는 공통 404 다.
- 데이터 API(`/api/v1/{endpointPath}`)의 디스패처는 계획(Phase 3·4)이다. 지금은 공통 404 를 돌려준다.
- 관리 API(`/admin/**`)와 health 세 경로 밖의 `/actuator/**` 는 관리자 토큰을 요구한다(구현됨, P1-03, 아래 「관리자 토큰 인증」). health 세 경로와 데이터 API 는 이 토큰이 필요 없다. API Key 는 Phase 4 다(아래 「계획」).

`GET /admin/api/v1/runtime` 의 성공 응답 (`runtime/adapter-web/src/main/java/com/queryhub/adapter/web/admin/runtime/RuntimeInfoController.java`, `RuntimeInfoResponse.java`):

```json
{
  "success": true,
  "data": {
    "name": "queryhub-runtime",
    "version": "0.1.0-SNAPSHOT",
    "mode": "desktop",
    "startedAt": "2026-11-16T01:23:45.678Z",
    "uptimeSeconds": 42
  },
  "meta": { "requestId": "3f2b0c1e-7a8d-4c55-9e1f-2a6b8d0c4e71" }
}
```

`name` 은 `spring.application.name`, `version` 은 빌드 정보(`runtime/gradle.properties` 의 `version`)이고, `mode` 는 `desktop` 또는 `server` 다.

### 공통 응답 형식

성공은 `SuccessEnvelope`, 오류는 `ErrorEnvelope` 이다(`runtime/adapter-web/src/main/java/com/queryhub/adapter/web/common/response/`). 모든 오류가 같은 형식이며 Content-Type 은 `application/json` 이다.

```json
{
  "success": false,
  "error": {
    "code": "NOT_FOUND",
    "message": "요청한 리소스를 찾을 수 없습니다.",
    "requestId": "3f2b0c1e-7a8d-4c55-9e1f-2a6b8d0c4e71"
  }
}
```

- 응답에는 예외 메시지, 클래스 이름, 스택 트레이스, SQL, DB 정보를 넣지 않는다. 500 의 상세 내용은 서버 로그에 `requestId` 와 함께 ERROR 로 남는다(`GlobalExceptionHandler`, `QueryHubErrorController`; Boot 기본 오류 속성도 `application.yml` 의 `spring.web.error.include-*` 로 막는다).
- 없는 경로(404), 지원하지 않는 메서드(405, `Allow` 헤더 포함), 지원하지 않는 Content-Type(415), 깨진 요청(400)도 같은 형식이다.

### 오류 코드

`ErrorCode`(`runtime/domain/src/main/java/com/queryhub/domain/error/ErrorCode.java`)의 14개 값과 HTTP 상태(`runtime/adapter-web/src/main/java/com/queryhub/adapter/web/common/error/ErrorHttpStatus.java`)다. 메시지는 `ErrorCode` 의 한국어 기본 문구다.

| 코드 | HTTP | 쓰임 |
|---|---|---|
| `INVALID_REQUEST` | 400 | 깨진 요청 (구현됨) |
| `INVALID_PARAMETER` | 400 | 파라미터 누락·타입 오류 (데이터 API, 계획) |
| `UNAUTHORIZED` | 401 | 관리자 토큰이 없거나 틀리다 (구현됨, P1-03). 데이터 API 의 API Key 실패는 계획 |
| `FORBIDDEN` | 403 | desktop 관리 요청의 루프백·Host 검사 실패 (구현됨, P1-03). API 별 권한은 계획 |
| `NOT_FOUND` | 404 | 없는 경로 (구현됨) |
| `API_NOT_FOUND` | 404 | 없는 URL, 비활성 API (데이터 API, 계획) |
| `METHOD_NOT_ALLOWED` | 405 | 지원하지 않는 메서드, `Allow` 헤더 포함 (구현됨) |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 Content-Type (구현됨) |
| `ROW_LIMIT_EXCEEDED` | 422 | XLSX 행 한도 초과 (계획) |
| `RATE_LIMITED` | 429 | 속도·동시 실행 한도 초과 (계획) |
| `INTERNAL_ERROR` | 500 | 그 밖의 서버 오류 (구현됨) |
| `QUERY_FAILED` | 500 | SQL 실행 오류 (계획) |
| `SERVICE_STOPPED` | 503 | API 서비스 중지 (계획) |
| `QUERY_TIMEOUT` | 504 | 쿼리·API timeout (계획) |

코드와 상태의 대응은 14개 모두 구현됐다. 「계획」은 그 코드를 내는 기능이 아직 없다는 뜻이다. 컨테이너 오류의 HTTP 상태를 코드로 바꿀 때는 404, 405, 415 가 각자의 코드가 되고, 그 밖의 4xx 는 `INVALID_REQUEST`, 나머지는 `INTERNAL_ERROR` 가 된다(`ErrorHttpStatus`).

### X-Request-Id

- 요청 헤더가 `^[A-Za-z0-9._-]{1,64}$` 에 맞으면 그 값을, 아니면 새 UUID 를 쓴다(`runtime/adapter-web/src/main/java/com/queryhub/adapter/web/common/requestid/RequestIds.java` 의 `VALID_PATTERN`).
- 모든 응답(actuator 와 오류 포함)의 `X-Request-Id` 헤더와 `meta.requestId`/`error.requestId` 가 같은 값이다. 필터(`RequestIdFilter`)는 가장 먼저 실행된다.
- **로그의 요청 ID:** 오류 응답을 만들 때 오류 처리기(`GlobalExceptionHandler`, `QueryHubErrorController`)가 남기는 로그는 메시지 본문에 `requestId=<값>` 을 넣는다(예: `처리하지 못한 예외 (requestId=..., ...)`). 그 밖의 로그 줄에는 요청 ID 가 찍히지 않는다. 필터가 요청 처리 중에 MDC 키 `requestId` 에도 같은 값을 넣지만, 지금의 로그 패턴(Boot 기본값)은 MDC 를 출력하지 않는다. 필터에서 던진 예외의 `/error` 처리 로그는 필터가 MDC 를 지운 뒤에 남으므로, 그 요청 ID 는 요청 속성에서 읽어 메시지에 넣는다.

### 관리자 토큰 인증 (구현됨, P1-03 커밋 전)

결정은 [ADR-021](ADR/ADR-021-admin-token-bearer.md)(승인됨)과 [ADR-022](ADR/ADR-022-admin-token-implementation.md)(제안)에 있다. 코드: `runtime/adapter-web/src/main/java/com/queryhub/adapter/web/admin/security/`.

- **대상:** `/admin` 과 `/admin/` 아래의 모든 경로(대소문자 무관)와 `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` 밖의 모든 `/actuator/**`. health 세 경로, `/api/v1/**`, 그 밖의 경로는 토큰을 보지 않는다.
- **헤더:** `Authorization: Bearer <토큰>` 하나. scheme 은 대소문자를 가리지 않고 토큰 앞 공백은 하나다. 헤더가 없거나 둘 이상이거나, 토큰이 비었거나, Basic 이거나, 토큰을 쿼리 파라미터로 보내면 401 이다.
- **401:** 공통 오류 형식 `UNAUTHORIZED`(메시지 「인증이 필요합니다.」), `WWW-Authenticate: Bearer`, `X-Request-Id`. 없는 토큰과 틀린 토큰의 응답은 `requestId` 말고 같다.
- **403:** 공통 오류 형식 `FORBIDDEN`(메시지 「요청한 작업에 대한 권한이 없습니다.」). desktop 프로파일에서 소켓 원격 주소가 루프백이 아니거나(`queryhub.admin.allow-remote=false`. 이때 런타임은 RemoteIpValve 를 켜는 설정 — `server.forward-headers-strategy` 가 none 이 아닌 값, `server.tomcat.remoteip.*` — 이 있으면 기동하지 않으므로 `X-Forwarded-For` 와 `Forwarded` 는 판정에 쓰이지 않는다) `Host` 가 허용 목록에 없을 때다. 형식이 깨진 Host(대괄호 없는 IPv6(`::1`), zone id(`[::1%25lo0]`), userinfo(`user@localhost`), 숫자가 아닌 포트(`localhost:abc`), Host 헤더 둘, HTTP/1.1 의 Host 없음)는 애플리케이션 전에 Tomcat 의 평문 400 이 될 수 있다. 토큰보다 먼저 검사하고 CORS 사전 요청에도 적용한다. 허용 Host: `localhost`, `127.0.0.1`, `[::1]` 과 `queryhub.admin.allowed-hosts` 의 이름, 포트는 있으면 1-65535(로컬 포트와 비교하지 않는다). server 프로파일은 원격 요청을 받고 `allowed-hosts` 를 설정할 때만 Host 를 검사한다.
- **actuator:** 토큰을 보낸 `/actuator`, `/actuator/env`, `/actuator/beans`, `/actuator/info`, `/actuator/health/<그 밖>` 은 404 `NOT_FOUND` 다(토큰이 없으면 401). `/actuator` 탐색(discovery)과 health 의 하위 경로(구성 요소, 그룹)는 P1-03 부터 제공하지 않는다.
- **깨진 경로 (P1-03 부터):** Spring Security 의 StrictHttpFirewall 이 `;`, `//`, `/./` 같은 경로를 모든 경로에서 400 `INVALID_REQUEST` 로 거부한다. 관리 체인 경로(`/admin/**`, `/actuator/**`)에서는 모든 퍼센트 인코딩이 400 이다. 일부 깨진 요청 대상(예: `/admin%2Fapi/v1/runtime`, `/admin/api/v1/runtime%00`, `{` 나 `}` 가 들어간 대상, 절대 형식 대상)과 위의 깨진 Host 는 서블릿 컨테이너(Tomcat)가 애플리케이션보다 먼저 400 으로 거부한다. 그 응답은 공통 오류 형식이 아닌 평문 400 이고 `X-Request-Id` 가 없으며 애플리케이션 코드는 실행되지 않는다([BACKLOG.md](BACKLOG.md) P103-4).
- **기동 검사 (P1-03):** 관리 체인을 끄거나 관리·actuator 핸들러를 체인 밖이나 열린 health 경로로 옮기는 설정이면 런타임이 기동을 거부한다(목록은 [SECURITY.md](SECURITY.md) 「관리 체인의 기동 검사」). 기동 직후 자기 점검에서 토큰 없는 `/admin/api/v1/runtime` 이 401 이나 403 이 아니면 런타임이 멈춘다. 토큰 없이 여는 경로는 health 세 경로다. `/livez`, `/readyz` 같은 health 추가 경로를 켜는 설정은 기동 거부다.
- **토큰 설정:** desktop 은 셸이 실행마다 만든다. server 와 손으로 실행한 JAR 은 [DEPLOYMENT.md](DEPLOYMENT.md)의 「관리자 토큰」을 본다. 토큰이 없으면 런타임은 기동을 거부한다.

### 관리 API CORS (desktop 프로파일, 구현됨 P0-C, P1-03 변경)

결정과 이유는 [ADR-015](ADR/ADR-015-desktop-fixed-port-and-cors.md)(제안)에 있다. 코드: `runtime/adapter-web/src/main/java/com/queryhub/adapter/web/admin/cors/AdminCorsConfiguration.java`, `AdminCorsProperties.java`, 설정 `runtime/app/src/main/resources/application-desktop.yml` 의 `queryhub.admin.cors.allowed-origins`.

- 허용 출처: `tauri://localhost`(macOS·Linux 의 WebView), `http://tauri.localhost`(Windows, 미검증) 두 개뿐이다.
- 범위: `/admin/api/v1/**` 만(`PATH_PATTERN`), GET 만(`ALLOWED_METHODS`), 요청 헤더는 `Authorization` 하나(P1-03. 사전 요청은 토큰 없이 답하고, 요청한 헤더가 모두 허용될 때만 통과한다), 자격 증명 없음, 노출 헤더 `X-Request-Id`, preflight 캐시 1800 초(`MAX_AGE_SECONDS`).
- server 프로파일과 기본 설정에는 CORS 가 없다. 거부된 CORS 요청은 공통 오류 형식이 아니라 Spring 의 403 본문(`Invalid CORS request`)이다(P1-03 에서 다시 보고 그대로 두었다). P1-03 부터 CORS 는 관리 보안 체인 안에서 처리하므로 허용 출처의 401·403 에도 `Access-Control-Allow-Origin` 이 한 번 붙는다.
- 출처 값은 `scheme://host[:port]`(http, https, tauri)만 받는다. `*`, `null`, 경로가 있는 값은 기동을 실패시킨다.
- 개발 서버(`http://localhost:1420`)의 UI 는 같은 출처의 `/admin/api` 로 요청하고, Vite 프록시가 Origin 헤더를 지워 `http://127.0.0.1:18580` 으로 넘긴다(`desktop/vite.config.ts`). 그래서 런타임은 개발 출처를 허용하지 않는다.

## 계획

### 데이터 API — `GET|POST /api/v1/{endpointPath}` (계획, Phase 3·4)

설계 §5.2. 결정은 [ADR-003](ADR/ADR-003-single-dispatcher-route-registry.md)과 [ADR-005](ADR/ADR-005-streaming-single-path.md)에 있다.

- 디스패처 컨트롤러 하나가 `RouteRegistry` 에서 API Definition 을 찾는다. URL 과 SQL 을 하드코딩하지 않는다.
- 파라미터: GET 은 query string, POST 는 JSON body. form-urlencoded 는 확장 지점으로 남긴다.
- 형식 선택: `?format=` → `Accept` → API 기본 `responseType` 순. CSV·XLSX·HTML 은 첨부 파일로 내려준다(Phase 5).
- 인증: `X-API-Key` 헤더(Phase 4).
- 성공 응답의 `meta` 는 `rowCount`, `executionTimeMs`, `truncated`, `requestId` 를 담는다.

| 상황 | HTTP | code |
|---|---|---|
| 없는 URL, 비활성 API | 404 | `API_NOT_FOUND` |
| 인증 실패 | 401 | `UNAUTHORIZED` |
| 권한 없음 | 403 | `FORBIDDEN` |
| 파라미터 누락·타입 오류 | 400 | `INVALID_PARAMETER` |
| SQL 실행 오류 | 500 | `QUERY_FAILED` |
| 쿼리·API timeout | 504 | `QUERY_TIMEOUT` |
| 속도·동시 실행 한도 초과 | 429 | `RATE_LIMITED` |
| XLSX 행 한도 초과 | 422 | `ROW_LIMIT_EXCEEDED` |
| API 서비스 중지 | 503 | `SERVICE_STOPPED` |
| 그 밖의 서버 오류 | 500 | `INTERNAL_ERROR` |

- **스트리밍 도중 오류:** 첫 행을 받은 뒤에 응답을 시작하므로 실행 전 오류는 정상적인 오류 응답이 된다. 도중 실패는 HTTP 상태를 바꿀 수 없으므로 연결을 끊고 이력에 실패로 남긴다.

### 관리 API — `/admin/api/v1` (계획, Phase 1-7)

설계 §5.3.

- 리소스: `connections`, `sql-definitions`, `api-definitions`, `api-keys`, `executions`, `dashboard`, `exports`, `runtime`(구현됨: `GET` 하나), `settings`.
- 주요 동작: 연결 테스트, SQL 파라미터 감지·테스트 실행·이력, 엔드포인트 중복 검사, API 활성·비활성, API 서비스 시작·중지, 런타임 상태.
- API Key 원문은 생성 응답에서 한 번만 내려간다([ADR-006](ADR/ADR-006-password-aes-gcm-api-key-hmac.md), Phase 4).
- 헬스 체크는 인증 없이 접근할 수 있어야 한다(Tauri 감독, Docker healthcheck).

### 인증 (계획)

- **관리자 토큰:** 구현됨 (P1-03). 위 「관리자 토큰 인증」을 본다.
- **API Key (Phase 4):** `X-API-Key` 헤더. HMAC-SHA-256 해시와 prefix 만 저장하고, API 별 권한은 `api_key_permission` 이다([ERD.md](ERD.md)).

### OpenAPI 계약 (계획, Phase 1·6)

관리 API 의 OpenAPI 문서(springdoc)를 `contracts/` 에 고정하고 TS 클라이언트를 생성한다([ADR-008](ADR/ADR-008-openapi-ts-client.md)). 저장소에는 아직 `contracts/` 가 없다. 데이터 API 의 OpenAPI 문서는 API Definition 에서 동적으로 만들고 API Explorer 메뉴가 쓴다.

### 운영 지표 엔드포인트 (계획, Phase 7)

[REQUIREMENTS.md](REQUIREMENTS.md) AR-1 OM-6. 런타임 안에서 Micrometer 미터를 읽어 관리 API 엔드포인트 하나가 공통 응답 형식으로 돌려준다. 경로는 Phase 7 에서 정하며 `runtime` 또는 `dashboard` 리소스 아래에 둔다. 관리자 토큰을 요구한다. actuator 의 `metrics`, `prometheus` 는 열지 않고 노출은 `health` 하나로 둔다. 보안 제약(OM-7)은 [SECURITY.md](SECURITY.md)에 있다.
