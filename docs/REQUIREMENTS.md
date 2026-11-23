# QueryHub 요구사항

이 문서는 QueryHub 요구사항의 기준이다.

- **출처:** 사용자가 2026-11-15 에 준 명세 원문이다.
- **원문 보존:** `SPEC-VERBATIM-BEGIN` 표시와 `SPEC-VERBATIM-END` 표시 사이의 글은 원문을 한 글자도 바꾸지 않고 옮긴 것이다. 이 부분은 고치지 않는다. 오타나 낡은 내용이 있어도 그대로 둔다.
- **우선순위:** 원문 뒤의 [추가 요구사항 (승인된 변경)](#추가-요구사항-승인된-변경)은 사용자가 승인한 변경이다. 추가 요구사항이 원문의 어느 부분을 대체한다고 밝힌 곳에서는 추가 요구사항이 우선한다. 그 밖에는 원문이 기준이다.
- **상태 표기:** 모든 문서는 같은 말을 쓴다. 구현됨 (P0-x), 일부 구현, 계획 (Phase n), 미실측, 미검증. 무엇이 구현됐는지는 [ARCHITECTURE.md](ARCHITECTURE.md)와 [CHANGELOG.md](../CHANGELOG.md)를 본다.

<!-- SPEC-VERBATIM-BEGIN -->
프로젝트: QueryHub

SQL 기반 API 데이터 서비스 및 데이터 추출 데스크톱 플랫폼

목표는 SQL을 데이터베이스에 저장하고, 해당 SQL을 API와 연결하여 외부 시스템에서 HTTP API로 호출할 수 있는 데이터 서비스 플랫폼을 개발하는 것입니다.

단순한 SQL 클라이언트가 아니라 데이터웨어하우스(DW)의 데이터 조회 API 서버로 활용할 수 있는 독립형 제품을 개발합니다.

---

## 1. 프로젝트 목표

사용자가 데스크톱 애플리케이션에서 다음 작업을 수행할 수 있어야 합니다.

1. MSSQL, Oracle, MariaDB, PostgreSQL 데이터베이스 연결 관리
2. SQL 쿼리 등록 및 관리
3. SQL에 사용할 파라미터 변수 정의
4. SQL과 API Endpoint 연결
5. API HTTP Method 및 응답 형식 설정
6. 등록한 API를 데스크톱에서 직접 테스트
7. 테스트 결과를 데이터 그리드로 확인
8. JSON, XML, CSV, XLSX, HTML 형식으로 데이터 추출
9. API 서버 실행 및 중지
10. 외부 시스템에서 실제 API 호출
11. API 실행 이력 및 오류 관리

프로그램 가칭은 QueryHub입니다.

## 2. 아키텍처

다음 두 가지 실행 환경을 반드시 지원합니다.

### A. Desktop Mode

- Windows / macOS / Linux 지원
- Tauri 2 기반 데스크톱 애플리케이션
- React + TypeScript + Vite 프론트엔드
- Java Spring Boot Runtime을 로컬 프로세스로 실행
- Desktop UI에서 Runtime 시작 / 중지 / 상태 확인
- 로컬 또는 원격 DB 연결 설정
- SQL 및 API 관리

### B. Server Mode

- 동일한 Spring Boot Runtime을 독립 서버로 실행
- Docker 및 독립 실행 가능한 JAR 지원
- Desktop 앱과 무관하게 API 서비스 지속 운영
- 외부 시스템에서 API 호출 가능
- API 관리용 Metadata DB와 실제 조회용 Business DB 분리

Desktop Mode와 Server Mode는 동일한 Runtime Core 및 API Contract를 사용해야 합니다.

## 3. 기술 스택

### Frontend

- React 18+
- TypeScript
- Vite
- Tauri 2
- Tailwind CSS
- shadcn/ui
- TanStack Query
- TanStack Table
- Monaco Editor (SQL Editor)
- Recharts (Dashboard)

### Backend

- Java 17 이상
- Spring Boot 3.x
- Spring Web
- Spring JDBC
- NamedParameterJdbcTemplate
- HikariCP
- Spring Security
- Springdoc OpenAPI
- Gradle

### Metadata Database

- MariaDB
- Flyway

### Data Export

- Jackson: JSON
- Jackson XML: XML
- Apache Commons CSV: CSV
- Apache POI SXSSF: Excel XLSX
- Thymeleaf 또는 안전한 HTML Template: HTML

### Testing

- JUnit 5
- Mockito
- Spring Boot Test
- Testcontainers
- Playwright

## 4. 데이터베이스 지원

다음 4개 DB를 필수 지원합니다.

1. Microsoft SQL Server
2. Oracle Database
3. MariaDB
4. PostgreSQL

각 데이터베이스는 JDBC Driver 기반으로 연결합니다.

DatabaseProvider 인터페이스를 정의하고 DB별 구현체를 분리합니다.

필요한 기능:

- Connection 생성
- Connection Test
- Connection Pool 관리
- Connection Timeout
- Query Timeout
- PreparedStatement 실행
- Named Parameter Binding
- ResultSet Metadata 조회
- JDBC 타입 변환
- 대용량 데이터 Streaming
- DB별 Pagination SQL 처리
- DB별 오류 메시지 표준화

SQL 문법은 DB별로 작성하는 것을 기본으로 합니다.

하나의 SQL이 모든 DB에서 동일하게 동작한다고 가정하지 않습니다.

## 5. 핵심 데이터 모델

다음 엔티티를 설계합니다.

### DatabaseConnection

- id
- connectionName
- databaseType
- host
- port
- databaseName
- username
- encryptedPassword
- jdbcUrl
- connectionProperties
- connectionTimeout
- enabled
- createdAt
- updatedAt

### SqlDefinition

- id
- sqlCode
- sqlName
- description
- databaseConnectionId
- sqlContent
- queryType
- version
- enabled
- createdAt
- updatedAt

### SqlParameter

- id
- sqlDefinitionId
- parameterName
- dataType
- required
- defaultValue
- description
- validationRule
- displayOrder

지원 타입:
STRING, INTEGER, LONG, DECIMAL, BOOLEAN, DATE, TIME, DATETIME, TIMESTAMP

### ApiDefinition

- id
- apiCode
- apiName
- description
- endpointPath
- httpMethod
- sqlDefinitionId
- responseType
- authenticationType
- cacheEnabled
- cacheTtlSeconds
- maxRows
- timeoutSeconds
- enabled
- version
- createdAt
- updatedAt

### ApiExecutionHistory

- id
- apiDefinitionId
- requestId
- requestedAt
- requestedBy
- executionTimeMs
- resultRowCount
- responseFormat
- status
- errorCode
- errorMessage

### ApiKey

- id
- keyName
- keyHash
- permissions
- expiresAt
- enabled
- createdAt

필요한 추가 엔티티는 아키텍처 판단에 따라 설계합니다.

ERD와 Flyway Migration을 함께 관리합니다.

## 6. API 설계

API는 등록된 API Definition을 기반으로 동적으로 실행되어야 합니다.

예시:

`POST /api/v1/orders/search`

Request:

```json
{
  "startDate": "2026-11-01",
  "endDate": "2026-11-15",
  "customerId": "CUST001"
}
```

Response JSON:

```json
{
  "success": true,
  "data": [
    {
      "orderId": 1001,
      "customerName": "Customer A",
      "orderAmount": 150000
    }
  ],
  "meta": {
    "rowCount": 1,
    "executionTimeMs": 35
  }
}
```

응답 형식은 다음을 지원합니다.

- JSON
- XML
- CSV
- XLSX
- HTML

응답 형식 선택은 Accept Header 및 명시적인 format 파라미터를 지원합니다.

예시:

- `?format=json`
- `?format=xml`
- `?format=csv`
- `?format=xlsx`
- `?format=html`

GET과 POST를 지원합니다.

GET은 Query Parameter, POST는 JSON Body를 기본 지원합니다.

필요한 경우 Form URL Encoded 요청도 확장 가능하도록 설계합니다.

API Endpoint는 SQL Definition과 API Definition을 연결하는 구조로 구현합니다.

API URL과 SQL 내용을 하드코딩하지 않습니다.

## 7. Parameter Binding

가장 중요한 기능 중 하나입니다.

SQL에 다음과 같은 Named Parameter를 사용할 수 있어야 합니다.

```sql
SELECT *
FROM TB_ORDERS
WHERE ORDER_DATE >= :startDate
AND ORDER_DATE <= :endDate
AND CUSTOMER_ID = :customerId
```

사용자는 Desktop UI에서 다음 변수를 관리할 수 있어야 합니다.

- 변수명
- 데이터 타입
- 필수 여부
- 기본값
- 입력값 검증
- 설명
- UI 표시 순서

파라미터는 반드시 PreparedStatement 기반으로 바인딩합니다.

문자열 단순 치환은 금지합니다.

IN 절 등 컬렉션 파라미터도 별도로 지원합니다.

파라미터 정의와 SQL 내 실제 변수의 일치 여부를 저장 전 검증합니다.

## 8. Desktop UI

전문적인 데이터베이스 관리 도구의 디자인을 구현합니다.

DbGate와 DBeaver의 UI/UX를 참고하되 QueryHub에 맞는 독립적인 디자인을 구현합니다.

### 메뉴 구성

1. Dashboard
2. Database Connections
3. SQL Repository
4. API Designer
5. API Tester
6. API Explorer
7. Execution History
8. Data Export
9. Security
10. Settings

### Dashboard

표시 항목:

- 전체 DB 연결 수
- 활성 API 수
- 오늘 API 호출 수
- 성공 / 실패 호출 수
- 평균 응답 시간
- 최근 실행 API
- API별 호출 통계
- 오류 발생 현황

### Database Connections

- DB 연결 등록 / 수정 / 삭제
- DB 종류 선택
- 연결 테스트
- 연결 상태 표시
- 연결별 SQL 목록
- 연결별 활성 API 목록

### SQL Repository

- SQL 목록 조회
- SQL 등록 / 수정 / 삭제
- SQL Editor
- SQL Syntax Highlighting
- SQL Formatting
- 파라미터 자동 감지
- SQL 실행 테스트
- 실행 결과 미리보기
- SQL 버전 관리
- 변경 이력 확인

SQL 편집 화면은 코드 에디터와 결과 데이터 그리드를 분할하여 구성합니다.

### API Designer

- API Name
- API Code
- HTTP Method
- Endpoint URL
- SQL Definition 연결
- Parameter Mapping
- Response Format
- Authentication
- Timeout
- Max Rows
- Cache 설정
- Enable / Disable

API 등록 시 Endpoint 중복 검사를 수행합니다.

### API Tester

Postman과 유사한 테스트 인터페이스를 구현합니다.

- HTTP Method 선택
- Endpoint 표시
- Parameters 입력
- JSON Body 편집
- API Key 설정
- Execute 버튼
- HTTP Status 확인
- Response Headers 확인
- Response Body 확인
- Execution Time 표시
- Result Grid 표시
- JSON / XML / CSV / XLSX 다운로드

### Data Export

지원 형식:

- HTML
- JSON
- XML
- CSV
- XLSX

HTML은 별도의 파일로 다운로드할 수 있도록 합니다.

CSV는 UTF-8 BOM 옵션을 지원합니다.

Excel은 대용량 데이터 처리에 SXSSF를 활용합니다.

Export 시 컬럼명과 데이터 타입을 유지합니다.

날짜, 숫자, NULL, 한글, 특수문자 처리에 주의합니다.

## 9. 보안 및 운영 정책

DW API 서버로 활용할 수 있도록 반드시 다음 정책을 적용합니다.

1. 기본적으로 SELECT 전용 SQL만 실행합니다.
2. INSERT, UPDATE, DELETE, DDL은 기본 차단합니다.
3. 허용된 SQL만 실행하는 Allowlist 정책을 구현합니다.
4. DB 계정은 읽기 전용 계정 사용을 권장합니다.
5. SQL Injection 방지
6. API Key 인증
7. API Key는 원문을 저장하지 않고 Hash 저장
8. DB 비밀번호 암호화 저장
9. API별 접근 권한 관리
10. 요청 및 응답 로그의 민감정보 마스킹
11. Query Timeout
12. API Timeout
13. 최대 조회 건수 제한
14. 동시 실행 수 제한
15. Connection Pool 제한
16. API 호출 Rate Limit
17. 오류 발생 시 DB 상세 정보 외부 노출 금지

SQL 문자열 검사만으로 SELECT 전용 정책을 완벽하게 구현할 수 있다고 가정하지 않습니다.

SQL Parser 또는 DB 권한과 읽기 전용 트랜잭션을 함께 활용하여 안전성을 확보합니다.

## 10. 성능 및 대용량 데이터

대용량 DW 조회를 고려하여 설계합니다.

- JDBC Fetch Size
- Streaming ResultSet
- CSV Streaming Response
- JSON Streaming 또는 제한된 크기의 응답 처리
- XLSX SXSSF Streaming
- 최대 Row Limit
- Pagination
- Query Timeout
- Connection Pool 관리
- API 동시 실행 제한
- 메모리 사용량 관리

대용량 결과를 List에 전부 적재한 뒤 파일로 변환하는 구조는 금지합니다.

XLSX의 최대 행 제한을 고려하고, 초과 시 명확한 오류 또는 분할 파일 정책을 적용합니다.

## 11. 개발 단계

각 Phase는 구현 → 테스트 → 코드 리뷰 → 품질 게이트 순서로 진행합니다.

### Phase 0. 프로젝트 기반 구축

- 프로젝트 초기화
- 멀티 모듈 구조
- Clean Architecture
- Gradle 설정
- Flyway 설정
- 공통 예외 및 응답 처리
- CI 빌드

### Phase 1. DB Connection Management

- 4개 DB Driver 연결
- Connection CRUD
- Connection Test
- Connection Pool
- DB별 통합 테스트

### Phase 2. SQL Repository

- SQL CRUD
- SQL Editor
- Named Parameter Parser
- Parameter Definition
- SQL Test Execution
- Query History

### Phase 3. API Management

- API Definition CRUD
- SQL Mapping
- HTTP Method
- Endpoint Router
- Parameter Binding
- API Enable / Disable

### Phase 4. API Runtime

- Dynamic API Execution
- JSON Response
- XML Response
- GET / POST
- API Key
- Error Handling
- API Integration Test

### Phase 5. Data Export

- HTML
- JSON
- XML
- CSV
- XLSX
- Streaming Export
- 한글 및 특수문자 검증

### Phase 6. Desktop Application

- Tauri 연동
- React UI
- Database UI
- SQL Repository UI
- API Designer
- API Tester
- Export UI

### Phase 7. Monitoring & Security

- Execution History
- Dashboard
- API Key Management
- Query Timeout
- Rate Limit
- Access Control
- Logging
- Audit

### Phase 8. Deployment & Stabilization

- Desktop Installer
- Windows / macOS / Linux 빌드
- Server JAR
- Docker Image
- Configuration Profile
- 운영 문서
- API 문서
- 통합 테스트
- 성능 테스트
- 보안 테스트

## 12. 테스트 기준

다음 테스트를 반드시 수행합니다.

### Database Test

- MSSQL 연결 및 SELECT
- Oracle 연결 및 SELECT
- MariaDB 연결 및 SELECT
- PostgreSQL 연결 및 SELECT

### Parameter Test

- 정상 파라미터
- 필수 파라미터 누락
- 잘못된 데이터 타입
- NULL
- 기본값
- SQL Injection 시도
- 컬렉션 파라미터

### API Test

- GET
- POST
- 잘못된 URL
- 비활성 API
- 인증 실패
- 파라미터 검증 실패
- SQL 오류
- Timeout

### Export Test

- JSON
- XML
- CSV
- XLSX
- HTML
- 한글
- NULL
- 날짜 및 숫자
- 대용량 데이터

### Performance Test

- 1,000건
- 10,000건
- 100,000건
- 동시 요청
- Connection Pool 한계
- 메모리 사용량
- 응답 시간

테스트 결과를 문서화합니다.

## 13. 프로젝트 산출물

다음 문서를 생성하고 유지 관리합니다.

- README.md
- ARCHITECTURE.md
- ADR/
- ERD.md
- API_SPECIFICATION.md
- DATABASE_SUPPORT.md
- SECURITY.md
- DEPLOYMENT.md
- TEST_PLAN.md
- CHANGELOG.md

각 Phase 완료 시 구현 기능, 테스트 결과, 남은 문제를 기록합니다.

## 14. 개발 운영 원칙

- Clean Architecture를 준수합니다.
- Controller에 비즈니스 로직을 작성하지 않습니다.
- Database별 구현체를 분리합니다.
- 공통 인터페이스를 활용합니다.
- 테스트 가능한 구조를 유지합니다.
- SOLID 원칙을 적용합니다.
- 중복 코드 생성을 최소화합니다.
- 기능 구현 후 반드시 테스트합니다.
- 테스트 실패를 무시하지 않습니다.
- Mock 테스트만으로 실제 DB 호환성을 통과 처리하지 않습니다.
- 기존 기능이 깨지는 변경을 금지합니다.
- 모든 DB 비밀번호와 API Key는 안전하게 관리합니다.
- 운영 환경에서 SQL 원문과 민감정보를 무분별하게 로그에 기록하지 않습니다.

## 15. 최초 작업 지시

지금 당장 전체 시스템을 구현하지 마십시오.

먼저 다음 작업을 수행합니다.

1. 프로젝트 전체 아키텍처 제안
2. Desktop과 Runtime 분리 구조 확정
3. Clean Architecture 모듈 설계
4. ERD 초안
5. API Contract 초안
6. DB별 Driver 및 호환성 검토
7. Phase별 작업 계획 작성
8. 각 Phase별 품질 게이트 정의
9. 위험 요소 및 기술적 의사결정 문서 작성

이후 Phase 0 구현 계획을 제시합니다.

작업 시작 전 설계 내용을 사용자에게 보여주고 승인을 받습니다.

승인 전에는 실제 파일 생성이나 대규모 코드 수정을 진행하지 않습니다.

최종 목표는 단순히 SQL을 실행하는 도구가 아니라, 여러 데이터베이스를 연결하고 SQL을 API로 서비스하며 데이터를 다양한 파일 형식으로 제공할 수 있는 독립형 DW Data API Platform을 완성하는 것입니다.
<!-- SPEC-VERBATIM-END -->

## 추가 요구사항 (승인된 변경)

원문 뒤에 사용자가 승인한 변경을 적는다. 항목마다 승인 날짜와 승인 문구를 그대로 인용한다. 결정의 이유는 링크한 ADR 에 있고, 구현 상태는 각 ADR 의 `구현` 줄과 [CHANGELOG.md](../CHANGELOG.md)를 본다.

### AR-0 설계 결정 D1–D4 (승인 2026-11-15)

사용자는 2026-11-15 에 Queen 이 제안한 설계를 다음 문구로 승인했다.

> 「1. 권장하는 방향으로 진행해줘. 2. MariaDB로 단일로 진행해줘. 3. 관리 포트와 데이터 포트는 굳이 분리하지 말고, 일단 같게 유지해줘. 나중에 필요하면 그 때 분리하자. 4. 승인할게.」

문구의 번호는 결정에 이렇게 대응한다: 1 = D1, 2 = D2, 3 = D3, 4 = D4 (설계 전체 승인, 여기에 D4 가 들어 있다).

| 결정 | 승인된 내용 | 대체하는 원문 | 문서 |
|---|---|---|---|
| D1 | Spring Boot 4.1 + Java 21. **Java 21 부분은 [AR-2](#ar-2-java-17-에서-실행-승인-2026-11-23) / [ADR-025](ADR/ADR-025-java-17-baseline.md) 가 대체했다 (2026-11-23): Spring Boot 4.1 은 그대로이고 Java 17 로 컴파일·테스트·실행한다** | 원문 3절 Backend 의 「Java 17 이상」, 「Spring Boot 3.x」 | [ADR-010](ADR/ADR-010-spring-boot-4-java-21.md), [ADR-025](ADR/ADR-025-java-17-baseline.md) |
| D2 | Metadata DB 는 Desktop Mode 와 Server Mode 모두 MariaDB 하나다. 내장 DB(H2)를 쓰자는 제안은 반려됐다. **이 결정은 [ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md) 이 대체했다 (2026-11-23): 사용자가 「2. SQLite 내장」과 「3. 권장하는 반식으로 진행」으로 답해, Metadata 저장소는 두 모드 모두 내장 SQLite 이고 MariaDB 는 metadata 저장소 선택지로 남지 않는다([AR-2](#ar-2-java-17-에서-실행-승인-2026-11-23) 2·3항). 구현됨 (P1-06, 커밋 전). MariaDB 는 업무 DB 로서 계속 지원 계획이다** | 원문 3절 Metadata Database 의 「MariaDB」를 두 모드 모두에 적용한다는 확인이었다. ADR-026 부터는 원문 3절의 「MariaDB」와 다르다([ARCHITECTURE.md](ARCHITECTURE.md) 「설계와 다른 구현」 13) | [ADR-009](ADR/ADR-009-mariadb-metadata-db.md) (대체됨), [ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md) |
| D3 | HTTP 포트는 하나다. 관리 API 와 데이터 API 는 경로로 나눈다. 포트 분리는 「나중에 필요하면 그 때」 정한다 | 원문에는 포트에 관한 문장이 없다. 새로 정한 것이다 | [ADR-002](ADR/ADR-002-single-http-port.md) |
| D4 | Phase 0 에 Desktop 셸 스파이크(Tauri 에서 런타임 기동·중지·헬스 표시)를 넣는다. UI 본 구현은 Phase 6 그대로다 | 원문 11절 「Phase 0. 프로젝트 기반 구축」 목록(Desktop 없음)과 「Phase 6. Desktop Application」의 「Tauri 연동」 | [ARCHITECTURE.md 의 Phase 계획](ARCHITECTURE.md#phase-계획) |

### AR-1 런타임 운영 지표 (승인 2026-11-19, 계획)

**승인.** 2026-11-19 에 사용자가 Queen 의 제안을 승인했다. 승인 문구는 사용자 답의 둘째 항목 「2. 진행해주세요」다.

**동기.** 제안은 Phase 0 Desktop 셸 화면을 본 사용자의 다음 질문에 대한 답이었다.

> 저 앱화면의 역할이 뭐야? 백엔드 데몬 서버 역할을 하는 거야? 로그 창도 없고, 요청 Queue, 레이턴시나 쿼리 조회 속도, 여러 요청으로 인한 멀티 쓰레드 수량 등에 대한 정보는 하나도 없는데, 있는게 좋지 않을까?

**승인된 제안 (원문 그대로).**

> 계획에 없어서 추가하면 좋은 것: 런타임 로그 창(셸이 이미 런타임 출력 마지막 200줄을 비밀번호를 가린 채 보관하고 있어서 붙이기 쉽습니다), 처리 중 요청 수와 대기열(DB 연결 풀(Hikari)에서 연결을 기다리는 요청 수(DB별), Phase 7의 동시 실행 제한에 걸려 기다리는 요청 수), 스레드(Tomcat 요청 스레드 사용 중/최대, DB 풀 사용 중/유휴/대기), 레이턴시(API별 p50/p95/p99, 전체 응답 시간과 그중 DB 쿼리 시간을 나눠서), JVM 힙 사용량 정도. 수치는 Spring Boot가 이미 가진 Micrometer 지표에서 가져와 관리 API로 넘기면 됩니다(actuator를 외부로 직접 여는 방식은 R1에서 막은 경로라 쓰지 않습니다). 이 항목들을 "런타임 운영 지표"로 계획에 넣는 걸 권합니다(화면은 Phase 6 Dashboard, 지표 수집은 Phase 7 모니터링 범위). 다음 문서 기준선 작업(docs/ARCHITECTURE.md, Phase 계획) 때 이 요구를 정식으로 적어 두겠습니다.

[편집자 주: 인용문의 「R1」은 P0-C 코드 리뷰 1회차의 지적 R1 이다. 셸의 환경변수(`SERVER_ADDRESS`, `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` 같은 것)가 자식 런타임에 그대로 넘어가 바인딩 주소나 actuator 노출을 바꿀 수 있었다. 지금은 자식 환경 허용 목록으로 막혀 있다. 결정은 [ADR-013](ADR/ADR-013-desktop-child-env-allowlist.md)에 있다. 이 주는 인용문의 일부가 아니다.]

**요구사항.** 아래 OM-1..OM-7 은 모두 **계획**이다. 화면은 Phase 6 Dashboard, 지표 수집과 관리 API 엔드포인트는 Phase 7 모니터링 범위다.

| ID | 요구 | 상태 |
|---|---|---|
| OM-1 | **런타임 로그 창.** Desktop 셸이 이미 보관하는 런타임 출력을 화면에 보여 준다. 셸은 출력을 링 버퍼에 최대 200줄, 줄마다 최대 2048바이트까지 두고, Metadata DB 비밀번호는 `********` 로 가린다(`desktop/src-tauri/src/runtime/supervisor.rs` 의 `SupervisorConfig::desktop`: `output_lines: 200`, `output_line_bytes: 2048`, `status_tail_lines: 40`; 가림 문자열은 `desktop/src-tauri/src/runtime/output.rs` 의 `MASK`). 지금 화면에 나오는 것은 런타임이 실패했을 때 실패 카드의 마지막 40줄뿐이다. Server Mode 에서 로그 창을 어디까지 제공할지는 Phase 7 에서 정한다. | 계획 (화면 Phase 6, Server Mode 범위 Phase 7) |
| OM-2 | **처리 중 요청과 대기열.** 데이터 API 와 관리 API 각각의 처리 중(in-flight) 요청 수. DB 연결 풀(Hikari)에서 연결을 기다리는 스레드 수를 풀마다 보인다: 메타데이터 풀(`queryhub-metadata`, `runtime/app/src/main/resources/application.yml` 의 `pool-name`)과 Phase 1 부터 생기는 업무 DB 풀 각각. Phase 7 의 동시 실행 제한에 걸려 기다리는 요청 수. | 계획 (Phase 7) |
| OM-3 | **스레드.** Tomcat 요청 스레드의 사용 중 수와 최대 수. Hikari 풀마다 사용 중(active), 유휴(idle), 대기(pending), 최대(max). | 계획 (Phase 7) |
| OM-4 | **레이턴시.** API 마다 p50/p95/p99. 집계 단위는 원시 URI 가 아니라 API 정의다. 단일 디스패처가 `/api/v1/**` 의 모든 경로를 처리하므로([ADR-003](ADR/ADR-003-single-dispatcher-route-registry.md)) URI 로는 API 를 구분하지 못하고 태그 수만 늘어난다. 전체 응답 시간과 그중 DB 쿼리 시간을 따로 보인다. | 계획 (Phase 7) |
| OM-5 | **JVM 힙.** 사용량(used), 확보량(committed), 최대(max). | 계획 (Phase 7) |
| OM-6 | **출처와 전달.** 수치는 Spring Boot 가 이미 등록하는 Micrometer 미터에서 가져오고, 필요한 곳에는 QueryHub 타이머를 더한다. 런타임 안에서 읽어 관리 API 엔드포인트 하나가 공통 응답 형식으로 돌려준다. 경로는 Phase 7 에서 정하며 runtime 또는 dashboard 관리 리소스 아래에 둔다. 이 엔드포인트는 관리자 토큰(Phase 1)을 요구한다. actuator 의 `metrics`, `prometheus` 엔드포인트는 열지 않는다. 노출은 지금처럼 `health` 하나로 둔다(`runtime/app/src/main/resources/application.yml` 의 `management.endpoints.web.exposure.include`). 미터 이름과 Tomcat 스레드 지표를 얻는 조건(예: `server.tomcat.mbeanregistry.enabled` 가 필요한지)은 미확인이며 Phase 7 에서 확인한다. | 계획 (Phase 7) |
| OM-7 | **보안.** 지표 이름, 태그, 로그 창에 SQL 원문, 파라미터 값, 비밀 값, API Key 를 넣지 않는다. 태그 값의 종류 수(카디널리티)는 API ID 와 풀 이름처럼 개수가 정해진 것으로 제한한다. 로그 창은 계속 가린 채 보이고, Phase 7 의 마스킹 정책(원문 9절 10항 「요청 및 응답 로그의 민감정보 마스킹」)을 따른다. | 계획 (Phase 6, Phase 7) |

**Phase 배치와 순서.** 화면은 Phase 6 Dashboard 에 넣는다(OM-1..OM-5 를 보여 주는 운영 지표 화면). 지표 수집과 관리 API 엔드포인트는 Phase 7 모니터링에 넣는다(OM-1..OM-7). Phase 6 이 Phase 7 보다 먼저이므로, Phase 7 전에는 데이터가 없는 패널을 「아직 수집하지 않음」으로 보여야 한다. 이 순서는 Phase 6 을 계획할 때 다시 검토한다. 배치는 사용자가 승인한 그대로 둔다. Phase 표는 [ARCHITECTURE.md 의 Phase 계획](ARCHITECTURE.md#phase-계획)에 있다.

### AR-2 Java 17 에서 실행 (승인 2026-11-23)

**요청.** 2026-11-23 에 사용자가 다음과 같이 적었다.

> 「Java OpenJDK 17 환경에서 사용할 수 있어야 해. Metadata DB는 꼭 필요한가요? SQLLite 등의 내부DB나 설정파일 기반으로 동작할 수 있어야 할 것 같아요.」

**승인.** Queen 은 P1-04 커밋, P1-05 Java 17, P1-06 내장 metadata 저장소, 그다음 DB Provider 순서를 제안했다. 사용자는 2026-11-23 에 다음과 같이 답했다(줄바꿈과 글자를 원문 그대로 둔다).

> 「1. 진행
> 2. SQLite 내장
> 3. 권장하는 반식으로 진행
> 4. 병합 및 승인 (키ID 방식은 나중에 도입)」

**항목별 대응.**

| 항목 | 뜻 | 상태 | 문서 |
|---|---|---|---|
| 1. 진행 | P1-05: Spring Boot 4.1 은 그대로 두고 Java 17 로 컴파일·테스트·실행한다. AR-0 D1 의 Java 21 부분을 대체한다. Gradle toolchain 은 정확히 17 이고 로컬에 설치된 JDK 17 만 쓴다(JDK 를 내려받지 않는다). desktop 셸은 Java 17 이상을 받고 16 이하는 `java_too_old` 로 거부한다. CI 는 Java 17 을 준비한다. Java 18 이상의 API 와 언어 기능은 쓰지 않는다 | 구현됨 (P1-05, 6ceef51 (2026-11-23)). 테스트는 Java 17 에서 돌았다. Java 21 이상에서는 이 변경 뒤에 다시 돌리지 않았다. CI 는 정의됨·미실행(CI-1), Windows·Linux 는 미검증(PLAT-1) | [ADR-025](ADR/ADR-025-java-17-baseline.md) |
| 2. SQLite 내장 | P1-06: Metadata 저장소를 desktop·server 모두 내장 SQLite(`org.xerial:sqlite-jdbc` 3.53.4.0, BOM 관리)로 바꾼다. 데이터 디렉터리 `QUERYHUB_DATA_DIR`(환경변수, 외부 설정 파일 또는 명령행)의 `queryhub.db`. AR-0 D2(Metadata DB 는 MariaDB 하나)를 대체한다. desktop 셸은 `<앱 로컬 데이터 디렉터리>/data` 를 넘기므로 앱이 환경변수 없이 시작한다 | 구현됨 (P1-06, 커밋 전). macOS arm64 만 실행했다. 다른 네이티브와 Windows·Linux 는 미검증(PLAT-1), CI 는 정의됨·미실행(CI-1) | [ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md) (ADR-009 를 대체) |
| 3. 권장하는 반식으로 진행 | P1-06 을 Queen 이 권한 방식으로 진행한다: MariaDB 를 metadata 저장소 선택지로 남기지 않는다. `QUERYHUB_METADATA_DB_*`, MariaDB 드라이버와 `flyway-mysql`, Testcontainers MariaDB 시험, `deploy/compose.dev.yml` 을 지웠다. 여러 서버가 저장소 하나를 함께 써야 할 때 다시 더한다([BACKLOG.md](BACKLOG.md) P106-1) | 구현됨 (P1-06, 커밋 전) | [ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md) |
| 4. 병합 및 승인 (키ID 방식은 나중에 도입) | P1-04 커밋(037385b, 2026-11-23)을 승인했다. 「키ID 방식은 나중에 도입」은 P1-04 리뷰 지적 R7(kid 를 AES 키 자체로 만든 HMAC-SHA256 으로 얻는다)에 대한 답이다: 지금의 kid 유도를 그대로 두고 나중에 다시 본다 | P1-04 구현됨 (037385b). kid 유도 변경은 정하지 않음 | [ADR-024](ADR/ADR-024-secret-cipher-format-and-rotation.md), [BACKLOG.md](BACKLOG.md) P104-R7 |

[편집자 주: 3항의 「반식」은 사용자가 적은 글자 그대로다(「방식」의 오타로 읽는다). 「SQLLite」도 원문 그대로다. 이 주는 인용문의 일부가 아니다.]

**P1-05 커밋 승인.** 사용자는 2026-11-23 에 다음과 같이 답해 P1-05 의 커밋과 병합(6ceef51)을 승인하고 P1-06 을 시작하게 했다(줄바꿈과 글자를 원문 그대로 둔다).

> 「1. 커밋과 병합 승인
> 2. 다음 작업 진행」
