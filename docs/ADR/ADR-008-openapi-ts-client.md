# ADR-008 관리 API 의 OpenAPI 에서 TS 클라이언트를 생성한다

- 상태: 승인됨
- 날짜: 2026-11-15
- 구현: 계획 (Phase 1, 6) — 저장소에는 아직 `contracts/` 디렉터리도 springdoc 의존성도 없다

## 맥락

명세 2절은 Desktop Mode 와 Server Mode 가 같은 API Contract 를 쓰라고 한다. UI 는 Tauri IPC 가 아니라 관리 API(HTTP)로만 런타임과 통신한다.

## 결정

- 관리 API 의 OpenAPI 문서(springdoc)를 `contracts/` 에 고정하고, 거기서 UI 의 TypeScript 클라이언트를 생성한다.
- 데이터 API 의 OpenAPI 문서는 API 정의에서 동적으로 만들고 API Explorer 메뉴가 쓴다.

## 결과

- 두 모드의 계약이 한 파일로 고정되고, UI 와 런타임의 불일치가 빌드에서 드러난다.
- 계약 파일이 바뀌면 리뷰 대상이 된다. 생성 절차와 검사는 관리 API 리소스가 처음 생기는 Phase 1 과 UI 가 쓰는 Phase 6 에서 정한다.
- 지금 UI 가 HTTP 로 부르는 관리 API 는 `GET /admin/api/v1/runtime` 하나이고, 그 클라이언트는 손으로 쓴 코드다(`desktop/src/runtime/adminApi.ts` 의 `RUNTIME_INFO_PATH`). 런타임 기동·중지·상태는 HTTP 가 아니라 Tauri 명령으로 셸에 요청한다.

## 근거

- 승인된 설계 §5.3 과 §10 의 ADR 008 (두 모드의 contract 일치).
