# QueryHub 메타데이터 ERD

Metadata 저장소의 테이블과 파일 배치를 적는다. P1-06 부터 저장소는 두 모드 모두 내장 SQLite 다([ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md). P1-05 까지는 MariaDB, [ADR-009](ADR/ADR-009-mariadb-metadata-db.md) 대체됨). 구현된 테이블은 `app_setting` 하나다. 나머지는 명세 5절과 설계 §4 의 초안이며 **계획**이다. 메타데이터 접근 방식은 [ADR-007](ADR/ADR-007-spring-jdbc-metadata.md), 드라이버와 연결 설정은 [DATABASE_SUPPORT.md](DATABASE_SUPPORT.md), 데이터 디렉터리와 백업은 [DEPLOYMENT.md](DEPLOYMENT.md)에 있다.

## 저장소 파일 배치 (구현됨, P1-06)

데이터 디렉터리는 `QUERYHUB_DATA_DIR`(설정 `queryhub.data-dir`)이다. desktop 은 셸이 `<앱 로컬 데이터 디렉터리>/data` 를 넘긴다.

```
<data-dir>/                 0700, 런타임을 실행한 사용자 소유
├─ queryhub.db              SQLite DB 파일. 0600. 없으면 런타임이 CREATE_NEW 로 먼저 만든다
├─ queryhub.db-wal          WAL 파일 (SQLite 가 만든다. queryhub.db 의 권한을 따라 0600)
├─ queryhub.db-shm          WAL 의 공유 메모리 파일 (같다)
├─ queryhub.lock            런타임 하나만 쓰게 하는 배타 잠금 파일. 0600. 지우지 않는다
└─ native/                  sqlite-jdbc 가 네이티브 라이브러리를 푸는 곳. 0700 (QUERYHUB_SQLITE_NATIVE_DIR 로 옮길 수 있다)
```

- 파일 규칙(심볼릭 링크 거부, 소유자, 그룹·기타 비트 없음, 느슨한 권한은 고치지 않고 `chmod` 안내)은 [DEPLOYMENT.md](DEPLOYMENT.md) 「데이터 디렉터리」에 있다. Windows 는 권한을 확인하지 않는다(미검증, PLAT-1).
- 연결 설정: `journal_mode=WAL`, `synchronous=FULL`, `foreign_keys=ON`, `busy_timeout=5000`, `transaction_mode=IMMEDIATE`. 기동할 때 모든 풀 연결에서 확인한다([DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)).

## 구현된 테이블

### app_setting (구현됨, P0-B. SQLite DDL 은 P1-06)

원본: `runtime/adapter-metadata/src/main/resources/db/migration/V1__create_app_setting.sql`. 저장소: `runtime/adapter-metadata/src/main/java/com/queryhub/adapter/metadata/setting/JdbcAppSettingRepository.java` (포트 `AppSettingRepository`).

```sql
CREATE TABLE app_setting (
    setting_key   TEXT NOT NULL COLLATE BINARY
                  CHECK (length(setting_key) BETWEEN 1 AND 100),
    setting_value TEXT NOT NULL,
    created_at    TEXT NOT NULL
                  CHECK (created_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9]Z'),
    updated_at    TEXT NOT NULL
                  CHECK (updated_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9][0-9][0-9][0-9]Z'),
    CONSTRAINT pk_app_setting PRIMARY KEY (setting_key)
) STRICT;
```

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| `setting_key` | `TEXT COLLATE BINARY`, 1-100 자(코드 포인트, SQLite `length()`) | NOT NULL | 기본 키 `pk_app_setting`. 바이트 비교라 대소문자와 뒤쪽 공백을 구분한다(`'k'`, `'k '`, `'K'` 는 서로 다른 키). SQLite 에는 PAD SPACE 정렬이 없다. P0-B 의 `utf8mb4_nopad_bin` 과 같은 뜻이다 |
| `setting_value` | `TEXT` | NOT NULL | 빈 문자열을 저장할 수 있다 |
| `created_at` | `TEXT`, UTC 고정 폭 `uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'` | NOT NULL | 예: `2026-01-02T03:04:05.123456Z`. GLOB 검사가 형식을 지킨다 |
| `updated_at` | `TEXT`, 같은 형식 | NOT NULL | 같다 |

