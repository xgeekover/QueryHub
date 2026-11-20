# ADR-006 DB 비밀번호는 AES-256-GCM, API Key 는 HMAC-SHA-256 해시

- 상태: 승인됨
- 날짜: 2026-11-15
- 구현: 계획 (Phase 1, 4)

## 맥락

명세 9절 7·8항은 API Key 원문을 저장하지 않고 해시로 저장하고, DB 비밀번호를 암호화해 저장할 것을 요구한다. 업무 DB 의 접속 비밀번호는 런타임이 다시 써야 하므로 되돌릴 수 있어야 하고, API Key 는 대조만 하면 된다.

## 결정

- 업무 DB 비밀번호는 AES-256-GCM 으로 암호화해 Metadata DB 에 둔다. 마스터 키는 Desktop 에서는 OS 키체인, Server 에서는 환경변수나 시크릿 파일에서 받는다(Phase 1).
- API Key 는 HMAC-SHA-256 해시와 식별용 prefix 만 저장한다. 원문은 생성 응답에서 한 번만 보여 준다. API 별 권한은 `api_key_permission` 으로 둔다(Phase 4).
- 마스터 키와 비밀번호는 환경변수로 넘기고 명령행 인자에 넣지 않는다([ADR-011](ADR-011-metadata-secrets-env-only.md) 과 같은 원칙).

## 결과

- Metadata DB 가 새어도 비밀번호와 API Key 원문이 바로 드러나지 않는다.
- 마스터 키를 잃으면 저장된 비밀번호를 되살릴 수 없다. 키 회전 절차는 Phase 1 에서 정한다.
- Phase 1 통과 기준: 비밀번호 평문이 DB, 로그, 응답에 없다.

## 근거

- 승인된 설계 §1.2, §7 과 §10 의 ADR 006 (원문 미저장).
