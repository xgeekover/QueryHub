# 백로그 (남은 문제)

Phase 0 태스크의 리뷰와 결정에서 나온 후속 과제를 한곳에 모은다. 다른 문서는 이 ID 로 링크한다.

- **ID:** `P0A-`, `P0B-`, `P0C-` 는 그 태스크의 리뷰 지적 번호를 붙인 것이다. `PLAT-n` 은 플랫폼 검증, `DOC-n` 은 문서 과제다.
- **출처:** 팩토리 태스크 ID 와 리뷰 회차다. P0-A 는 T-20261115-233911-3446, P0-B 는 T-20261117-204259-6611, P0-C 는 T-20261118-095453-0662 이다.
- **심각도:** 리뷰가 매긴 값이다(critical, high, medium, low, info). 리뷰에서 나오지 않은 항목도 info 로 적는다.
- **목표:** 처리할 Phase 나 태스크다. 계획이며 바뀔 수 있다.

## 열린 항목

| ID | 출처 | 심각도 | 한 줄 설명 | 목표 | 관련 문서 |
|---|---|---|---|---|---|
| P0A-N1 | T-20261115-233911-3446 (P0-A) 리뷰 2회차 | low | `scripts/gate/selftest.sh` 의 awk 실패 시나리오는 root 로 돌면 SKIP 되고 selftest 는 그대로 0 으로 끝난다. root 로 도는 CI 에서는 `junit-check.sh` 의 해당 수정이 되돌아가도 잡지 못하고, 합계 형식 거부 분기는 어떤 시나리오도 다루지 않는다 | P0-D (CI 는 root 가 아닌 사용자로 돌리거나, root 에서는 selftest 를 실패시킨다) | [TEST_PLAN.md](TEST_PLAN.md), [ADR-019](ADR/ADR-019-gate-scripts-judge-results.md) |
| P0A-N2 | T-20261115-233911-3446 (P0-A) 리뷰 2회차 | low | 심볼릭 링크인 모듈 디렉터리는 Gradle 모듈 규칙을 통과하지만 `scripts/gate/lib.sh` 의 탐색(`find`, `-L` 없음)에서 빠져, 그 모듈의 테스트 결과가 판정에 들어가지 않는다 | Phase 1 (모듈을 더할 때 다시 본다) | [TEST_PLAN.md](TEST_PLAN.md) |
| P0A-N3 | T-20261115-233911-3446 (P0-A) 리뷰 2회차 | info | 선언 의존 검사에 남은 우회로 두 개: 소비용 구성(`apiElements` 등)에 붙인 의존과 `resolutionStrategy.dependencySubstitution`. 클래스 수준에서는 ArchUnit 이 막는다 | Phase 1 (모듈을 더할 때 다시 본다) | [ARCHITECTURE.md](ARCHITECTURE.md) |
| P0A-N4 | T-20261115-233911-3446 (P0-A) 리뷰 2회차 | info | `.gitignore` 의 키 파일 패턴(`*.key`, `*.p12`, `*.pem`)이 경로 고정 없이 걸려, `docs/slides.key` 나 테스트용 TLS 키 저장소 같은 정상 파일도 커밋에서 말없이 빠진다 | Phase 1 (TLS 키 저장소나 `.key` 파일을 처음 넣기 전에 명시적인 부정 패턴을 더한다) | [SECURITY.md](SECURITY.md) |
| P0A-I1 | T-20261115-233911-3446 (P0-A) 리뷰 1회차 | info | `GET /admin/api/v1/runtime` 에 인증이 없다. server 프로파일에서는 0.0.0.0 에서 열리고, desktop 에서는 Host 허용 목록이 없어 DNS 리바인딩으로 읽힐 수 있다. 응답은 민감하지 않은 런타임 정보뿐이다 | Phase 1 (관리자 토큰, Host 허용 목록, desktop 루프백 전용 관리 API) | [SECURITY.md](SECURITY.md), [ADR-002](ADR/ADR-002-single-http-port.md) |
| P0A-I2 | T-20261115-233911-3446 (P0-A) 리뷰 1회차 | info | ArchUnit 자체 검증이 R5 의 api→admin 방향과 R6 을 실패시켜 보지 않는다. `com.queryhub` 밖 패키지의 클래스는 ArchUnit 규칙에서 빠진다 | Phase 1 (provider 모듈과 R6 픽스처, 데이터 API 가 생길 때 api→admin 픽스처) | [TEST_PLAN.md](TEST_PLAN.md) |
| P0B-R3 | T-20261117-204259-6611 (P0-B) 리뷰 1회차 | low | `app_setting.setting_value` 는 TEXT(최대 65,535 바이트)인데 저장소 계약에 길이 한도가 없다. strict `sql_mode` 가 아닌 MariaDB 에서는 경고만 남기고 값을 잘라 저장한다 | Phase 1 (처음 값을 쓰는 기능과 함께) | [ERD.md](ERD.md) |
| P0B-R4 | T-20261117-204259-6611 (P0-B) 리뷰 1회차 | info | `scripts/gate/integration.sh` 의 Docker 사전 점검이 stderr 를 출력에 합쳐, 서버 버전 없이 경고만 내는 docker CLI 를 빈 출력 검사로 잡지 못한다. 거짓 통과로 이어지지는 않는다 | P0-D | [TEST_PLAN.md](TEST_PLAN.md), [ADR-019](ADR/ADR-019-gate-scripts-judge-results.md) |
| P0B-R5 | T-20261117-204259-6611 (P0-B) 리뷰 1회차 | info | ArchUnit R8 이 `org.springframework.boot.jdbc..`, `org.springframework.boot.flyway..` 패키지를 다루지 않는다 | Phase 1 (DB Provider 와 함께) | [ADR-007](ADR/ADR-007-spring-jdbc-metadata.md) |
| P0B-R6 | T-20261117-204259-6611 (P0-B) 리뷰 1회차 | info | Testcontainers 가 MariaDB 포트를 모든 호스트 인터페이스에 연다. 테스트가 도는 약 20 초 동안 같은 네트워크에서 테스트용 계정으로 접속할 수 있다 | P0-D (CI 에서 포트를 127.0.0.1 에 묶을지 정한다) | [SECURITY.md](SECURITY.md), [TEST_PLAN.md](TEST_PLAN.md) |
| P0B-R7 | T-20261117-204259-6611 (P0-B) 리뷰 1회차 | info | 새 사본 검사의 읽는 법: `git add -A` 만 하고 커밋하지 않은 사본에서는 `git status --short` 가 staged `A` 줄을 늘 보여 준다. `A` 줄 말고 아무것도 없어야 통과다. 지금은 TEST_PLAN 이 이 읽는 법을 적는다 | P0-D (CI 의 새 clone 검사로 바꾼다) | [TEST_PLAN.md](TEST_PLAN.md) |
| P0B-H1 | T-20261117-204259-6611 (P0-B) 후속 결정 (Queen 기록). 리뷰 2회차 지적 R13(문구, 3회차에 고침)이 Phase 1 로 미룬 결정 | info | `spring.datasource.hikari.catalog` 와 `connection-init-sql` 을 프로세스에 보이는 출처에서 막을지 정한다. catalog 는 연결 DB 를 `QUERYHUB_METADATA_DB_NAME` 이 아닌 곳으로 바꾸고, connection-init-sql 은 UTC 세션 설정을 되돌릴 수 있다 | Phase 1 (DB Provider·보안) | [SECURITY.md](SECURITY.md), [ADR-011](ADR/ADR-011-metadata-secrets-env-only.md) |
| P0B-R14 | T-20261117-204259-6611 (P0-B) 리뷰 3회차 | low | `--spring.autoconfigure.exclude`(또는 `SPRING_AUTOCONFIGURE_EXCLUDE`)로 `MetadataDatabaseAutoConfiguration` 을 빼면 가드, 검사한 연결 정보, Flyway 고정이 함께 사라지고 Boot 가 `spring.datasource.*` 로 풀을 만든다 | Phase 1 (검사를 `spring.factories` 에 등록한 EnvironmentPostProcessor 로 옮긴다) | [SECURITY.md](SECURITY.md), [ADR-011](ADR/ADR-011-metadata-secrets-env-only.md), [ADR-012](ADR/ADR-012-flyway-connection-pin.md) |
| P0B-R15 | T-20261117-204259-6611 (P0-B) 리뷰 3회차 | info | `spring.flyway.url`, `user`, `password` 와 `spring.datasource.url`, `password` 를 명령행이나 `-D` 로 주면 무시되지만 거부도 경고도 없다. 값은 프로세스 목록에 보인다 | Phase 1 (hikari catalog 결정과 함께, 거부하거나 경고 한 줄을 남긴다) | [SECURITY.md](SECURITY.md), [ADR-012](ADR/ADR-012-flyway-connection-pin.md) |
| P0C-R5 | T-20261118-095453-0662 (P0-C) 리뷰 1·2회차 | info | `desktop/pnpm-workspace.yaml` 의 `minimumReleaseAgeExclude` 에 `lucide-react@1.74.0` 예외가 남아 있다. 그 릴리스가 나이 한도를 넘으면 지운다 | Phase 6 | [SECURITY.md](SECURITY.md) |
| P0C-R6 | T-20261118-095453-0662 (P0-C) 리뷰 1·2회차 | info | `ParentProcessWatch` 가 공용 ForkJoinPool 스레드(`onExit().thenRun`)에서 `System.exit` 를 부르고 종료 훅이 끝날 때까지 그 스레드를 붙잡는다. 전용 스레드는 선택 사항이다 | Phase 8 (안정화) | [ADR-014](ADR/ADR-014-runtime-parent-watch.md) |
| P0C-R7 | T-20261118-095453-0662 (P0-C) 리뷰 1·2회차 | info | `tauri dev` 재빌드 때 이전 런타임이 부모 감시에 걸릴 때까지(약 5 초) 남아 새 인스턴스의 자동 시작이 `port_in_use` 에 걸릴 수 있다. 디버그 출력의 `eprintln!` 은 stderr 가 닫혀 있으면 panic 한다 | Phase 6 | [ADR-014](ADR/ADR-014-runtime-parent-watch.md), [ADR-015](ADR/ADR-015-desktop-fixed-port-and-cors.md) |
| P0C-C2-1 | T-20261118-095453-0662 (P0-C) 리뷰 2회차 | info | 포트 확인의 연결 탐침이 임시 포트 범위에서는 TCP 자기 연결 때문에 드물게 `port_in_use` 를 잘못 낼 수 있다. 테스트에만 해당하고 18580 은 영향이 없다 | Phase 6 | [TEST_PLAN.md](TEST_PLAN.md) |
| P0C-C2-2 | T-20261118-095453-0662 (P0-C) 리뷰 2회차 | info | 자식 환경은 실제로 띄운 런타임 자식에서 확인하지만, `java -version` 사전 점검의 환경은 단위 비교와 코드 읽기로만 확인했다 | Phase 6 | [TEST_PLAN.md](TEST_PLAN.md), [ADR-013](ADR/ADR-013-desktop-child-env-allowlist.md) |
| P0C-C2-3 | T-20261118-095453-0662 (P0-C) 리뷰 2회차 | info | `JAVA_HOME` 은 java 를 고르는 데만 쓰고 자식에게 넘기지 않는다. 고른 java 가 macOS 의 `/usr/bin/java` 스텁이면 기본 JDK 가 뜬다. `QUERYHUB_DESKTOP_JAVA` 는 실제 JDK 의 `bin/java` 를 가리켜야 한다 | Phase 8 (번들 JRE) | [DEPLOYMENT.md](DEPLOYMENT.md), [ADR-013](ADR/ADR-013-desktop-child-env-allowlist.md) |
| P0C-C2-4 | T-20261118-095453-0662 (P0-C) 리뷰 2회차 | info | Windows 에서 `resolve_java` 가 `PATH` 를 대소문자를 구분해 찾는데 Windows 는 보통 `Path` 로 준다. `JAVA_HOME` 이나 `QUERYHUB_DESKTOP_JAVA` 가 없으면 `java_not_found` 가 날 수 있다(미검증 플랫폼) | P0-D (Windows CI) | [ADR-013](ADR/ADR-013-desktop-child-env-allowlist.md) |
| PLAT-1 | T-20261118-095453-0662 (P0-C), 설계 §9 의 미검증 규칙 | info | Windows·Linux 의 Desktop 셸(빌드, 번들, 자식 환경 허용 목록, 경로 처리)은 macOS 에서 검증할 수 없어 미검증이다 | P0-D (CI) / Phase 8 | [DEPLOYMENT.md](DEPLOYMENT.md), [TEST_PLAN.md](TEST_PLAN.md) |
| DOC-1 | T-20261119-074437-3980 (문서 기준선) | info | 문서의 상대 링크 검사와 명세 원문 보존 검사가 게이트 단계가 아니다. 지금은 TEST_PLAN 의 수동 절차다 | P0-D | [TEST_PLAN.md](TEST_PLAN.md) |

## 완료

항목을 닫으면 위 표에서 이 표로 옮기고, 고친 커밋과 날짜를 적는다. ID 는 다시 쓰지 않는다.

| ID | 닫은 커밋 | 날짜 | 메모 |
|---|---|---|---|
