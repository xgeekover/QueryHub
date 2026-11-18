# QueryHub

SQL 기반 API 데이터 서비스 및 데이터 추출 데스크톱 플랫폼.

SQL 을 저장하고 API 엔드포인트와 연결해, 외부 시스템이 HTTP API 로 데이터를 조회할 수 있게 한다. 단순한 SQL 클라이언트가 아니라
데이터웨어하우스(DW)의 조회 API 서버로 쓸 수 있는 독립형 제품이다. 하나의 런타임(Spring Boot 실행 JAR)이 두 가지 모드로 동작한다.

- **desktop**: Tauri 데스크톱 앱이 런타임을 자식 프로세스로 띄운다. 루프백(127.0.0.1)에만 바인딩한다.
- **server**: `java -jar` 나 Docker 로 서버에 배포한다. 모든 인터페이스에 바인딩한다.

## 현재 상태: Phase 0 / P0-C (Desktop 셸 스파이크)

| 구분 | 내용 |
|---|---|
| 구현됨 (P0-A) | Gradle 멀티 모듈 빌드(`runtime/`), 모듈 의존 규칙(Gradle 선언 검사 + ArchUnit), 공통 응답·오류 형식, X-Request-Id, health 엔드포인트, 런타임 상태 관리 API, 프로파일(desktop/server), 게이트 스크립트(`scripts/gate/`) |
| 구현됨 (P0-B) | Metadata DB(MariaDB) 연결과 기동 검사, Flyway 마이그레이션 V1(`app_setting`), `AppSettingRepository` 포트와 JDBC 구현(`adapter-metadata`), ArchUnit R8, Testcontainers 통합 테스트와 통합 테스트 게이트(`integration.sh`), 개발용 compose(`deploy/compose.dev.yml`) |
| 구현됨 (P0-C) | Tauri 2.11 데스크톱 셸(`desktop/`): 런타임 JAR 을 자식 프로세스로 자동 시작·중지·재시작, 상태 화면(React 19 + shadcn/ui), 앱 종료 시 런타임 정지, 런타임의 부모 프로세스 감시(`QUERYHUB_DESKTOP_PARENT_PID`), desktop 프로파일의 관리 API CORS, 게이트의 desktop 단계(vitest, cargo test 판정기 `cargo-test-check.sh`, Tauri 버전 고정 검사) |
| 계획 | CI(P0-D), 설계 문서 `docs/`(후속 태스크) |
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
├─ desktop/                Tauri 데스크톱 셸 (pnpm 프로젝트: package.json, pnpm-lock.yaml, vite.config.ts, components.json)
│  ├─ src/                 UI: App.tsx, components/RuntimePanel.tsx, components/ui/(shadcn), runtime/(shell.ts, adminApi.ts …)
│  └─ src-tauri/           Rust 크레이트 queryhub-desktop: Cargo.toml, Cargo.lock(NexTerm 에서 복사), tauri.conf.json,
│                          capabilities/, icons/, src/(lib.rs, commands.rs, runtime/: supervisor, launch, java, health, output)
├─ deploy/                 개발용 compose (compose.dev.yml), 환경변수 예시 (env.example)
├─ scripts/gate/           build · test · lint · integration 게이트 스크립트, JUnit 결과 판정기, cargo test 결과 판정기
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

## Desktop 셸 (P0-C 스파이크)

`desktop/` 은 Tauri 2.11 앱이다. Rust 셸이 런타임 JAR 을 자식 프로세스
`<java> -jar <jar> --spring.profiles.active=desktop --server.port=18580` 로 띄운다(Tauri sidecar·`externalBin` 은 쓰지 않는다).
UI 는 수명 주기만 셸 커맨드 네 개(`runtime_status`, `runtime_start`, `runtime_stop`, `runtime_restart`)로 다루고, 런타임 정보는
HTTP 로 관리 API 에서 읽는다. 셸은 `/admin/api/**` 를 부르지 않는다.

### 준비물 (desktop)

- Node 24, pnpm 11.9.0(`package.json` 의 `packageManager`), Rust 1.97 과 rustfmt·clippy, Xcode Command Line Tools(macOS).
- **JDK 21**(런타임 실행용). 셸은 `<java> -version` 의 주 버전이 21 미만이면 `java_too_old` 로 거부한다. 이 머신의 기본 `java`(17)는
  거부되므로 `QUERYHUB_DESKTOP_JAVA` 에 JDK 21 의 `bin/java` 를 지정한다(예: Gradle toolchain
  `~/.gradle/jdks/<배포판 디렉터리>/Contents/Home/bin/java`).
- 실행 중인 Metadata DB(개발용 compose)와 런타임 JAR.

### 실행 (개발 모드)

