# ADR-016 개발용 compose 예시 파일 이름은 deploy/env.example

- 상태: 승인됨
- 날짜: 2026-11-17
- 구현: 구현됨 (P0-B, f913239)

## 맥락

승인된 설계 §13 은 「`.env*` 는 git-ignore 하고, 예시는 `.env.example` 로만 둔다」고 적었다. P0-B 에서 개발용 MariaDB compose 의 예시 환경 파일이 필요해졌다. 그런데 이 저장소를 다루는 개발 팩토리는 `.env.*` 이름의 파일을 비밀 파일로 본다. 품질 게이트의 diff 검사는 그런 이름의 변경 파일을 실패로 판정하고(`.example` 예외가 없다), 에이전트는 그런 파일을 읽을 수 없어 내용을 확인하거나 리뷰할 수 없다.

## 결정

- 예시 파일은 앞에 점이 없는 `deploy/env.example` 이다. 설계 §13 의 `.env.example` 을 대신한다.
- 실제 파일은 git 이 무시하는 `deploy/.env` 이고, `cp deploy/env.example deploy/.env` 로 만든다.
- `deploy/env.example` 에는 자리 표시 값만 둔다.

## 결과

- 구현됨 (P0-B): `deploy/env.example`, `deploy/compose.dev.yml`. `.gitignore` 에서 쓰이지 않게 된 `!.env.example` 부정 패턴을 뺐다.
  - 2026-11-23 주 (P1-06, [ADR-026](ADR-026-sqlite-embedded-metadata-store.md)): 개발용 MariaDB 가 필요 없어져 `deploy/compose.dev.yml` 을 지웠다. `deploy/env.example` 은 같은 이름으로 남아, `java -jar` 로 런타임을 직접 실행할 때의 `QUERYHUB_DATA_DIR`(주석 예시), 관리자 토큰, 마스터 키 자리를 보인다(자리 표시 값만).
- `env.example` 은 비밀 파일 패턴(`*.env`, `.env`, `.env.*`)에 걸리지 않으므로 리뷰와 게이트가 내용을 확인할 수 있다.
- 다른 프로젝트의 관례(`.env.example`)와 이름이 다르다. 사용 절차는 [DEPLOYMENT.md](../DEPLOYMENT.md)에 있다.

## 근거

- 사용자 결정, 2026-11-17 (P0-B 진행 중): 「P0-B의 예시 파일을 어떻게 할까요?」라는 질문에 「이름을 env.example로 (권장)」이라고 답했다.
