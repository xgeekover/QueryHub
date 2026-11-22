# ADR-015 Desktop 포트 18580 고정과 Tauri 출처용 관리 API CORS

- 상태: 제안
- 날짜: 2026-11-19
- 구현: 구현됨 (P0-C, f84c628)

## 맥락

승인된 설계 §1.2 는 Desktop 포트를 「설정값(충돌이 적은 기본값), 기본 바인딩 127.0.0.1」로 적었다. Phase 0 에는 설정 화면이 없다. 또 WebView 의 UI 는 다른 출처(`tauri://localhost`)에서 런타임의 관리 API 를 부르므로 브라우저의 CORS 규칙에 걸린다. 설계에는 CORS 에 관한 내용이 없다.

## 결정

- Desktop 포트는 18580 으로 고정한다. `desktop` 프로파일의 기본값(`runtime/app/src/main/resources/application-desktop.yml` 의 `server.address: 127.0.0.1`, `server.port: 18580`)과 셸의 `DESKTOP_PORT`(`desktop/src-tauri/src/runtime/supervisor.rs`)가 같고, 셸은 언제나 `--server.port=18580` 을 넘긴다. 포트가 이미 쓰이고 있으면 셸은 `port_in_use` 로 실패한다.
- 관리 API(`/admin/api/v1/**`)의 CORS 는 Tauri WebView 출처 두 개만 허용한다: `tauri://localhost`(macOS·Linux)와 `http://tauri.localhost`(Windows). 메서드는 GET 만, 허용하는 요청 헤더는 `Authorization` 하나이고(P1-03, 관리자 토큰), 자격 증명(쿠키)은 쓰지 않는다. `Authorization` 때문에 생기는 사전 요청(OPTIONS)에는 토큰 없이 답하고, 요청한 헤더가 모두 허용될 때만 통과시킨다. 출처 값이 잘못되면 기동을 멈춘다.
- CORS 설정 키(`queryhub.admin.cors.allowed-origins`)는 `application-desktop.yml` 에만 있다. `server` 프로파일에는 CORS 가 없다.
- P1-03 부터 CORS 는 `WebMvcConfigurer` 매핑이 아니라 관리 Spring Security 체인의 `cors()` 가 쓰는 `CorsConfigurationSource` 다. 그래서 401·403 응답에도 허용 출처의 `Access-Control-Allow-Origin` 이 붙는다([ADR-022](ADR-022-admin-token-implementation.md)).
- WebView 의 CSP `connect-src` 는 `http://127.0.0.1:18580` 만 런타임 주소로 허용한다(`desktop/src-tauri/tauri.conf.json`).

## 결과

- 구현됨: 포트 기본값은 P0-A 부터 `application-desktop.yml` 에 있었고, 셸의 고정 전달과 CORS, CSP 는 P0-C 다. CORS 는 `runtime/adapter-web/src/main/java/com/queryhub/adapter/web/admin/cors/AdminCorsConfiguration.java` (`PATH_PATTERN`, `ALLOWED_METHODS = {"GET"}`, `allowCredentials(false)`).
- 치르는 값: 사용자가 포트를 바꿀 수 없다. 18580 을 다른 프로그램이 쓰면 Desktop 을 쓸 수 없다. 포트 설정은 계획 (Phase 6 설정 화면)이다. `tauri dev` 재빌드 때는 이전 런타임이 부모 감시에 걸릴 때까지 18580 을 붙잡고 있어 새 인스턴스의 자동 시작이 `port_in_use` 에 걸릴 수 있다([BACKLOG](../BACKLOG.md) P0C-R7).
- 해결됨 (P1-03): 같은 PC 의 다른 Tauri 앱도 출처가 `tauri://localhost` 라 CORS 만으로는 관리 API 응답을 읽을 수 있었다. 이제 모든 관리 요청은 셸이 이 앱의 WebView 에만 주는 관리자 토큰을 요구하므로, 출처가 같아도 토큰이 없으면 401 이다([ADR-021](ADR-021-admin-token-bearer.md), [BACKLOG](../BACKLOG.md) P0A-I1 닫힘).

## 근거

- P0-C 계획과 구현에서 정했다. 사용자는 P0-C 의 커밋과 병합을 승인했지만(2026-11-19), 그것은 코드의 승인이고 이 결정의 승인이 아니다. 사용자가 받아들이면 승인됨으로 바꾼다.
- 설계와 다른 점은 [ARCHITECTURE.md](../ARCHITECTURE.md)의 「설계와 다른 구현」에 있다.
