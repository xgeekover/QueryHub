package com.queryhub.adapter.metadata.setting;

import com.queryhub.application.setting.AppSettingRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link AppSettingRepository} 의 JDBC 구현 (app_setting 테이블, MariaDB).
 *
 * <p>시각은 {@link Clock} 의 {@code Instant} 를 UTC 벽시계 값({@link LocalDateTime})으로 바꿔 바인딩한다. {@code
 * Instant} 를 그대로 바인딩하지 않는다: Connector/J 3.5 는 {@code Instant} 를 JVM 기본 시간대의 벽시계로 바꾸므로, JVM 시간대에 따라
 * 저장 값이 달라진다. DATETIME(6) 에 맞춰 마이크로초 단위로 자른다.
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
          + " ON DUPLICATE KEY UPDATE setting_value = :value, updated_at = :now";

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
    LocalDateTime now = utcNow();
    jdbcClient.sql(UPSERT).param("key", key).param("value", value).param("now", now).update();
  }

  /** Clock 의 현재 시각을 UTC 벽시계 값으로 바꾼다(마이크로초 단위). */
  LocalDateTime utcNow() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
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
