# ADR-025 Java 기준선은 17 이다 (Spring Boot 4.1 은 그대로)

- 상태: 승인됨
- 날짜: 2026-11-23
- 구현: 구현됨 (P1-05, 6ceef51 (2026-11-23)). 테스트는 Java 17 에서 돌았다. Java 21 이상에서는 이 변경 뒤에 다시 돌리지 않았다. CI 는 2026-11-23 부터 실행되고 Linux(Temurin 17)와 macOS 작업은 통과했다. Windows 는 미검증(PLAT-1, CI-1)

## 맥락

- 명세 3절은 「Java 17 이상」을 적는다. 2026-11-15 의 사용자 결정 D1([ADR-010](ADR-010-spring-boot-4-java-21.md))은 Spring Boot 4.1 과 Java 21 을 골랐다. 그래서 P0-A 부터 P1-04 까지 런타임은 Java 21 toolchain 으로 컴파일·테스트했고, desktop 셸은 Java 21 미만을 거부했고, Gradle 은 Java 21 이 없으면 foojay resolver 로 내려받았다.
- 사용자는 QueryHub 를 닫힌 사내망에서 쓴다. 그 환경에는 OpenJDK 17 이 있고, 빌드가 JDK 를 내려받을 수 없다.
- 2026-11-23 에 사용자가 Java 17 에서 쓸 수 있어야 한다고 요청했고(아래 근거), Queen 의 제안(P1-04 커밋, P1-05 Java 17, P1-06 내장 metadata 저장소)에 「1. 진행」으로 답했다.
- 확인한 것 (P1-05): 코드에서 Java 17 에 없는 API 는 테스트 한 줄(`Thread.join(Duration)`, Java 19)뿐이었다. 실행 JAR 의 모든 클래스는 주 버전 61(Java 17) 이하다(멀티 릴리스 JAR 의 `META-INF/versions/18+` 항목은 Java 17 이 읽지 않는다). 이 사실은 아래의 통합 테스트가 매번 다시 확인한다.

## 결정

