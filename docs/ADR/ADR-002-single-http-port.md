# ADR-002 HTTP 포트는 하나, 관리 API 와 데이터 API 는 경로로 구분한다

- 상태: 승인됨
- 날짜: 2026-11-15
- 구현: 일부 구현 — 단일 포트, 관리 API 경로, ArchUnit R5 는 구현됨 (P0-A, ee00165). 데이터 API 와 API 서비스 스위치는 계획 (Phase 3, 4)

## 맥락

런타임은 UI 가 쓰는 관리 API 와 외부 시스템이 부르는 데이터 API 를 함께 제공한다. 두 API 를 다른 포트에 두자는 안이 있었다.

## 결정

- 포트는 `server.port` 하나다. 관리 API 는 `/admin/api/v1/**`, 데이터 API 는 `/api/v1/**` 로 경로만 다르다.
- 두 API 는 인증이 다르다(관리자 토큰 / API Key, 둘 다 계획). 보안 설정과 필터는 경로 접두 단위로 따로 둔다. 두 API 의 코드는 서로의 경로와 인증을 참조하지 않는다. 그래서 나중에 포트를 나누게 되면 설정 변경으로 끝난다.
- 「API 서버 실행·중지」는 런타임 안의 스위치다. 꺼져 있으면 `/api/v1/**` 는 503 `SERVICE_STOPPED` 를 돌려주고, 관리 API 는 계속 동작한다. 「Runtime 시작·중지」는 JVM 프로세스 단위다.
- 포트 분리는 보류한다.

## 결과

- 구현됨 (P0-A): 관리 API 는 `runtime/adapter-web/src/main/java/com/queryhub/adapter/web/admin/` 아래의 `GET /admin/api/v1/runtime` 하나다. ArchUnit R5(`runtime/app/src/test/java/com/queryhub/architecture/ArchitectureRules.java`)가 관리 API 와 데이터 API 패키지의 상호 의존을 막는다. `SERVICE_STOPPED` 와 503 은 `runtime/domain/src/main/java/com/queryhub/domain/error/ErrorCode.java` 와 `ErrorHttpStatus` 에 정의만 돼 있다.
- 계획: 데이터 API 패키지와 디스패처([ADR-003](ADR-003-single-dispatcher-route-registry.md)), API 서비스 스위치(Phase 3, 4), 관리자 토큰(Phase 1), API Key(Phase 4).
- 치르는 값: 런타임을 외부 주소에 바인딩하면 관리 API 도 같은 주소로 열린다(설계 §11 6번). 대책은 관리자 토큰, Host 허용 목록, desktop 프로파일의 루프백 전용 관리 API 이며 모두 계획 (Phase 1)이다. 지금 관리 API 에는 인증이 없다([BACKLOG](../BACKLOG.md) P0A-I1).

## 근거

- 사용자 결정 D3, 2026-11-15: 「3. 관리 포트와 데이터 포트는 굳이 분리하지 말고, 일단 같게 유지해줘. 나중에 필요하면 그 때 분리하자.」 ([REQUIREMENTS.md](../REQUIREMENTS.md) AR-0)
- 승인된 설계 §1.1 과 §10 의 ADR 002.
