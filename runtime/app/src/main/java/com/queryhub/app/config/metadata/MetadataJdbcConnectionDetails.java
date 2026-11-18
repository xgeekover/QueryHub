package com.queryhub.app.config.metadata;

import java.util.Objects;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/**
 * 검사를 통과한 Metadata DB 접속 정보. Boot 의 DataSource 자동 구성은 이 빈으로 Hikari 풀을 만든다(그래서
 * spring.datasource.hikari.* 설정, Flyway, JdbcClient, db health 가 그대로 동작한다).
 *
 * <p>{@link MetadataDatabaseProperties#toConnectionDetails()} 만 만든다. 드라이버 클래스 이름은 URL({@code
 * jdbc:mariadb:})로 정해진다(기본 구현). {@link #toString()} 은 비밀번호를 가린다.
 */
public final class MetadataJdbcConnectionDetails implements JdbcConnectionDetails {

  private final String jdbcUrl;
  private final String username;
  private final String password;

  MetadataJdbcConnectionDetails(String jdbcUrl, String username, String password) {
    this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    this.username = Objects.requireNonNull(username, "username");
    this.password = Objects.requireNonNull(password, "password");
  }

  @Override
  public String getJdbcUrl() {
    return jdbcUrl;
  }

  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public String getPassword() {
    return password;
  }

  @Override
  public String toString() {
    return "MetadataJdbcConnectionDetails[jdbcUrl="
        + jdbcUrl
        + ", username="
        + username
        + ", password=******]";
  }
}