```sh
docker compose -f deploy/compose.dev.yml up -d --wait   # 메인 체크아웃에서. 중지는 위 "개발용 DB" 의 stop
sh runtime/gradlew -p runtime :app:bootJar              # 런타임 JAR (bash scripts/gate/build.sh 도 만든다)
set -a; . deploy/.env; set +a                           # Metadata DB 변수를 셸 환경으로
export QUERYHUB_DESKTOP_JAVA="$HOME/.gradle/jdks/<배포판 디렉터리>/Contents/Home/bin/java"
pnpm --dir desktop install
pnpm --dir desktop tauri dev
```

`tauri dev` 가 Vite 개발 서버(`http://localhost:1420`, strictPort)를 띄우고 앱을 연다. 앱은 시작하자마자 런타임을 자동으로 시작한다
(버튼을 누르지 않아도 된다). 디버그 빌드는 상태 전이(예: `runtime: starting -> failed(exited) ...`)와 비밀번호를 가린 런타임 출력을
셸의 stderr 에 찍는다.

### 환경변수 (desktop)

| 변수 | 읽는 쪽 | 설명 |
|---|---|---|
| `QUERYHUB_DESKTOP_JAVA` | 셸 | java 실행 파일 경로. 없으면 `JAVA_HOME/bin/java`, 그다음 `PATH` 의 `java`. 지정했는데 실행할 수 없으면 다른 곳으로 넘어가지 않고 `java_not_found` |
| `QUERYHUB_DESKTOP_RUNTIME_JAR` | 셸 | 런타임 JAR 경로. 없으면 디버그 빌드는 저장소의 `runtime/app/build/libs/queryhub-runtime.jar`, 릴리스 빌드는 `jar_not_found`(JAR 묶기는 Phase 8 계획) |
| `QUERYHUB_METADATA_DB_HOST`, `_PORT`(선택), `_NAME`, `_USERNAME`, `_PASSWORD` | 셸 → 런타임 | 셸이 자기 환경에서 읽어 런타임에 **환경변수로만** 넘긴다(명령행 인자에 넣지 않는다). 셸은 비어 있지 않은지만 보고 형식 검사는 런타임이 한다. 없으면 `metadata_settings_missing`(빠진 이름만 말한다) |
| `QUERYHUB_DESKTOP_PARENT_PID` | 셸 → 런타임 | 셸의 PID. 런타임의 부모 감시가 읽는다(아래). 손으로 설정하지 않는다 |

- 셸은 런타임(과 `java -version` 확인)을 빈 환경에서 띄우고, 자기 환경에서 허용 목록의 변수만 물려준다:
  `PATH`, `HOME`, `USER`, `LOGNAME`, `LANG`, `LC_*`, `TZ`, `TMPDIR`(Windows 는 `PATH`, `SYSTEMROOT`, `WINDIR`, `TEMP`,
  `TMP`, `USERPROFILE`, `APPDATA`, `LOCALAPPDATA`, `PATHEXT`, `COMPUTERNAME`, 대소문자 무시, 미검증). 그다음 위 Metadata DB
  변수와 `QUERYHUB_DESKTOP_PARENT_PID` 를 넣는다. 목적: Spring Boot 는 OS 환경변수를 `application-desktop.yml` 보다 앞세우므로,
  셸의 환경(`SERVER_ADDRESS`, `MANAGEMENT_*`, `LOGGING_*`, `DEBUG`, `SPRING_*`, `JAVA_TOOL_OPTIONS` 같은 JVM 옵션 변수 등)이
  런타임의 바인딩 주소, 엔드포인트 노출, 로깅, 프로파일을 바꾸지 못하게 한다. 허용 목록의 이름은 Spring 속성에 바인딩되지 않는다.
- 셸은 환경을 로그에 남기지 않고 로그 파일을 쓰지 않는다. 화면의 출력 꼬리(마지막 40 줄)와 디버그 stderr 에서는 비밀번호 값을
  `********` 로 바꾼다.
- **계획**: Metadata DB 첫 실행 설정 화면과 OS 키체인 저장(Phase 6). 그때까지 Finder 에서 연 앱은 변수가 없어
  `metadata_settings_missing` 를 보인다.
- Claude Code 셸에서 앱을 시작하면 그 셸의 환경(세션 변수 포함)을 물려받는다. 셸이 허용 목록 밖의 변수를 런타임에 넘기지 않으므로 런타임에는 영향이 없지만,
  깨끗한 환경에서 시작하려면 `env -i` 를 쓴다.

```sh
env -i HOME="$HOME" USER="$USER" PATH="$PATH" QUERYHUB_DESKTOP_JAVA="$QUERYHUB_DESKTOP_JAVA" \
  sh -c 'set -a; . deploy/.env; set +a; exec pnpm --dir desktop tauri dev'
```

### 상태와 버튼

