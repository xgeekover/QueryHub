# QueryHub

SQL 기반 API 데이터 서비스 및 데이터 추출 데스크톱 플랫폼.

SQL 을 저장하고 API 엔드포인트와 연결해, 외부 시스템이 HTTP API 로 데이터를 조회할 수 있게 한다. 단순한 SQL 클라이언트가 아니라
데이터웨어하우스(DW)의 조회 API 서버로 쓸 수 있는 독립형 제품이다. 하나의 런타임(Spring Boot 실행 JAR)이 두 가지 모드로 동작한다.

- **desktop**: Tauri 데스크톱 앱이 런타임을 자식 프로세스로 띄운다. 루프백(127.0.0.1)에만 바인딩한다.
- **server**: `java -jar` 나 Docker 로 서버에 배포한다. 모든 인터페이스에 바인딩한다.

## 현재 상태: Phase 0 / P0-A (런타임 골격)

| 구분 | 내용 |
|---|---|
| 구현됨 | Gradle 멀티 모듈 빌드(`runtime/`), 모듈 의존 규칙(Gradle 선언 검사 + ArchUnit), 공통 응답·오류 형식, X-Request-Id, health 엔드포인트, 런타임 상태 관리 API, 프로파일(desktop/server), 게이트 스크립트(`scripts/gate/`) |
| 계획 | Metadata DB(MariaDB, Flyway)와 통합 테스트 게이트(P0-B), Desktop 셸 스파이크(P0-C), CI(P0-D), 설계 문서 `docs/`(후속 태스크) |
| 계획 | DB 연결과 Provider 4종·관리자 토큰·보안(Phase 1), SQL 저장소(Phase 2), API 관리(Phase 3), API 런타임·API Key(Phase 4), Export(Phase 5), Desktop UI(Phase 6), 모니터링(Phase 7), 배포(Phase 8) |

설계 문서(요구사항, 아키텍처, ADR, ERD, API 명세 등)는 `docs/` 에 둘 예정이다(계획: P0-A 다음의 별도 태스크). 그때까지는 이 README 가 저장소의 유일한 문서다.

## 저장소 구조

```
.
├─ runtime/                Gradle 루트 (settings.gradle, build.gradle, gradlew, gradle/libs.versions.toml)
│  ├─ domain/              오류 어휘(ErrorCode, QueryHubException), 실행 모드(RuntimeMode)
│  ├─ application/         유스케이스와 포트 (GetRuntimeInfoUseCase, RuntimeInfoService)
│  ├─ adapter-web/         관리 REST, 공통 응답 형식, 요청 ID 필터, 오류 매핑
│  └─ app/                 Spring Boot 조립, 프로파일, 실행 JAR, 아키텍처 테스트
├─ scripts/gate/           build · test · lint 게이트 스크립트와 JUnit 결과 판정기
├─ .editorconfig  .gitattributes  .gitignore
└─ README.md
```

## 모듈과 의존 규칙

의존은 한 방향이다: `app → adapter-* → application → domain`.

| 모듈 | 패키지 | 선언할 수 있는 의존 |
|---|---|---|
| domain | `com.queryhub.domain` | 없음 (Spring 을 포함한 어떤 프레임워크도 쓰지 않는다) |
| application | `com.queryhub.application` | `:domain` 만 (Spring 웹·JDBC 를 모른다) |
| adapter-web | `com.queryhub.adapter.web` | `:application`, `:domain`, 외부 라이브러리. 다른 어댑터는 안 된다 |
| app | `com.queryhub.app` | 모든 모듈 (유일한 조립 지점) |
| adapter-metadata, adapter-dbprovider, provider-{mssql,oracle,mariadb,postgresql}, adapter-export | | 계획 (P0-B, Phase 1, Phase 5) |

규칙은 두 겹으로 강제한다.

1. **Gradle**: 모듈 선언 때문에 금지된 import 는 컴파일되지 않는다. 또 `runtime/build.gradle` 의 검사가 모든 모듈의 `api`,
   `implementation`, `compileOnly`, `compileOnlyApi`, `runtimeOnly` 선언과 `compileClasspath`·`runtimeClasspath` 에 상속되는 모든
   설정(사용자 정의 설정 포함)의 선언을 확인해, 규칙을 넓히는 선언이 있으면 어떤 태스크도 실행하기 전에 빌드를 실패시킨다. 파일
   의존성(`files(...)`, `fileTree(...)`)은 `app` 이외의 모든 모듈에서 거부한다. 테스트 설정(`testImplementation` 등)은 검사하지 않는다.
   모듈은 `settings.gradle` 에 최상위로 include 한 `:domain`, `:application`, `:app`, `:adapter-*`, `:provider-*` 중 하나여야 하고
   (중첩 프로젝트는 거부한다), `runtime/<이름>/` 에 자기 `build.gradle` 이 있어야 한다(`build.gradle.kts` 는 쓰지 않는다).
