# ADR-018 MariaDB Connector/J 3.5.11 로 BOM 버전을 덮어쓴다

- 상태: 제안
- 날짜: 2026-11-18
- 구현: 구현됨 (P0-B, f913239). P1-06 에서는 MariaDB 드라이버가 어떤 클래스패스에도 없었고, P1-07(커밋 전)에서 업무 DB 드라이버로 다시 들어왔다(아래 주)

> 2026-11-23 주 (P1-06, [ADR-026](ADR-026-sqlite-embedded-metadata-store.md)): Metadata 저장소가 내장 SQLite 가 되어 `mariadb-java-client` 는 DB Provider 태스크까지 어떤 main·integrationTest 클래스패스에도 없다. `runtime/build.gradle` 의 `ext['mariadb.version']` 과 카탈로그의 `mariadb-connector-j`·`mariadb-java-client` 별칭은 주석을 달아 남겼다(클래스패스에 아무것도 넣지 않는다). Provider 태스크가 드라이버를 다시 더할 때 OSV 권고를 고친 3.5.11 을 받게 하려는 것이다. 그래서 위 확인 명령은 지금 아무 결과도 내지 않는다. `allowLocalInfile=false` 를 넣던 Metadata URL 과 `LOAD DATA LOCAL INFILE` 통합 시험은 P1-06 에서 지웠다. 업무 DB 연결의 같은 방어는 Provider 태스크가 정한다. 상태는 제안 그대로다.

> 2026-11-23 주 (P1-07, [ADR-027](ADR-027-database-provider.md)): Connector/J **3.5.11** 이 `runtime/provider-mariadb` 의 업무 DB 드라이버로 클래스패스에 다시 들어왔다(실행 JAR 의 `BOOT-INF/lib/mariadb-java-client-3.5.11.jar`, 793,387 바이트, `BootJarClassVersionIntegrationTest` 가 확인한다). 버전은 이 ADR 의 덮어쓰기에서 오고 `runtime/build.gradle` 은 바뀌지 않았다. 위 확인 명령은 다시 3.5.11 을 보인다. LOCAL INFILE 은 버전과 MariaDB Provider 의 고정 속성 `allowLocalInfile=false` 두 겹으로 막고, 통합 시험이 `--local-infile=1` 서버에서 `LOAD DATA LOCAL INFILE` 이 실패하고 표가 비어 있는 것을 확인한다(`MariaDbProviderIntegrationTest`). 상태는 제안 그대로다.

## 맥락

승인된 설계 §2 는 「버전은 Boot BOM 이 관리하는 것은 덮어쓰지 않는다」고 적었다. Boot 4.1.2 BOM 이 관리하는 MariaDB Connector/J 3.5.10 에는 OSV 권고가 있다. 가장 큰 것은 GHSA-fhx5-2xqw-2cgm 으로, 악성 DB 서버나 중간자가 LOCAL INFILE 로 클라이언트 호스트의 파일을 읽을 수 있다. 3.5.11 에서 고쳐졌다.

## 결정

- Connector/J 를 3.5.11 로 올린다. 이것이 BOM 덮어쓰기의 유일한 예외다.
- 방법: `runtime/build.gradle` 의 `ext['mariadb.version']` 이 `runtime/gradle/libs.versions.toml` 의 `mariadb-connector-j = "3.5.11"` 을 BOM 속성으로 넣는다. `mavenBom` 의 `bomProperty` 를 쓰지 않는 이유는 그 파일의 주석에 있다.
- 별도로 런타임이 만드는 JDBC URL 에 `allowLocalInfile=false` 를 넣는다.
- 제거 조건: Boot BOM 이 3.5.11 이상을 관리하게 되면 이 줄과 카탈로그의 버전을 지운다. 확인 명령: `sh runtime/gradlew -p runtime -q :app:dependencyInsight --dependency mariadb-java-client --configuration runtimeClasspath`.

## 결과

- 알려진 권고가 없는 드라이버를 쓰고, LOCAL INFILE 은 버전과 URL 옵션 두 겹으로 막는다. 통합 테스트가 `LOAD DATA LOCAL INFILE` 거부를 확인한다([TEST_PLAN.md](../TEST_PLAN.md)).
- Boot 를 올릴 때마다 제거 조건을 확인해야 한다. 잊으면 더 새 BOM 버전을 낮은 버전으로 묶어 둘 수 있다.
- 설계와 다른 점은 [ARCHITECTURE.md](../ARCHITECTURE.md)의 「설계와 다른 구현」에 있다.

## 근거

- P0-B 계획과 구현에서 정했고, P0-B 코드 리뷰 1회차가 이 예외를 검토해 받아들였다. 사용자의 명시적인 결정은 없다. 사용자가 받아들이면 승인됨으로 바꾼다.
