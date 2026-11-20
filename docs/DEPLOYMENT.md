# QueryHub 빌드·실행·배포

런타임 JAR 을 빌드하고 desktop·server 프로파일로 실행하는 방법, Metadata DB 변수, 개발용 DB, Desktop 셸의 실행 방법을 적는다. 지금 확인된 플랫폼은 macOS 뿐이다. Windows·Linux 는 **미검증**이다([BACKLOG.md](BACKLOG.md) PLAT-1). 설치본과 Docker 이미지는 계획(Phase 8)이다.

## 준비물 (구현됨, P0-A·P0-B)

- **빌드**: JDK 17 이상 하나면 된다. Gradle 은 이 JDK 로 실행되고, 컴파일·테스트에 쓰는 Java 21 toolchain 은 Gradle 이 자동으로 준비한다(foojay resolver, `~/.gradle/jdks` 에 캐시). Gradle 9.7.1 도 wrapper 가 내려받는다.
- **실행**: 실행 JAR 은 Java 21 용으로 컴파일된다. `java -jar` 에는 **Java 21 이상 런타임**이 필요하다. JDK 17 로 실행하면 `UnsupportedClassVersionError` 로 실패한다. Java 21 이 따로 없으면 Gradle 이 내려받은 toolchain 의 `bin/java` 를 쓸 수 있다(예: macOS 에서는 `~/.gradle/jdks/<배포판 디렉터리>/Contents/Home/bin/java`).
- **Metadata DB**: 런타임은 MariaDB 에 연결할 수 없으면 기동하지 않는다(아래 「Metadata DB 변수」, [DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)). 개발용 DB 는 `deploy/compose.dev.yml` 로 띄운다.
- **Docker**: 통합 테스트 게이트(`integration.sh`)와 개발용 DB 에 필요하다. `build.sh`, `test.sh`, `lint.sh` 는 Docker 없이 돈다.
- **네트워크**: 첫 빌드에는 Maven Central 접근이 필요하다. Java 21 이 캐시에 없으면 foojay/Adoptium 접근도 필요하다. `mariadb:12.3` 이미지가 없으면 첫 통합 테스트가 Docker Hub 에서 내려받는다.
- **셸**: 게이트 스크립트는 bash 3.2(macOS 기본) 이상과 POSIX 도구(find, awk, grep, mktemp)만 쓴다. 게이트 명령과 판정은 [TEST_PLAN.md](TEST_PLAN.md)에 있다.

## Metadata DB 변수 (구현됨, P0-B)

런타임은 메타데이터를 MariaDB 에 둔다. desktop 과 server 가 같은 규칙을 쓴다. 접속 정보는 **환경변수로만** 받는다(`application*.yml` 에는 접속 정보가 없다). 변수는 `queryhub.metadata.db.*` 에 바인딩되고 기동 전에 검사된다(`runtime/app/src/main/java/com/queryhub/app/config/metadata/MetadataDatabaseProperties.java`).

| 환경변수 | 필수 | 기본값 | 규칙 |
|---|---|---|---|
| `QUERYHUB_METADATA_DB_HOST` | 예 | | 호스트 이름 또는 IPv4(영문자, 숫자, `.`, `-`), 또는 `[IPv6]` |
| `QUERYHUB_METADATA_DB_PORT` | 아니요 | `3306` | 1-65535 |
| `QUERYHUB_METADATA_DB_NAME` | 예 | | `[A-Za-z0-9_]{1,64}` |
| `QUERYHUB_METADATA_DB_USERNAME` | 예 | | 비어 있지 않음 |
| `QUERYHUB_METADATA_DB_PASSWORD` | 예 | | 비어 있지 않음. 환경변수로만 넘긴다 |

- 비밀번호와 Hikari 비밀 키를 명령행 인자나 `-D` 로 넘기면 런타임이 기동을 거부한다. 규칙 전체는 [SECURITY.md](SECURITY.md)의 「Metadata DB 비밀 값 규칙」에 있다([ADR-011](ADR/ADR-011-metadata-secrets-env-only.md)).
- 값이 없거나 규칙에 어긋나면 포트를 열기 전에 기동이 실패하고, 메시지는 문제가 있는 변수 이름을 모두 말한다. JDBC URL, 서버 버전, DDL 권한, 빈 데이터베이스 조건은 [DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)에 있다.

