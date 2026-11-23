# ADR-010 Spring Boot 4.1 + Java 21

- 상태: 승인됨 (Java 21 부분은 [ADR-025](ADR-025-java-17-baseline.md) 가 대체, 2026-11-23)
- 날짜: 2026-11-15
- 구현: 구현됨 (P0-A, ee00165)

## 맥락

명세 3절은 「Java 17 이상」, 「Spring Boot 3.x」를 적는다.

## 결정

- Spring Boot 4.1 계열과 Java 21 을 쓴다. 이 결정이 명세의 해당 줄을 대체한다.
- Boot BOM 이 관리하는 라이브러리는 버전을 덮어쓰지 않는다. 예외는 [ADR-018](ADR-018-mariadb-connector-j-override.md) 하나다.

## 결과

- 구현됨 (P0-A): `runtime/gradle/libs.versions.toml` 의 `spring-boot = "4.1.2"`, `runtime/build.gradle` 의 Java toolchain `JavaLanguageVersion.of(21)`, Gradle wrapper 9.7.1(`runtime/gradle/wrapper/gradle-wrapper.properties`).
  - 2026-11-23 주: toolchain 은 [ADR-025](ADR-025-java-17-baseline.md) 가 `JavaLanguageVersion.of(17)` 로 바꿨다(P1-05). Spring Boot 4.1.2 와 Gradle wrapper 9.7.1 은 그대로다. 위 줄은 P0-A 때의 기록으로 남긴다.
- Boot 4 의 기본 Jackson 은 Jackson 3 이다. Boot 4.1.2 고유의 동작은 [ADR-011](ADR-011-metadata-secrets-env-only.md) 과 [ADR-012](ADR-012-flyway-connection-pin.md) 에 적었다.
- Boot 3.5 의 OSS 지원 종료일은 외부 자료 기준, 미확인이다. 이 결정의 근거로 쓰지 않는다.

## 근거

- 사용자 결정 D1, 2026-11-15: 「1. 권장하는 방향으로 진행해줘.」 ([REQUIREMENTS.md](../REQUIREMENTS.md) AR-0)
- 승인된 설계 §0, §2 와 §10 의 ADR 010.