- Spring Boot 4.1 계열(4.1.2)은 그대로 둔다. BOM 덮어쓰기 예외는 [ADR-018](ADR-018-mariadb-connector-j-override.md) 하나 그대로다.
- Java 기준선은 17 이다. `runtime/build.gradle` 의 Java toolchain 은 `JavaLanguageVersion.of(17)` 이다. 컴파일(`-Xlint:all -Werror -parameters` 그대로), 단위 테스트, 통합 테스트, 통합 테스트가 띄우는 실행 JAR 자식 프로세스가 모두 JDK 17 에서 돈다. `--release 17` 로 컴파일하고 21 에서 테스트하는 방식은 쓰지 않는다(Java 17 에서 도는지 증명하지 못하고 JDK 21 이 필요하다).
- JDK 를 내려받지 않는다. `runtime/settings.gradle` 에서 foojay resolver 플러그인을 뺐고, `runtime/gradle.properties` 에 `org.gradle.java.installations.auto-download=false` 를 두었다. Gradle 은 로컬에 설치된 JDK 를 자동 감지해 찾는다(현재 JVM, `JAVA_HOME`, macOS 의 java_home, `~/.gradle/jdks` 등). 다른 위치의 JDK 17 은 `~/.gradle/gradle.properties` 의 `org.gradle.java.installations.paths` 로 알려 준다(Windows 에서는 경로의 `\` 를 `/` 로 쓰거나 `\\` 로 적는다. `gradle.properties` 는 `\` 를 이스케이프로 읽는다. 미검증, PLAT-1. P1-05 리뷰 R3). JDK 17 이 없으면 첫 `JavaCompile` 태스크가 Gradle 의 toolchain 오류로 실패한다. toolchain 의 언어 버전은 정확히 일치해야 하므로 JDK 21 이 있어도 17 대신 쓰이지 않는다. 실제 메시지는 [DEPLOYMENT.md](../DEPLOYMENT.md) 준비물에 있다.
- desktop 셸은 Java 17 이상을 받는다(`desktop/src-tauri/src/runtime/java.rs` 의 `MIN_MAJOR = 17`, `is_supported`). 16 이하는 `java_too_old` 로 거부한다. `launch.rs` 의 안내 문구도 `MIN_MAJOR` 에서 숫자를 가져온다.
- CI(`.github/workflows/ci.yml`)의 `setup-java` 는 Temurin 17 을 준비한다. CI 는 2026-11-23 부터 실행된다(CI-1).
- Java 18 이상의 API 와 언어 기능은 쓰지 않는다. 기대값을 일부러 고정한 테스트가 이 기준선을 지킨다: `runtime/app/src/test/java/com/queryhub/app/JavaBaselineTest.java`(테스트 JVM 이 17, 모든 모듈의 main 클래스가 주 버전 61), `runtime/app/src/integrationTest/java/com/queryhub/app/it/BootJarIntegrationTest.java`(실행 JAR 이 Java 17 로 기동. P1-06 부터 기동 줄은 `using Java 17 with` 와 `using Java 17.0.x with` 를 모두 받는다(P1-05 리뷰 R2). GA 빌드의 `java.version` 은 `17` 이다), `runtime/app/src/integrationTest/java/com/queryhub/app/it/BootJarClassVersionIntegrationTest.java`(실행 JAR 안의 모든 클래스가 61 이하, `com/queryhub` 클래스는 61). 결정 없이 toolchain 을 바꾸면 이 테스트들이 실패한다. 기준선을 다시 바꿀 때는 새 ADR 을 쓰고 이 값들을 함께 고친다.

## 결과

- JDK 를 내려받을 필요가 없다. 로컬에 설치된 OpenJDK 17 로 빌드·테스트·실행한다. 의존성(Maven Central)과 Gradle 배포본은 처음 한 번 네트워크나 사내 미러가 필요하다([DEPLOYMENT.md](../DEPLOYMENT.md) 준비물의 네트워크). P1-05 때는 통합 테스트의 Docker 이미지(`mariadb:12.3`)도 필요했다. P1-06 부터 통합 테스트는 이미지를 받지 않지만 통합 게이트는 실행 중인 Docker 를 요구한다. 닫힌 사내망에서는 시험하지 않았다. 캐시가 있으면 `--offline` 으로 돈다(P1-05 에서 확인). (2026-11-23, P1-05 리뷰 R1 에 따라 시험한 것만 말하도록 고쳐 적었다.)
- 치르는 값: Java 18–21 의 API 와 언어 기능(예: 가상 스레드, `Thread.join(Duration)`, 레코드 패턴)을 쓰지 못한다. 문서에 그 기능을 쓰자는 계획은 없었다. 컴파일 옵션 `-Xlint:all -Werror` 는 그대로지만 javac 17 의 `-Xlint:all` 은 javac 21 의 일부 검사(예: `this-escape`)를 하지 않는다(P1-05 리뷰 R6).
- JDK 17 이 없는 새 장비에서는 빌드가 실패한다(내려받아 준비하지 않는다). 의도한 동작이다.
- Java 21 이상은 더 이상 어떤 테스트도 돌리지 않는다. 여러 Java 버전에 걸친 테스트(CI 행렬)는 나중 과제다.
- 의존성을 올려 Java 17 보다 높은 클래스가 실행 JAR 에 들어오면 `BootJarClassVersionIntegrationTest` 가 그 JAR 과 항목을 적고 실패한다. 테스트 클래스패스(JUnit, Mockito, ArchUnit. P1-05 때는 Testcontainers 도)는 검사하지 않는다. 테스트가 Java 17 에서 돈다는 것이 그쪽의 확인이다.
- [ADR-010](ADR-010-spring-boot-4-java-21.md) 의 Java 21 부분은 이 ADR 이 대체한다. ADR-010 의 Spring Boot 4.1 결정은 그대로다.

## 근거

- 사용자 요청, 2026-11-23: 「Java OpenJDK 17 환경에서 사용할 수 있어야 해. Metadata DB는 꼭 필요한가요? SQLLite 등의 내부DB나 설정파일 기반으로 동작할 수 있어야 할 것 같아요.」
- 사용자 결정, 2026-11-23 (Queen 의 제안에 대한 답, 줄바꿈과 글자를 원문 그대로 둔다):

> 「1. 진행
> 2. SQLite 내장
> 3. 권장하는 반식으로 진행
> 4. 병합 및 승인 (키ID 방식은 나중에 도입)」

- 항목 대응: 1 = 이 ADR (P1-05). 2·3 = P1-06: SQLite 내장 metadata 저장소, MariaDB 는 metadata 저장소 선택지로 남기지 않는다([ADR-026](ADR-026-sqlite-embedded-metadata-store.md), 구현됨 P1-06). 4 = P1-04 커밋 037385b(2026-11-23) 승인, 「키ID 방식은 나중에 도입」은 P1-04 리뷰 지적 R7 에 대한 답이다([BACKLOG.md](../BACKLOG.md) P104-R7). 자세한 대응은 [REQUIREMENTS.md](../REQUIREMENTS.md) AR-2 에 있다.
