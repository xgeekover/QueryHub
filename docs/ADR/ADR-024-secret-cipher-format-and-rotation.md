# ADR-024 비밀 값 암호 형식 v1, 키 id, 키 교체

- 상태: 제안
- 날짜: 2026-11-23
- 구현: 일부 구현 (P1-04, 037385b (2026-11-23)). 형식 v1, 키 id, 여러 키 복호화, `needsReencryption`, `reencrypt` 는 구현했다. 이전 키 입력과 일괄 재암호화는 계획이다([BACKLOG](../BACKLOG.md) P104-4)

## 맥락

[ADR-006](ADR-006-password-aes-gcm-api-key-hmac.md)(승인됨)은 업무 DB 비밀번호를 AES-256-GCM 으로 암호화한다고 정했다. 설계는 저장 형식, AAD(함께 인증하는 데이터), 키 식별, 키 교체를 정하지 않았다. 다음 태스크(연결 저장)가 업무 DB 비밀번호를 저장한다. 행이 생기기 전에 형식을 고정해야 나중에 형식을 바꾸지 않아도 된다. 키를 받는 방법은 [ADR-023](ADR-023-master-key-delivery.md)에 있다.

## 결정

1. **포트 (`runtime/application`, 순수 Java):** `com.queryhub.application.secret.SecretCipher` 의 메서드는 네 개다.
   - `encrypt(plaintext, context)`
   - `decrypt(stored, context)`
   - `needsReencryption(stored)`: kid 가 현재 키가 아니면 true
   - `reencrypt(stored, context)`

   `SecretContext(purpose, recordId)` 는 만들 때 검사한다. purpose 는 `[a-z][a-z0-9_.]{0,63}` 이고 recordId 는 UTF-8 로 1-256 바이트다. 상수 `database_connection.password` 를 둔다. `SecretCipherException` 의 `Reason` 은 `MALFORMED`, `UNSUPPORTED_VERSION`, `UNKNOWN_KEY`, `AUTHENTICATION_FAILED`, `PLAINTEXT_TOO_LONG` 이다. 메시지는 고정된 한국어이고 키, 평문, 저장 값을 담지 않는다. 모듈은 여전히 `:domain` 만 선언한다.
2. **어댑터 (`runtime/adapter-crypto`, JDK 만):** 패키지는 `com.queryhub.adapter.crypto` 다.
   - `MasterKey`: 정확히 32 바이트를 방어적 복사로 들고, 바이트가 모두 같은 키를 거부한다. `toString` 은 kid 만 보인다.
   - `AesGcmSecretCipher(current, decryptOnly)`: 같은 kid 가 둘이면 거부한다. 호출마다 새 `Cipher` 를 만들므로(`AES/GCM/NoPadding`) 스레드에 안전하다. nonce 는 96 비트이고 SecureRandom 하나에서 받는다. 태그는 128 비트다.
   - ArchUnit R10 은 `javax.crypto..` 를 `com.queryhub.adapter.crypto..` 에서만 쓰게 한다. `ArchitectureRulesSelfTest` 가 위반 픽스처로 이 규칙이 실제로 실패하는지 확인한다.
3. **키 id (kid):** HMAC-SHA256(키, ASCII `queryhub master key id v1`)의 앞 8 바이트를 소문자 16진수 16 자로 쓴다. kid 는 키의 접두사가 아니고 키를 드러내지 않는다. 기동 로그는 kid 만 INFO 로 한 줄 남긴다.
4. **저장 형식 v1 (ASCII):** `qh1.` + kid + `.` + base64url(패딩 없음)(nonce 12 바이트 ‖ 암호문 ‖ 태그 16 바이트).
   - AAD 는 차례로 이어 붙인다: UTF-8 헤더 `qh1.<kid>.`, 4 바이트 빅엔디언 길이와 purpose 의 UTF-8, 같은 방식의 recordId.
   - 그래서 버전, kid, 문맥을 바꾸면 태그 검증이 실패한다. 다른 행이나 다른 용도로 옮긴 암호문은 풀리지 않는다.
   - 평문은 UTF-8 로 0-4096 바이트다.
   - 고정한 키, nonce, 평문, 문맥에서 나오는 고정 문자열을 알려진 답 시험(`AesGcmSecretCipherTest`)으로 고정한다. 시험 벡터는 시험 소스에만 있고 NOT A SECRET 으로 표시한다.