## 개발용 DB (compose, 구현됨 P0-B)

`deploy/compose.dev.yml` 이 개발용 MariaDB 12.3 을 `127.0.0.1:13306` 에 띄운다(compose 프로젝트 이름 `queryhub-dev`, 서비스 `metadata-db`, 볼륨 `queryhub-dev-metadata-db`). 호스트 포트는 `QUERYHUB_METADATA_DB_PORT` 값이고, 없으면 13306 이다. 서버는 utf8mb4 / `utf8mb4_uca1400_ai_ci` / UTC 로 뜨고 root 비밀번호는 무작위다.

- 값은 `deploy/.env`(git 이 무시한다)에서 읽는다. **메인 체크아웃에서만 실행한다**(worktree 에서 실행하지 않는다. compose 프로젝트 이름이 `queryhub-dev` 로 고정돼 있어 worktree 마다 실행하면 같은 이름의 컨테이너를 다시 만든다. 개발용 DB 는 이름 있는 볼륨을 쓰고 호스트 경로를 바인드하지 않는다).
- 예시 파일 `deploy/env.example` 은 일부러 이름 앞에 점이 없다. 도구와 검사가 `.env*` 를 비밀 파일로 다루므로, 커밋하고 읽을 수 있어야 하는 예시는 그 패턴을 피한다([ADR-016](ADR/ADR-016-env-example-naming.md)).
- **개발용 비밀번호에는 영문자, 숫자, `-`, `_` 만 쓴다.** 셸은 `deploy/.env` 를 `. deploy/.env` 로 읽고 compose 는 같은 파일을 자기 dotenv 규칙으로 읽으므로, `$`, 따옴표, `\`, 공백이 든 값은 둘이 다르게 해석해 `Access denied` 가 날 수 있다.

```sh
cp deploy/env.example deploy/.env       # 그리고 QUERYHUB_METADATA_DB_PASSWORD 를 바꾼다 (영문자, 숫자, -, _ 만)
docker compose -f deploy/compose.dev.yml up -d --wait     # 시작 (healthcheck 가 통과할 때까지 기다린다)
docker compose -f deploy/compose.dev.yml stop             # 중지 (데이터는 볼륨에 남는다)
docker compose -f deploy/compose.dev.yml rm -f metadata-db  # 데이터까지 지우려면: 중지한 뒤 컨테이너를 지우고
docker volume rm queryhub-dev-metadata-db                   # 볼륨을 명시적으로 지운다
```

## 런타임 실행 (구현됨, P0-A·P0-B)

실행에는 Metadata DB 가 필요하다. 아래 예는 `deploy/.env` 의 변수를 환경변수로 넘긴다(명령행 인자로 넘기지 않는다). JAR 은 `runtime/app/build/libs/queryhub-runtime.jar` 에 생긴다.

차례로 빌드, desktop(기본 프로파일, `127.0.0.1:18580`), desktop 의 다른 포트, server(`0.0.0.0:8080`) 다.

```sh
bash scripts/gate/build.sh
(set -a; . deploy/.env; set +a; java -jar runtime/app/build/libs/queryhub-runtime.jar)
(set -a; . deploy/.env; set +a; java -jar runtime/app/build/libs/queryhub-runtime.jar --server.port=18581)
(set -a; . deploy/.env; set +a; SPRING_PROFILES_ACTIVE=server java -jar runtime/app/build/libs/queryhub-runtime.jar)
```

- 프로파일을 지정하지 않으면 `desktop` 이다. 실수로 프로파일 없이 실행해도 루프백에만 열린다. 바인딩 주소의 공통 기본값(`application.yml`)도 `127.0.0.1` 이고, 모든 인터페이스로 넓히는 것은 `server` 프로파일뿐이다.
- `desktop` 과 `server` 를 함께 켜거나, 다른 프로파일만 켜서 둘 다 없으면 기동 단계에서 포트를 열기 전에 실패한다. `spring.main.lazy-initialization=true` 여도 마찬가지다(모드 검사 빈은 `@Lazy(false)`).
- 지금은 인증이 없다. `server` 프로파일은 모든 인터페이스에 바인딩하므로 신뢰할 수 있는 네트워크에서만 실행한다. 관리자 토큰, 루프백 전용 관리 API, API Key 는 Phase 1·4 에서 추가한다(계획, [SECURITY.md](SECURITY.md)).
- 손으로 실행한 `java -jar` 에는 `QUERYHUB_DESKTOP_PARENT_PID` 가 없으므로 부모 감시가 동작하지 않는다([ADR-014](ADR/ADR-014-runtime-parent-watch.md)).

## Desktop 셸 (구현됨, P0-C 스파이크)

`desktop/` 은 Tauri 2.11 앱이다. 셸이 런타임을 자식 프로세스로 띄우는 방식, 상태와 버튼, 멈추는 방식은 [ARCHITECTURE.md](ARCHITECTURE.md)에 있다.

### 준비물 (desktop)

- Node 24, pnpm 11.9.0(`desktop/package.json` 의 `packageManager`), Rust 1.97 과 rustfmt·clippy, Xcode Command Line Tools(macOS).
- **JDK 21**(런타임 실행용). 셸은 `<java> -version` 의 주 버전이 21 미만이면 `java_too_old` 로 거부한다. 기본 `java` 가 17 이면 거부되므로 `QUERYHUB_DESKTOP_JAVA` 에 JDK 21 의 `bin/java` 를 지정한다(예: Gradle toolchain `~/.gradle/jdks/<배포판 디렉터리>/Contents/Home/bin/java`). macOS 의 `/usr/bin/java` 스텁이 아니라 실제 JDK 의 `bin/java` 를 가리켜야 한다([BACKLOG.md](BACKLOG.md) P0C-C2-3).
- 실행 중인 Metadata DB(개발용 compose)와 런타임 JAR.

### 실행 (개발 모드)

```sh
docker compose -f deploy/compose.dev.yml up -d --wait   # 메인 체크아웃에서. 중지는 위 "개발용 DB" 의 stop
sh runtime/gradlew -p runtime :app:bootJar              # 런타임 JAR (bash scripts/gate/build.sh 도 만든다)
export QUERYHUB_DESKTOP_JAVA="$HOME/.gradle/jdks/<배포판 디렉터리>/Contents/Home/bin/java"
pnpm --dir desktop install
(set -a; . deploy/.env; set +a; pnpm --dir desktop tauri dev)   # Metadata DB 변수는 이 하위 셸에만
```

Metadata DB 변수(비밀번호 포함)는 `( ... )` 하위 셸에서만 읽으므로 작업 중인 셸과 그 셸에서 나중에 띄우는 프로세스에는 남지 않는다. 런타임 실행 예와 같은 방식이다.

`tauri dev` 가 Vite 개발 서버(`http://localhost:1420`, strictPort)를 띄우고 앱을 연다. 앱은 시작하자마자 런타임을 자동으로 시작한다(버튼을 누르지 않아도 된다). 디버그 빌드는 상태 전이와 비밀번호를 가린 런타임 출력을 셸의 stderr 에 찍는다.

