package com.queryhub.app.config.metadata;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;

/**
 * Flyway 가 따로 접속하지 않게 하는 접속 정보. URL, 사용자, 비밀번호, 드라이버를 모두 비워 둔다.
 *
 * <p>Boot 4.1.2 의 FlywayAutoConfiguration 은 이 타입의 빈이 없을 때만 spring.flyway.url·user·password 로
 * PropertiesFlywayConnectionDetails 를
 * 만든다({@code @ConditionalOnMissingBean(FlywayConnectionDetails.class)}). 마이그레이션 DataSource 를 고를
 * 때(FlywayConfiguration.getMigrationDataSource) URL 이 있으면 그 URL 로, 사용자만 있으면 주 DataSource 에서 파생해
 * SimpleDriverDataSource 를 새로 만들고, 둘 다 없으면 주 DataSource 를 그대로 쓴다. 이 빈은 둘 다 비워 두므로 Flyway 는 항상 검사한
 * 접속 정보({@link MetadataJdbcConnectionDetails})로 만든 Hikari 풀로 마이그레이션한다. 그래서
 * spring.flyway.url·user·password 는 어느 속성 소스에서 오든 접속에 쓰이지 않는다.
 */
final class MetadataFlywayConnectionDetails implements FlywayConnectionDetails {

  @Override
  public @Nullable String getJdbcUrl() {
    return null;
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
  public @Nullable String getDriverClassName() {
    return null;
  }

  @Override
  public String toString() {
    return "MetadataFlywayConnectionDetails[main DataSource]";
  }
}
