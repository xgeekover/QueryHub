# QueryHub

SQL 기반 API 데이터 서비스 및 데이터 추출 데스크톱 플랫폼.

SQL 을 저장하고 API 엔드포인트와 연결해, 외부 시스템이 HTTP API 로 데이터를 조회할 수 있게 한다. 단순한 SQL 클라이언트가 아니라
데이터웨어하우스(DW)의 조회 API 서버로 쓸 수 있는 독립형 제품이다. 하나의 런타임(Spring Boot 실행 JAR)이 두 가지 모드로 동작한다.

- **desktop**: Tauri 데스크톱 앱이 런타임을 자식 프로세스로 띄운다. 루프백(127.0.0.1)에만 바인딩한다.
- **server**: `java -jar` 나 Docker 로 서버에 배포한다. 모든 인터페이스에 바인딩한다.

## 현재 상태: Phase 0 / P0-B (메타데이터 기반)

| 구분 | 내용 |
|---|---|
| 구현됨 (P0-A) | Gradle 멀티 모듈 빌드(`runtime/`), 모듈 의존 규칙(Gradle 선언 검사 + ArchUnit), 공통 응답·오류 형식, X-Request-Id, health 엔드포인트, 런타임 상태 관리 API, 프로파일(desktop/server), 게이트 스크립트(`scripts/gate/`) |
| 구현됨 (P0-B) | Metadata DB(MariaDB) 연결과 기동 검사, Flyway 마이그레이션 V1(`app_setting`), `AppSettingRepository` 포트와 JDBC 구현(`adapter-metadata`), ArchUnit R8, Testcontainers 통합 테스트와 통합 테스트 게이트(`integration.sh`), 개발용 compose(`deploy/compose.dev.yml`) |
| 계획 | Desktop 셸 스파이크(P0-C), CI(P0-D), 설계 문서 `docs/`(후속 태스크) |
| 계획 | DB 연결과 Provider 4종·관리자 토큰·보안·비밀번호 암호화(Phase 1), SQL 저장소(Phase 2), API 관리(Phase 3), API 런타임·API Key(Phase 4), Export(Phase 5), Desktop UI와 Metadata DB 접속 화면(Phase 6), 모니터링(Phase 7), 배포·Dockerfile(Phase 8) |

설계 문서(요구사항, 아키텍처, ADR, ERD, API 명세 등)는 `docs/` 에 둘 예정이다(계획: 별도 태스크). 그때까지는 이 README 가 저장소의 유일한 문서다.

## 저장소 구조

