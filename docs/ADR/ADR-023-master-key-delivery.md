# ADR-023 마스터 키 전달: server 는 환경변수나 시크릿 파일, desktop 은 셸이 만드는 키 파일

- 상태: 제안 (키체인을 쓰지 않는다는 결정은 사용자가 2026-11-23 에 내렸다. 키 파일의 세부는 P1-04 계획이 정했다)
- 날짜: 2026-11-23
- 구현: 구현됨 (P1-04, 037385b (2026-11-23)) — Windows·Linux 의 셸은 미검증(PLAT-1, CI-1)

## 맥락

승인된 설계 §1.2 와 §7 은 desktop 의 마스터 키를 OS 키체인에 두고, server 는 환경변수나 시크릿 파일로 받는다고 정했다. P1-04 는 업무 DB 비밀번호를 암호화할 256 비트 마스터 키와 `SecretCipher`([ADR-024](ADR-024-secret-cipher-format-and-rotation.md))를 더한다. 첫 계획은 macOS 키체인을 쓰려 했다. 2026-11-23 에 사용자가 이렇게 결정했다.

> 「MacOS에서뿐만 아니고, 리눅스/윈도우 환경에서도 동작시킬 수 있어야 해.」
>
> 「키체인이 필수가 아니라면, 사내 폐쇄망에서 사용할 거라 지금 당장은 키체인을 사용 안하고 싶습니다.」

키가 없을 때 server 도 기동을 거부할지, Linux·Windows 를 지금 확인할지에 대해서는 사용자가 선호를 말하지 않았다. 그래서 권장안을 따랐다. 키는 두 프로파일 모두 필수이고, Linux·Windows 는 미검증으로 둔다(PLAT-1, CI-1).

## 결정

1. **키 형식 (두 프로파일 공통):** 32 바이트를 16진수 64 자로 쓴다. `openssl rand -hex 32` 의 출력과 형식이 같다. 대소문자는 가리지 않는다. 32 바이트가 모두 같은 키와, 관리자 토큰과 같은 값(대소문자 무관)은 거부한다.
2. **server (런타임):** `QUERYHUB_MASTER_KEY`(속성 `queryhub.master-key`)와 `QUERYHUB_MASTER_KEY_FILE`(`queryhub.master-key-file`) 중 정확히 하나를 받는다. 파일은 읽을 수 있는 일반 파일이고, 비어 있지 않고, 4096 바이트 이하여야 한다. 끝의 LF 나 CRLF 하나는 지운다. 다음 경우에는 포트를 열기 전에 기동을 거부한다.
   - 키가 없거나 두 출처가 모두 있다.
   - 파일에 문제가 있거나 형식이 틀렸거나 약한 키이거나 관리자 토큰과 같다.
   - `queryhub.master-key` 가 명령행 인자, JVM 시스템 속성, 또는 그 두 곳의 `spring.application.json` 에서 온다(`ProcessVisibleSources`).

   메시지는 변수와 속성 이름만 말하고 값과 파일 경로는 말하지 않는다. 키는 설정 속성 클래스에 바인딩하지 않는다. 구현은 `MasterKeySource` 와 `SecretCipherConfiguration` 이다. 빈은 `@Lazy(false)` 라서 지연 초기화를 켜도 기동할 때 만든다. desktop 프로파일의 런타임도 같은 규칙을 따른다.
3. **desktop 의 키 파일 위치:** 셸(Tauri)이 앱 로컬 데이터 디렉터리 아래의 `secrets/master.key` 를 쓴다. 경로는 `desktop/src-tauri/src/lib.rs` 만 계산하고, 시험은 임시 디렉터리를 준다. 기준 디렉터리는 셸이 런타임의 작업 디렉터리로 쓰는 것과 같다. Tauri 2 의 `app_local_data_dir` 에 식별자 `com.queryhub.desktop` 을 붙인 디렉터리다. 예상 경로는 다음과 같다.
   - macOS: `~/Library/Application Support/com.queryhub.desktop/secrets/master.key` (Queen 의 수동 확인 대상, AC28)
   - Linux: `$XDG_DATA_HOME/com.queryhub.desktop/secrets/master.key`, 변수가 없으면 `~/.local/share/com.queryhub.desktop/secrets/master.key` (미검증)
   - Windows: `%LOCALAPPDATA%\com.queryhub.desktop\secrets\master.key` (미검증). Roaming(`%APPDATA%`)이 아니라 Local 이다. 로밍 프로필이 키를 도메인 파일 서버로 복사하지 않게 한다.
