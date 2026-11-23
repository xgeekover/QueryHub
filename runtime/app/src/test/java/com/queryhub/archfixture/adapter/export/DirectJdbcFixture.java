package com.queryhub.archfixture.adapter.export;

import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 아키텍처 자기 검사용 고의 위반(R8): 저장 기술을 쓸 수 없는 어댑터(export)가 Spring JDBC 를 직접 쓴다. P1-07(P0B-R5)부터 Spring
 * Boot 의 JDBC 지원(org.springframework.boot.jdbc)과 Flyway 지원(org.springframework.boot.flyway)도 쓴다. 운영
 * 코드가 아니며 Spring 스테레오타입이 없다.
 */
public final class DirectJdbcFixture {

  public int count(JdbcClient jdbcClient) {
    return jdbcClient.sql("SELECT 1").query(Integer.class).single();
  }

  public Object bootJdbc() {
    return DataSourceBuilder.create();
  }

  public Object bootFlyway() {
    return new FlywayProperties();
  }
}