```
.
├─ runtime/                Gradle 루트 (settings.gradle, build.gradle, gradlew, gradle/libs.versions.toml)
│  ├─ domain/              오류 어휘(ErrorCode, QueryHubException), 실행 모드(RuntimeMode)
│  ├─ application/         유스케이스와 포트 (GetRuntimeInfoUseCase, RuntimeInfoService, AppSettingRepository)
│  ├─ adapter-web/         관리 REST, 공통 응답 형식, 요청 ID 필터, 오류 매핑
│  ├─ adapter-metadata/    메타데이터 저장소: JdbcAppSettingRepository, Flyway 마이그레이션(db/migration)
│  └─ app/                 Spring Boot 조립, 프로파일, Metadata DB 설정, 실행 JAR, 아키텍처 테스트, 통합 테스트(src/integrationTest)
├─ deploy/                 개발용 compose (compose.dev.yml), 환경변수 예시 (env.example)
├─ scripts/gate/           build · test · lint · integration 게이트 스크립트와 JUnit 결과 판정기
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
| adapter-metadata | `com.queryhub.adapter.metadata` | `:application`, `:domain`, 외부 라이브러리(Spring JDBC, Flyway, MariaDB 드라이버는 실행 시에만). 다른 어댑터는 안 된다 |
| app | `com.queryhub.app` | 모든 모듈 (유일한 조립 지점) |
| adapter-dbprovider, provider-{mssql,oracle,mariadb,postgresql}, adapter-export | | 계획 (Phase 1, Phase 5) |

규칙은 두 겹으로 강제한다.

1. **Gradle**: 모듈 선언 때문에 금지된 import 는 컴파일되지 않는다. 또 `runtime/build.gradle` 의 검사가 모든 모듈의 `api`,
   `implementation`, `compileOnly`, `compileOnlyApi`, `runtimeOnly` 선언과 `compileClasspath`·`runtimeClasspath` 에 상속되는 모든
   설정(사용자 정의 설정 포함)의 선언을 확인해, 규칙을 넓히는 선언이 있으면 어떤 태스크도 실행하기 전에 빌드를 실패시킨다. 파일
   의존성(`files(...)`, `fileTree(...)`)은 `app` 이외의 모든 모듈에서 거부한다. 테스트 설정(`testImplementation` 등)은 검사하지 않는다.
   모듈은 `settings.gradle` 에 최상위로 include 한 `:domain`, `:application`, `:app`, `:adapter-*`, `:provider-*` 중 하나여야 하고
   (중첩 프로젝트는 거부한다), `runtime/<이름>/` 에 자기 `build.gradle` 이 있어야 한다(`build.gradle.kts` 는 쓰지 않는다).
2. **ArchUnit** (`runtime/app/src/test/java/com/queryhub/architecture/`): R1 계층, R2 어댑터 간 독립, R3 domain 은 `java..` 만,
   R4 application 은 웹·HTTP·JDBC·Boot·서블릿·어댑터 금지, R5 관리 API(`adapter.web.admin`)와 데이터 API(`adapter.web.api`) 분리,
   R6 JDBC 드라이버 클래스(`org.mariadb.jdbc..` 등)는 `adapter.dbprovider.provider..` 만, R7 모든 클래스는 네 계층 중 하나에,
   R8 저장 기술(`org.springframework.jdbc..`, `org.flywaydb..`, `javax.sql..`, `com.zaxxer.hikari..`)은 `adapter.metadata..`,
   `adapter.dbprovider..`, `app..` 만(`java.sql` 은 허용). Gradle 규칙은 어떤 `adapter-*` 든 Spring JDBC 를 선언하게 두므로 R8 이 두 번째
   방어선이다. `ArchitectureRulesSelfTest` 가 고의 위반 픽스처로 R1~R8 이 실제로 실패하는지 확인한다.

## 준비물

- **빌드**: JDK 17 이상 하나면 된다. Gradle 은 이 JDK 로 실행되고, 컴파일·테스트에 쓰는 Java 21 toolchain 은 Gradle 이 자동으로
  준비한다(foojay resolver, `~/.gradle/jdks` 에 캐시). Gradle 9.7.1 도 wrapper 가 내려받는다.
- **실행**: 실행 JAR 은 Java 21 용으로 컴파일된다. `java -jar` 에는 **Java 21 이상 런타임**이 필요하다. JDK 17 로 실행하면
  `UnsupportedClassVersionError` 로 실패한다. Java 21 이 따로 없으면 Gradle 이 내려받은 toolchain 의 `bin/java` 를 쓸 수 있다
  (예: macOS 에서는 `~/.gradle/jdks/<배포판 디렉터리>/Contents/Home/bin/java`).
- **Metadata DB**: 런타임은 MariaDB 에 연결할 수 없으면 기동하지 않는다(아래 "Metadata DB"). 개발용 DB 는 `deploy/compose.dev.yml` 로
  띄운다.
- **Docker**: 통합 테스트 게이트(`integration.sh`)와 개발용 DB 에 필요하다. `build.sh`, `test.sh`, `lint.sh` 는 Docker 없이 돈다.
- **네트워크**: 첫 빌드에는 Maven Central 접근이 필요하다. Java 21 이 캐시에 없으면 foojay/Adoptium 접근도 필요하다. `mariadb:12.3`
  이미지가 없으면 첫 통합 테스트가 Docker Hub 에서 내려받는다.
- **셸**: 게이트 스크립트는 bash 3.2(macOS 기본) 이상과 POSIX 도구(find, awk, grep, mktemp)만 쓴다.

## 게이트 명령

저장소 루트에서 실행한다(스크립트는 어느 cwd 에서 실행해도 저장소 루트 기준으로 동작한다). 모두 성공하면 exit 0 이다.

```sh
bash scripts/gate/build.sh        # main·test·integrationTest 를 -Xlint:all -Werror 로 컴파일하고 실행 JAR 을 만든다
                                  # (assemble testClasses integrationTestClasses)
