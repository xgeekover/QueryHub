package com.queryhub.app.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryhub.app.QueryHubApplication;
import com.queryhub.app.it.support.IntegrationHttp;
import com.queryhub.app.it.support.MetadataDatabaseIntegrationTest;
import com.queryhub.app.it.support.MetadataMariaDb;
import com.queryhub.app.it.support.QueryHubIntegrationTest;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * AC-13: 실제 MariaDB 에 붙은 기동 스모크. desktop 프로파일 컨텍스트(무작위 포트, 127.0.0.1)와, 같은 DB 로 띄우는 server 프로파일
 * 컨텍스트.
 */
@QueryHubIntegrationTest
class MetadataStartupIntegrationTest extends MetadataDatabaseIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private HealthContributorRegistry healthContributors;

  @Autowired private JdbcClient jdbcClient;

  @ParameterizedTest
  @ValueSource(
      strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
  void healthEndpointsAreUp(String path) throws Exception {
    HttpResponse<String> response = IntegrationHttp.get(port, path);

    assertThat(response.statusCode()).as("%s body=%s", path, response.body()).isEqualTo(200);
    assertThat(IntegrationHttp.json(response).path("status").stringValue()).isEqualTo("UP");
  }

  @Test
  void dbHealthContributorIsRegisteredAndUp() {
    HealthContributor db = healthContributors.getContributor("db");

    assertThat(db).as("Boot 의 db health contributor").isInstanceOf(HealthIndicator.class);
    assertThat(((HealthIndicator) db).health(false).getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void flywayAppliedExactlyV1() {
    assertThat(flywayHistory(jdbcClient)).containsExactly(Map.of("version", "1", "success", 1L));
  }

  @Test
  void runtimeInfoIsUnchangedFromP0A() throws Exception {
    HttpResponse<String> response = IntegrationHttp.adminGet(port, "/admin/api/v1/runtime");

    assertThat(response.statusCode()).as("body=%s", response.body()).isEqualTo(200);
    JsonNode body = IntegrationHttp.json(response);
    assertThat(body.propertyNames()).containsExactlyInAnyOrder("success", "data", "meta");
    assertThat(body.path("success").booleanValue()).isTrue();
    JsonNode data = body.path("data");
    assertThat(data.propertyNames())
        .containsExactlyInAnyOrder("name", "version", "mode", "startedAt", "uptimeSeconds");
    assertThat(data.path("name").stringValue()).isEqualTo("queryhub-runtime");
    assertThat(data.path("mode").stringValue()).isEqualTo("desktop");
  }

  /** server 프로파일도 같은 변수로 같은 DB 에 붙는다. V1 은 이미 적용되어 다시 적용하지 않는다. 바인딩은 루프백으로 고정한다. */
  @Test
  void serverProfileStartsAgainstTheSameDatabaseWithoutReapplyingV1() throws Exception {
    assertThat(flywayHistory(jdbcClient)).hasSize(1);

    try (ConfigurableApplicationContext server =
        new SpringApplicationBuilder(QueryHubApplication.class)
            .properties(IntegrationHttp.ADMIN_TOKEN_PROPERTY)
            .web(WebApplicationType.SERVLET)
            .profiles("server")
            .properties(
                MetadataMariaDb.applicationProperties(
                    MetadataMariaDb.DATABASE, MetadataMariaDb.PASSWORD))
            .run("--server.address=127.0.0.1", "--server.port=0")) {
      int serverPort = ((WebServerApplicationContext) server).getWebServer().getPort();

      HttpResponse<String> health = IntegrationHttp.get(serverPort, "/actuator/health");
      assertThat(health.statusCode()).as("body=%s", health.body()).isEqualTo(200);
      assertThat(IntegrationHttp.json(health).path("status").stringValue()).isEqualTo("UP");
      HttpResponse<String> runtime = IntegrationHttp.adminGet(serverPort, "/admin/api/v1/runtime");
      assertThat(IntegrationHttp.json(runtime).path("data").path("mode").stringValue())
          .isEqualTo("server");
      assertThat(server.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    assertThat(flywayHistory(jdbcClient)).containsExactly(Map.of("version", "1", "success", 1L));
  }

  /**
   * R10: spring.flyway.url·user·password 가 다른 곳을 가리켜도 Flyway 는 검사한 주 풀로 마이그레이션한다. 새 데이터베이스에 V1 이
   * 적용되고(주 풀로 읽는다), Flyway 의 DataSource 는 주 DataSource 이며, 웹 서버가 뜬다. 그 URL 의 포트에는 연결이 하나도 오지 않는다.
   */
  @Test
  void flywayConnectionKeysPointingElsewhereStillMigrateThroughTheCheckedMainPool()
      throws Exception {
    String database = "queryhub_flyway_keys_it";
    MetadataMariaDb.createEmptyDatabase(database);

    try (ServerSocket trap = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      try (ConfigurableApplicationContext context =
          new SpringApplicationBuilder(QueryHubApplication.class)
              .properties(IntegrationHttp.ADMIN_TOKEN_PROPERTY)
              .web(WebApplicationType.SERVLET)
              .profiles("desktop")
              .properties(MetadataMariaDb.applicationProperties(database, MetadataMariaDb.PASSWORD))
              .run(
                  "--server.address=127.0.0.1",
                  "--server.port=0",
                  "--spring.flyway.url=jdbc:mariadb://127.0.0.1:"
                      + trap.getLocalPort()
                      + "/otherdb?allowLocalInfile=true",
                  "--spring.flyway.user=migrator",
                  "--spring.flyway.password=not-the-metadata-password")) {
        DataSource main = context.getBean(DataSource.class);
        assertThat(context.getBean(Flyway.class).getConfiguration().getDataSource())
            .as("Flyway 는 주 풀로 마이그레이션한다")
            .isSameAs(main);
        assertThat(flywayHistory(context.getBean(JdbcClient.class)))
            .as("검사한 데이터베이스(%s)에 V1 이 적용된다", database)
            .containsExactly(Map.of("version", "1", "success", 1L));
        int serverPort = ((WebServerApplicationContext) context).getWebServer().getPort();
        HttpResponse<String> health = IntegrationHttp.get(serverPort, "/actuator/health");
        assertThat(health.statusCode()).as("body=%s", health.body()).isEqualTo(200);
      }

      trap.setSoTimeout(200);
      assertThatThrownBy(trap::accept)
          .as("Flyway 의 URL(포트 %d)로는 연결하지 않는다", trap.getLocalPort())
          .isInstanceOf(SocketTimeoutException.class);
    }
  }

  static List<Map<String, Object>> flywayHistory(JdbcClient jdbcClient) {
    return jdbcClient
        .sql(
            "SELECT version, CAST(success AS UNSIGNED) AS success FROM flyway_schema_history"
                + " ORDER BY installed_rank")
        .query(
            (rs, rowNum) ->
                Map.<String, Object>of(
                    "version", rs.getString("version"), "success", rs.getLong("success")))
        .list();
  }
}
