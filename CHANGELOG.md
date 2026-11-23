# 변경 이력

이 파일은 [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) 형식을 따른다. 분류 이름은 한국어로 쓴다: 추가, 변경, 수정, 보안. 아직 릴리스한 버전이 없으므로 모든 항목은 `[Unreleased]` 아래에 있다. 버전 번호 규칙은 첫 릴리스(Phase 8) 때 정한다.

Phase 마다 구현한 기능, 테스트 결과, 남은 문제를 적는다(명세 13절). 항목은 최신이 위에 온다. 날짜는 커밋 날짜(`git log -1 --format=%cs <커밋>`)다. 테스트 수는 그 태스크의 마지막 게이트 실행에서 센 값이다.

## [Unreleased]

### Phase 1 (진행 중)

남은 문제: [docs/BACKLOG.md](docs/BACKLOG.md). P1-01 과 P1-02 는 Phase 1 의 범위(DB 연결, Provider, 관리자 토큰)가 아니라 P0-D 리뷰와 P1-01 리뷰의 후속이다. P1-03 이 Phase 1 범위의 첫 태스크(관리자 토큰), P1-04 가 둘째(마스터 키와 비밀번호 암호)다. P1-05 는 사용자 요청에 따라 Java 기준선을 17 로 맞춘 태스크다. 다음 태스크 P1-06(SQLite 내장 metadata 저장소)은 계획이다.

#### P1-05 Java 17 (커밋 전)

사용자 요청(2026-11-23)에 따른 태스크다. 결정은 [ADR-025](docs/ADR/ADR-025-java-17-baseline.md)(승인됨)에 있다. Spring Boot 4.1.2 는 그대로이고, [ADR-010](docs/ADR/ADR-010-spring-boot-4-java-21.md)(사용자 결정 D1)의 Java 21 부분을 대체한다. 설계 §0·§2 와 다른 점은 [ARCHITECTURE.md](docs/ARCHITECTURE.md) 「설계와 다른 구현」 12, 사용자 문구와 항목별 대응은 [REQUIREMENTS.md](docs/REQUIREMENTS.md) AR-2 에 있다.

##### 추가

- runtime: `JavaBaselineTest`(단위). 단위 테스트 JVM 이 Java 17 이고 domain, application, adapter-web, adapter-metadata, adapter-crypto, app 의 main 클래스가 클래스 파일 주 버전 61 인지 확인한다. 기대값은 일부러 17/61 로 고정했다.
- runtime: `BootJarClassVersionIntegrationTest`(통합, Docker 불필요). 실행 JAR 의 Boot 로더, `BOOT-INF/classes`, `BOOT-INF/lib/*.jar` 안의 모든 클래스가 주 버전 61 이하이고(멀티 릴리스 JAR 의 `META-INF/versions/18+` 만 뺀다) `com/queryhub` 클래스는 정확히 61 인지 확인한다. 어긋나면 JAR 과 항목을 모두 적는다. 검사기 자체의 음성 시험도 있다.
- desktop 셸: `java::is_supported`(순수 함수)와 플랫폼과 무관한 경계 시험(16 거부, 17·21 허용, `MIN_MAJOR == 17`, `parse_major("16.0.2") == Some(16)`). 감독 시험: 가짜 java 17.0.19 가 버전 확인을 통과하고 런타임을 띄운다.
- 문서: ADR-025, REQUIREMENTS AR-2 와 AR-0 D1·D2 의 주, ARCHITECTURE 「설계와 다른 구현」 12, [BACKLOG](docs/BACKLOG.md) P104-R1–P104-R7(P1-04 리뷰 1회차 지적. R7 은 사용자 문구 「키ID 방식은 나중에 도입」).

##### 변경

- runtime: Java toolchain 을 `JavaLanguageVersion.of(21)` 에서 `JavaLanguageVersion.of(17)` 로 바꿨다. 컴파일(`-Xlint:all -Werror -parameters` 그대로), 단위·통합 테스트, 통합 테스트가 띄우는 실행 JAR 이 JDK 17 에서 돈다.
- runtime: `runtime/settings.gradle` 에서 foojay resolver 플러그인을 뺐고 `runtime/gradle.properties` 에 `org.gradle.java.installations.auto-download=false` 를 더했다. JDK 를 내려받지 않고 로컬에 설치된 JDK 17 을 자동 감지로 찾는다. JDK 17 이 없으면 Gradle 의 toolchain 오류로 실패한다(실제 메시지는 [DEPLOYMENT.md](docs/DEPLOYMENT.md) 준비물).
- runtime 시험: `MetadataStartupFailureTest` 의 `Thread.join(Duration)`(Java 19)을 `Thread.join(long)` 으로 바꿨다(같은 5 초). `BootJarIntegrationTest` 는 통합 테스트 JVM 이 17 이고 실행 JAR 의 기동 줄에 `using Java 17.` 이 있는지도 확인한다. 주석의 Java 21 을 17 로 고쳤다(`ParentProcessWatch` 의 javadoc 포함, 동작 변경 없음).
- desktop 셸: `MIN_MAJOR` 21 → 17. 16 이하는 `java_too_old` 다. `launch.rs` 의 안내 문구 두 곳은 숫자를 `MIN_MAJOR` 에서 가져온다. 감독 시험 (9) 는 가짜 java 16.0.2 가 `java_too_old` 이고 메시지가 16.0.2, java 경로, `QUERYHUB_DESKTOP_JAVA`, 17 을 말하는지 본다(예전에는 17.0.2 를 거부했다).
- CI: `.github/workflows/ci.yml` 의 `setup-java` 단계 이름과 `java-version` 을 17 로 바꿨다. 정의됨·미실행 그대로다(CI-1).
- 문서: `README.md`, `docs/DEPLOYMENT.md`(준비물, desktop 준비물, `QUERYHUB_DESKTOP_JAVA` 예시), `docs/ARCHITECTURE.md`, `docs/TEST_PLAN.md`, ADR-001, ADR-010(상태 줄과 결과 줄의 주), ADR-020, `docs/ADR/README.md`. P1-04 를 아직 커밋하지 않은 것으로 적던 곳을 037385b (2026-11-23) 로 바꿨다(`README.md`, `CHANGELOG.md`, `docs/DEPLOYMENT.md`, `docs/SECURITY.md`, `docs/TEST_PLAN.md`, ADR-006, ADR-023, ADR-024, `docs/ADR/README.md`). `README.md` 의 「지금은 인증이 없다」를 P1-03 의 관리자 토큰에 맞게 고쳤다.

