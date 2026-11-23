# QueryHub

SQL 기반 API 데이터 서비스 및 데이터 추출 데스크톱 플랫폼.

SQL 을 저장하고 API 엔드포인트와 연결해, 외부 시스템이 HTTP API 로 데이터를 조회할 수 있게 한다. 단순한 SQL 클라이언트가 아니라
데이터웨어하우스(DW)의 조회 API 서버로 쓸 수 있는 독립형 제품이다. 하나의 런타임(Spring Boot 실행 JAR)이 두 가지 모드로 동작한다.

- **desktop**: Tauri 데스크톱 앱이 런타임을 자식 프로세스로 띄운다. 루프백(127.0.0.1)에만 바인딩한다.
- **server**: `java -jar` 로 서버에서 실행한다(Docker 이미지는 계획, Phase 8). 모든 인터페이스에 바인딩한다.

이 README 는 입구다. 자세한 내용은 아래 [문서](#문서)의 각 파일에 있다.

## 현재 상태: Phase 0 완료, Phase 1 진행 중

Phase 0 은 마지막 태스크 P0-D 의 커밋(d0b0f0f, 2026-11-20)으로 완료됐다. Phase 1 의 첫 태스크 P1-01(P0-D 리뷰 후속, 99040c0, 2026-11-21)은 게이트의 두 점검기가 다루지 못하는 입력을 거부하게 했다. 둘째 태스크 P1-02(P1-01 리뷰 5회차 후속, 9fc70db (2026-11-22))는 워크플로 점검이 셸 시작 env 이름과 더 많은 표현식 모양을 거부하게 하고, 데스크톱 셸의 포트 확인이 macOS 에서 연결 시간에 기대지 않게 했다(P101-T1). CI 워크플로는 정의만 했고 한 번도 실행되지 않았다(정의됨·미실행).

셋째 태스크 P1-03(2698115 (2026-11-22))은 Phase 1 본래 범위의 첫 태스크로, 관리 API 에 관리자 토큰(Bearer)과 desktop 의 루프백 전용·Host 허용 목록을 더했다.

넷째 태스크 P1-04(037385b (2026-11-23))는 업무 DB 비밀번호를 암호화할 마스터 키와 `SecretCipher`(AES-256-GCM)를 더했다. desktop 은 OS 키체인 대신 셸이 만드는 키 파일을 쓴다(2026-11-23 사용자 결정, [ADR-023](docs/ADR/ADR-023-master-key-delivery.md)).

다섯째 태스크 P1-05(커밋 전)는 Spring Boot 4.1 을 그대로 두고 런타임을 OpenJDK 17 에서 컴파일·테스트·실행하게 했다(2026-11-23 사용자 결정, [ADR-025](docs/ADR/ADR-025-java-17-baseline.md)). 빌드는 로컬에 설치된 JDK 17 만 쓰고 JDK 를 내려받지 않는다. 데스크톱 셸은 Java 17 이상을 받는다. 다음 태스크 P1-06 은 Metadata 저장소를 SQLite 내장으로 바꾸는 계획이다(아직 구현되지 않았다, [REQUIREMENTS.md](docs/REQUIREMENTS.md) AR-2).

| 구분 | 내용 |
|---|---|
| 구현됨 (P0-A) | Gradle 멀티 모듈 빌드(`runtime/`), 모듈 의존 규칙(Gradle 선언 검사 + ArchUnit), 공통 응답·오류 형식, X-Request-Id, health 엔드포인트, 런타임 상태 관리 API, 프로파일(desktop/server), 게이트 스크립트(`scripts/gate/`) |
| 구현됨 (P0-B) | Metadata DB(MariaDB) 연결과 기동 검사, Flyway 마이그레이션 V1(`app_setting`), `AppSettingRepository` 포트와 JDBC 구현(`adapter-metadata`), ArchUnit R8, Testcontainers 통합 테스트와 통합 테스트 게이트(`integration.sh`), 개발용 compose(`deploy/compose.dev.yml`) |
| 구현됨 (P0-C) | Tauri 2.11 데스크톱 셸(`desktop/`): 런타임 JAR 을 자식 프로세스로 자동 시작·중지·재시작, 상태 화면(React 19 + shadcn/ui), 앱 종료 시 런타임 정지, 런타임의 부모 프로세스 감시(`QUERYHUB_DESKTOP_PARENT_PID`), desktop 프로파일의 관리 API CORS, 게이트의 desktop 단계(vitest, cargo test 판정기 `cargo-test-check.sh`, Tauri 버전 고정 검사) |
| 문서 기준선 (51141df) | `docs/` 의 요구사항, 아키텍처, ADR 19건, ERD, API 명세, DB 지원, 보안, 배포, 테스트 계획, 백로그와 루트의 `CHANGELOG.md` |
| 구현됨 (P0-D, d0b0f0f) | CI 워크플로 `.github/workflows/ci.yml`(**정의됨·미실행**: 원격 저장소가 없어 한 번도 실행되지 않았다, [ADR-020](docs/ADR/ADR-020-ci-runs-gate-scripts.md)), 게이트의 `--desktop-only`, 문서 점검 `docs-check.sh`, 워크플로 점검 `workflow-check.sh`, `selftest.sh --no-skip`, stdout 만 읽는 Docker 사전 점검, Windows `Path` 처리(미검증), ADR 은 모두 20건, [Phase 0 보고](docs/PHASE0-REPORT.md) |
| 구현됨 (P1-01, 99040c0) | P0-D 리뷰 후속 R1–R7. `workflow-check.sh` 는 YAML 파서가 아니므로 줄 단위로 다룰 수 없는 문법, 허용 목록 밖의 줄 모양, 여러 줄에 걸친 평범한 스칼라와 LF 가 아닌 줄바꿈 문자를 거부하고(`[UNSUPPORTED_SYNTAX]`), 같은 매핑의 같은 키(`[DUPLICATE_KEY]`), 대문자가 든 키(`[KEY_NOT_LOWERCASE]`, `env:` 변수 이름 말고), `continue-on-error`, `if:`, bash 가 아닌 `shell:`, 단계 밖의 `run:`(`[RUN_KEY]`), run 값 안의 `${{ }}` 를 거부하며, checkout 의 `persist-credentials: false` 는 그 단계의 `with:` 바로 아래만 센다. `docs-check.sh` 는 저장소 루트 밖으로 나가는 상대 링크를 깨진 링크로 센다. 두 점검기가 잡는 것과 잡지 못하는 것은 [SECURITY.md](docs/SECURITY.md)와 [TEST_PLAN.md](docs/TEST_PLAN.md)에 있다 |
| 구현됨 (P1-02, 9fc70db (2026-11-22)) | P1-01 리뷰 5회차 후속 C5-R1–R4 와 P101-T1, P0C-C2-1. `workflow-check.sh` 는 워크플로·job·단계 `env:` 의 셸·로더·러너 시작 변수 이름과 Windows 명령 찾기 변수(`BASH_ENV`, `PATH`, `PATHEXT`, `COMSPEC` 등)와 한 줄 `env:` 값(`[FORBIDDEN_ENV]`), 식별자 `secrets` 와 허용 속성 밖의 `github` 표현식(`[FORBIDDEN_EXPR]`), 블록 스칼라 안 빈 줄의 내용 열 앞 탭(`[UNSUPPORTED_SYNTAX]`)을 거부한다. 데스크톱 셸의 포트 확인(`launch::port_is_free`)은 macOS 에서 0.0.0.0·[::] 리스너를 바인딩만 하는 시험으로 찾고, TCP 자기 연결은 비어 있다로 본다(Windows 는 자기 연결 예외만 바뀌었고 미검증이다, PLAT-1). 남은 한계는 [SECURITY.md](docs/SECURITY.md)와 [TEST_PLAN.md](docs/TEST_PLAN.md)에 있다 |
| 구현됨 (P1-03, 2698115 (2026-11-22)) | 관리자 토큰([ADR-021](docs/ADR/ADR-021-admin-token-bearer.md), [ADR-022](docs/ADR/ADR-022-admin-token-implementation.md)). `/admin/**` 와 health 세 경로 밖의 `/actuator/**` 는 `Authorization: Bearer <토큰>` 을 요구한다(없거나 틀리면 401 `UNAUTHORIZED`, `WWW-Authenticate: Bearer`). 토큰이 없으면 런타임은 기동을 거부한다. 데스크톱 셸은 실행마다 OS 난수 생성기로 토큰을 만들어 런타임에는 환경변수 `QUERYHUB_ADMIN_TOKEN` 으로, UI 에는 Tauri 커맨드 `admin_token` 으로 넘긴다. server 는 `QUERYHUB_ADMIN_TOKEN` 이나 `QUERYHUB_ADMIN_TOKEN_FILE` 로 받는다. desktop 의 관리 요청은 소켓 원격 주소가 루프백이고 Host 가 허용 목록에 있을 때만 받는다(아니면 403 `FORBIDDEN`). Windows·Linux 의 셸은 미검증이다(PLAT-1). 남은 한계는 [SECURITY.md](docs/SECURITY.md)에 있다 |
| 구현됨 (P1-04, 037385b (2026-11-23)) | 마스터 키와 비밀 값 암호([ADR-023](docs/ADR/ADR-023-master-key-delivery.md), [ADR-024](docs/ADR/ADR-024-secret-cipher-format-and-rotation.md)). 런타임은 두 프로파일 모두 16진수 64 자의 마스터 키가 없으면 기동을 거부한다. server 는 `QUERYHUB_MASTER_KEY` 나 `QUERYHUB_MASTER_KEY_FILE` 로 받는다. desktop 셸은 첫 실행에 앱 로컬 데이터 디렉터리의 `secrets/master.key` 를 만들고(unix 0700/0600, 있는 파일은 덮어쓰지 않는다) 시작마다 읽어 환경변수로 넘긴다. 키 파일은 파일 권한만 지킨다([SECURITY.md](docs/SECURITY.md) 「마스터 키」). 새 모듈 `adapter-crypto` 의 AES-256-GCM 과 저장 형식 `qh1`. 비밀번호를 저장하는 연결 기능은 다음 태스크다. Windows·Linux 의 셸은 미검증이다(PLAT-1) |
| 구현됨 (P1-05, 커밋 전) | Java 17 기준선([ADR-025](docs/ADR/ADR-025-java-17-baseline.md)). Spring Boot 4.1.2 는 그대로다. Gradle toolchain 은 정확히 17 이고 로컬에 설치된 JDK 만 자동 감지한다(foojay 없음, `runtime/gradle.properties` 의 `org.gradle.java.installations.auto-download=false`). 단위·통합 테스트와 통합 테스트가 띄우는 실행 JAR 이 Java 17 에서 돈다. 테스트가 테스트 JVM 의 버전, 모듈마다 클래스 파일 버전(61), 실행 JAR 안의 모든 클래스 버전을 확인한다. 데스크톱 셸은 Java 17 이상을 받고 16 이하는 `java_too_old` 로 거부한다. CI 는 Temurin 17 을 준비한다(정의됨·미실행). 더 높은 Java 버전에서는 이 변경 뒤에 다시 시험하지 않았다. Windows·Linux 는 미검증이다(PLAT-1) |
| 계획 | DB 연결과 Provider 4종·보안·연결 비밀번호의 암호화 저장(Phase 1), SQL 저장소(Phase 2), API 관리(Phase 3), API 런타임·API Key(Phase 4), Export(Phase 5), Desktop UI·Metadata DB 접속 화면·운영 지표 화면(Phase 6), 모니터링과 운영 지표 수집(Phase 7), 배포·Dockerfile(Phase 8) |

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

- **빌드**: 로컬에 설치된 JDK 17(Gradle toolchain 은 정확히 17 이고 JDK 를 내려받지 않는다). Gradle 9.7.1 은 wrapper 가 내려받는다. 실행(`java -jar`)에는 Java 17 이상이 필요하다.
- **Metadata DB 와 Docker**: 런타임은 MariaDB 에 연결할 수 없으면 기동하지 않는다. 개발용 DB 와 `integration.sh` 에 Docker 가 필요하다.
- **Desktop**: Node 24, pnpm 11.9.0, Rust(rustfmt·clippy), Xcode Command Line Tools(macOS), JDK 17 이상(`QUERYHUB_DESKTOP_JAVA`).

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
export QUERYHUB_DESKTOP_JAVA="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java"   # JDK 17 이상의 bin/java
pnpm --dir desktop install
(set -a; . deploy/.env; set +a; pnpm --dir desktop tauri dev)   # Metadata DB 변수는 이 하위 셸에만
```

관리 API 는 관리자 토큰을 요구한다(P1-03). 데이터 API 의 API Key(Phase 4)와 바인딩 가드([docs/BACKLOG.md](docs/BACKLOG.md) P103-1)는 아직 없으므로 `server` 프로파일(`SPRING_PROFILES_ACTIVE=server`, `0.0.0.0:8080`)은 신뢰할 수 있는 네트워크에서만 실행한다([docs/SECURITY.md](docs/SECURITY.md)).

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
