package com.queryhub.app.it.dbprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryhub.adapter.dbprovider.JdbcProviderSpi;
import com.queryhub.adapter.dbprovider.provider.mariadb.MariaDbJdbcProvider;
import com.queryhub.domain.connection.ConnectionFailureKind;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.ConnectionTestResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/** P1-07 AC18, AC19: MariaDB Provider 를 로컬 이미지 mariadb:12.3 로 시험한다. */
@Testcontainers
class MariaDbProviderIntegrationTest extends DatabaseProviderContainerTest {

  private static final int PORT = 3306;

  /** 컨테이너 사용자와 root 의 비밀번호 (Testcontainers 가 둘을 같게 둔다). 버리는 값이다. */
  private static final String ADMIN_PASSWORD = throwawayPassword();

  private static final String READER_PASSWORD = throwawayPassword();

  @Container
  static final MariaDBContainer MARIADB =
      new MariaDBContainer(DockerImageName.parse("mariadb:12.3"))
          .withDatabaseName(DATABASE)
          .withUsername("qh_owner")
          .withPassword(ADMIN_PASSWORD)
          // LOCAL INFILE 거부 시험이 클라이언트 쪽 거부를 보도록 서버는 허용해 둔다.
          .withCommand("--local-infile=1")
          .withCreateContainerCmdModifier(LoopbackPorts.only(PORT))
          .withStartupTimeout(Duration.ofMinutes(3));

  @TempDir Path tmp;

  @Override
  JdbcDatabaseContainer<?> container() {
    return MARIADB;
  }

  @Override
  int containerPort() {
    return PORT;
  }

  @Override
  JdbcProviderSpi spi() {
    return new MariaDbJdbcProvider();
  }

  @Override
  Connection adminConnection() throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", "root");
    properties.setProperty("password", ADMIN_PASSWORD);
    return MARIADB
        .getJdbcDriverInstance()
        .connect("jdbc:mariadb://127.0.0.1:" + mappedPort() + "/" + DATABASE, properties);
  }

  @Override
  List<String> readerSetupSql(String readerPassword) {
    return List.of(
        "CREATE USER '" + READER + "'@'%' IDENTIFIED BY '" + readerPassword + "'",
        "CREATE TABLE " + DATABASE + "." + TABLE + " (id INT)",
        "GRANT SELECT, INSERT ON " + DATABASE + "." + TABLE + " TO '" + READER + "'@'%'");
  }

  @Override
  String sessionCountSql() {
    return "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER = ?";
  }

  @Override
  String readerPassword() {
    return READER_PASSWORD;
  }

  @Override
  String productNameFragment() {
    return "MariaDB";
  }

  /** 권한이 있는 사용자에게 MariaDB 는 1049(ER_BAD_DB_ERROR)로 답한다. 권한이 없는 사용자(공통 시험)에게는 1044 다. */
  @Test
  void unknownDatabaseForAPrivilegedUserIsAlsoDatabaseNotFound() {
    ConnectionSettings root =
        new ConnectionSettings(
            spi().type(),
            LoopbackPorts.LOOPBACK,
            mappedPort(),
            "qh_no_such_db",
            "root",
            CONNECT_TIMEOUT,
            1,
            Map.of());

    ConnectionTestResult result = provider().testConnection(root, password(ADMIN_PASSWORD));

    assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.DATABASE_NOT_FOUND);
  }

  /**
   * allowLocalInfile=false: 서버(local_infile=1)가 LOAD DATA LOCAL INFILE 로 클라이언트 파일을 달라고 해도 드라이버가
   * 거부한다. 쓰기 권한이 있는 일반(읽기 전용이 아닌) 연결에 Provider 의 고정 속성을 그대로 준다.
   */
  @Test
  void localInfileRequestsAreRefusedByTheDriver() throws IOException, SQLException {
    Path file = Files.writeString(tmp.resolve("client-file.txt"), "7\n");
    Properties properties = new Properties();
    spi()
        .fixedProperties(readerSettings(DATABASE, CONNECT_TIMEOUT, 1))
        .forEach(properties::setProperty);
    properties.setProperty("user", READER);
    properties.setProperty("password", READER_PASSWORD);

    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement();
        ResultSet rows = statement.executeQuery("SELECT @@GLOBAL.local_infile")) {
      rows.next();
      assertThat(rows.getInt(1)).as("서버는 LOCAL INFILE 을 허용한다 (시험의 전제)").isEqualTo(1);
    }
    try (Connection connection =
            spi()
                .driver()
                .connect(spi().jdbcUrl(readerSettings(DATABASE, CONNECT_TIMEOUT, 1)), properties);
        Statement statement = connection.createStatement()) {
      assertThat(connection.isReadOnly()).isFalse();
      String load =
          "LOAD DATA LOCAL INFILE '"
              + file.toAbsolutePath().toString().replace("\\", "/")
              + "' INTO TABLE "
              + TABLE;
      assertThatThrownBy(() -> statement.execute(load)).isInstanceOf(SQLException.class);
    }
    assertThat(rowCount()).as("LOAD DATA LOCAL INFILE 뒤 표").isZero();
  }
}