테스트(macOS 로컬의 게이트 실행, Java 17.0.19): runtime 단위 871, 통합 28, vitest 43, cargo test 71, 모두 통과, 건너뜀 0. `selftest.sh` 는 279 시나리오다. Java 21 이상에서는 이 변경 뒤에 다시 돌리지 않았다. CI 의 수는 없다(미실행). Windows·Linux 는 미검증이다(PLAT-1).

#### P1-04 마스터 키와 SecretCipher — 037385b (2026-11-23)

Phase 1 범위의 둘째 태스크다. 결정은 [ADR-023](docs/ADR/ADR-023-master-key-delivery.md)(제안. 키체인을 쓰지 않는다는 것은 2026-11-23 사용자 결정)과 [ADR-024](docs/ADR/ADR-024-secret-cipher-format-and-rotation.md)(제안)에 있다. 설계 §1.2·§7 과 다른 점은 [ARCHITECTURE.md](docs/ARCHITECTURE.md) 「설계와 다른 구현」 11 이다.

##### 추가

- runtime: 포트 `SecretCipher`, `SecretContext`, `SecretCipherException`(`com.queryhub.application.secret`)과 JDK 만 쓰는 새 모듈 `runtime/adapter-crypto`(`MasterKey`, `StoredSecretFormat`, `AesGcmSecretCipher`). AES-256-GCM, 96 비트 nonce, 128 비트 태그, 저장 형식 `qh1.<kid>.<base64url>`, AAD 는 헤더와 문맥(purpose, recordId)이다. 알려진 답 시험이 형식을 고정한다. 여러 키 복호화, `needsReencryption`, `reencrypt` 를 구현했다.
- runtime: `MasterKeySource` 와 `SecretCipherConfiguration`(`@Lazy(false)`). `QUERYHUB_MASTER_KEY` 와 `QUERYHUB_MASTER_KEY_FILE` 중 하나를 16진수 64 자로 받는다. 키가 없거나, 둘 다 있거나, 파일이나 형식이 틀리거나, 약한 키이거나, 관리자 토큰과 같거나, 명령행·`-D`·그 두 곳의 `spring.application.json` 에서 오면 두 프로파일 모두 포트를 열기 전에 기동을 거부한다. 기동 로그에는 kid 만 남긴다.
- runtime: ArchUnit R10(`javax.crypto..` 는 `adapter.crypto..` 만)과 자체 검증 픽스처.
- desktop 셸: `runtime/master_key.rs`. 첫 실행에 앱 로컬 데이터 디렉터리의 `secrets/master.key` 를 `create_new` 로 만들고(unix 0700/0600, `sync_all` 뒤 다시 읽어 비교한다) 시작마다 다시 읽는다. 있는 파일은 덮어쓰지 않는다. 심볼릭 링크, 일반 파일이 아닌 것, 4096 바이트 초과, 형식 오류, 약한 키, unix 의 다른 소유자와 그룹·기타 권한 비트는 거부하고 `chmod`·`chown` 명령을 안내한다. 새 크레이트는 없다(`std`, `getrandom`, unix 의 `libc`).
- desktop 셸: `SupervisorConfig.master_key_path`(운영 경로는 `lib.rs` 만 계산한다), 사전 점검의 마지막 단계, 자식 환경 `QUERYHUB_MASTER_KEY`, 출력 가림, 실패 종류 `master_key_unavailable`(UI 의 `FailureKind` 도).
- 문서: ADR-023, ADR-024, `docs/SECURITY.md` 「마스터 키」, `docs/DEPLOYMENT.md` 「마스터 키」, `docs/ARCHITECTURE.md` 「설계와 다른 구현」 11, `deploy/env.example` 의 주석 자리표시자 줄, [BACKLOG](docs/BACKLOG.md) P104-1–P104-8.

##### 변경

- `StartupFailureHalt` 의 stderr 알림이 ASCII 표식 `QUERYHUB_STARTUP_HALT exit=1 after=15s` 로 시작한다(P1-03 리뷰 C4-R1). LANG=C 에서도 읽힌다. 중단 동작은 그대로다.
- 컨텍스트를 띄우는 runtime 시험과 통합 시험은 시험용 키(NOT A SECRET)를 인라인 속성, 기본 속성, 자식 환경으로 준다(`-D` 는 쓰지 않는다).
- 문서: ADR-006, ADR-013, `docs/ADR/README.md`, `docs/TEST_PLAN.md`, `README.md`. P1-03 을 아직 커밋하지 않은 것으로 적던 곳을 2698115 (2026-11-22) 로 바꿨다(`README.md`, `CHANGELOG.md`, `docs/API_SPECIFICATION.md`, `docs/BACKLOG.md`, `docs/DEPLOYMENT.md`, `docs/SECURITY.md`, `docs/TEST_PLAN.md`, ADR-021, ADR-022, `docs/ADR/README.md`).

##### 보안

- 키는 argv, 로그, 오류 응답, 예외 메시지, `toString`·Debug, actuator 출력, 상태 JSON, WebView 에 나오지 않는다(누출 시험과 cargo 시험).
- 남은 것: desktop 의 키는 파일 권한만 지킨다(같은 사용자의 프로세스, 관리자·root, 백업과 복사본이 읽을 수 있다. P104-2). 이전 키 입력과 재암호화(P104-4), desktop 의 키 교체(P104-5), Windows 의 DACL 과 Windows·Linux 확인(P104-7, PLAT-1).

테스트(macOS 로컬의 게이트 실행): runtime 단위 868, 통합 26, vitest 43, cargo test 68, 모두 통과, 건너뜀 0. `selftest.sh` 는 279 시나리오다. 수동 확인(AC28)은 Queen 이 한다([docs/TEST_PLAN.md](docs/TEST_PLAN.md)).