4. **처음 만들기와 다시 읽기:** 셸은 시작할 때마다(재시작 포함) 키 파일을 다시 읽는다. 캐시하지 않는다.
   - 키 디렉터리 `secrets/`: 없으면 만들고, unix 에서는 0700 으로 정한다. 있으면 심볼릭 링크가 아닌 디렉터리여야 한다. unix 에서는 셸의 실효 uid 가 소유하고 그룹·기타 권한 비트가 없어야 한다.
   - 파일이 없으면 첫 실행이다. OS 난수 생성기(`getrandom`)에서 32 바이트를 받아 소문자 16진수 64 자와 LF 를 `create_new` 로 쓴다(unix 0600). `sync_all` 한 뒤 다시 읽어 쓴 값과 같은지 본다. 다른 인스턴스가 먼저 만들었으면(AlreadyExists) 그 파일을 읽는다.
   - 파일이 있으면 읽기만 한다. 파일은 심볼릭 링크가 아닌 일반 파일이어야 한다. unix 에서는 `O_NOFOLLOW` 로 열고, 연 파일을 다시 확인하며, 실효 uid 소유이고 그룹·기타 비트가 없어야 한다. 크기는 4096 바이트 이하여야 한다. 끝의 줄바꿈 하나를 뺀 값은 16진수 64 자여야 하고(소문자로 맞춘다), 바이트가 모두 같으면 안 된다.
   - 그 밖의 모든 경우는 `master_key_unavailable` 로 실패하고 런타임을 띄우지 않는다. 새 키를 만들지 않는다. 있는 파일은 덮어쓰거나, 자르거나, 이름을 바꾸거나, 지우지 않는다.
   - 느슨한 권한은 저절로 고치지 않는다. 대신 메시지가 `chmod 600 '<경로>'`, `chmod 700 '<경로>'` 또는 `chown <uid> '<경로>'` 를 안내한다(ssh 와 같은 방식). 메시지는 경로(비밀이 아니다)와 고치는 방법만 말하고 파일 내용은 말하지 않는다.
   - Windows 에서는 소유자와 권한을 확인하지 않는다. `%LOCALAPPDATA%` 사용자 프로필의 기본 ACL 에만 기대고, DACL 을 따로 좁히지 않는다(미검증).
5. **런타임으로의 전달:** 셸은 경로가 아니라 값을, [ADR-013](ADR-013-desktop-child-env-allowlist.md)의 자식 환경 `QUERYHUB_MASTER_KEY` 로만 넘긴다.
   - argv, `java -version` 의 환경, 상태(`RuntimeStatus`, `Failure`), 로그에는 키를 넣지 않는다.
   - 셸 자신의 환경에 있는 `QUERYHUB_MASTER_KEY`, `QUERYHUB_MASTER_KEY_FILE`, `QUERYHUB_MASTER_KEY_PREVIOUS`, `QUERYHUB_MASTER_KEY_PREVIOUS_FILE` 은 넘기지 않는다.
   - 자식 출력의 가림 목록에 키를 더했다. 이미 Metadata DB 비밀번호와 관리자 토큰을 가린다.
   - `MasterKey` 의 Debug 와 Display 는 값을 보이지 않는다. Tauri 커맨드와 capability 는 키를 WebView 에 주지 않는다.
6. **조직이 관리하는 키 (미리 배치):** 관리자는 첫 실행 전에 같은 형식의 키 파일을 기본 경로에 둘 수 있다. unix 에서는 소유자와 모드(0700/0600)도 같아야 한다. 셸은 있는 파일을 덮어쓰지 않으므로 그 키를 그대로 쓴다.

   Metadata DB 하나를 함께 쓰는 여러 데스크톱은 이렇게 같은 키 파일을 받아야 한다. 키가 다르면 서로의 연결 비밀번호를 풀지 못한다(`UNKNOWN_KEY`). P1-04 는 셸의 키 경로를 바꾸는 환경변수를 두지 않는다([BACKLOG](../BACKLOG.md) P104-1).

## 검토한 대안

