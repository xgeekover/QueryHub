package com.queryhub.app.it.dbprovider;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.adapter.dbprovider.JdbcProviderSpi;
import com.queryhub.adapter.dbprovider.provider.postgresql.PostgreSqlJdbcProvider;
import com.queryhub.domain.connection.ConnectionFailureKind;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.ConnectionTestResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** P1-07 AC18, AC19: PostgreSQL Provider 를 로컬 이미지 postgres:16-alpine 로 시험한다. */
@Testcontainers
class PostgreSqlProviderIntegrationTest extends DatabaseProviderContainerTest {

  private static final int PORT = 5432;

  private static final String ADMIN_PASSWORD = throwawayPassword();

  private static final String READER_PASSWORD = throwawayPassword();

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
          .withDatabaseName(DATABASE)
          .withUsername("qh_admin")
          .withPassword(ADMIN_PASSWORD)
          .withCreateContainerCmdModifier(LoopbackPorts.only(PORT))
          .withStartupTimeout(Duration.ofMinutes(3));

  @TempDir Path tmp;

  @Override
  JdbcDatabaseContainer<?> container() {
    return POSTGRES;
  }

  @Override
  int containerPort() {
    return PORT;
  }

  @Override
  JdbcProviderSpi spi() {
    return new PostgreSqlJdbcProvider();
  }

  @Override
  Connection adminConnection() throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", "qh_admin");
    properties.setProperty("password", ADMIN_PASSWORD);
    return POSTGRES
        .getJdbcDriverInstance()
        .connect("jdbc:postgresql://127.0.0.1:" + mappedPort() + "/" + DATABASE, properties);
  }

  @Override
  List<String> readerSetupSql(String readerPassword) {
    return List.of(
        "CREATE ROLE " + READER + " LOGIN PASSWORD '" + readerPassword + "'",
        "CREATE TABLE " + TABLE + " (id INT)",
        "GRANT SELECT, INSERT ON " + TABLE + " TO " + READER);
  }

  @Override
  String sessionCountSql() {
    return "SELECT COUNT(*) FROM pg_stat_activity WHERE usename = ?";
  }

  @Override
  String readerPassword() {
    return READER_PASSWORD;
  }

  @Override
  String productNameFragment() {
    return "PostgreSQL";
  }

  /**
   * pgjdbc 는 비밀번호가 없을 때 .pgpass 를, service 옵션이 있을 때 pg_service.conf 를 읽는다. 맞는 비밀번호를 담은 두 파일을
   * 가리켜도(시스템 속성, 사용자 홈의 파일보다 앞선다) 틀린 비밀번호는 인증 실패이고 service 옵션은 거부된다.
   */
  @Test
  void pgpassAndServiceFilesAreNeverUsed() throws IOException {
    Path pgpass =
        Files.writeString(tmp.resolve("pgpass"), "*:*:*:" + READER + ":" + READER_PASSWORD + "\n");
    Path services =
        Files.writeString(
            tmp.resolve("pg_service.conf"),
            "[qh_it_service]\nhost=127.0.0.1\nport="
                + mappedPort()
                + "\ndbname="
                + DATABASE
                + "\nuser="
                + READER
                + "\npassword="
                + READER_PASSWORD
                + "\n");
    String previousPass = System.setProperty("org.postgresql.pgpassfile", pgpass.toString());
    String previousService =
        System.setProperty("org.postgresql.pgservicefile", services.toString());
    try {
      ConnectionTestResult wrong =
          provider()
              .testConnection(
                  readerSettings(DATABASE, CONNECT_TIMEOUT, 1), password(throwawayPassword()));
      assertThat(wrong.failureKind()).isEqualTo(ConnectionFailureKind.AUTHENTICATION_FAILED);

      ConnectionSettings service =
          new ConnectionSettings(
              spi().type(),
              LoopbackPorts.LOOPBACK,
              mappedPort(),
              DATABASE,
              READER,
              CONNECT_TIMEOUT,
              1,
              Map.of("service", "qh_it_service"));
      assertThat(provider().testConnection(service, password(throwawayPassword())).failureKind())
          .isEqualTo(ConnectionFailureKind.OPTION_REJECTED);
    } finally {
      restore("org.postgresql.pgpassfile", previousPass);
      restore("org.postgresql.pgservicefile", previousService);
    }
  }

  private static void restore(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }
}