#### P1-03 관리자 토큰 — 2698115 (2026-11-22)

Phase 1 범위의 첫 태스크다. 결정은 [ADR-021](docs/ADR/ADR-021-admin-token-bearer.md)(승인됨)과 [ADR-022](docs/ADR/ADR-022-admin-token-implementation.md)(제안)에 있다. [BACKLOG](docs/BACKLOG.md) P0A-I1 과 P0C-C2-2 를 닫는다.

##### 추가

- runtime: Spring Security(`spring-boot-starter-security`, BOM 관리)의 관리 보안 체인 하나(`com.queryhub.adapter.web.admin.security`). `/admin/**`(대소문자 무관)와 health 세 경로 밖의 `/actuator/**` 는 `Authorization: Bearer <토큰>` 을 요구한다. 비교는 SHA-256 다이제스트의 `MessageDigest.isEqual` 이고 ArchUnit R9 가 지킨다. 토큰 실패는 401 `UNAUTHORIZED`(`WWW-Authenticate: Bearer`), 네트워크 검사 실패는 403 `FORBIDDEN` 이고 둘 다 공통 오류 형식과 `X-Request-Id` 를 쓴다.
- runtime: 토큰 출처 `QUERYHUB_ADMIN_TOKEN` 또는 `QUERYHUB_ADMIN_TOKEN_FILE`(4 KiB 이하, 끝의 LF·CRLF 하나를 지운다). 32-512 자 RFC 6750 b64token. 없거나, 둘 다 있거나, 파일·형식이 틀리거나, 명령행·`-D`·그 두 곳의 `spring.application.json` 에서 오면 포트를 열기 전에 기동을 거부한다. ADR-011 의 프로세스 출처 검사는 공용 `ProcessVisibleSources` 로 옮겼다(Metadata DB 메시지는 그대로).
- runtime: desktop 의 관리 요청은 소켓 원격 주소가 루프백일 때만 받는다(`queryhub.admin.allow-remote`, server 는 true). allow-remote=false 이면 소켓 원격 주소를 바꾸는 설정을 기동에서 거부한다: `server.forward-headers-strategy` 가 none 이 아닌 값, 값이 있는 `server.tomcat.remoteip.*` 속성(접두사 전체), 만들어진 Tomcat 파이프라인의 RemoteIpValve 나 허용 목록 밖의 valve(`TomcatRemoteAddressGuard`, 포트를 열기 전의 `SmartLifecycle`). 그래서 이 조건에서 `X-Forwarded-For`·`Forwarded` 는 루프백 판정에 쓰이지 않는다(리뷰 1회차 R1 이 1회차 JAR 에서 `remote-ip-header`·`protocol-header` 만으로 판정이 바뀌는 것을 재현했다). Host 허용 목록(desktop 은 늘, server 는 `queryhub.admin.allowed-hosts` 를 설정할 때만).
- runtime: 두 프로파일 모두 `management.endpoints.web.base-path` 가 `/actuator` 가 아니거나, `management.server.port` 로 actuator 를 다른 포트에 열거나, `spring.mvc.servlet.path` 가 `/` 가 아니면 기동을 거부한다. 관리 체인은 그 경로와 포트를 보호하지 않는다(리뷰 1회차 R4. 2회차에 1회차 JAR 이 `spring.mvc.servlet.path=/qh` 에서 `/qh/admin/api/v1/runtime` 을 토큰 없이 200 으로 답하는 것을 확인했다).
- runtime (리뷰 2회차 후속): 관리 체인을 끄거나 핸들러를 체인 밖으로 옮기는 설정을 세 겹으로 막는다. 두 프로파일 모두 `spring.security.filter.dispatcher-types` 가 Boot 기본 다섯 가지를 다 갖지 않을 때, `management.endpoints.web.path-mapping.*` 에 값이 있을 때, health 추가 경로(`management.endpoint.health.probes.add-additional-paths=true`, `management.endpoint.health.group.*.additional-path`)가 있을 때, `server.tomcat.accesslog.pattern` 에 `Authorization` 이 들어가거나 `spring.mvc.log-request-details=true` 일 때 기동을 거부한다. 포트를 열기 전에 Spring Security 필터의 Tomcat 매핑(`/*`, dispatcher 다섯 가지, 앞의 필터는 허용 목록)을 검사한다(`TomcatSecurityFilterGuard`). 포트를 연 직후 readiness 전에 토큰 없는 요청으로 자기 점검을 하고, 관리 경로가 체인의 401·403 이 아니거나 열린 health 경로가 health 문서·404 가 아니면 멈춘다(`AdminChainSelfProbe`). 내장 TLS(`server.ssl.*`)는 점검할 수 없어 기동을 거부한다.
- desktop 셸: `runtime/token.rs` 의 `AdminToken`. 셸 프로세스마다 한 번 `getrandom`(0.3.4, Cargo.lock 에 이미 있던 버전을 직접 의존으로 더했다. 잠금 파일은 의존 한 줄만 바뀌었다)으로 32 바이트를 받아 소문자 16진수 64 자로 만든다. Debug·Display 는 값을 보이지 않고, 만들지 못하면 setup 이 실패한다. 자식 환경에 `QUERYHUB_ADMIN_TOKEN` 을 더하고(인자에는 넣지 않는다), Tauri 커맨드 `admin_token` 과 권한 `allow-admin-token`(main 창, 로컬 출처)을 더했다.
- UI: `shell.ts` 의 `adminToken()`, `useAdminToken` 훅(한 번, 메모리에만). `fetchRuntimeInfo(baseUrl, token, timeout)` 는 `Authorization: Bearer <토큰>` 헤더 하나만 더하고(`credentials: "omit"` 을 명시한다) 토큰이 없으면 요청하지 않는다. 401 과 403 은 서로 다른 고정 한국어 문장이다.
- 문서: ADR-021, ADR-022, `docs/SECURITY.md` 「관리자 토큰」, `docs/API_SPECIFICATION.md` 「관리자 토큰 인증」, `docs/DEPLOYMENT.md` 「관리자 토큰」, `deploy/env.example` 의 주석 자리표시자 줄(값 없음).

