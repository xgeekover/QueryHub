# QueryHub

SQL 기반 API 데이터 서비스 및 데이터 추출 데스크톱 플랫폼.

SQL 을 저장하고 API 엔드포인트와 연결해, 외부 시스템이 HTTP API 로 데이터를 조회할 수 있게 한다. 단순한 SQL 클라이언트가 아니라
데이터웨어하우스(DW)의 조회 API 서버로 쓸 수 있는 독립형 제품이다. 하나의 런타임(Spring Boot 실행 JAR)이 두 가지 모드로 동작한다.

- **desktop**: Tauri 데스크톱 앱이 런타임을 자식 프로세스로 띄운다. 루프백(127.0.0.1)에만 바인딩한다.
- **server**: `java -jar` 로 서버에서 실행한다(Docker 이미지는 계획, Phase 8). 모든 인터페이스에 바인딩한다.

이 README 는 입구다. 자세한 내용은 아래 [문서](#문서)의 각 파일에 있다.

## 현재 상태: Phase 0 마무리 (P0-D 커밋 전)

Phase 0 의 마지막 태스크 P0-D 를 사람이 검토하고 커밋하면 Phase 0 이 끝난다. CI 워크플로는 정의만 했고 한 번도 실행되지 않았다(정의됨·미실행).

| 구분 | 내용 |
|---|---|
| 구현됨 (P0-A) | Gradle 멀티 모듈 빌드(`runtime/`), 모듈 의존 규칙(Gradle 선언 검사 + ArchUnit), 공통 응답·오류 형식, X-Request-Id, health 엔드포인트, 런타임 상태 관리 API, 프로파일(desktop/server), 게이트 스크립트(`scripts/gate/`) |
| 구현됨 (P0-B) | Metadata DB(MariaDB) 연결과 기동 검사, Flyway 마이그레이션 V1(`app_setting`), `AppSettingRepository` 포트와 JDBC 구현(`adapter-metadata`), ArchUnit R8, Testcontainers 통합 테스트와 통합 테스트 게이트(`integration.sh`), 개발용 compose(`deploy/compose.dev.yml`) |
| 구현됨 (P0-C) | Tauri 2.11 데스크톱 셸(`desktop/`): 런타임 JAR 을 자식 프로세스로 자동 시작·중지·재시작, 상태 화면(React 19 + shadcn/ui), 앱 종료 시 런타임 정지, 런타임의 부모 프로세스 감시(`QUERYHUB_DESKTOP_PARENT_PID`), desktop 프로파일의 관리 API CORS, 게이트의 desktop 단계(vitest, cargo test 판정기 `cargo-test-check.sh`, Tauri 버전 고정 검사) |
| 문서 기준선 (51141df) | `docs/` 의 요구사항, 아키텍처, ADR 19건, ERD, API 명세, DB 지원, 보안, 배포, 테스트 계획, 백로그와 루트의 `CHANGELOG.md` |
| 구현됨 (P0-D, 커밋 전) | CI 워크플로 `.github/workflows/ci.yml`(**정의됨·미실행**: 원격 저장소가 없어 한 번도 실행되지 않았다, [ADR-020](docs/ADR/ADR-020-ci-runs-gate-scripts.md)), 게이트의 `--desktop-only`, 문서 점검 `docs-check.sh`, 워크플로 점검 `workflow-check.sh`, `selftest.sh --no-skip`, stdout 만 읽는 Docker 사전 점검, Windows `Path` 처리(미검증), ADR 은 모두 20건, [Phase 0 보고](docs/PHASE0-REPORT.md) |
| 계획 | DB 연결과 Provider 4종·관리자 토큰·보안·비밀번호 암호화(Phase 1), SQL 저장소(Phase 2), API 관리(Phase 3), API 런타임·API Key(Phase 4), Export(Phase 5), Desktop UI·Metadata DB 접속 화면·운영 지표 화면(Phase 6), 모니터링과 운영 지표 수집(Phase 7), 배포·Dockerfile(Phase 8) |

Phase 별 범위와 통과 기준은 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#phase-계획), 태스크별 변경과 테스트 수는 [CHANGELOG.md](CHANGELOG.md), 남은 문제는 [docs/BACKLOG.md](docs/BACKLOG.md), Phase 0 의 결과·사람의 결정·미검증 항목은 [docs/PHASE0-REPORT.md](docs/PHASE0-REPORT.md)에 있다.

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
├─ docs/                   요구사항, 아키텍처, ADR/, ERD, API 명세, DB 지원, 보안, 배포, 테스트 계획, 백로그
├─ scripts/gate/           build · test · lint · integration 게이트 스크립트, JUnit 결과 판정기, cargo test 결과 판정기,
│                          문서 점검(docs-check.sh), 워크플로 점검(workflow-check.sh), 판정기 자체 검사(selftest.sh)
├─ .github/workflows/      CI 워크플로 ci.yml (정의됨·미실행, 팩토리 게이트와 같은 스크립트를 부른다)
├─ .editorconfig  .gitattributes  .gitignore
├─ CHANGELOG.md
└─ README.md
```

의존은 한 방향이다: `app → adapter-* → application → domain`. Gradle 선언 검사와 ArchUnit R1-R8 이 두 겹으로 강제한다.
규칙 전체는 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)의 「모듈과 의존 규칙」에 있다.

## 빠른 시작

준비물 (자세한 내용과 네트워크·셸 조건은 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)):

- **빌드**: JDK 17 이상. Java 21 toolchain 과 Gradle 9.7.1 은 Gradle 이 내려받는다. 실행(`java -jar`)에는 Java 21 이상이 필요하다.
- **Metadata DB 와 Docker**: 런타임은 MariaDB 에 연결할 수 없으면 기동하지 않는다. 개발용 DB 와 `integration.sh` 에 Docker 가 필요하다.
- **Desktop**: Node 24, pnpm 11.9.0, Rust(rustfmt·clippy), Xcode Command Line Tools(macOS), JDK 21(`QUERYHUB_DESKTOP_JAVA`).

게이트 (저장소 루트에서, 모두 성공하면 exit 0. 판정과 거짓 통과 방어는 [docs/TEST_PLAN.md](docs/TEST_PLAN.md)):

```sh
bash scripts/gate/build.sh        # main·test·integrationTest 를 -Xlint:all -Werror 로 컴파일하고 실행 JAR 을 만든다
                                  # (assemble testClasses integrationTestClasses)
bash scripts/gate/test.sh         # 단위 테스트를 실제로 실행하고 JUnit XML 로 판정한다 (Docker 불필요)
bash scripts/gate/lint.sh         # 게이트 스크립트 문법(bash -n), 문서 점검, 워크플로 점검, Spotless 형식 검사, 컴파일
                                  # (spotlessCheck compileJava compileTestJava compileIntegrationTestJava)
bash scripts/gate/integration.sh  # 통합 테스트(실제 MariaDB, Testcontainers)를 실행하고 JUnit XML 로 판정한다 (Docker 필요)
```

`build.sh`, `test.sh`, `lint.sh` 에 `--desktop-only` 를 주면 runtime(Gradle) 단계를 빼고 실행한다(Java 가 없는 CI 의 macOS·Windows 작업용). 팩토리 게이트와 CI 의 `gates` 작업은 인자 없이 실행한다.

개발용 DB (**메인 체크아웃에서만**, 중지와 삭제는 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)):

```sh
cp deploy/env.example deploy/.env       # 그리고 QUERYHUB_METADATA_DB_PASSWORD 를 바꾼다 (영문자, 숫자, -, _ 만)
docker compose -f deploy/compose.dev.yml up -d --wait
```

런타임 실행 (desktop 기본 프로파일, `127.0.0.1:18580`. 접속 정보는 환경변수로만 넘긴다):

```sh
bash scripts/gate/build.sh
(set -a; . deploy/.env; set +a; java -jar runtime/app/build/libs/queryhub-runtime.jar)
```

Desktop 셸 (개발 모드. 앱이 런타임을 자동으로 시작한다):

```sh
sh runtime/gradlew -p runtime :app:bootJar              # 런타임 JAR (build.sh 도 만든다)
export QUERYHUB_DESKTOP_JAVA="$HOME/.gradle/jdks/<배포판 디렉터리>/Contents/Home/bin/java"
pnpm --dir desktop install
(set -a; . deploy/.env; set +a; pnpm --dir desktop tauri dev)   # Metadata DB 변수는 이 하위 셸에만
```

지금은 인증이 없다. `server` 프로파일(`SPRING_PROFILES_ACTIVE=server`, `0.0.0.0:8080`)은 신뢰할 수 있는 네트워크에서만 실행한다([docs/SECURITY.md](docs/SECURITY.md)).

## 문서

| 문서 | 내용 |
|---|---|
| [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) | 명세 원문(2026-11-15, 그대로 보존)과 승인된 추가 요구사항(AR-0 설계 결정 D1–D4, AR-1 런타임 운영 지표) |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 전체 구조, 단일 포트, Desktop 과 Runtime(셸의 감독, 상태와 버튼, 멈추는 방식), 모듈 규칙, 기술 스택, Phase 계획, 위험, 설계와 다른 구현 |
| [docs/ADR/README.md](docs/ADR/README.md) | 아키텍처 결정 기록 ADR-001..ADR-020, 상태 규칙과 템플릿 |
| [docs/ERD.md](docs/ERD.md) | Metadata DB 테이블(`app_setting`)과 ERD 초안, 마이그레이션 규칙 |
| [docs/API_SPECIFICATION.md](docs/API_SPECIFICATION.md) | 엔드포인트, 공통 응답 형식, 오류 코드와 HTTP 상태, X-Request-Id, 관리 API CORS, 계획된 데이터·관리 API |
| [docs/DATABASE_SUPPORT.md](docs/DATABASE_SUPPORT.md) | Metadata DB(MariaDB 버전, JDBC URL, 드라이버, 권한)와 업무 DB 4종의 계획 |
| [docs/SECURITY.md](docs/SECURITY.md) | 명세 9절 정책과 상태, 구현된 통제, Metadata 비밀 값 규칙, 자식 환경 허용 목록, 알려진 빈 곳 |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | 준비물, Metadata DB 변수, 개발용 compose, 런타임 실행, Desktop 셸 실행과 변수, 서명 없는 번들 |
| [docs/TEST_PLAN.md](docs/TEST_PLAN.md) | 테스트 계층, 게이트 스크립트와 거짓 통과 방어, 게이트 범위, CI(정의됨·미실행), 테스트 수, 명세 12절 기준, 문서 점검 절차 |
| [docs/BACKLOG.md](docs/BACKLOG.md) | 리뷰에서 넘어온 후속 과제와 목표 Phase |
| [docs/PHASE0-REPORT.md](docs/PHASE0-REPORT.md) | Phase 0 보고: 통과 기준과 근거, 태스크별 요약, 사람의 결정, 남은 문제, 미검증 항목, 다음 단계 |
| [CHANGELOG.md](CHANGELOG.md) | 태스크별 변경, 테스트 수, 남은 문제 |
