package com.queryhub.app.config.dbprovider;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.adapter.dbprovider.HikariConnectionPools;
import com.queryhub.app.QueryHubApplication;
import com.queryhub.app.support.TestAdminToken;
import com.queryhub.app.support.TestComponentScanExclusion;
import com.queryhub.app.support.TestMasterKey;
import com.queryhub.app.support.TestSqlite;
import com.queryhub.application.connection.TestConnectionUseCase;
import com.queryhub.domain.connection.ConnectionId;
import com.queryhub.domain.connection.ConnectionPassword;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.DatabaseType;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * P1-07 AC13(앱 컨텍스트 부분), AC20: 실제 QueryHubApplication 을 임시 데이터 디렉터리와 루프백 포트 0 으로 띄운다. 업무 DB 는 없다.
 *
 * <ul>
 *   <li>DataSource 빈은 정확히 하나이고 Metadata 저장소(SQLite) 풀이다. 업무 풀 관리자는 DataSource 가 아니다.
 *   <li>기동은 업무 DB 에 접속하지 않는다: 업무 풀이나 접속 테스트 스레드(queryhub-conn-*)가 생기지 않는다.
 *   <li>컨텍스트를 닫으면 업무 풀이 닫힌다.
 * </ul>
 */
class DatabaseProviderConfigurationTest {

  private static final String BUSINESS_THREAD_PREFIX = HikariConnectionPools.POOL_NAME_PREFIX;

  @TempDir Path tmp;

  @BeforeAll
  static void loadNative() throws Exception {
    TestSqlite.loadNativeOnce();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void theOnlyDataSourceBeanIsTheMetadataPoolAndStartupOpensNoBusinessConnection(boolean lazy) {
    Set<String> threadsBefore = businessThreads();

    try (ConfigurableApplicationContext context = start(lazy)) {
      String[] dataSources = context.getBeanNamesForType(DataSource.class);
      assertThat(dataSources).as("DataSource 빈").hasSize(1);
      DataSource only = context.getBean(dataSources[0], DataSource.class);
      assertThat(only).isInstanceOf(HikariDataSource.class);
      assertThat(((HikariDataSource) only).getJdbcUrl()).startsWith("jdbc:sqlite:");
      assertThat(context.getBeansOfType(HikariDataSource.class)).hasSize(1);

      HikariConnectionPools pools = context.getBean(HikariConnectionPools.class);
      assertThat(pools).isNotInstanceOf(DataSource.class);
      assertThat(context.getBeanNamesForType(HikariConnectionPools.class)).hasSize(1);
      assertThat(context.getBean(TestConnectionUseCase.class).supportedTypes())
          .containsExactlyInAnyOrder(DatabaseType.MARIADB, DatabaseType.POSTGRESQL);

      assertThat(businessThreads()).as("기동이 만든 업무 풀·접속 테스트 스레드").isSubsetOf(threadsBefore);
    }
  }

  @Test
  void closingTheContextClosesTheBusinessPools() throws IOException {
    ConnectionId id = new ConnectionId("shutdown-check");
    HikariConnectionPools pools;
    try (ConfigurableApplicationContext context = start(false)) {
      pools = context.getBean(HikariConnectionPools.class);
      // 여는 순간에는 접속하지 않으므로 닫힌 로컬 포트로도 풀이 열린다.
      pools.open(
          id, settings(closedLocalPort()), ConnectionPassword.of("ctx-throwaway".toCharArray()));
      assertThat(pools.isOpen(id)).isTrue();
    }
    assertThat(pools.isOpen(id)).as("컨텍스트 종료 뒤 업무 풀").isFalse();
  }

  private ConfigurableApplicationContext start(boolean lazy) {
    return new SpringApplicationBuilder(QueryHubApplication.class)
        .web(WebApplicationType.SERVLET)
        .lazyInitialization(lazy)
        .profiles("desktop")
        .properties(TestAdminToken.PROPERTY, TestMasterKey.PROPERTY)
        .initializers(TestComponentScanExclusion::register)
        .run(
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--queryhub.data-dir=" + tmp.resolve("data" + (lazy ? "-lazy" : "")));
  }

  private static ConnectionSettings settings(int port) {
    return new ConnectionSettings(
        DatabaseType.POSTGRESQL,
        "127.0.0.1",
        port,
        "app_db",
        "reader",
        Duration.ofSeconds(2),
        1,
        Map.of());
  }

  private static int closedLocalPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static Set<String> businessThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(Thread::isAlive)
        .map(Thread::getName)
        .filter(name -> name.startsWith(BUSINESS_THREAD_PREFIX))
        .collect(Collectors.toSet());
  }
}