##### 변경

- CORS 는 `WebMvcConfigurer` 매핑 대신 관리 체인의 `CorsConfigurationSource` 다. 허용 요청 헤더는 `Authorization` 하나이고, 사전 요청은 토큰 없이 답하며 요청한 헤더가 모두 허용될 때만 통과한다. 허용 출처의 401 에도 `Access-Control-Allow-Origin` 이 한 번 붙는다([ADR-015](docs/ADR/ADR-015-desktop-fixed-port-and-cors.md)). 거부된 CORS 요청은 여전히 Spring 의 403 본문이다.
- StrictHttpFirewall 이 `;`, `//`, `/./` 같은 경로를 400 `INVALID_REQUEST` 로 거부한다. 관리 체인 경로의 퍼센트 인코딩은 모두 400 이다(`AdminPathFirewall`). 일부 깨진 요청 대상(`/admin%2Fapi/v1/runtime`, `/admin/api/v1/runtime%00`, 절대 형식 등)과 깨진 Host(대괄호 없는 IPv6, zone id, userinfo, 숫자가 아닌 포트, Host 둘, HTTP/1.1 의 Host 없음)는 Tomcat 이 애플리케이션보다 먼저 평문 400 으로 거부한다(공통 오류 형식과 `X-Request-Id` 없음, BACKLOG P103-4).
- `/actuator` 탐색(`management.endpoints.web.discovery.enabled: false`)과 health 의 하위 경로는 더는 제공하지 않는다(토큰이 있으면 404 `NOT_FOUND`, 없으면 401).
- desktop 셸의 출력 가림은 비밀 값 목록(Metadata DB 비밀번호와 관리자 토큰)을 가린다(`output.rs` 의 `mask_secrets`).
- desktop 셸의 감독 시험 `free_port()` 는 OS 임시 포트 범위 밖(20000-31999)에서 테스트마다 다른 포트를 고른다. 놓은 임시 포트를 다른 테스트 스레드의 나가는 연결이 로컬 포트로 받아 `port_in_use` 로 실패하던 경쟁(P1-02 리뷰 R4, P1-03 실행 중 한 번 관찰)을 없애려는 것이다.
- `README.md`, `docs/ARCHITECTURE.md`, `docs/TEST_PLAN.md`, `docs/BACKLOG.md`, ADR-013, ADR-015, ADR-019, `docs/ADR/README.md`. P1-02 의 커밋 표시를 9fc70db (2026-11-22) 로 채웠다. 게이트 스크립트는 바꾸지 않았다.

##### 보안

- 리뷰 3회차 후속(4회차): 관리 체인 자기 점검이 실패하면 이유(경로와 상태)를 먼저 ERROR 로그에 남기고, 컨텍스트 정리가 멈춰도(메타데이터 DB 가 멈춘 경우) 15 초 뒤 종료 코드 1 로 끝난다. health 경로가 5 초 안에 답하지 않으면 시간 초과로 따로 말한다. 응답 본문은 JSON 으로 읽어 `spring.jackson.serialization.indent-output=true` 에서도 기동한다. 내장 TLS(`server.ssl.*`)는 포트를 열기 전에 거부한다(사용자 결정 2026-11-22 「받아들임 (권장)」, TLS 는 앞단 리버스 프록시에서 끝낸다, 런타임 자체 HTTPS 는 P103-7). 필터 매핑 검사는 보안 필터가 DelegatingFilterProxy 인지도 보고, 점검 소켓은 프록시를 거치지 않는다. [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)에 「기동 거부 설정」을 더했다.
- 남은 것: 바인딩 가드(P103-1), WebView 탐색 경화(P103-2), desktop 의 allow-remote 설정(P103-3), 컨테이너의 평문 400(P103-4), 비동기 관리 핸들러의 재디스패치 시험(P103-5). 토큰은 런타임 자식의 환경에 있으므로 같은 사용자의 프로세스가 읽을 수 있다(설계 §1.2 와 ADR-011 이 받아들인 경로). Windows·Linux 의 셸은 미검증이다(PLAT-1). CI 는 정의됨·미실행이다(CI-1).

테스트(macOS 로컬의 게이트 실행): runtime 단위 703, 통합 24, vitest 42, cargo test 50, 모두 통과, 건너뜀 0. `selftest.sh` 는 279 시나리오다. 수동 확인(AC16)의 GUI 부분은 사람이 한다([docs/TEST_PLAN.md](docs/TEST_PLAN.md)).

#### P1-02 P1-01 리뷰 5회차 후속과 포트 확인 — 9fc70db (2026-11-22)

##### 추가

- `workflow-check.sh` 의 새 코드 `FORBIDDEN_ENV`(P1-01 리뷰 5회차 C5-R1): 워크플로·job·단계 `env:` 바로 아래 변수 이름(대소문자 무시) 가운데 bash 로 시작하는 이름(`BASH_ENV`, `BASHOPTS`, `BASH_FUNC_*` 등), `LD_`·`DYLD_`·`GITHUB_`·`RUNNER_`·`ACTIONS_`·`MSYS` 로 시작하는 이름, `ENV`, `SHELLOPTS`, `POSIXLY_CORRECT`, `PS4`, `IFS`, `CDPATH`, `GLOBIGNORE`, `EXECIGNORE`, `PATH`, `PATHEXT`, `COMSPEC` 를 거부한다. 같은 세 자리의 한 줄 `env:` 값(`env: ${{ fromJSON(...) }}`)도 거부한다. `env:` 는 `이름: 값` 줄의 블록 매핑으로만 쓴다.
- `selftest.sh` 의 새 시나리오 52개 (w149)-(w200): `FORBIDDEN_ENV` 거부 28개, `FORBIDDEN_EXPR` 거부 16개, `UNSUPPORTED_SYNTAX` 거부 5개, 받는 픽스처 3개(w173, w190, w200). 99040c0 의 점검기는 새 거부 픽스처 49개를 모두 받는다(exit 0). `PATHEXT`·`COMSPEC` 과 첫 내용 줄 앞 빈 줄의 탭 규칙을 더하기 전의 점검기는 (w193)-(w199) 7개를 받는다.
- `launch.rs` 의 단위 테스트 6건(P101-T1, P0C-C2-1): 연결을 시간 초과로 바꿔 끼워도 0.0.0.0 리스너(a), [::] 리스너(b), 127.0.0.1 리스너(c)가 있는 포트는 사용 중이고, 빈 포트는 시간 초과와 거부 모두에서 비어 있으며(d), 자기 연결은 비어 있고 다른 소켓과의 연결은 사용 중이고(e), 서버 쪽 TIME_WAIT 만 남은 포트는 비어 있다(f). (a), (b), (f) 는 `#[cfg(not(windows))]` 다.

