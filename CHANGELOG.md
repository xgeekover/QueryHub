# 변경 이력

이 파일은 [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) 형식을 따른다. 분류 이름은 한국어로 쓴다: 추가, 변경, 수정, 보안. 아직 릴리스한 버전이 없으므로 모든 항목은 `[Unreleased]` 아래에 있다. 버전 번호 규칙은 첫 릴리스(Phase 8) 때 정한다.

Phase 마다 구현한 기능, 테스트 결과, 남은 문제를 적는다(명세 13절). 항목은 최신이 위에 온다. 날짜는 커밋 날짜(`git log -1 --format=%cs <커밋>`)다. 테스트 수는 그 태스크의 마지막 게이트 실행에서 센 값이다.

## [Unreleased]

### Phase 0 (진행 중)

남은 문제: [docs/BACKLOG.md](docs/BACKLOG.md)

#### 문서 기준선 (커밋 전)

##### 추가

- `docs/REQUIREMENTS.md`: 2026-11-15 명세 원문을 표시 사이에 그대로 보존하고, 승인된 변경을 추가 요구사항으로 적었다. AR-0 은 설계 결정 D1–D4, AR-1 은 2026-11-19 에 승인된 런타임 운영 지표(OM-1..OM-7, 계획: 화면 Phase 6, 수집 Phase 7)다.
- `docs/ADR/`: 상태 규칙, 템플릿, 목록과 ADR-001..ADR-019. 001–010 은 승인된 설계의 결정이고, 011–019 는 Phase 0 에서 정한 결정이다(015, 017, 018 은 제안).
- `docs/BACKLOG.md`: P0-A, P0-B, P0-C 리뷰에서 넘어온 후속 과제와 플랫폼·문서 과제.
- `docs/ARCHITECTURE.md`, `docs/ERD.md`, `docs/API_SPECIFICATION.md`, `docs/DATABASE_SUPPORT.md`, `docs/SECURITY.md`, `docs/DEPLOYMENT.md`, `docs/TEST_PLAN.md`: 명세 13절의 설계 문서. 지금 구현된 동작은 f84c628 의 코드에서 확인해 적었고, 나머지는 계획으로 적었다. `docs/ARCHITECTURE.md` 의 Phase 계획은 운영 지표의 화면을 Phase 6, 수집을 Phase 7 에 두고, 「설계와 다른 구현」 표가 승인된 설계와 달라진 10곳을 ADR 과 함께 적는다.
- 이 파일 `CHANGELOG.md`.

##### 변경

- `README.md`: 자세한 설명을 `docs/` 로 옮기고 소개, 현재 상태, 저장소 구조, 빠른 시작, 문서 목록만 남겼다.

테스트: 코드, 게이트 스크립트, 설정은 바뀌지 않았다.

#### P0-C Desktop 셸 스파이크 — f84c628 (2026-11-19)

##### 추가

- `desktop/`: pnpm 프로젝트(React 19, Vite 8, Tailwind 4, shadcn/ui)와 Tauri 2.11.5 크레이트. 버전은 정확히 고정했고, `Cargo.lock` 은 `--locked` 로만 쓴다.
- Rust 런타임 감독: java 21 이상과 런타임 JAR 찾기, 포트 확인(bind 와 연결 탐침), 기동·중지·재시작과 헬스 확인, 중지 때 SIGTERM 뒤 SIGKILL, 앱 종료 때 런타임 종료. Tauri 명령 네 개(`runtime_status`, `runtime_start`, `runtime_stop`, `runtime_restart`).
- Desktop UI: 상태, 헬스, 시작·중지·재시작 버튼, `GET /admin/api/v1/runtime` 의 런타임 정보 카드, 가린 출력 끝부분을 보이는 실패 카드.
- 런타임: `QUERYHUB_DESKTOP_PARENT_PID` 로 부모 셸을 감시해 앱이 죽으면 런타임도 끝난다. 값이 잘못되면 종료 코드 2 로 기동을 멈춘다([ADR-014](docs/ADR/ADR-014-runtime-parent-watch.md)).
- 게이트: build·test·lint 에 desktop 단계(frozen install, 타입 검사, eslint, vitest 와 junit-check, cargo build·test·fmt·clippy `--locked`), Tauri 버전 고정 검사, `scripts/gate/cargo-test-check.sh` 와 그 자체 검사. 서명하지 않은 디버그 번들 확인 절차.

##### 변경

- `README.md`: Desktop 준비물(JDK 21), 변수, 실행 절차, 게이트, 서명 없는 번들 확인.

##### 보안

- 자식 런타임에는 허용 목록의 환경변수만 넘긴다. 셸의 환경으로 런타임의 바인딩, 노출, 로깅, 프로파일을 바꿀 수 없다([ADR-013](docs/ADR/ADR-013-desktop-child-env-allowlist.md)).
- Metadata DB 설정은 환경변수로만 넘기고 명령행 인자에 넣지 않는다. 출력에 나온 비밀번호는 `********` 로 가린다([ADR-017](docs/ADR/ADR-017-desktop-metadata-settings-from-env.md)).
- 관리 API CORS 는 Tauri WebView 출처만, GET 만, 자격 증명 없이, desktop 프로파일에서만 허용한다. 출처 값이 잘못되면 기동을 멈춘다. WebView 는 엄격한 CSP 를 쓴다([ADR-015](docs/ADR/ADR-015-desktop-fixed-port-and-cors.md)).

테스트: runtime 단위 300, vitest 30, cargo test 30, 통합 23. 모두 통과, 건너뜀 0.

