# ADR-001 JVM 런타임을 자식 프로세스로 실행하고 JRE 를 번들한다

- 상태: 승인됨
- 날짜: 2026-11-15
- 구현: 일부 구현 — 자식 프로세스 실행과 `bundle.externalBin` 미사용은 구현됨 (P0-C, f84c628). 번들 JRE(jlink)와 설치본은 계획 (Phase 8)

## 맥락

명세 2절은 두 실행 환경을 요구한다. Desktop Mode 는 Spring Boot Runtime 을 로컬 프로세스로 실행하고, Server Mode 는 같은 Runtime 을 독립 서버로 실행한다. 두 모드는 같은 Runtime Core 와 API Contract 를 써야 한다.

## 결정

- 런타임은 Spring Boot 실행 JAR 하나다. 두 모드의 차이는 Spring 프로파일(`desktop`, `server`)뿐이다.
- Desktop 에서는 Tauri 셸(Rust)이 JAR 을 `java` 자식 프로세스로 실행한다. Server 에서는 `java -jar` 나 Docker 로 실행한다.
- 배포본은 jlink 로 만든 JRE 와 JAR 을 Tauri `bundle.resources` 에 넣고 Rust 의 `std::process::Command` 로 실행한다. `bundle.externalBin`(사이드카)은 쓰지 않는다.
- GraalVM native image 는 쓰지 않는다.

## 결과

- 구현됨 (P0-C): `desktop/src-tauri/src/runtime/supervisor.rs` 가 런타임 JAR 을 자식 프로세스로 기동·중지·재시작하고 헬스를 확인한다. `desktop/src-tauri/tauri.conf.json` 에는 `externalBin` 이 없다.
- 지금은 JRE 를 번들하지 않는다. 셸은 외부 JDK 를 `QUERYHUB_DESKTOP_JAVA`, `JAVA_HOME/bin/java`, `PATH` 의 `java` 순으로 고르고(`desktop/src-tauri/src/runtime/launch.rs` 의 `resolve_java`), 버전이 17 보다 낮으면 `java_too_old` 로 실패한다(기준선 Java 17, [ADR-025](ADR-025-java-17-baseline.md)). JAR 은 `QUERYHUB_DESKTOP_RUNTIME_JAR` 이 없으면 디버그 빌드에서 저장소의 JAR 경로를 쓴다. 번들 JRE, `bundle.resources`, 설치본은 계획 (Phase 8)이다.
- 치르는 값: 3개 OS 의 패키징과 서명이 가장 큰 위험이다(설계 §11 1번). Windows·Linux 는 미검증이다([BACKLOG](../BACKLOG.md) PLAT-1).

## 근거

- 승인된 설계 §1.2 와 §10 의 ADR 001 (2026-11-15 사용자 승인, [REQUIREMENTS.md](../REQUIREMENTS.md) AR-0).
- 두 모드가 같은 JAR 을 쓴다. native image 는 Oracle·SQL Server 드라이버와 POI 의 리플렉션 설정 부담이 크다. 사이드카는 다른 프로젝트의 CI 에서 복사 위치가 어긋난 선례가 있다(설계 §1.2).