bash scripts/gate/test.sh         # 단위 테스트를 실제로 실행하고 JUnit XML 로 판정한다 (Docker 불필요)
bash scripts/gate/lint.sh         # 게이트 스크립트 문법(bash -n), Spotless 형식 검사, 컴파일
                                  # (spotlessCheck compileJava compileTestJava compileIntegrationTestJava)
bash scripts/gate/integration.sh  # 통합 테스트(실제 MariaDB, Testcontainers)를 실행하고 JUnit XML 로 판정한다 (Docker 필요)
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
`GATE PASS: test` 다. 테스트 JVM 은 셸의 `QUERYHUB_*` 환경변수를 물려받지 않는다(`runtime/build.gradle`). 그래서 `deploy/.env` 를
source 한 셸에서도 결과가 같다.

`integration.sh` 는 `runtime/app/src/integrationTest` 의 통합 테스트만 실행한다(단위 테스트는 실행하지 않는다). **Docker 가 필요하고,
Docker 가 없으면 건너뛰지 않고 실패한다.**

- `test.sh` 와 같은 판정을 쓴다: `selftest.sh` 먼저, `cleanIntegrationTest integrationTest --continue --no-build-cache`, 그리고
  `junit-check.sh --require-each --newer`(0건, 실패·오류, 건너뜀, 오래된 결과, 해석할 수 없는 결과를 거부한다).
- `src/integrationTest` 가 있는 모듈을 자동으로 찾는다. 하나도 없거나 `runtime/app` 이 없으면 실패한다.
- Gradle 을 실행하기 전에 `docker info` 로 데몬을 확인한다(최대 60 초). 응답이 없으면 실패한다.
- MariaDB 컨테이너(`mariadb:12.3`)는 Testcontainers 가 테스트 JVM 마다 하나 띄우고, 끝나면 Ryuk 가 지운다. 서버 기본값은 일부러
  latin1 / latin1_swedish_ci / +09:00 으로 두어, 런타임이 서버 기본값에 기대지 않는다는 것을 확인한다.
- 걸리는 시간: 이 머신(이미지가 이미 있을 때)에서 컨테이너 기동을 포함해 1 분 안팎이다. Gradle 태스크 제한은 10 분이다. 마지막 줄은
  `GATE PASS: integration (..., <초>s)` 다.

개발 중에는 필요한 모듈만 실행한다.

```sh
sh runtime/gradlew -p runtime spotlessApply            # 형식 자동 수정 (google-java-format)
sh runtime/gradlew -p runtime :domain:test             # 한 모듈의 테스트
sh runtime/gradlew -p runtime :app:test --tests 'com.queryhub.architecture.*'
```

빌드·테스트 산출물은 모두 git 이 무시하는 경로(`runtime/.gradle/`, 각 모듈의 `build/`)에 생긴다. 게이트 스크립트와 테스트 설정은
품질 판정 기준이므로, 계획되지 않은 변경은 리뷰에서 범위 위반으로 다룬다.

## Metadata DB

런타임은 메타데이터(설정, 이후 Phase 의 SQL·API 정의 등)를 MariaDB 에 둔다. desktop 과 server 가 같은 규칙을 쓴다. 접속 정보는
**환경변수로만** 받는다(`application*.yml` 에는 접속 정보가 없다).

