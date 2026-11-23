-- V1: 런타임 설정(키-값) 테이블 app_setting. Metadata 저장소는 내장 SQLite 다 (P1-06, ADR-026).
--
-- STRICT 테이블: 열 형식을 강제한다(TEXT 열에 정수나 BLOB 이 들어가지 않는다). SQLite 3.37 이상이 필요하다(sqlite-jdbc 3.53.x).
--   - setting_key: COLLATE BINARY. 대소문자와 뒤쪽 공백을 구분한다('k', 'k ', 'K' 는 서로 다른 키다, P0-B 의미 그대로).
--     길이는 1-100 자(코드 포인트, SQLite length())다. 저장소 구현이 SQL 전에 같은 계약을 검사한다.
--   - created_at, updated_at: UTC 시각의 고정 폭 문자열 uuuu-MM-ddTHH:mm:ss.SSSSSSZ (예: 2026-01-02T03:04:05.123456Z).
--     런타임이 Clock 의 시각을 UTC 로 바꿔 문자열로 바인딩한다. 드라이버의 날짜 변환과 JVM 시간대에 기대지 않는다. GLOB 검사가 형식을 지킨다.
-- 시드 행은 넣지 않는다. 설정 키의 의미는 그 기능을 구현하는 Phase 가 정한다.
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