| 상태 | 화면 | 시작 | 중지 | 재시작 |
|---|---|---|---|---|
| `stopped` | 중지됨 | 가능 | | |
| `starting` | 시작 중 | | 가능 | |
| `running` | 실행 중 | | 가능 | 가능 |
| `failed` | 실패 | 가능 | | 가능 |

- `starting` → `running`: `GET /actuator/health/readiness` 가 200 일 때(500 ms 간격). 90 초 안에 안 되면 런타임을 죽이고
  `failed(startup_timeout)`.
- `running` 동안 `GET /actuator/health` 를 5 초마다 확인해 health(정상·비정상)를 따로 보인다. 상태는 `running` 그대로다.
- 중지 요청 없이 런타임이 끝나면 `failed(exited)` 와 종료 코드, 출력 꼬리를 보인다. 자동 재시작은 하지 않는다.
- 실패 종류: `java_not_found`, `java_too_old`, `jar_not_found`, `metadata_settings_missing`, `port_in_use`
  (`lsof -nP -iTCP:18580 -sTCP:LISTEN` 으로 확인), `spawn_failed`, `exited`, `startup_timeout`. 메시지는 한국어이고 변수 이름과 경로만
  말한다(비밀 값은 넣지 않는다).
- 포트는 18580 고정이다. 운영 CSP 의 `connect-src` 와 데이터 API 사용자에게 고정 주소가 필요하므로 빈 포트를 찾지 않는다.
- `running` 이면 런타임 정보 카드(이름, 버전, 모드, 시작 시각, 가동 시간)를 `GET /admin/api/v1/runtime` 에서 10 초마다 읽는다.
  상태는 셸 커맨드로 1 초(시작 중)·3 초 간격으로 읽는다. 브라우저에서 개발 서버만 열면(Tauri 밖) 안내만 보이고 셸을 부르지 않는다.

### 런타임이 멈추는 방식

- **앱 종료**(창 닫기, Cmd+Q): `RunEvent::Exit` 에서 셸이 런타임에 SIGTERM 을 보내고(Spring 의 정상 종료), 10 초 안에 끝나지
  않으면 SIGKILL 한다. 중지·재시작 버튼도 같다.
- **셸이 비정상 종료**(SIGKILL, 크래시)되면 셸은 아무것도 할 수 없다. 대신 런타임의 부모 감시가 `QUERYHUB_DESKTOP_PARENT_PID` 의
  프로세스가 끝난 것을 감지해(JDK 가 최대 5 초 간격으로 확인하고 PID 재사용도 확인한다) INFO 로그 한 줄을 남기고 종료한다(exit 0).
  30 초 안에 끝나지 않으면 `Runtime.halt`. 값이 잘못되었으면(숫자가 아님, 0 이하, 살아 있지 않은 PID) 포트를 열기 전에 종료 코드 2 로
  끝난다. 변수가 없으면(server 모드, 손으로 실행한 `java -jar`) 감시하지 않는다.
- **미검증**: Windows 와 Linux(정상 종료 신호가 없는 Windows 의 즉시 종료, `java.exe` 콘솔 창, Windows 출처
  `http://tauri.localhost`, WebKitGTK). P0-D CI 에서 확인한다.

### CORS 와 개발 프록시

- 앱 번들의 UI(출처 `tauri://localhost`, Windows 는 `http://tauri.localhost`)는 `http://127.0.0.1:18580` 의 관리 API 를 직접 읽는다.
  desktop 프로파일(`application-desktop.yml`)의 `queryhub.admin.cors.allowed-origins` 가 이 두 출처만 둔다.
- 허용 범위: `/admin/api/v1/**` 만, GET 만, 요청 헤더 없음, 자격 증명 없음, 노출 헤더 `X-Request-Id`. server 프로파일과 기본 설정에는
  CORS 가 없다. 거부된 CORS 요청은 공통 오류 형식이 아니라 Spring 의 403 `Invalid CORS request` 다(Phase 1 에서 다시 본다).
  출처는 `scheme://host[:port]`(http, https, tauri)만 받는다. `*`, `null`, 경로가 있는 값은 기동을 실패시킨다.
- 개발 서버(`http://localhost:1420`)의 UI 는 같은 출처의 `/admin/api` 로 요청하고, Vite 프록시가 Origin 헤더를 지워
  `http://127.0.0.1:18580` 으로 넘긴다. 그래서 런타임은 개발 출처를 허용하지 않는다.
- Tauri 권한: `build.rs` 의 앱 매니페스트가 커맨드 네 개를 등록하고, `capabilities/default.json` 이 main 창에 `allow-runtime-status`,
  `allow-runtime-start`, `allow-runtime-stop`, `allow-runtime-restart` 만 준다(core 권한 없음). 운영 CSP 는
  `connect-src 'self' ipc: http://ipc.localhost http://127.0.0.1:18580` 이고 `'unsafe-eval'` 이 없다.