- **OS 키체인**(macOS Keychain, Windows 자격 증명 관리자, Linux Secret Service. `security-framework`, `keyring` 크레이트, FFI, Tauri 플러그인): 설계 §1.2 와 §7 의 방법이다. 사용자가 2026-11-23 에 지금은 쓰지 않기로 했다(사내 폐쇄망에서 쓰고, 세 OS 에서 동작해야 한다). 나중의 통합 여부는 BACKLOG P104-2 로 남긴다.
- **키 파일 경로(`QUERYHUB_MASTER_KEY_FILE`)를 자식에게 넘기기:** 키가 자식 환경에 들어가지 않는 장점은 있다. 하지만 셸이 검사한 뒤 런타임이 읽기 전에 파일이 바뀔 수 있고, 셸의 소유자·권한 검사가 런타임이 읽은 파일을 보장하지 못한다. 그래서 값을 넘긴다.
- **셸의 키 경로 환경변수:** 키를 아무 곳에나 두기는 쉬워진다. 하지만 입력과 우선순위 규칙이 늘고(경로를 지정했는데 파일이 없으면 첫 실행이 아니라 실패여야 한다) ADR-013 의 설명도 늘어난다. Finder 에서 연 macOS 앱은 셸 환경을 보지도 못한다. 기본 경로에 미리 두면 같은 결과를 얻으므로 미뤘다(P104-1).
- **임시 파일에 쓰고 하드 링크나 이름 바꾸기로 놓기:** 반쯤 쓴 파일이 남지 않는다. 하지만 하드 링크는 모든 파일 시스템에 있지 않고, 덮어쓰지 않는 rename 은 플랫폼마다 다르다. 그래서 `create_new`, `sync_all`, 다시 읽기를 골랐다. 첫 쓰기 도중 멈추면 형식이 틀린 파일이 남고, 다음 시작에서 거부된다([DEPLOYMENT.md](../DEPLOYMENT.md) 「마스터 키」).
- **느슨한 권한을 저절로 고치기:** 이미 노출됐을 수 있는 파일을 숨기게 된다. 거부하고 명령을 안내한다.
- **Roaming `%APPDATA%`:** 로밍 프로필이 키를 서버로 복사한다. Local 을 골랐다.
- **셸 프로세스 안에서 키를 캐시하기:** 대화상자가 없으므로 파일 읽기는 싸다. 시작마다 읽어 코드 경로를 하나로 둔다.
- **Base64 키:** 표준·URL 안전 알파벳과 패딩 유무가 섞여 모호하다. 16진수는 `openssl rand -hex 32` 와 같고 관리자 토큰의 인코더를 다시 쓴다.
- **키 없이 기동하고 암호화할 때 거부하기:** 잘못된 설정이 늦게 드러나고 관리자 토큰과 동작이 달라진다.

## 결과

- 세 OS 에서 같은 코드가 돈다. unix 권한 코드는 `cfg(unix)` 뒤에 있다. 새 크레이트는 없다(`std`, 이미 있던 `getrandom` 0.3.4, unix 의 `libc`). Windows·Linux 는 미검증이다(PLAT-1, CI-1). macOS 의 clippy 는 `cfg(windows)` 코드를 검사하지 않는다.
- **위협 모델이 키체인보다 약하다.**
  - 키 파일을 지키는 것은 파일 권한뿐이다(unix 0700/0600, Windows 는 프로필 ACL).
  - 같은 사용자로 도는 모든 프로세스와, 관리자나 root 는 키를 읽을 수 있다.
  - 데이터 디렉터리의 백업(예: Time Machine)과 복사본에는 키가 들어 있다.
  - 키체인처럼 앱마다 동의를 받는 단계가 없다.

  [SECURITY.md](../SECURITY.md) 「마스터 키」가 이 차이를 적는다.
- 키 파일을 잃거나 지우면 그 키로 암호화한 비밀번호는 되살릴 수 없다. 이때 셸은 다음 시작에서 새 키를 만든다. 지금은 암호화한 데이터가 없다. 연결 저장 태스크가 `UNKNOWN_KEY` 를 화면에 보여야 한다(BACKLOG P104-3).
- 런타임 JVM 의 Environment 에 키 문자열이 남으므로 힙 덤프에 들어 있다. 노출하는 actuator 엔드포인트는 health 뿐이고, 관리 체인이 `/actuator/**` 를 지킨다.
- desktop 의 키 교체(이전 키를 남겨 두고 키 파일을 바꾸기)는 후속이다([ADR-024](ADR-024-secret-cipher-format-and-rotation.md), BACKLOG P104-5).
- 첫 실행을 두 셸이 동시에 하면, `create_new` 에서 진 쪽이 반쯤 쓴 파일을 읽고 그 시작만 실패할 수 있다. 드물고 다음 시작에서 풀린다. 고정 포트 때문에 둘째 인스턴스는 어차피 실패한다.

## 근거

- 키체인을 쓰지 않고 세 OS 에서 동작한다: 사용자 결정, 2026-11-23(위 「맥락」의 인용).
- 키는 필수, Linux·Windows 는 미검증: 같은 날 사용자가 선호를 말하지 않아 Queen 이 권장안을 유지했다.
- 키 파일의 위치, 형식, 만들기·읽기 규칙, 권한, 미리 배치: P1-04 수정 계획(T-20261122-234625-5340). 이 부분은 제안이다.
- 설계 §1.2·§7 과의 차이는 [ARCHITECTURE.md](../ARCHITECTURE.md) 「설계와 다른 구현」 11 행에 있다. 이 ADR 은 [ADR-006](ADR-006-password-aes-gcm-api-key-hmac.md)이 가리키던 desktop 키 출처(키체인)를 대신한다.
