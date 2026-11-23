# ADR-013 Desktop 셸은 자식 런타임에 허용 목록의 환경변수만 넘긴다

- 상태: 승인됨
- 날짜: 2026-11-18
- 구현: 구현됨 (P0-C, f84c628) — Windows 목록은 미검증

## 맥락

Spring Boot 는 OS 환경변수를 패키지에 든 `application-desktop.yml` 보다 우선한다. P0-C 코드 리뷰 1회차(지적 R1)는 셸이 `QUERYHUB_*`, `SPRING_*` 와 JVM 옵션 변수만 지우고 나머지를 자식에게 넘긴다는 것을 지적했다. 그래서 셸을 띄운 터미널의 `SERVER_ADDRESS=0.0.0.0` 은 인증 없는 API 를 모든 인터페이스에 열고, `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=*` 는 DB 비밀번호가 든 heapdump 같은 actuator 엔드포인트를 연다.

## 결정

- 지우는 목록(deny-list) 대신 허용 목록(allow-list)을 쓴다. 자식 환경을 비운 뒤(`env_clear`) 다음만 넘긴다.
  - unix: `PATH`, `HOME`, `USER`, `LOGNAME`, `LANG`, `TZ`, `TMPDIR`, 그리고 `LC_` 로 시작하는 모든 변수.
  - Windows (미검증, 이름은 대소문자 구분 없이 비교): `PATH`, `SYSTEMROOT`, `WINDIR`, `TEMP`, `TMP`, `USERPROFILE`, `APPDATA`, `LOCALAPPDATA`, `PATHEXT`, `COMPUTERNAME`.
  - 그다음 셸이 정하는 값: Metadata DB 변수 다섯 개(`QUERYHUB_METADATA_DB_*`), 관리자 토큰 `QUERYHUB_ADMIN_TOKEN`(P1-03, 셸이 실행마다 만든 값, [ADR-021](ADR-021-admin-token-bearer.md)), `QUERYHUB_DESKTOP_PARENT_PID`. 셸 자신의 환경에 있는 `QUERYHUB_ADMIN_TOKEN`, `QUERYHUB_ADMIN_TOKEN_FILE` 은 허용 목록 밖이므로 넘기지 않는다.
  - P1-04: 마스터 키 `QUERYHUB_MASTER_KEY`. 셸이 키 파일에서 읽은 값이다([ADR-023](ADR-023-master-key-delivery.md)). 셸 자신의 환경에 있는 `QUERYHUB_MASTER_KEY`, `QUERYHUB_MASTER_KEY_FILE`, `QUERYHUB_MASTER_KEY_PREVIOUS`, `QUERYHUB_MASTER_KEY_PREVIOUS_FILE` 은 허용 목록 밖이므로 넘기지 않는다.
- `java -version` 사전 점검은 허용 목록의 변수만 받는다(`launch.rs` 의 `inherited_env`). Metadata DB 변수, 관리자 토큰, 마스터 키, `QUERYHUB_DESKTOP_PARENT_PID` 는 넘기지 않고, 런타임 자식의 환경에만 더한다(`child_env`).
- 인자는 `-jar <jar> --spring.profiles.active=desktop --server.port=<port>` 그대로다. 비밀 값은 인자에 넣지 않는다.

## 결과

- 구현됨 (P0-C): `desktop/src-tauri/src/runtime/launch.rs` 의 `INHERITED_NAMES`, `INHERITED_PREFIX` 와 자식 인자 구성. 단위 테스트와 실제 자식 프로세스를 띄우는 테스트가 `SERVER_ADDRESS`, `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` 같은 변수가 넘어가지 않음을 확인한다.
- 허용된 이름 중 Spring 속성에 바인딩되는 것은 없다. 셸의 환경으로는 런타임의 바인딩, 노출, 로깅, 프로파일을 바꿀 수 없다.
- P1-03: 자식 출력의 가림은 Metadata DB 비밀번호와 관리자 토큰을 함께 가린다(`output.rs` 의 `mask_secrets`). 토큰은 인자와 `java -version` 의 환경에 들어가지 않는다(cargo 시험이 확인한다).
- P1-04: 가림 목록에 마스터 키를 더했다. 키는 인자와 `java -version` 의 환경에 들어가지 않는다. cargo 시험 `child_env_uses_the_key_file_value_and_drops_inherited_master_key_variables`, `the_child_gets_only_the_metadata_variables_the_parent_pid_and_the_fixed_argv`, `an_exiting_child_gives_failed_exited_with_a_masked_tail_and_no_password_in_json` 이 확인한다.
- 치르는 값과 남은 문제([BACKLOG](../BACKLOG.md)): `JAVA_HOME` 은 java 를 고르는 데만 쓰고 자식에게 넘기지 않는다(P0C-C2-3). Windows 의 `Path` 대소문자 처리(P0C-C2-4)는 P0-D 에서 고쳤지만(`env_value`, 모든 플랫폼에서 컴파일되고 `cfg!(windows)` 로 고른다) Windows 에서 실행해 보지 않았고, Windows·Linux 전체(PLAT-1)와 함께 미검증이다. 사전 점검 환경은 P1-03 부터 가짜 java 가 남긴 환경 파일로 확인한다(P0C-C2-2 닫힘).

## 근거

- 사용자 결정, 2026-11-18 (P0-C 코드 리뷰 1회차): 「지금 고침」을 골라 R1 을 병합 전에 고치기로 했다. 허용 목록 방식과 목록의 내용은 그 수정의 요구사항으로 Queen 이 정했다.
- 승인된 설계 §1.2 (비밀 값은 환경변수로, 명령행 인자에 넣지 않는다)와 §1.1 (desktop 은 기본적으로 127.0.0.1 에 바인딩한다).
