# ADR-014 런타임이 QUERYHUB_DESKTOP_PARENT_PID 로 부모 셸을 감시한다

- 상태: 승인됨
- 날짜: 2026-11-15
- 구현: 구현됨 (P0-C, f84c628)

## 맥락

Tauri 셸이 비정상 종료하면 자식 런타임이 고아로 남아 포트와 DB 연결을 붙잡을 수 있다. 셸은 정상 종료 때 런타임을 멈추지만(SIGTERM, 10 초 뒤 SIGKILL), 셸이 강제로 끝나면 그 코드가 돌지 않는다.

## 결정

- 셸은 자기 PID 를 `QUERYHUB_DESKTOP_PARENT_PID` 환경변수로 자식에게 넘긴다.
- 런타임은 Spring 컨텍스트를 만들기 전(`QueryHubApplication.main`)에 감시를 시작한다. 그래서 기동이 멈춰 있는 동안에도 셸이 끝나면 런타임이 끝난다.
- 값이 있으면 0 보다 큰 10진수여야 하고, 그 프로세스가 살아 있어야 하며, 런타임 자신의 PID 가 아니어야 한다. 아니면 변수 이름을 말하는 메시지와 함께 종료 코드 2 로 기동을 멈춘다.
- 변수가 없으면 감시하지 않는다(Server Mode, 손으로 `java -jar` 를 실행한 경우).
- 부모가 끝나면 INFO 한 줄을 남기고 `System.exit` 로 Boot 의 종료 훅이 컨텍스트를 닫게 한다. 30 초(`HALT_AFTER`) 뒤에도 JVM 이 살아 있으면 `Runtime.halt` 로 끝낸다(종료 코드 3).

## 결과

- 구현됨 (P0-C): `runtime/app/src/main/java/com/queryhub/app/desktop/ParentProcessWatch.java` (`ENV_PARENT_PID`, `HALT_AFTER = Duration.ofSeconds(30)`), `runtime/app/src/main/java/com/queryhub/app/QueryHubApplication.java` (`EXIT_INVALID_PARENT_WATCH = 2`).
- JDK 는 자식이 아닌 프로세스를 길어야 5 초마다 확인하므로 감지가 5 초쯤 늦을 수 있다. 그 사이 `tauri dev` 재빌드가 새 인스턴스를 띄우면 포트가 아직 쓰이고 있을 수 있다([BACKLOG](../BACKLOG.md) P0C-R7).
- `System.exit` 는 공용 ForkJoinPool 스레드에서 불린다(P0C-R6).

## 근거

- 승인된 설계 §1.2: 「Tauri 가 비정상 종료해도 런타임이 남지 않도록 런타임이 부모 프로세스를 감시한다.」 감시의 필요는 승인된 설계가 정했고, 환경변수로 PID 를 넘기는 방식은 P0-C 계획에서 정했다.
