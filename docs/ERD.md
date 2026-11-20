# QueryHub 메타데이터 ERD

Metadata DB(MariaDB)의 테이블을 적는다. 구현된 테이블은 `app_setting` 하나다. 나머지는 명세 5절과 설계 §4 의 초안이며 **계획**이다. 메타데이터 접근 방식은 [ADR-007](ADR/ADR-007-spring-jdbc-metadata.md), DB 선택은 [ADR-009](ADR/ADR-009-mariadb-metadata-db.md), 서버 버전과 연결 옵션은 [DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)에 있다.

## 구현된 테이블

### app_setting (구현됨, P0-B)

원본: `runtime/adapter-metadata/src/main/resources/db/migration/V1__create_app_setting.sql`. 저장소: `runtime/adapter-metadata/src/main/java/com/queryhub/adapter/metadata/setting/JdbcAppSettingRepository.java` (포트 `AppSettingRepository`).

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| `setting_key` | `VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_nopad_bin` | NOT NULL | 기본 키 `pk_app_setting`. 대소문자와 뒤쪽 공백을 구분한다(`'k'`, `'k '`, `'K'` 는 서로 다른 키) |
| `setting_value` | `TEXT` | NOT NULL | 테이블 기본 정렬 규칙을 따른다 |
| `created_at` | `DATETIME(6)` | NOT NULL | UTC 벽시계 값, 시간대 정보 없음 |
| `updated_at` | `DATETIME(6)` | NOT NULL | UTC 벽시계 값, 시간대 정보 없음 |

- 제약: `CONSTRAINT pk_app_setting PRIMARY KEY (setting_key)`.
- 테이블: `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci`. 문자 집합과 정렬 규칙을 서버·데이터베이스 기본값에 기대지 않고 마이그레이션에 직접 적는다. 그래서 MariaDB 10.11 이상이 필요하다.
- 시각: 런타임이 `Clock` 의 시각을 UTC `LocalDateTime` 으로 바꿔 바인딩하고, 접속 세션 시간대도 UTC 로 고정한다(연결 옵션은 [DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)). 서버 기본 시간대와 관계없이 같은 값이 저장된다.
- 시드 행은 넣지 않는다. 설정 키의 의미는 그 기능을 구현하는 Phase 가 정한다(설계 §4: API 서비스 스위치, 기본 제한값, 이력 보존 기간 — 계획).
- 남은 문제: `setting_value` 는 TEXT(최대 65,535 바이트)인데 저장소 계약에 길이 한도가 없다. strict `sql_mode` 가 아닌 서버에서는 경고만 남기고 잘라 저장한다([BACKLOG.md](BACKLOG.md) P0B-R3, Phase 1).

## ERD 초안 (계획)

설계 §4. 테이블은 그 기능을 구현하는 Phase 가 자기 Flyway 마이그레이션으로 더한다.

```
database_connection 1─N sql_definition 1─N sql_parameter
                            ├─ 1─N sql_definition_history
                            ├─ 1─N sql_execution_history
                            └─ 1─N api_definition 1─N api_parameter_mapping
                                        ├─ N─M api_key (api_key_permission)
                                        └─ 1─N api_execution_history
audit_log, app_setting (독립)
```

### 명세 5절의 엔티티

필드는 명세 원문([REQUIREMENTS.md](REQUIREMENTS.md) 5절) 그대로이고, 설계 §4 가 바꾸는 점을 「설계 변경」에 적는다.

