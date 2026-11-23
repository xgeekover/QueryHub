package com.queryhub.app.it;

import static com.queryhub.app.it.support.IntegrationSqlite.fileKey;
import static com.queryhub.app.it.support.IntegrationSqlite.mode;
import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.adapter.metadata.store.StorePragmas;
import com.queryhub.app.QueryHubApplication;
import com.queryhub.app.it.support.IntegrationHttp;
import com.queryhub.app.it.support.IntegrationSqlite;
import com.queryhub.app.it.support.MetadataDatabaseIntegrationTest;
import com.queryhub.app.it.support.QueryHubIntegrationTest;
import com.queryhub.application.setting.AppSettingRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * AC-13, P1-06 AC6, AC7, AC11, AC13: 실제 SQLite 저장소에 붙은 기동 스모크. desktop 프로파일의 공유 컨텍스트(무작위 포트,
 * 127.0.0.1)와, 자기 임시 데이터 디렉터리로 띄우는 컨텍스트(재시작, server 프로파일, Flyway 접속 키).
 */
@QueryHubIntegrationTest
class MetadataStartupIntegrationTest extends MetadataDatabaseIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private HealthContributorRegistry healthContributors;

  @Autowired private JdbcClient jdbcClient;

  @Autowired private DataSource dataSource;

  @TempDir private Path tmp;

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

  /** AC6: 런타임이 만든 데이터 디렉터리와 네이티브 디렉터리는 0700, DB 와 WAL·공유 메모리 파일은 0600 이다. */
  @Test
  void freshStoreFilesAreOwnerOnly() throws Exception {
    Path dataDir = sharedDataDir();
    jdbcClient
        .sql(
            "INSERT INTO app_setting VALUES ('it.mode-probe', 'v', '2026-01-02T03:04:05.000000Z',"
                + " '2026-01-02T03:04:05.000000Z') ON CONFLICT (setting_key) DO NOTHING")
        .update();

    assertThat(mode(dataDir)).isEqualTo("rwx------");
    assertThat(mode(dataDir.resolve("native"))).isEqualTo("rwx------");
    assertThat(mode(dataDir.resolve("queryhub.db"))).isEqualTo("rw-------");
    assertThat(mode(dataDir.resolve("queryhub.db-wal"))).isEqualTo("rw-------");
    assertThat(mode(dataDir.resolve("queryhub.db-shm"))).isEqualTo("rw-------");
    assertThat(mode(dataDir.resolve("queryhub.lock"))).isEqualTo("rw-------");
  }

  /** AC11: 풀 크기(4)만큼 연결을 동시에 빌려도 모두 런타임의 SQLite 설정을 갖는다. */
  @Test
  void everyPoolConnectionCarriesTheStorePragmas() throws Exception {
    HikariDataSource pool = (HikariDataSource) dataSource;
    assertThat(pool.getMaximumPoolSize()).as("application.yml 의 maximum-pool-size").isEqualTo(4);
    List<Connection> borrowed = new ArrayList<>();
    try {
      for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
        Connection connection = pool.getConnection();
        borrowed.add(connection);
        assertThat(StorePragmas.mismatches(connection)).as("풀 연결 %d", i + 1).isEmpty();
      }
    } finally {
      for (Connection connection : borrowed) {
        connection.close();
      }
    }
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

  /**
   * AC7 (같은 JVM): 첫 컨텍스트(desktop)가 값을 쓰고 닫힌 뒤, server 프로파일 컨텍스트가 같은 데이터 디렉터리로 뜬다. 같은 파일(inode)을 쓰고,
   * V1 을 다시 적용하지 않으며, 첫 실행이 쓴 값을 읽는다. 바인딩은 루프백으로 고정한다.
   */
  @Test
  void serverProfileRestartsOnTheSameStoreWithoutReapplyingV1() throws Exception {
    Path dataDir = tmp.resolve("data");
    Path db = dataDir.resolve("queryhub.db");
    Object firstFile;
    try (ConfigurableApplicationContext desktop = start("desktop", dataDir)) {
      desktop.getBean(AppSettingRepository.class).putValue("it.restart", "written by run 1");
      firstFile = fileKey(db);
    }
    assertThat(IntegrationSqlite.flywayHistory(db)).hasSize(1);

    try (ConfigurableApplicationContext server = start("server", dataDir)) {
      int serverPort = ((WebServerApplicationContext) server).getWebServer().getPort();

      HttpResponse<String> health = IntegrationHttp.get(serverPort, "/actuator/health");
      assertThat(health.statusCode()).as("body=%s", health.body()).isEqualTo(200);
      assertThat(IntegrationHttp.json(health).path("status").stringValue()).isEqualTo("UP");
      HttpResponse<String> runtime = IntegrationHttp.adminGet(serverPort, "/admin/api/v1/runtime");
      assertThat(IntegrationHttp.json(runtime).path("data").path("mode").stringValue())
          .isEqualTo("server");
      assertThat(server.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
      assertThat(server.getBean(AppSettingRepository.class).findValue("it.restart"))
          .contains("written by run 1");
      assertThat(fileKey(db)).as("같은 DB 파일").isEqualTo(firstFile);
    }

    assertThat(IntegrationSqlite.flywayHistory(db))
        .containsExactly(Map.of("version", "1", "success", 1L));
  }

  /**
   * R10, P1-06 AC13: spring.flyway.url·user·password 가 다른 SQLite 파일을 가리켜도 Flyway 는 검사한 주 풀로
   * 마이그레이션한다. 새 저장소에 V1 이 적용되고, Flyway 의 DataSource 는 주 DataSource 이며, 웹 서버가 뜬다. 그 URL 의 파일은 생기지
   * 않는다.
   */
  @Test
  void flywayConnectionKeysPointingElsewhereStillMigrateThroughTheCheckedMainPool()
      throws Exception {
    Path dataDir = tmp.resolve("data");
    Path other = tmp.resolve("other.db");

    try (ConfigurableApplicationContext context =
        start(
            "desktop",
            dataDir,
            "--spring.flyway.url=jdbc:sqlite:" + other,
            "--spring.flyway.user=migrator",
            "--spring.flyway.password=not-the-store-password")) {
      DataSource main = context.getBean(DataSource.class);
      assertThat(context.getBean(Flyway.class).getConfiguration().getDataSource())
          .as("Flyway 는 주 풀로 마이그레이션한다")
          .isSameAs(main);
      assertThat(flywayHistory(context.getBean(JdbcClient.class)))
          .as("검사한 저장소(%s)에 V1 이 적용된다", dataDir)
          .containsExactly(Map.of("version", "1", "success", 1L));
      int serverPort = ((WebServerApplicationContext) context).getWebServer().getPort();
      HttpResponse<String> health = IntegrationHttp.get(serverPort, "/actuator/health");
      assertThat(health.statusCode()).as("body=%s", health.body()).isEqualTo(200);
    }

    assertThat(other).as("Flyway 의 URL 이 가리키는 파일은 생기지 않는다").doesNotExist();
  }

  private static ConfigurableApplicationContext start(
      String profile, Path dataDir, String... extraArgs) {
    List<String> args =
        new ArrayList<>(
            List.of(
                "--server.address=127.0.0.1", "--server.port=0", "--queryhub.data-dir=" + dataDir));
    args.addAll(List.of(extraArgs));
    return new SpringApplicationBuilder(QueryHubApplication.class)
        .properties(IntegrationHttp.ADMIN_TOKEN_PROPERTY, IntegrationHttp.MASTER_KEY_PROPERTY)
        .web(WebApplicationType.SERVLET)
        .profiles(profile)
        .run(args.toArray(String[]::new));
  }

  static List<Map<String, Object>> flywayHistory(JdbcClient jdbcClient) {
    return jdbcClient
        .sql("SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")
        .query(
            (rs, rowNum) ->
                Map.<String, Object>of(
                    "version", rs.getString("version"), "success", rs.getLong("success")))
        .list();
  }
}