| 환경변수 | 필수 | 기본값 | 규칙 |
|---|---|---|---|
| `QUERYHUB_METADATA_DB_HOST` | 예 | | 호스트 이름 또는 IPv4(영문자, 숫자, `.`, `-`), 또는 `[IPv6]` |
| `QUERYHUB_METADATA_DB_PORT` | 아니요 | `3306` | 1-65535 |
| `QUERYHUB_METADATA_DB_NAME` | 예 | | `[A-Za-z0-9_]{1,64}` |
| `QUERYHUB_METADATA_DB_USERNAME` | 예 | | 비어 있지 않음 |
| `QUERYHUB_METADATA_DB_PASSWORD` | 예 | | 비어 있지 않음. 환경변수로만 넘긴다 |

- **비밀번호는 명령행 인자(`--queryhub.metadata.db.password=...`)나 JVM 시스템 속성(`-D...`)으로 넘기지 않는다.** 프로세스 목록에
  보이기 때문이다. 그렇게 넘기면 런타임은 기동을 거부하고 `QUERYHUB_METADATA_DB_PASSWORD` 를 쓰라고 안내한다(값은 출력하지 않는다).
  명령행 인자나 `-D` 로 넘긴 `spring.application.json`(`SPRING_APPLICATION_JSON`) 안의 비밀번호도 같이 거부한다. 비밀 값이나 드라이버
  옵션을 담는 `spring.datasource.hikari.password`, `spring.datasource.hikari.jdbc-url`, `spring.datasource.hikari.data-source-properties.*`
  도 명령행 인자, `-D`, 그 두 곳의 `spring.application.json` 으로 넘기면 거부한다(메시지는 키 이름만 말하고 `QUERYHUB_METADATA_DB_*` 를
  쓰라고 안내한다). Spring Boot 4.1.2 는 바인딩 뒤에 Hikari 의 `jdbc-url`·`password` 를 검사한 접속 정보로 되돌리므로 이 둘은 실제 접속에
  쓰이지 않지만, 값이 프로세스 목록에 보이므로 방어적으로 거부한다. 그 밖의 `spring.datasource.hikari.*` 설정(풀 크기 등)은 운영자 설정이다.
  OS 환경변수 `SPRING_APPLICATION_JSON` 은 허용한다.
- Flyway 는 항상 검사한 접속 정보로 만든 연결 풀로 마이그레이션한다. `spring.flyway.url`, `spring.flyway.user`, `spring.flyway.password` 는
  어디서 넘겨도(명령행, `-D`, `spring.application.json`, 환경변수, 설정 파일) 접속에 쓰이지 않는다. 마이그레이션 사용자는
  `QUERYHUB_METADATA_DB_USERNAME` 이다.
- JDBC URL 은 런타임이 만든다: `jdbc:mariadb://<host>:<port>/<name>?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true&allowLocalInfile=false`.
  세션 시간대는 항상 UTC, LOCAL INFILE 은 항상 꺼진다(서버가 허용해도 드라이버가 파일 요청을 거부한다). 호스트와 이름에 URL 구분 문자를
  허용하지 않으므로 옵션을 바꿀 수 없다.
- `spring.flyway.enabled=false` 로 Flyway 를 끄면 마이그레이션 대신 연결 하나를 열어 확인한다. DB 에 닿지 않으면 기동은 같은 방식으로 실패한다.
- 기동 실패(웹 서버가 포트를 열기 전, `spring.main.lazy-initialization=true` 여도 마찬가지): 필수 변수가 없거나 비어 있음, 값이 규칙에
  어긋남(메시지는 문제가 있는 변수 이름을 모두 말한다), DB 에 닿지 않음(연결 제한 10 초), 자격 증명 거부.
- 기동할 때 Flyway 가 마이그레이션(`adapter-metadata` 의 `db/migration`, 지금은 V1 `app_setting`)을 적용한다. 그래서 **DB 사용자에게
  DDL 권한**(CREATE, ALTER, INDEX 등)이 필요하다. Flyway 는 기록 테이블(`flyway_schema_history`) 없이 테이블이 이미 있는 스키마에는
  마이그레이션을 거부한다(`baseline-on-migrate` 를 켜지 않는다). 빈 데이터베이스를 쓴다.