##### 변경

- `workflow-check.sh` 의 `FORBIDDEN_EXPR`(C5-R2): 원래의 세 부분 문자열 검사(`secrets.`, `github.event.`, `github.head_ref`)는 그대로 두고, 식별자 `secrets`(`secrets['X']`, `toJSON(secrets)`, job 의 `secrets: inherit`)를 주석을 포함한 어디서나 거부한다. 표현식 범위(줄의 첫 `${{` 부터 줄 끝까지. 블록 스칼라에서는 `${{` 가 든 내용 줄 다음의 같은 블록 내용 줄 전체) 안의 식별자 `github` 는 바로 뒤에 `.` 과 허용 속성(`ref`, `ref_name`, `ref_type`, `sha`, `repository`, `repository_owner`, `run_id`, `run_number`, `run_attempt`, `workflow`, `job`, `event_name`, `server_url`, `api_url`)이 와야 한다. 그래서 `github['event']`, `github . event`, `toJSON(github)`, `github.*`, `format('{0}', github)`, `github.token`, `github.actor` 를 거부한다. 범위 밖의 GitHub, `https://github.com`, `$GITHUB_OUTPUT`, `.github/` 경로는 받는다.
- `launch::port_is_free`(P101-T1): 127.0.0.1 바인딩(그대로) 다음에, macOS 에서만 0.0.0.0 과 [::] 에 SO_REUSEADDR 를 켠 libc 소켓을 바인딩만 하고(listen 하지 않는다) 닫으며 EADDRINUSE 면 사용 중으로 본다. 그 다음의 300 ms 연결 시험은 그대로이고 연결 실패(시간 초과 포함)는 비어 있다. Linux 는 2 단계를 두지 않았고 Windows 는 자기 연결 예외만 바뀌었다(둘 다 미검증, CI-1, PLAT-1).
- 문서: P1-01 을 아직 커밋하지 않은 것으로 적던 곳을 99040c0 (2026-11-21) 로 바꿨다(`CHANGELOG.md`, `README.md`, `docs/BACKLOG.md`, `docs/TEST_PLAN.md`, ADR-019). ADR-019 의 P1-01 항목에 리뷰 3회차의 규칙(`KEY_NOT_LOWERCASE`, 대소문자를 가리지 않는 `FORBIDDEN_TRIGGER`·`FORBIDDEN_EXPR`·`LATEST_RUNNER`, 빈 줄·주석 줄의 탭)을 더했다(C5-R3). `docs/BACKLOG.md` 에서 P101-T1 과 P0C-C2-1 을 닫았다. `docs/SECURITY.md`, `docs/TEST_PLAN.md`, ADR-019, ADR-020 에 새 규칙과 남은 한계를 적었다.

##### 수정

- macOS 에서 다른 프로세스의 0.0.0.0·[::] 리스너를 찾는 것이 루프백 연결 시간에 기댔다(P101-T1). 이제 바인딩만 하는 시험이 연결 시간과 상관없이 찾는다. 그 시험을 false 로 바꾼 임시 사본에서 (a), (b) 가 실패하는 것을 확인했다(macOS 로컬). 원래 실패의 원인(부하 아래의 연결 시간 초과)은 여전히 추정이다.
- 포트 확인의 연결 시험이 TCP 자기 연결(로컬 주소와 상대 주소가 같다)을 사용 중으로 읽을 수 있었다(P0C-C2-1). 이제 자기 연결은 비어 있다로 본다.
- `workflow-check.sh` 는 블록 스칼라 안의 공백·탭만 있는 줄에서 내용 열보다 앞의 탭을 받았다(C5-R4). 이제 `UNSUPPORTED_SYNTAX` 다. 첫 내용 줄 앞의 빈 줄(내용 열을 아직 모른다)에 있는 탭은 열과 상관없이 거부한다(libyaml 0.2.1 이 거부하는 것을 Ruby Psych 로 확인했다).

##### 보안

- bash 는 `BASH_ENV` 를 명령 치환까지 펼쳐 스크립트보다 먼저 읽으므로, 워크플로 `env:` 의 `BASH_ENV: $(...)` 한 줄이 게이트 네 단계를 실행하지 않고 exit 0 으로 끝낼 수 있었다(리뷰어가 로컬 bash 로 재현). `FORBIDDEN_ENV` 는 이런 셸·로더·러너 시작 변수, `PATH`(`run: bash scripts/gate/...` 의 bash 를 고른다), Windows 러너(windows-2025)의 명령 찾기를 바꾸는 `PATHEXT`·`COMSPEC`, 이름을 만들어 내는 한 줄 `env:` 를 거부한다.
- 남은 한계: `FORBIDDEN_ENV` 밖의 env 이름(`HOME`, `TMPDIR`, `LANG`·`LC_*`, `JAVA_TOOL_OPTIONS`·`NODE_OPTIONS`·`GRADLE_OPTS`·`RUSTC_WRAPPER` 같은 도구 옵션 변수)으로 도구를 바꿔치기하는 것과 `container:`·`services:` 의 env 는 확인하지 않는다. 앞 단계가 `$GITHUB_ENV`·`$GITHUB_PATH`·`$GITHUB_OUTPUT` 에 써서 뒤 단계를 바꾸는 것도 잡지 못한다. 표현식은 `secrets` 와 `github` 문맥만 본다: `inputs`, `vars`, `needs`, `steps`, `matrix`, `env`, `job`, `runner`, `strategy` 문맥의 값은 보지 않는다. GitHub 이 색인·공백 표현식, 한 줄 `env:`, `GITHUB_*` 같은 예약 이름을 어떻게 다루는지는 확인하지 않았다(CI-1. 새 규칙은 거부만 더한다). 한계는 [docs/SECURITY.md](docs/SECURITY.md)와 [docs/TEST_PLAN.md](docs/TEST_PLAN.md) 「lint 의 저장소 단계」에 있다.