- STRICT 테이블이다. 열 형식을 강제하므로 TEXT 열에 정수나 BLOB 이 들어가지 않는다(SQLite 3.37 이상, 번들된 SQLite 는 3.53.x).
- 시각: 저장소 구현이 `Clock.instant()` 를 UTC 의 위 형식 문자열로 바꿔 `String` 으로 바인딩한다. 드라이버의 날짜 변환(sqlite-jdbc 의 기본은 정수로 저장한다)과 JVM 시간대에 기대지 않는다. 시험은 JVM 과 `Clock` 을 Asia/Seoul 에 두고 저장된 값이 정확히 UTC 문자열인지 본다. 고정 폭이므로 문자열 순서가 시각 순서와 같다.
- 쓰기: `INSERT … ON CONFLICT (setting_key) DO UPDATE SET setting_value = excluded.setting_value, updated_at = excluded.updated_at`. 두 번째 쓰기는 `created_at` 을 그대로 두고 값과 `updated_at` 만 바꾼다. 키 길이(1-100 코드 포인트)는 SQL 전에 저장소 구현이 검사한다(101 자는 `IllegalArgumentException`).
- 시드 행은 넣지 않는다. 설정 키의 의미는 그 기능을 구현하는 Phase 가 정한다(설계 §4: API 서비스 스위치, 기본 제한값, 이력 보존 기간 — 계획).
- 남은 문제: `setting_value` 에 저장소 계약의 길이 한도가 없다. SQLite 는 TEXT 를 자르지 않는다(P0-B 의 MariaDB TEXT 65,535 바이트 잘림은 없어졌다). 한도는 처음 값을 쓰는 기능이 정한다([BACKLOG.md](BACKLOG.md) P0B-R3).

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
| DatabaseConnection (`database_connection`) | id, connectionName, databaseType, host, port, databaseName, username, encryptedPassword, jdbcUrl, connectionProperties, connectionTimeout, enabled, createdAt, updatedAt | `maxPoolSize`, `queryTimeoutSeconds` 추가. `connectionName` 유니크. 비밀번호는 AES-256-GCM([ADR-006](ADR/ADR-006-password-aes-gcm-api-key-hmac.md)) | 계획 (P1-08: V2 마이그레이션, 암호화한 비밀번호). P1-07 은 저장 없이 설정 형식(`ConnectionSettings`)과 연결 시험·풀만 만들었다. 사용자가 주는 원시 `jdbcUrl` 은 받지 않는다(P107-1, [ADR-027](ADR/ADR-027-database-provider.md)) |
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

- 도구는 Flyway 다. `flyway-core` 12.4.0 이 SQLite 지원을 담고 있어 따로 모듈을 더하지 않는다(P1-06, P0-B 의 `flyway-mysql` 은 뺐다). 위치는 `adapter-metadata` 의 `classpath:db/migration` 이고, 위치를 찾지 못하면 조용히 건너뛰지 않고 실패한다(`runtime/app/src/main/resources/application.yml` 의 `spring.flyway.fail-on-missing-locations: true`). 구현됨 (P0-B).
- 기능 Phase 마다 그 기능의 테이블을 새 마이그레이션(`V<n>__<설명>.sql`)으로 더한다. 적용된 마이그레이션은 고치지 않는다. 마이그레이션 이름과 버전은 테스트가 지킨다(P0-B). P1-06 은 V1 을 SQLite DDL 로 그 자리에서 다시 썼다. 그때 SQLite 저장소는 어디에도 없었으므로 체크섬이 깨질 곳이 없었다. 기록 테이블의 V1 체크섬이 다른 저장소는 포트를 열기 전에 거부된다(Flyway validate).
- 모든 `CREATE TABLE` 은 `STRICT` 다. MariaDB 전용 문법(`ENGINE`, `CHARSET`·`CHARACTER SET`, `COLLATE utf8mb4…`, `AUTO_INCREMENT`, `ON DUPLICATE KEY`)은 쓰지 않는다. `MigrationScriptConventionTest` 가 지킨다(P1-06).
- `baseline-on-migrate` 를 켜지 않는다. 새 저장소는 런타임이 만든 빈 파일에서 시작한다.
- 마이그레이션 연결은 검사한 메인 풀로 고정돼 있다([ADR-012](ADR/ADR-012-flyway-connection-pin.md)). SQLite 에는 계정과 DDL 권한이 없다. 데이터 디렉터리에 쓸 수 있으면 된다.
- 키의 정렬 규칙(`COLLATE BINARY`)과 시각 형식(UTC 고정 폭 TEXT)은 V1 처럼 마이그레이션에 직접 적는다.
- 오래된 런타임이 더 새 저장소를 여는 경우(스키마 내리기 감지)는 다루지 않는다(P1-06 범위 밖).
