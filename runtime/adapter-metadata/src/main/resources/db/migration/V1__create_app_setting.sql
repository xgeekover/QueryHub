-- V1: 런타임 설정(키-값) 테이블 app_setting.
--
-- 문자 집합과 정렬 규칙은 서버·데이터베이스 기본값에 기대지 않고 여기에 직접 적는다. Desktop 사용자는 기본값이 제각각인 MariaDB
-- 서버(예: latin1)를 가져온다. 그래서 MariaDB 10.11 이상이 필요하다(utf8mb4_uca1400_ai_ci).
--   - 테이블 기본값: utf8mb4 / utf8mb4_uca1400_ai_ci (setting_value 가 따른다).
--   - setting_key: utf8mb4_nopad_bin. 대소문자와 뒤쪽 공백을 구분한다('k', 'k ', 'K' 는 서로 다른 키다).
-- created_at, updated_at 은 UTC 벽시계 값이다(DATETIME(6), 시간대 정보 없음). 런타임이 Clock 의 시각을 UTC LocalDateTime 으로
-- 바꿔 바인딩하고, 접속 세션 시간대도 UTC(+00:00)로 고정한다. 서버 기본 시간대와 관계없이 같은 값이 저장된다.
-- 시드 행은 넣지 않는다. 설정 키의 의미는 그 기능을 구현하는 Phase 가 정한다.
CREATE TABLE app_setting (
    setting_key   VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_nopad_bin NOT NULL,
    setting_value TEXT NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    CONSTRAINT pk_app_setting PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;
