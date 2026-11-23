package com.queryhub.adapter.metadata.setting;

import com.queryhub.application.setting.AppSettingRepository;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link AppSettingRepository} 의 JDBC 구현 (app_setting 테이블, 내장 SQLite, P1-06).
 *
 * <p>시각은 {@link Clock} 의 {@code Instant} 를 UTC 의 고정 폭 문자열({@value #TIMESTAMP_PATTERN}, 마이크로초까지)로 바꿔
 * 문자열로 바인딩한다. 날짜 객체를 바인딩하지 않는다: sqlite-jdbc 의 기본 날짜 변환은 정수(밀리초)로 저장하고, 벽시계 값은 JVM 시간대에 기댄다. 문자열은
 * 사전순이 시간순이고 V1 의 GLOB 검사가 형식을 지킨다.
 *
 * <p>upsert 는 SQLite 의 {@code INSERT ... ON CONFLICT (setting_key) DO UPDATE} 다. 키가 이미 있으면 값과
 * updated_at 만 바꾸고 created_at 은 그대로 둔다.
 *
 * <p>Spring 스테레오타입이 없다. 빈은 app 의 설정 클래스가 만든다. 드라이버 클래스를 참조하지 않는다(ArchUnit R6).
 */
public final class JdbcAppSettingRepository implements AppSettingRepository {

  private static final String FIND_VALUE =
      "SELECT setting_value FROM app_setting WHERE setting_key = :key";

  // 키가 이미 있으면 값과 updated_at 만 바꾼다. created_at 은 처음 저장한 시각 그대로다.
  private static final String UPSERT =
      "INSERT INTO app_setting (setting_key, setting_value, created_at, updated_at)"
          + " VALUES (:key, :value, :now, :now)"
          + " ON CONFLICT (setting_key) DO UPDATE SET setting_value = excluded.setting_value,"
          + " updated_at = excluded.updated_at";

  /** 저장 시각 형식 (UTC, 고정 폭, 마이크로초). */
  static final String TIMESTAMP_PATTERN = "uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'";

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN, Locale.ROOT).withZone(ZoneOffset.UTC);

  private final JdbcClient jdbcClient;
  private final Clock clock;

  public JdbcAppSettingRepository(JdbcClient jdbcClient, Clock clock) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Optional<String> findValue(String key) {
    requireValidKey(key);
    return jdbcClient.sql(FIND_VALUE).param("key", key).query(String.class).optional();
  }

  @Override
  public void putValue(String key, String value) {
    requireValidKey(key);
    if (value == null) {
      throw new IllegalArgumentException("설정 값은 null 일 수 없다");
    }
    String now = utcNow();
    jdbcClient.sql(UPSERT).param("key", key).param("value", value).param("now", now).update();
  }

  /** Clock 의 현재 시각을 UTC 고정 폭 문자열로 바꾼다(마이크로초 아래는 버린다). Clock 의 시간대는 쓰지 않는다. */
  String utcNow() {
    return TIMESTAMP.format(clock.instant());
  }

  /** 키 계약을 확인한다. SQL 을 실행하기 전에 호출한다. 메시지에 키 값은 넣지 않는다. */
  static void requireValidKey(String key) {
    if (key == null) {
      throw new IllegalArgumentException("설정 키는 null 일 수 없다");
    }
    if (key.isBlank()) {
      throw new IllegalArgumentException("설정 키는 비어 있거나 공백만일 수 없다");
    }
    int length = key.codePointCount(0, key.length());
    if (length > MAX_KEY_LENGTH) {
      throw new IllegalArgumentException("설정 키는 최대 " + MAX_KEY_LENGTH + "자다 (현재 " + length + "자)");
    }
  }
}