### 환경변수 (desktop)

코드: `desktop/src-tauri/src/runtime/launch.rs` 의 `JAVA_VAR`, `JAR_VAR`, `PARENT_PID_VAR`, `DB_*_VAR`.

| 변수 | 읽는 쪽 | 설명 |
|---|---|---|
| `QUERYHUB_DESKTOP_JAVA` | 셸 | java 실행 파일 경로. 없으면 `JAVA_HOME/bin/java`, 그다음 `PATH` 의 `java`. 지정했는데 실행할 수 없으면 다른 곳으로 넘어가지 않고 `java_not_found` |
| `QUERYHUB_DESKTOP_RUNTIME_JAR` | 셸 | 런타임 JAR 경로. 없으면 디버그 빌드는 저장소의 `runtime/app/build/libs/queryhub-runtime.jar`, 릴리스 빌드는 `jar_not_found`(JAR 묶기는 Phase 8 계획) |
| `QUERYHUB_METADATA_DB_HOST`, `_PORT`(선택), `_NAME`, `_USERNAME`, `_PASSWORD` | 셸 → 런타임 | 셸이 자기 환경에서 읽어 런타임에 **환경변수로만** 넘긴다(명령행 인자에 넣지 않는다). 셸은 비어 있지 않은지만 보고 형식 검사는 런타임이 한다. 없으면 `metadata_settings_missing`(빠진 이름만 말한다) |
| `QUERYHUB_DESKTOP_PARENT_PID` | 셸 → 런타임 | 셸의 PID. 런타임의 부모 감시가 읽는다([ADR-014](ADR/ADR-014-runtime-parent-watch.md)). 손으로 설정하지 않는다 |