2. **ArchUnit** (`runtime/app/src/test/java/com/queryhub/architecture/`): R1 계층, R2 어댑터 간 독립, R3 domain 은 `java..` 만,
   R4 application 은 웹·HTTP·JDBC·Boot·서블릿·어댑터 금지, R5 관리 API(`adapter.web.admin`)와 데이터 API(`adapter.web.api`) 분리,
   R6 JDBC 드라이버는 `adapter.dbprovider.provider..` 만, R7 모든 클래스는 네 계층 중 하나에. `ArchitectureRulesSelfTest` 가 고의
   위반 픽스처로 각 규칙이 실제로 실패하는지 확인한다(R6 의 픽스처는 드라이버가 들어오는 Phase 1 에 추가한다, 계획).

## 준비물

- **빌드**: JDK 17 이상 하나면 된다. Gradle 은 이 JDK 로 실행되고, 컴파일·테스트에 쓰는 Java 21 toolchain 은 Gradle 이 자동으로
  준비한다(foojay resolver, `~/.gradle/jdks` 에 캐시). Gradle 9.7.1 도 wrapper 가 내려받는다.
- **실행**: 실행 JAR 은 Java 21 용으로 컴파일된다. `java -jar` 에는 **Java 21 이상 런타임**이 필요하다. JDK 17 로 실행하면
  `UnsupportedClassVersionError` 로 실패한다. Java 21 이 따로 없으면 Gradle 이 내려받은 toolchain 의 `bin/java` 를 쓸 수 있다
  (예: macOS 에서는 `~/.gradle/jdks/<배포판 디렉터리>/Contents/Home/bin/java`).
- **네트워크**: 첫 빌드에는 Maven Central 접근이 필요하다. Java 21 이 캐시에 없으면 foojay/Adoptium 접근도 필요하다.
- **셸**: 게이트 스크립트는 bash 3.2(macOS 기본) 이상과 POSIX 도구(find, awk, grep, mktemp)만 쓴다.

## 게이트 명령

저장소 루트에서 실행한다(스크립트는 어느 cwd 에서 실행해도 저장소 루트 기준으로 동작한다). 모두 성공하면 exit 0 이다.

```sh
bash scripts/gate/build.sh   # main·test 를 -Xlint:all -Werror 로 컴파일하고 실행 JAR 을 만든다 (assemble testClasses)
bash scripts/gate/test.sh    # 단위 테스트를 실제로 실행하고 JUnit XML 로 판정한다
bash scripts/gate/lint.sh    # 게이트 스크립트 문법(bash -n), Spotless 형식 검사, 컴파일
```

`test.sh` 는 종료 코드만 보는 게이트가 거짓으로 통과하지 않도록 다음을 막는다.

- 먼저 `selftest.sh` 로 판정기(`junit-check.sh`) 자체를 검증한다. 실패하면 중단한다.
- `runtime/` 아래 `build.gradle` 을 가진 모든 모듈을 찾는다(모든 깊이의 `build/`, `.gradle/` 은 건너뛴다). 탐색(`find`)이 실패하거나
  모듈이 하나도 없으면 테스트를 실행하기 전에 실패한다. `settings.gradle` 에 include 된 프로젝트에 자기 `build.gradle` 이 없으면
  모듈 의존 규칙이 빌드를 실패시키므로, 탐색에서 빠지는 모듈이 없다. 새 모듈도 자동으로 포함된다.
- `cleanTest test --continue --no-build-cache` 로 항상 테스트를 다시 실행한다. `cleanTest` 는 UP-TO-DATE 로 0건 실행이 되는 일을,
  `--no-build-cache` 는 사용자 홈이나 CI 에서 빌드 캐시를 켜 두었을 때 Test 태스크를 캐시에서 복원(FROM-CACHE)해 실행 없이 결과
  XML 이 생기는 일을 막는다.
- 모듈마다 실행된 테스트가 1건 이상인지 확인한다.
- 실패·오류가 있으면, 건너뛴 테스트(`@Disabled` 등)가 하나라도 있으면, 이번 실행보다 오래된 결과 파일이 있으면, 읽거나 해석할 수
  없는 결과 파일(`[INVALID]`: 읽기 권한 없음, 일반 파일이 아님, `tests` 속성 없음, 닫히지 않은 태그, XML 이 아님)이 있으면 실패한다.

성공하면 모듈마다 한 줄(`tests`, `executed`, `skipped`, `failures`, `errors`)과 합계 줄을 출력하고, 마지막 줄은
`GATE PASS: test` 다.

개발 중에는 필요한 모듈만 실행한다.

```sh
sh runtime/gradlew -p runtime spotlessApply            # 형식 자동 수정 (google-java-format)
sh runtime/gradlew -p runtime :domain:test             # 한 모듈의 테스트
sh runtime/gradlew -p runtime :app:test --tests 'com.queryhub.architecture.*'
```

