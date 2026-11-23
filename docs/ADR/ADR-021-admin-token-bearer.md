# ADR-021 관리 API 는 Bearer 관리자 토큰으로 인증하고, desktop 은 루프백과 Host 허용 목록으로 좁힌다

- 상태: 승인됨
- 날짜: 2026-11-22
- 구현: 구현됨 (P1-03, 2698115) — Windows·Linux 의 셸은 미검증(PLAT-1)

## 맥락

P0-A 부터 관리 API(`GET /admin/api/v1/runtime`)에는 인증이 없었다([BACKLOG](../BACKLOG.md) P0A-I1). server 프로파일은 모든 인터페이스에서 열리고, desktop 은 Host 허용 목록이 없어 DNS 리바인딩으로 읽힐 수 있었으며, 같은 PC 의 다른 Tauri 앱도 같은 출처 `tauri://localhost` 로 응답을 읽을 수 있었다. Phase 1 의 다음 태스크들은 관리 API 에 DB 연결 같은 민감한 기능을 더하므로 그 전에 인증이 있어야 한다. 승인된 설계는 이 방향을 이미 정했다: 관리 API 는 쿠키가 아닌 Bearer 토큰(§7), Desktop 의 토큰은 실행마다 셸이 만들어 런타임과 WebView 에 넘긴다(§1.2), 비밀 값은 환경변수로 넘기고 명령행 인자에 넣지 않는다(§1.2), 헬스 체크는 인증 없이(§5.3), 관리 API 와 데이터 API 는 경로로 나눈다(§1.1), Host 헤더 허용 목록과 루프백 기본 바인딩(§7).

## 결정

- **대상:** `/admin` 과 `/admin/` 아래의 모든 경로(대소문자 무관)와, `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` 밖의 모든 `/actuator/**` 경로는 `Authorization: Bearer <토큰>` 을 요구한다. 쿠키와 세션은 쓰지 않는다.
- **열린 경로:** health 세 경로는 토큰 없이 답한다(설계 §5.3, Tauri 감독과 Docker healthcheck). 데이터 API `/api/v1/**` 는 이 토큰과 관계없다(API Key 는 Phase 4).
- **Desktop:** 셸이 실행마다 OS 난수 생성기에서 256 비트 토큰을 만든다. 런타임에는 자식 환경변수 `QUERYHUB_ADMIN_TOKEN` 으로([ADR-013](ADR-013-desktop-child-env-allowlist.md)의 허용 목록에 셸이 정하는 값으로 더한다), WebView 의 UI 에는 Tauri 커맨드 `admin_token` 으로 넘긴다. 명령행 인자에는 넣지 않는다.
- **Server:** 환경변수 `QUERYHUB_ADMIN_TOKEN` 이나 시크릿 파일 경로 `QUERYHUB_ADMIN_TOKEN_FILE` 중 하나로 받는다.
- **실패 닫힘:** 토큰이 없거나 형식이 틀리면 런타임은 포트를 열기 전에 기동을 거부한다(두 프로파일 모두).
- **Desktop 의 루프백 전용:** desktop 프로파일의 관리 요청은 소켓의 원격 주소가 루프백일 때만 받는다(`queryhub.admin.allow-remote=false`, server 프로파일은 true). `X-Forwarded-For`, `Forwarded` 는 읽지 않는다(이를 켜는 설정이면 기동을 거부한다, ADR-022 §6).
- **Host 허용 목록:** desktop 은 늘 `localhost`, `127.0.0.1`, `[::1]` 과 `queryhub.admin.allowed-hosts` 의 이름만 받는다(DNS 리바인딩 방어). server 는 `queryhub.admin.allowed-hosts` 를 설정할 때만 검사한다.

세부 규칙(토큰 형식, 필터 순서, Host 문법, CORS, 경로 방화벽)은 [ADR-022](ADR-022-admin-token-implementation.md)(제안)에 있다.

## 결과

- 구현됨 (P1-03): runtime 의 `runtime/adapter-web/src/main/java/com/queryhub/adapter/web/admin/security/`, `runtime/app/src/main/java/com/queryhub/app/config/admin/`, 셸의 `desktop/src-tauri/src/runtime/token.rs`, `launch.rs` 의 `child_env`, `commands.rs` 의 `admin_token`, UI 의 `desktop/src/runtime/adminApi.ts`, `useRuntimeStatus.ts` 의 `useAdminToken`.
- P0A-I1 을 닫는다. 다른 Tauri 앱은 출처가 같아도 토큰이 없으므로 401 을 받는다.
- 치르는 값: 토큰은 런타임 자식의 환경에 있으므로 같은 사용자의 프로세스(와 root)는 읽을 수 있다(macOS `ps -E`, Linux `/proc/<pid>/environ`). 설계 §1.2 와 [ADR-011](ADR-011-metadata-secrets-env-only.md)이 받아들인 경로다. 셸을 다시 시작하면 토큰이 바뀐다. desktop 에서 손으로 관리 API 를 부르려면 런타임을 직접 실행하고 토큰을 정해야 한다.
- 남은 문제: 넓은 바인딩 주소를 거부하는 바인딩 가드(P103-1), WebView 의 최상위 탐색 경화(P103-2), desktop 에서 allow-remote 와 allowed-hosts 를 바꾸는 설정(P103-3). [BACKLOG](../BACKLOG.md), [SECURITY.md](../SECURITY.md).

## 근거

- 2026-11-15 에 승인된 설계 §1.1(경로 분리, 루프백 기본 바인딩), §1.2(실행마다 셸이 만든 토큰을 런타임과 WebView 에 전달, 비밀 값은 환경변수로), §5.3(헬스 체크는 인증 없이), §7(쿠키가 아닌 Bearer 토큰, Host 헤더 허용 목록, 루프백 바인딩). 이 ADR 은 그 절들을 한곳에 옮긴 것이다.
