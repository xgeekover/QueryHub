package com.queryhub.app.config.metadata;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/**
 * Metadata DB 기동 검사. {@link MetadataDatabaseAutoConfiguration} 이 {@code @Lazy(false)} 빈으로 만든다.
 *
 * <p>생성자 인자인 접속 정보 빈이 만들어질 때 설정 검사가 끝난다. 초기화 단계에서는 Flyway 마이그레이션 초기화 빈을 꺼내 마이그레이션을 실행한다(빈을 만들면 Boot
 * 의 FlywayMigrationInitializer 가 migrate 를 호출한다). Flyway 는 {@link MetadataFlywayConnectionDetails}
 * 때문에 항상 주 DataSource(검사한 접속 정보로 만든 Hikari 풀)로 마이그레이션하므로(spring.flyway.url·user·password 는 쓰이지
 * 않는다), 그 과정에서 Hikari 풀이 첫 연결을 연다. DB 에 닿지 않거나 자격 증명이 거부되면 여기서 예외가 나고 기동이 실패한다. 그래서 Flyway 가 있으면 따로
 * 연결을 열지 않는다. 이 빈은 싱글톤 생성 단계에서 만들어지므로 웹 서버가 포트를 여는 단계(컨텍스트 refresh 의 마지막)보다 먼저다.
 *
 * <p>Flyway 를 꺼 두었으면(spring.flyway.enabled=false) 연결 하나를 열어 확인한다.
 */
final class MetadataDatabaseStartupCheck implements InitializingBean {

  private static final int VALIDATION_TIMEOUT_SECONDS = 10;

  private final ObjectProvider<FlywayMigrationInitializer> flywayInitializer;
  private final ObjectProvider<DataSource> dataSource;

  MetadataDatabaseStartupCheck(
      JdbcConnectionDetails connectionDetails,
      ObjectProvider<FlywayMigrationInitializer> flywayInitializer,
      ObjectProvider<DataSource> dataSource) {
    Objects.requireNonNull(connectionDetails, "connectionDetails");
    this.flywayInitializer = Objects.requireNonNull(flywayInitializer, "flywayInitializer");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public void afterPropertiesSet() throws SQLException {
    if (flywayInitializer.getIfAvailable() != null) {
      return;
    }
    try (Connection connection = dataSource.getObject().getConnection()) {
      if (!connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
        throw new IllegalStateException("Metadata DB 연결이 유효하지 않다");
      }
    }
  }
}