빌드·테스트 산출물은 모두 git 이 무시하는 경로(`runtime/.gradle/`, 각 모듈의 `build/`)에 생긴다. 게이트 스크립트와 테스트 설정은
품질 판정 기준이므로, 계획되지 않은 변경은 리뷰에서 범위 위반으로 다룬다.

## 실행

```sh
bash scripts/gate/build.sh

# desktop (기본 프로파일): 127.0.0.1:18580
java -jar runtime/app/build/libs/queryhub-runtime.jar

# desktop, 다른 포트
java -jar runtime/app/build/libs/queryhub-runtime.jar --server.port=18581

# server: 0.0.0.0:8080
SPRING_PROFILES_ACTIVE=server java -jar runtime/app/build/libs/queryhub-runtime.jar
```

- 프로파일을 지정하지 않으면 `desktop` 이다. 실수로 프로파일 없이 실행해도 루프백에만 열린다. 바인딩 주소의 공통 기본값
  (`application.yml`)도 `127.0.0.1` 이고, 모든 인터페이스로 넓히는 것은 `server` 프로파일뿐이다.
- `desktop` 과 `server` 를 함께 켜거나, 다른 프로파일만 켜서 둘 다 없으면 기동 단계에서 포트를 열기 전에 실패한다.
  `spring.main.lazy-initialization=true` 여도 마찬가지다(모드 검사 빈은 `@Lazy(false)`).
- P0-A 에는 인증이 없다. `server` 프로파일은 모든 인터페이스에 바인딩하므로 신뢰할 수 있는 네트워크에서만 실행한다. 관리자 토큰,
  루프백 전용 관리 API, API Key 는 Phase 1·4 에서 추가한다(계획).

## P0-A 엔드포인트

| 메서드·경로 | 응답 |
|---|---|
| `GET /actuator/health` | 200 `{"status":"UP", ...}` (인증 없음, 상세 정보 없음) |
| `GET /actuator/health/liveness` | 200 `{"status":"UP"}` |
| `GET /actuator/health/readiness` | 200 `{"status":"UP"}` (P0-B 부터 Metadata DB 상태가 포함된다, 계획) |
| `GET /admin/api/v1/runtime` | 200 런타임 상태 (아래 예) |
| 그 밖의 모든 경로 (다른 actuator 엔드포인트, `/api/v1/**` 포함) | 404 `NOT_FOUND` |

데이터 API(`/api/v1/{endpointPath}`)의 디스패처는 Phase 3·4 에 구현한다(계획). 지금은 공통 404 를 돌려준다.

성공 응답:

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

오류 응답(모든 오류가 같은 형식이며 Content-Type 은 `application/json` 이다):

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

| 코드 | HTTP | 코드 | HTTP |
|---|---|---|---|
| `INVALID_REQUEST`, `INVALID_PARAMETER` | 400 | `ROW_LIMIT_EXCEEDED` | 422 |
| `UNAUTHORIZED` | 401 | `RATE_LIMITED` | 429 |
| `FORBIDDEN` | 403 | `INTERNAL_ERROR`, `QUERY_FAILED` | 500 |
| `NOT_FOUND`, `API_NOT_FOUND` | 404 | `SERVICE_STOPPED` | 503 |
| `METHOD_NOT_ALLOWED` | 405 (`Allow` 헤더 포함) | `QUERY_TIMEOUT` | 504 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | | |

- 응답에는 예외 메시지, 클래스 이름, 스택 트레이스, SQL, DB 정보를 넣지 않는다. 500 의 상세 내용은 서버 로그에 `requestId` 와 함께
  ERROR 로 남는다.
- **X-Request-Id**: 요청 헤더가 `^[A-Za-z0-9._-]{1,64}$` 에 맞으면 그 값을, 아니면 새 UUID 를 쓴다. 모든 응답(actuator 와 오류
  포함)의 `X-Request-Id` 헤더와 `meta.requestId`/`error.requestId` 가 같은 값이다.
- **로그의 요청 ID**: 오류 응답을 만들 때 오류 처리기(`GlobalExceptionHandler`, `QueryHubErrorController`)가 남기는 로그는 메시지
  본문에 `requestId=<값>` 을 넣는다(예: `처리하지 못한 예외 (requestId=..., ...)`). 그 밖의 로그 줄에는 요청 ID 가 찍히지 않는다.
  필터가 요청 처리 중에 MDC 키 `requestId` 에도 같은 값을 넣지만, 지금의 로그 패턴(Boot 기본값)은 MDC 를 출력하지 않는다. 필터에서
  던진 예외의 `/error` 처리 로그는 필터가 MDC 를 지운 뒤에 남으므로, 그 요청 ID 는 요청 속성에서 읽어 메시지에 넣는다.
