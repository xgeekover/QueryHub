package com.queryhub.app.config.dbprovider;

import com.queryhub.adapter.dbprovider.HikariConnectionPools;
import com.queryhub.adapter.dbprovider.JdbcDatabaseProvider;
import com.queryhub.adapter.dbprovider.JdbcProviderSpi;
import com.queryhub.adapter.dbprovider.provider.mariadb.MariaDbJdbcProvider;
import com.queryhub.adapter.dbprovider.provider.postgresql.PostgreSqlJdbcProvider;
import com.queryhub.application.connection.TestConnectionUseCase;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 업무 DB 접속 조립 (P1-07): DB 별 Provider, 접속 테스트 유스케이스, 접속 정보별 읽기 전용 풀.
 *
 * <ul>
 *   <li>Provider 는 MariaDB, PostgreSQL 두 개다. Oracle, MSSQL 은 아직 없어서 유스케이스가
 *       UNSUPPORTED_DATABASE_TYPE 으로 답한다.
 *   <li>풀 관리자({@link HikariConnectionPools})는 DataSource 가 아니다. 앱의 DataSource 빈은 Metadata 저장소 하나뿐이고
 *       Spring Boot 의 DataSource 자동 구성, 상태 표시기, 지표는 업무 풀을 보지 않는다.
 *   <li>기동할 때 업무 DB 에 접속하지 않는다: 풀은 열 때도 접속하지 않고(minimumIdle=0), 기동 때 여는 풀도 없다.
 *   <li>컨텍스트가 닫힐 때 모든 풀을 닫고 비밀번호 사본을 지운다 ({@code closeAll}).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseProviderConfiguration {

  @Bean
  public MariaDbJdbcProvider mariaDbJdbcProvider() {
    return new MariaDbJdbcProvider();
  }

  @Bean
  public PostgreSqlJdbcProvider postgreSqlJdbcProvider() {
    return new PostgreSqlJdbcProvider();
  }

  @Bean
  public TestConnectionUseCase testConnectionUseCase(List<JdbcProviderSpi> providers) {
    return new TestConnectionUseCase(providers.stream().map(JdbcDatabaseProvider::new).toList());
  }

  @Bean(destroyMethod = "closeAll")
  public HikariConnectionPools connectionPools(List<JdbcProviderSpi> providers) {
    return new HikariConnectionPools(providers);
  }
}