- 셸은 허용 목록의 변수만 자식 런타임에 물려준다. 목록과 이유는 [SECURITY.md](SECURITY.md)의 「Desktop 셸의 자식 환경」에 있다([ADR-013](ADR/ADR-013-desktop-child-env-allowlist.md)).
- **계획**: Metadata DB 첫 실행 설정 화면과 OS 키체인 저장(Phase 6). 그때까지 Finder 에서 연 앱은 변수가 없어 `metadata_settings_missing` 을 보인다([ADR-017](ADR/ADR-017-desktop-metadata-settings-from-env.md)).
- 개발 도구(예: Claude Code)의 셸에서 앱을 시작하면 그 셸의 환경(세션 변수 포함)을 물려받는다. 셸이 허용 목록 밖의 변수를 런타임에 넘기지 않으므로 런타임에는 영향이 없지만, 깨끗한 환경에서 시작하려면 `env -i` 를 쓴다.

```sh
env -i HOME="$HOME" USER="$USER" PATH="$PATH" QUERYHUB_DESKTOP_JAVA="$QUERYHUB_DESKTOP_JAVA" \
  sh -c 'set -a; . deploy/.env; set +a; exec pnpm --dir desktop tauri dev'
```

### 서명하지 않은 번들 확인 (macOS)

```sh
pnpm --dir desktop tauri build --debug --bundles app --no-sign
```

결과는 `desktop/src-tauri/target/debug/bundle/macos/QueryHub.app` 이다(git 이 무시한다).

번들 안의 실행 파일(`QueryHub.app/Contents/MacOS/`)을 위 「실행 (개발 모드)」와 같은 환경에서 실행한다. 번들은 JAR 을 담지 않는다. 디버그 번들은 저장소의 JAR 경로를 기본값으로 쓰고, 다른 JAR 은 `QUERYHUB_DESKTOP_RUNTIME_JAR` 로 지정한다. `desktop/src-tauri/target` 은 작업 트리마다 수 GB 다.

## 계획 (Phase 8)

- **Desktop 설치본:** JRE 묶기(jlink), JAR 묶기(`bundle.resources`), dmg·설치 프로그램, 코드 서명과 공증. `bundle.externalBin` 사이드카는 쓰지 않는다([ADR-001](ADR/ADR-001-jvm-child-process.md)).
- **Server:** Docker 이미지와 `Dockerfile`(설계 §3 의 `deploy/`), 운영 프로파일과 관리자 토큰·마스터 키의 시크릿 파일 입력(Phase 1 과 함께).
- **플랫폼:** Windows·Linux 의 빌드와 실행은 **미검증**이다. CI 워크플로(`.github/workflows/ci.yml`, 정의됨·미실행)의 첫 실행과 Phase 8 에서 확인한다([BACKLOG.md](BACKLOG.md) CI-1, PLAT-1). Windows 의 `Path` 대소문자 처리(P0C-C2-4)는 P0-D 에서 고쳤지만 Windows 에서 실행해 보지 않았다.