- **계획**: 관리자 토큰(Phase 1). 그때까지 이 머신의 모든 Tauri 앱이 같은 출처 `tauri://localhost` 로 관리 API 의 GET 응답을 읽을 수
  있다(Phase 0 의 관리 API 에는 민감한 정보가 없다).

### 게이트의 desktop 단계

| 게이트 | runtime 단계 뒤에 더한 단계 |
|---|---|
| `build.sh` | 필수 도구(node, pnpm, cargo), desktop 존재 확인, `pnpm --dir desktop install --frozen-lockfile`, `pnpm --dir desktop run build`(tsc --noEmit, vite build) 와 `dist/index.html` 확인, `cargo build --locked` |
| `test.sh` | 필수 도구, 존재 확인, 설치, vitest(이전 XML 을 지우고 실행, `junit-check.sh --require-each --newer` 로 판정), `cargo test --locked`(출력을 임시 파일에 담아 `cargo-test-check.sh --require-lib` 로 판정). 앞 단계가 실패해도 모든 단계를 실행하고, 모든 종료 코드가 0 일 때만 마지막 줄이 `GATE PASS: test` 다 |
| `lint.sh` | 필수 도구, 존재 확인, Tauri 버전 고정 검사, 설치, `pnpm --dir desktop run typecheck`, `pnpm --dir desktop run lint`(eslint `--max-warnings 0`), `cargo fmt --check`, `cargo clippy --locked --all-targets -- -D warnings` |

거짓 통과 방어:

- desktop 이 없거나 도구가 없으면 건너뛰지 않고 실패한다.
- vitest: 0 건, 실패, `it.skip`(skipped), 이전 실행의 XML 은 `junit-check.sh` 가 거부한다. `it.only` 는 Vitest 설정
  `allowOnly: false` 가, 테스트 파일이 하나도 없으면 `passWithNoTests: false` 가 실패시킨다.
- cargo test: `cargo-test-check.sh` 가 결과 없음 `[NO_RESULTS]`, 0 건 `[ZERO_TESTS]`, 실패 `[FAILURES]`, `#[ignore]` `[IGNORED]`,
  이름 필터 `[FILTERED]`, lib 단위 테스트 없음 `[LIB_WITHOUT_TESTS]`, 해석할 수 없는 출력이나 결과 없이 끝난 바이너리 `[INVALID]` 를
  거부한다. `selftest.sh` 가 시나리오마다 픽스처로 이 판정기를 먼저 검증한다.
- 잠금 파일: `--frozen-lockfile` 과 `cargo --locked` 는 잠금 파일과 어긋나면 실패한다. `Cargo.lock` 은 NexTerm 의 검증된 잠금 파일에서
  복사했다. `cargo update` 나 `cargo generate-lockfile` 은 실행하지 않는다.
- Tauri 버전 고정 검사: `@tauri-apps/api`·`@tauri-apps/cli` 가 정확한 2.11.x 이고, `Cargo.toml` 이 `tauri = "=2.11.5"`,
  `tauri-build = "=2.6.3"` 이고, `Cargo.lock` 의 tauri 가 2.11.5 하나이며 npm 패키지와 major.minor 가 같아야 한다. 어기면 그 항목을
  말하고 실패한다(Tauri CLI 는 major.minor 가 다르면 거부하고, 새로 해석하면 2.12 크레이트가 섞인다).
- 셸 감독 테스트는 가짜 자식(sh 스크립트, 경로에 표식 `queryhub-fake-runtime`)과 임시 포트의 작은 HTTP 서버를 쓴다. 실제 런타임과
  DB 가 필요 없다. 테스트가 끝난 뒤 `pgrep -fl queryhub-fake-runtime` 은 아무것도 출력하지 않아야 한다.
- 비용: `desktop/src-tauri/target` 은 작업 트리마다 수 GB 다. 처음 cargo build 는 Tauri 의존성을 컴파일한다.

### 서명하지 않은 번들 확인 (macOS)

```sh
pnpm --dir desktop tauri build --debug --bundles app --no-sign
# 결과: desktop/src-tauri/target/debug/bundle/macos/QueryHub.app (git 이 무시한다)
```

번들 안의 실행 파일(`QueryHub.app/Contents/MacOS/`)을 위 "실행" 과 같은 환경에서 실행한다. 번들은 JAR 을 담지 않는다. 디버그 번들은
저장소의 JAR 경로를 기본값으로 쓰고, 다른 JAR 은 `QUERYHUB_DESKTOP_RUNTIME_JAR` 로 지정한다.

**계획**: JRE 묶기(jlink), JAR 묶기(`bundle.resources`), dmg·설치 프로그램, 코드 서명과 공증(Phase 8). Windows·Linux 의 빌드와 실행은
**미검증**이다(P0-D CI, Phase 8).

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