#### P0-B 메타데이터 기반 — f913239 (2026-11-18)

##### 추가

- `runtime/adapter-metadata` 모듈: application 계층에만 의존하고, `JdbcAppSettingRepository` 가 `AppSettingRepository` 포트를 구현한다([ADR-007](docs/ADR/ADR-007-spring-jdbc-metadata.md)).
- Flyway `V1__create_app_setting.sql`: utf8mb4, `utf8mb4_nopad_bin` 키, `DATETIME(6)`. 서버의 문자 집합, 정렬 규칙, 시간대와 무관하다. 마이그레이션 이름과 버전을 지키는 테스트.
- Metadata DB 연결: `QUERYHUB_METADATA_DB_*` 환경변수를 `queryhub.metadata.db.*` 에 바인딩한다. 값이 없거나 틀리거나, DB 에 닿지 않거나 인증이 실패하면 웹 서버가 열리기 전에 기동을 멈춘다. 메시지에는 비밀번호를 넣지 않는다([ADR-009](docs/ADR/ADR-009-mariadb-metadata-db.md)).
- 통합 테스트: Testcontainers 와 공유 `mariadb:12.3` 컨테이너. 기동, 스키마, LOCAL INFILE 거부, 저장소 왕복, 기동 실패, Flyway 고정, JAR 하위 프로세스 테스트.
- 게이트: `scripts/gate/integration.sh` (Docker 가 없으면 건너뛰지 않고 실패, junit-check 판정).
- 개발용 compose: `deploy/compose.dev.yml` 과 `deploy/env.example` (git 이 무시하는 `deploy/.env` 로 복사해서 쓴다, [ADR-016](docs/ADR/ADR-016-env-example-naming.md)).

##### 변경

- `build.sh` 와 `lint.sh` 가 통합 테스트 소스도 다룬다. 단위 테스트는 Docker 없이 돌고 물려받은 `QUERYHUB_*` 를 무시한다.
- `.gitignore`: 예시 파일 이름을 `deploy/env.example` 로 정하면서 쓰이지 않게 된 `!.env.example` 부정 패턴을 뺐다([ADR-016](docs/ADR/ADR-016-env-example-naming.md)).
- `README.md`: 모듈 목록, 의존 규칙, 환경변수 표, 비밀 값 처리, 통합 게이트, 개발용 compose.

##### 보안

- 프로세스에 보이는 출처(명령행 인자, JVM 시스템 속성, 그 둘로 넘어온 `spring.application.json`)의 Metadata 비밀번호와 Hikari `password`, `jdbc-url`, `data-source-properties` 키를 거부한다. 메시지는 키 이름만 말한다([ADR-011](docs/ADR/ADR-011-metadata-secrets-env-only.md)).
- Flyway 를 검사한 풀에 고정했다. `spring.flyway.url`, `user`, `password` 로 마이그레이션 연결을 돌릴 수 없다([ADR-012](docs/ADR/ADR-012-flyway-connection-pin.md)).
- MariaDB Connector/J 를 3.5.11 로 고정했다(GHSA-fhx5-2xqw-2cgm). 런타임 URL 에 `allowLocalInfile=false` 를 넣는다([ADR-018](docs/ADR/ADR-018-mariadb-connector-j-override.md)).

테스트: 단위 234, 통합 21. 모두 통과, 건너뜀 0.

#### P0-A 런타임 골격 — ee00165 (2026-11-17)

##### 추가

- `runtime/`: Gradle 9.7.1 멀티 모듈(domain, application, adapter-web, app), Java 21 toolchain, Spring Boot 4.1.2 BOM, 버전 카탈로그, 체크섬을 고정한 wrapper. 빌드 시점의 선언 의존 규칙이 Clean Architecture 방향을 강제한다([ADR-010](docs/ADR/ADR-010-spring-boot-4-java-21.md)).
- 공통 응답과 오류: 성공·오류 형식, HTTP 상태가 정해진 `ErrorCode`, 400/404/405/415/500 의 같은 형식 처리.
- 요청 ID: 유효한 `X-Request-Id` 는 그대로 쓰고 없으면 만든다. 모든 응답 헤더와 `meta.requestId` 에 싣는다.
- 프로파일: `desktop`(127.0.0.1:18580, 기본)과 `server`(0.0.0.0:8080). 모드 프로파일이 정확히 하나가 아니면 기동에 실패한다. health 프로브와 `GET /admin/api/v1/runtime`.
- ArchUnit: 계층, 슬라이스, domain·application 순수성, admin/api 분리, 패키지 위치 규칙과 일부러 위반하는 픽스처.
- 품질: Spotless 검사와 `javac -Xlint:all -Werror`. `scripts/gate/build.sh`, `test.sh`, `lint.sh`. `test.sh` 는 0건, 실패, 건너뜀, 이전 실행, 읽을 수 없는 JUnit 결과를 거부하고 selftest 로 판정기를 먼저 검사한다([ADR-019](docs/ADR/ADR-019-gate-scripts-judge-results.md)).
- 저장소 기본 파일: `.gitignore`, `.gitattributes`, `.editorconfig`, 한국어 `README.md`.

##### 보안

- 오류 응답에 스택 트레이스와 내부 메시지를 넣지 않는다. actuator 는 `health` 만 공개한다.
- desktop 프로파일은 기본으로 루프백(127.0.0.1)에만 바인딩한다.

테스트: 단위 107 (adapter-web 29, app 63, application 4, domain 11). 모두 통과, 건너뜀 0. 통합 게이트는 아직 없었다.