| 엔티티 (테이블) | 명세 필드 | 설계 변경 | 목표 |
|---|---|---|---|
| DatabaseConnection (`database_connection`) | id, connectionName, databaseType, host, port, databaseName, username, encryptedPassword, jdbcUrl, connectionProperties, connectionTimeout, enabled, createdAt, updatedAt | `maxPoolSize`, `queryTimeoutSeconds` 추가. `connectionName` 유니크. 비밀번호는 AES-256-GCM([ADR-006](ADR/ADR-006-password-aes-gcm-api-key-hmac.md)) | 계획 (Phase 1) |
| SqlDefinition (`sql_definition`) | id, sqlCode, sqlName, description, databaseConnectionId, sqlContent, queryType, version, enabled, createdAt, updatedAt | `sqlCode` 유니크 | 계획 (Phase 2) |
| SqlParameter (`sql_parameter`) | id, sqlDefinitionId, parameterName, dataType, required, defaultValue, description, validationRule, displayOrder. 타입: STRING, INTEGER, LONG, DECIMAL, BOOLEAN, DATE, TIME, DATETIME, TIMESTAMP | `multiple` 추가 (컬렉션 여부, IN 절) | 계획 (Phase 2) |
| ApiDefinition (`api_definition`) | id, apiCode, apiName, description, endpointPath, httpMethod, sqlDefinitionId, responseType, authenticationType, cacheEnabled, cacheTtlSeconds, maxRows, timeoutSeconds, enabled, version, createdAt, updatedAt | 유니크 `(httpMethod, endpointPath)`, `apiCode` | 계획 (Phase 3) |
| ApiExecutionHistory (`api_execution_history`) | id, apiDefinitionId, requestId, requestedAt, requestedBy, executionTimeMs, resultRowCount, responseFormat, status, errorCode, errorMessage | `apiKeyId`, `httpStatus`, `clientIp`, `sqlVersion` 추가 | 계획 (Phase 4, 7) |
| ApiKey (`api_key`) | id, keyName, keyHash, permissions, expiresAt, enabled, createdAt | `keyPrefix` 추가 (원문 없이 키 식별). `permissions` 는 `api_key_permission` 으로 정규화. 해시는 HMAC-SHA-256 | 계획 (Phase 4) |

### 설계가 더하는 테이블

| 테이블 | 이유 | 목표 |
|---|---|---|
| `sql_definition_history` | SQL 버전·변경 이력 (내용과 파라미터의 불변 스냅샷) | 계획 (Phase 2) |
| `sql_execution_history` | SQL Repository 의 테스트 실행 이력 (Query History) | 계획 (Phase 2) |
| `api_parameter_mapping` | 요청 필드명과 SQL 변수의 매핑, 출처(query/body) | 계획 (Phase 3) |
| `api_key_permission` | API 별 접근 권한 (명세의 `permissions` 를 정규화) | 계획 (Phase 4) |
| `audit_log` | 설정 변경 감사 (누가·무엇을·언제) | 계획 (Phase 7) |
| `app_setting` | 위 「구현된 테이블」 | 구현됨 (P0-B) |

## 마이그레이션 규칙

- 도구는 Flyway(`flyway-mysql`)다. 위치는 `adapter-metadata` 의 `classpath:db/migration` 이고, 위치를 찾지 못하면 조용히 건너뛰지 않고 실패한다(`runtime/app/src/main/resources/application.yml` 의 `spring.flyway.fail-on-missing-locations: true`). 구현됨 (P0-B).
- 기능 Phase 마다 그 기능의 테이블을 새 마이그레이션(`V<n>__<설명>.sql`)으로 더한다. 적용된 마이그레이션은 고치지 않는다. 마이그레이션 이름과 버전은 테스트가 지킨다(P0-B).
- `baseline-on-migrate` 를 켜지 않는다. Flyway 는 기록 테이블(`flyway_schema_history`) 없이 테이블이 이미 있는 스키마에는 마이그레이션을 거부하므로 빈 데이터베이스를 쓴다.
- 기동할 때 마이그레이션을 적용하므로 DB 사용자에게 DDL 권한(CREATE, ALTER, INDEX 등)이 필요하다. 마이그레이션 연결은 검사한 메인 풀로 고정돼 있다([ADR-012](ADR/ADR-012-flyway-connection-pin.md)).
- 문자 집합, 정렬 규칙, 시각 타입은 V1 처럼 마이그레이션에 직접 적는다.