테스트 (macOS 로컬의 게이트 실행): runtime 단위 300, vitest 30, cargo test 38, 통합 23. 모두 통과, 건너뜀 0. selftest 279 시나리오(P1-01 의 227, 새로 52), SKIP 0. cargo test 의 새 테스트 6건은 macOS 에서 돌았고, Linux 와 Windows 에서는 실행하지 않았다(Windows 에서는 (a), (b), (f) 가 컴파일되지 않는다). 점검기의 awk 는 macOS `/usr/bin/awk` 에서만 돌렸다(mawk, gawk 는 CI-1 전까지 미검증). CI 는 실행되지 않았다(CI-1).

#### P1-01 P0-D 리뷰 후속: 점검기의 실패 닫힘 — 99040c0 (2026-11-21)

##### 추가

- `workflow-check.sh` 의 새 코드(P0D-R1, P0D-R3, P0D-R4): `UNSUPPORTED_SYNTAX`(블록 스칼라 내용 밖의 줄에서 따옴표로 감싼 키, 흐름 매핑, 평범한 값만 담지 않은 흐름 시퀀스, 앵커·별칭·병합 키, 태그, 문서 표시·지시자, `key :`, 명시적 키, 들여쓰기 숫자가 붙은 블록 머리, 줄을 넘기는 따옴표 문자열, 큰따옴표 안의 `\` 이스케이프, 들여쓰기의 탭. 블록 내용과 주석을 포함한 모든 줄에서 LF 가 아닌 줄바꿈 문자 CR·U+0085·U+2028·U+2029, 탭이 아닌 제어 문자, NUL), `CONTINUE_ON_ERROR`, `IF_CONDITION`, `SHELL_NOT_BASH`, `RUN_KEY`(단계 밖의 `run:` 과 한 단계의 둘째 `run:`), `RUN_EXPR`(단계의 run 값 안의 `${{`).
- P1-01 리뷰 2회차(C2-R1-R3)와 YAML 문법 전수 점검: `UNSUPPORTED_SYNTAX` 는 블록 스칼라 내용 밖의 줄을 허용 목록(`키:`, `키: 값`, `- 키:`, `- 키: 값`, `- 값`, `-`)과 들여쓰기 구조로 보고, 여러 줄에 걸친 평범한 스칼라(값이 있는 키나 `- 값` 다음의 더 깊은 줄), 맞지 않는 들여쓰기, 키가 없는 줄, 탭, `- - x`, 평범한 값을 시작할 수 없는 문자, 평범한 값 안의 `: `, 닫는 따옴표 뒤의 글자, BOM, 블록 스칼라 내용 열보다 덜 들여 쓴 줄을 거부한다. 새 코드 `DUPLICATE_KEY` 는 같은 매핑의 같은 키(대소문자 무시)를 거부한다.
- P1-01 리뷰 3회차(C3-R1, C3-R3): 새 코드 `KEY_NOT_LOWERCASE` 는 대문자가 든 키(`If:`, `Continue-On-Error:`, `Shell:`, `Uses:`, `Run:`, `Permissions:` 등)를 거부한다. 규칙이 키를 이름으로 찾으므로 대소문자만 다른 키가 규칙을 피했다. 예외는 워크플로·job·단계 `env:` 바로 아래의 변수 이름이다. `FORBIDDEN_TRIGGER`, `FORBIDDEN_EXPR`, `LATEST_RUNNER` 는 대소문자를 가리지 않고 찾는다. `UNSUPPORTED_SYNTAX` 는 블록 스칼라 내용 밖의 빈 줄과 주석 줄의 들여쓰기 자리의 탭도 거부한다.
- `selftest.sh` 의 새 시나리오 134개: docs-check (d13)-(d15), workflow-check (w18)-(w148). (w48)-(w64) 는 P1-01 리뷰 1회차(R1, R2, R4), (w65)-(w131) 은 2회차(C2-R1-R3, C2-R5)와 YAML 문법 전수 점검, (w132)-(w148) 은 3회차(C3-R1, C3-R3)의 재현이다. 받는 픽스처에는 저장소 `ci.yml` 의 바이트 사본(w47)과 CRLF 사본(w131), CRLF 픽스처(w57), 여러 단계 모양의 픽스처(w63, w130), `defaults.run` 의 `working-directory` 표현식(w129), `env:` 의 대문자 변수 이름(w148)이 있다. 새 거부 시나리오마다 d0b0f0f 의 점검기((w48)-(w64) 는 1회차, (w65)-(w128) 은 2회차, (w132)-(w147) 은 3회차의 점검기)나 그 규칙을 끈 사본에서는 그 시나리오가 실패한다.
- `docs/BACKLOG.md`: CI-2(Gradle 의존성 검증 없음, 목표 Phase 1 의 DB Provider 태스크 전). P101-T1(P1-01 테스트에서 한 번 실패하고 다시 돌리자 통과한 cargo 포트 확인 테스트, 원인은 추정, 목표 Phase 1 의 별도 태스크).

##### 변경

- `workflow-check.sh`: `CHECKOUT_PERSIST` 는 checkout 단계의 `with:` 바로 아래 항목인 `persist-credentials: false` 만 센다(P0D-R2). `FACTORY_COMMAND_MISSING` 은 팩토리 명령을 단계(`jobs.<job>.steps` 의 항목)의 한 줄짜리 `run: bash scripts/gate/<이름>.sh` 값으로만 센다(블록 안의 줄과 `env:`·`with:` 아래의 `run:` 은 세지 않는다, P0D-R3). checkout 은 owner/repo 의 대소문자를 가리지 않고 찾는다. 팩토리 명령 확인을 grep 에서 같은 awk 로 옮겼고, 그 awk 는 `LC_ALL=C` 로 돌려 바이트로 비교한다. `uses:` 의 고정 모양은 빈 경로 조각과 끝의 `/` 를 받지 않고 `.`·`..` 조각도 거부한다(C2-R2). 블록 스칼라는 첫 내용 줄의 열을 내용 열로 보고, 그보다 덜 들여 쓴 주석 줄이 블록을 끝낸다(libyaml 과 같다). 출력 형식(`file:line: [CODE] 설명`, `workflow-check: OK|FAIL`)과 종료 코드는 그대로다.
- 문서: P0-D 를 아직 커밋하지 않은 것으로 적던 곳을 d0b0f0f (2026-11-20) 로 바꾸고 Phase 0 을 완료로 적었다(`README.md`, ADR-019, `docs/ARCHITECTURE.md`, `docs/BACKLOG.md`, `docs/PHASE0-REPORT.md`, `docs/TEST_PLAN.md`). 두 점검기가 잡는 것과 잡지 못하는 것을 `docs/SECURITY.md`, `docs/TEST_PLAN.md`, ADR-020 에 적었다. `docs/BACKLOG.md` 에서 P0D-R1..P0D-R7 을 닫았다.
- 캐시 설명(P0D-R7): `ci.yml` 머리 주석, ADR-020, `docs/TEST_PLAN.md` 가 캐시는 의존성 캐시뿐이고 산출물은 없으며, Gradle 캐시에는 Gradle 이 만든 데이터도 있고 복원한 캐시는 검증하지 않는다(CI-2)고 적는다.

##### 수정

- `launch.rs`, `supervisor.rs` 의 Windows 주석이 「P0-D CI」 대신 첫 CI 실행(CI-1, PLAT-1)을 가리킨다(P0D-R6).

##### 보안

- `workflow-check.sh` 는 줄 단위 점검기가 다루지 않는 YAML 문법(따옴표로 감싼 키, 흐름 매핑 등)을 `UNSUPPORTED_SYNTAX` 로 거부한다(P0D-R1). 모든 줄의 LF 가 아닌 줄바꿈 문자(CR, U+0085, U+2028, U+2029), 탭이 아닌 제어 문자와 NUL 도 거부한다. 이런 것을 받으면 그 뒤의 키가 SHA 고정·권한 규칙과 아래 규칙을 피한다. 따옴표로 감싼 `write` 도 `PERMISSIONS_WRITE` 로 거부한다.
- `workflow-check.sh` 는 `continue-on-error:`, `if:`, bash 가 아닌 `shell:` 을 워크플로·job·단계 어디서나 거부하고(P0D-R3), 단계의 run 값 안의 `${{` 를 `RUN_EXPR` 로(P0D-R4), 단계 밖이나 한 단계에 두 번 있는 `run:` 을 `RUN_KEY` 로 거부한다. 팩토리 명령은 단계의 한 줄짜리 run 값만 센다.
- `workflow-check.sh` 는 값이 있는 키나 `- 값` 다음의 더 깊은 줄을 `UNSUPPORTED_SYNTAX` 로 거부한다(C2-R1). YAML 은 이 줄을 앞 값에 이어 붙이므로, 받으면 `shell: bash` 다음 줄의 `-c true {0}` 이 shell 을 `bash -c true {0}` 으로 바꾸고(libyaml 기준. 게이트 스크립트가 실행되지 않는다), 팩토리 run 줄 다음 줄의 `2>/dev/null || true` 나 `--desktop-only` 가 그 명령을 바꾼다. 같은 매핑의 같은 키는 `DUPLICATE_KEY` 로 거부한다(C2-R3. libyaml 은 뒤의 값을 쓰므로 두 번째 `with:` 나 `persist-credentials: true` 가 앞의 `persist-credentials: false` 를 가린다). `actions//checkout` 처럼 checkout 찾기를 피하는 `uses:` 경로는 `USES_NOT_SHA` 로 거부한다(C2-R2).
- 이 점검기는 YAML 파서가 아니고 `working-directory`, env 나 `PATH` 로 도구를 바꿔치기하는 것, 트리거와 브랜치 필터, action 입력 이름, 표현식의 뜻을 확인하지 않는다. 단계 명령이 무엇을 하는지도 보지 않는다: 앞 단계가 게이트 스크립트를 고쳐 쓰거나 `$GITHUB_ENV`·`$GITHUB_PATH` 에 써서(예: `BASH_ENV` 설정) 뒤 단계를 바꾸는 것, `container:` 와 `services:` 는 잡지 못한다(P1-01 리뷰 3회차 C3-R2). YAML 을 GitHub 와 같게 읽는지도 확인하지 않았다(전수 점검은 libyaml 0.2.1 과 견준 구성에서만 했다). 그래서 점검을 통과해도 이런 방법으로는 게이트 단계를 무력화할 수 있다. 한계는 [docs/SECURITY.md](docs/SECURITY.md)와 [docs/TEST_PLAN.md](docs/TEST_PLAN.md) 「lint 의 저장소 단계」에 있다.
- `docs-check.sh` 는 상대 링크를 저장소 루트 안에 가둔다. 루트 위로 올라가는 링크는 그 파일이 있어도 깨진 링크다(P0D-R5). 루트 밖을 가리키는 저장소 안의 심볼릭 링크는 잡지 못한다.

테스트 (macOS 로컬의 게이트 실행): runtime 단위 300, vitest 30, cargo test 32, 통합 23. 모두 통과, 건너뜀 0. selftest 227 시나리오(P0-D 의 93, 새로 134), SKIP 0. 점검기의 awk 는 macOS `/usr/bin/awk` 에서만 돌렸다(mawk, gawk 는 CI-1 전까지 미검증). CI 는 실행되지 않았다(CI-1).

### Phase 0 (완료, d0b0f0f)

남은 문제: [docs/BACKLOG.md](docs/BACKLOG.md). Phase 0 보고(통과 기준, 사람의 결정, 미검증 항목): [docs/PHASE0-REPORT.md](docs/PHASE0-REPORT.md)

#### P0-D CI 정의와 Phase 0 마무리 — d0b0f0f (2026-11-20)

##### 추가

- `.github/workflows/ci.yml` (**정의됨·미실행**: 원격 저장소가 없어 한 번도 실행되지 않았다, [BACKLOG](docs/BACKLOG.md) CI-1). `gates` 작업(ubuntu-24.04)은 새 checkout 에서 root 가 아닌지 확인하고 `selftest.sh --no-skip` 과 팩토리 게이트 네 개를 인자 없이 돌린 뒤 작업 트리가 깨끗한지 본다. `desktop` 작업(macos-15, windows-2025)은 build·test·lint 를 `--desktop-only` 로 돌린다([ADR-020](docs/ADR/ADR-020-ci-runs-gate-scripts.md), 제안).
- `scripts/gate/docs-check.sh` (DOC-1): 명세 원문 블록(689줄, SHA-256)과 `README.md`, `CHANGELOG.md`, `docs/**/*.md` 의 상대 링크를 검사한다. `lint.sh` 가 모든 범위에서 실행한다.
- `scripts/gate/workflow-check.sh`: 워크플로의 SHA 고정, 권한, 금지 표현, checkout 의 `persist-credentials: false`, 팩토리 명령 네 줄을 검사한다. 워크플로 파일이 없으면 실패한다. `lint.sh` 가 모든 범위에서 실행한다.
- 게이트 범위: `build.sh`, `test.sh`, `lint.sh` 의 선택 인자 `--desktop-only`. runtime(Gradle) 단계만 빼고 시작 안내와 마지막 `GATE PASS` 줄에 그 사실을 적는다. 그 밖의 인자는 어떤 도구도 실행하기 전에 exit 2 이고, `integration.sh` 는 인자를 받지 않는다. 인자 없는 실행의 단계와 출력 형식은 그대로다.
- `selftest.sh --no-skip` (P0A-N1)과 새 시나리오 58개: 범위 파서, 가짜 docker 로 Docker 사전 점검, docs-check 와 workflow-check 의 픽스처, junit-check 의 형식이 틀린 `tests` 속성(빈 값, 숫자가 아님)과 합계 형식 거부 분기(`valid_counts`, 가짜 awk 가 틀린 합계를 찍는다, P0A-N1).
- `desktop/src-tauri/src/runtime/launch.rs` 의 테스트 3건: 설정 없음 메시지의 두 문구, 대소문자 무시 모드에서만 `Path` 를 찾는 `env_value`(두 건 모두 모든 플랫폼), Windows 의 `Path` 에서 `java.exe` 찾기(`#[cfg(windows)]`, 미검증).
- `docs/ADR/ADR-020-ci-runs-gate-scripts.md`(제안)와 `docs/PHASE0-REPORT.md`.

##### 변경

- `launch.rs`: Metadata DB 설정이 없을 때의 메시지가 `docs/DEPLOYMENT.md` 와 하위 셸 명령 `(set -a; . deploy/.env; set +a; pnpm --dir desktop tauri dev)` 를 가리킨다.
- `docs/BACKLOG.md`: P0A-N1, P0B-R4, P0B-R7, P0C-C2-4, DOC-1 을 닫았다. P0B-R6 은 Phase 1 로 미뤘고, PLAT-1 의 목표를 「CI-1 첫 실행 / Phase 8」로 바꿨고, CI-1 을 더했다(ID 24개).
- `docs/TEST_PLAN.md`(CI 절, 게이트 범위, lint 의 저장소 단계, 새 사본 검사, 문서 점검 절차, 테스트 수), `docs/ARCHITECTURE.md`, `docs/SECURITY.md`, `docs/DEPLOYMENT.md`, `docs/ADR/README.md`, ADR-013, ADR-017, ADR-019, `README.md`. 문서 기준선을 아직 커밋하지 않은 것으로 적던 곳을 51141df (2026-11-20) 로 바꿨다.

##### 수정

- Windows 에서 `resolve_java` 가 `PATH` 를 대소문자 구분 없이 찾는다(`launch.rs` 의 `env_value`, 모든 플랫폼에서 컴파일하고 `cfg!(windows)` 로 고른다, P0C-C2-4). Windows 에서는 실행해 보지 않았다(미검증).
- Docker 사전 점검(`lib.sh` 의 `docker_preflight`)이 서버 버전을 stdout 에서만 읽고 숫자로 시작하는 한 줄만 받는다. stderr 는 실패 때만 보인다(P0B-R4).
- ADR-013 의 `java -version` 사전 점검 환경 설명을 코드(`inherited_env`)와 `docs/SECURITY.md` 에 맞췄다. 아래 P0-C 보안 항목의 가림 문구를 「출력에 그대로 나온 비밀번호」로 고쳤다.

##### 보안

- CI 워크플로는 비밀 값이 필요 없다. 최상위 권한은 `contents: read` 하나이고, 모든 checkout 이 `persist-credentials: false` 이며, action 은 40자 커밋 SHA 로 고정했다. `pull_request_target`, `secrets.`, `github.event.`, `github.head_ref` 를 쓰지 않는다. `workflow-check.sh` 가 lint 단계에서 이 규칙을 점검한다. 이 줄 단위 점검기는 P1-01 전까지 따옴표로 감싼 키와 흐름 매핑을 보지 못했다(P0D-R1, 위의 P1-01).
- 게이트 범위는 명시한 인자로만 고른다. 환경변수로는 게이트의 단계를 줄일 수 없다.

테스트 (macOS 로컬의 게이트 실행): runtime 단위 300, vitest 30, cargo test 32, 통합 23. 모두 통과, 건너뜀 0. selftest 93 시나리오, SKIP 0. `#[cfg(windows)]` 테스트 1건은 macOS 에서 컴파일되지 않아 미검증이다. CI 는 실행되지 않았다(CI-1).

#### 문서 기준선 — 51141df (2026-11-20)

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
- Metadata DB 설정은 환경변수로만 넘기고 명령행 인자에 넣지 않는다. 출력에 그대로 나온 비밀번호는 `********` 로 가린다([ADR-017](docs/ADR/ADR-017-desktop-metadata-settings-from-env.md)).
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