- 지원 서버: MariaDB 10.11 이상(정렬 규칙 `utf8mb4_uca1400_ai_ci`, `utf8mb4_nopad_bin` 이 필요하다). **검증한 버전은 MariaDB 12.3
  (`mariadb:12.3` = 12.3.3)뿐이다.**
- 연결은 평문이다. 원격 DB 용 TLS 옵션은 계획이다(Phase 1 보안 또는 Phase 6). 신뢰할 수 있는 네트워크에서만 원격 DB 를 쓴다.
- `GET /actuator/health` 에는 DB 상태(`db`)가 포함된다. DB 에 닿지 않으면 503 `DOWN` 이다. liveness 와 readiness 는 바뀌지 않았다.

### 개발용 DB (compose)

`deploy/compose.dev.yml` 이 개발용 MariaDB 12.3 을 `127.0.0.1:13306` 에 띄운다(이름 `queryhub-dev`, 볼륨 `queryhub-dev-metadata-db`).
값은 `deploy/.env`(git 이 무시한다)에서 읽는다. **메인 체크아웃에서만 실행한다**(worktree 에서 실행하지 않는다).
예시 파일 `deploy/env.example` 은 일부러 이름 앞에 점이 없다. 도구와 검사가 `.env*` 를 비밀 파일로 다루므로, 커밋하고 읽을 수 있어야
하는 예시는 그 패턴을 피한다.
**개발용 비밀번호에는 영문자, 숫자, `-`, `_` 만 쓴다.** 아래 `java -jar` 예는 셸이 `deploy/.env` 를 읽고(`. deploy/.env`) compose 는 같은
파일을 자기 dotenv 규칙으로 읽으므로, `$`, 따옴표, `\`, 공백이 든 값은 둘이 다르게 해석해 `Access denied` 가 날 수 있다.

```sh
cp deploy/env.example deploy/.env       # 그리고 QUERYHUB_METADATA_DB_PASSWORD 를 바꾼다 (영문자, 숫자, -, _ 만)
docker compose -f deploy/compose.dev.yml up -d --wait     # 시작 (healthcheck 가 통과할 때까지 기다린다)
docker compose -f deploy/compose.dev.yml stop             # 중지 (데이터는 볼륨에 남는다)
# 데이터까지 지우려면 중지한 뒤 컨테이너와 볼륨을 명시적으로 지운다.
docker compose -f deploy/compose.dev.yml rm -f metadata-db
docker volume rm queryhub-dev-metadata-db
```

## 실행

실행에는 Metadata DB 가 필요하다. 아래 예는 `deploy/.env` 의 변수를 환경변수로 넘긴다(명령행 인자로 넘기지 않는다).

```sh
bash scripts/gate/build.sh

# desktop (기본 프로파일): 127.0.0.1:18580
(set -a; . deploy/.env; set +a; java -jar runtime/app/build/libs/queryhub-runtime.jar)

# desktop, 다른 포트
(set -a; . deploy/.env; set +a; java -jar runtime/app/build/libs/queryhub-runtime.jar --server.port=18581)

# server: 0.0.0.0:8080
(set -a; . deploy/.env; set +a; SPRING_PROFILES_ACTIVE=server java -jar runtime/app/build/libs/queryhub-runtime.jar)
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
| `GET /actuator/health` | 200 `{"status":"UP", ...}` (인증 없음, 상세 정보 없음). P0-B 부터 Metadata DB(`db`) 상태를 포함한다(DB 에 닿지 않으면 503) |
| `GET /actuator/health/liveness` | 200 `{"status":"UP"}` |
| `GET /actuator/health/readiness` | 200 `{"status":"UP"}` (Metadata DB 를 readiness 에 넣는 것은 계획) |
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