5. **복호화 오류:**
   - 모르는 버전 접두사: `UNSUPPORTED_VERSION`
   - 구조가 틀리거나, base64url 밖의 문자나 패딩이 있거나, 풀어 보니 28 바이트 미만: `MALFORMED`
   - kid 가 키 목록에 없음: `UNKNOWN_KEY`
   - 태그 실패(변조, 다른 문맥): `AUTHENTICATION_FAILED`

   어느 경우에도 평문을 돌려주지 않는다. 형식 v1 에는 길이 필드가 없다. 그래서 본문 안을 자르거나 덧붙여도 여전히 28 바이트 이상으로 풀리면 `MALFORMED` 가 아니라 `AUTHENTICATION_FAILED` 다.
6. **기동 거부:** 키가 없거나 틀리면 두 프로파일 모두 포트를 열기 전에 기동을 거부한다([ADR-023](ADR-023-master-key-delivery.md) 결정 2).
7. **교체의 기본 연산 (구현됨):** 형식 버전과 kid, 여러 키로 복호화, `needsReencryption`, `reencrypt`(풀어서 현재 키로 다시 암호화)를 구현했다. 암호화는 늘 현재 키로 한다. `needsReencryption` 은 kid 가 현재 키가 아닌 올바른 형식의 값이면 모두 true 다. 모르는 kid 도 true 이고, 그 값에 `reencrypt` 를 부르면 `UNKNOWN_KEY` 다.
8. **server 의 교체 절차 (이전 키 입력이 생긴 뒤, P104-4):**
   1. 새 키를 만든다(`openssl rand -hex 32`).
   2. 새 키를 `QUERYHUB_MASTER_KEY` 로, 옛 키를 복호화 전용 이전 키로 둔다.
   3. 재시작한다.
   4. 모든 행을 다시 암호화한다.
   5. 옛 kid 를 쓰는 행이 남지 않았는지 확인한다.
   6. 옛 키를 빼고 재시작한다.

   이전 키 입력(`QUERYHUB_MASTER_KEY_PREVIOUS[_FILE]`), 일괄 재암호화 작업, kid 별 보고는 연결 저장 태스크와 함께 만든다. 그때까지는 `QUERYHUB_MASTER_KEY` 를 바꾸면 기존 값이 `UNKNOWN_KEY` 가 된다. 지금은 저장된 행이 없다.
9. **desktop 의 교체:** 후속이다(P104-5). 옛 키를 이전 키로 남겨 두고 키 파일을 바꾸는 방식이다.
10. **nonce 한도:** nonce 는 무작위 96 비트다. NIST SP 800-38D 는 한 키로 2^32 번까지 암호화하라고 한다. 예상하는 비밀번호 수는 이 한도보다 훨씬 적다.

## 검토한 대안

- **이진 형식, 운영자가 정하는 키 버전:** 이진 형식은 사람이 보기 어렵다. 운영자가 정하는 버전은 설정이 늘고 서로 겹칠 수 있다. 그래서 키에서 얻는 kid 와 텍스트 형식을 골랐다.
- **AAD 없음:** DB 에 쓸 수 있는 사람이 암호문을 다른 행으로 옮길 수 있다. 문맥(`SecretContext`)을 필수로 했다.
- **카운터 nonce:** 상태를 저장해야 하고 여러 인스턴스가 겹칠 수 있다. 무작위 nonce 를 골랐다.
- **구현을 app 이나 application 에 두기:** app 은 조립 모듈이고, application 에 두면 인프라가 들어간다. `adapter-crypto` 를 새로 만들었다.
- **이전 키 입력과 재암호화 명령을 지금 만들기:** 다시 암호화할 행이 아직 없다. 미뤘다(P104-4).

## 결과

- 키를 잃으면 저장한 비밀번호를 되살릴 수 없고 다시 입력해야 한다.
- 연결 하나를 어떤 필드로 묶을지(recordId 와 AAD)는 연결 저장 태스크가 정한다. id 만 묶으면 DB 에 쓸 수 있는 사람이 같은 행의 호스트를 바꾸는 것은 막지 못한다. AAD 에 id 를 넣으려면 암호화 전에 id 가 있어야 한다(P104-3).
- 연결 저장 태스크는 `UNKNOWN_KEY` 를 사용자에게 보여야 한다. 키를 잃었거나, 데스크톱마다 키가 다른 경우다(P104-3).
- 운영 절차는 [DEPLOYMENT.md](../DEPLOYMENT.md) 「마스터 키」에 있다.

## 근거

- AES-256-GCM: [ADR-006](ADR-006-password-aes-gcm-api-key-hmac.md)(승인됨, 2026-11-15 설계).
- 형식, AAD, kid, 교체의 기본 연산, 모듈 위치: P1-04 수정 계획(T-20261122-234625-5340). 사용자가 따로 결정하지 않았으므로 제안이다.
