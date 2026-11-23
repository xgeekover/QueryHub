package com.queryhub.app.config.metadata;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/**
 * 검사를 통과한 Metadata 저장소(SQLite)의 접속 정보. Boot 의 DataSource 자동 구성은 이 빈으로 Hikari 풀을 만든다(그래서
 * spring.datasource.hikari.* 설정, Flyway, JdbcClient, db health 가 그대로 동작한다).
 *
 * <p>URL 은 런타임이 만든다({@code jdbc:sqlite:<데이터 디렉터리>/queryhub.db?journal_mode=WAL&...}). 파일 DB 라서 사용자와
 * 비밀번호가 없다. 드라이버는 이름으로만 적는다(ArchUnit R6).
 */
public final class MetadataJdbcConnectionDetails implements JdbcConnectionDetails {

  static final String DRIVER_CLASS_NAME = "org.sqlite.JDBC";

  private final String jdbcUrl;

  MetadataJdbcConnectionDetails(String jdbcUrl) {
    this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
  }

  @Override
  public String getJdbcUrl() {
    return jdbcUrl;
  }

  @Override
  public @Nullable String getUsername() {
    return null;
  }

  @Override
  public @Nullable String getPassword() {
    return null;
  }

  @Override
  public String getDriverClassName() {
    return DRIVER_CLASS_NAME;
  }

  @Override
  public String toString() {
    return "MetadataJdbcConnectionDetails[jdbcUrl=" + jdbcUrl + "]";
  }
}
