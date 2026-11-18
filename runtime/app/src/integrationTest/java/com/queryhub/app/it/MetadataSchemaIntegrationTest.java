package com.queryhub.app.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryhub.app.it.support.MetadataDatabaseIntegrationTest;
import com.queryhub.app.it.support.MetadataMariaDb;
import com.queryhub.app.it.support.QueryHubIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.TimeZone;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * AC-14: 스키마와 세션 시간대가 서버 기본값과 무관하다. 컨테이너는 latin1, latin1_swedish_ci, +09:00 으로 돈다(MetadataMariaDb).
 */
@QueryHubIntegrationTest
class MetadataSchemaIntegrationTest extends MetadataDatabaseIntegrationTest {

  private record Column(
      String name, String type, String nullable, String characterSet, String collation) {}

  private static final String CREATE_PROBE_TABLE =
      "CREATE TEMPORARY TABLE local_infile_probe (v VARCHAR(16)) CHARACTER SET utf8mb4";

  /**
   * Connector/J 3.5.11 이 allowLocalInfile=false 일 때 서버의 파일 요청을 받고 스스로 거부하며 내는 메시지
   * (org.mariadb.jdbc.message.ClientMessage.readPacket). 서버 오류가 아니라 드라이버의 거부다.
   */
  private static final String LOCAL_INFILE_REFUSED = "Local infile is disabled by connector";

  @Autowired private JdbcClient jdbcClient;

  @Autowired private DataSource dataSource;

  @Test
  void serverAndDatabaseDefaultsAreHostile() throws Exception {
    // 전제를 확인한다: 설정이 먹지 않았으면 아래 검사는 아무것도 증명하지 못한다.
    try (Connection connection = MetadataMariaDb.rootConnection();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT @@global.character_set_server, @@global.collation_server,"
                    + " @@global.time_zone")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("latin1");
      assertThat(rs.getString(2)).isEqualTo("latin1_swedish_ci");
      assertThat(rs.getString(3)).isEqualTo("+09:00");
    }
    assertThat(
            jdbcClient
                .sql(
                    "SELECT DEFAULT_CHARACTER_SET_NAME FROM information_schema.SCHEMATA"
                        + " WHERE SCHEMA_NAME = DATABASE()")
                .query(String.class)
                .single())
        .as("데이터베이스 기본 문자 집합")
        .isEqualTo("latin1");
    assertThat(TimeZone.getDefault().getID()).as("테스트 JVM 시간대").isEqualTo("Asia/Seoul");
  }

  @Test
  void appSettingDeclaresItsOwnCharsetAndCollation() {
    assertThat(
            jdbcClient
                .sql(
                    "SELECT TABLE_COLLATION FROM information_schema.TABLES"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_setting'")
                .query(String.class)
                .single())
        .isEqualTo("utf8mb4_uca1400_ai_ci");

    List<Column> columns =
        jdbcClient
            .sql(
                "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, CHARACTER_SET_NAME, COLLATION_NAME"
                    + " FROM information_schema.COLUMNS"
                    + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_setting'"
                    + " ORDER BY ORDINAL_POSITION")
            .query(
                (rs, rowNum) ->
                    new Column(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5)))
            .list();

    assertThat(columns)
        .extracting(Column::name)
        .containsExactly("setting_key", "setting_value", "created_at", "updated_at");
    assertThat(columns.get(0))
        .isEqualTo(new Column("setting_key", "varchar(100)", "NO", "utf8mb4", "utf8mb4_nopad_bin"));
    Column value = columns.get(1);
    assertThat(value.type()).isEqualTo("text");
    assertThat(value.nullable()).isEqualTo("NO");
    assertThat(value.characterSet()).isEqualTo("utf8mb4");
    assertThat(value.collation()).startsWith("utf8mb4_");
    assertThat(columns.subList(2, 4))
        .allSatisfy(
            column -> {
              assertThat(column.type()).isEqualTo("datetime(6)");
              assertThat(column.nullable()).isEqualTo("NO");
            });

    assertThat(
            jdbcClient
                .sql(
                    "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_setting'"
                        + " AND CONSTRAINT_NAME = 'PRIMARY' ORDER BY ORDINAL_POSITION")
                .query(String.class)
                .list())
        .containsExactly("setting_key");
  }

  @Test
  void applicationSessionsRunInUtc() throws Exception {
    // 런타임의 풀(Hikari)에서 꺼낸 연결이다. URL 옵션 connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
    // 의 결과다.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT @@session.time_zone")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("+00:00");
    }
  }

  /**
   * R2(b): URL 옵션 allowLocalInfile=false 가 실제로 LOCAL INFILE 을 막는다.
   *
   * <p>먼저 대조군으로 같은 서버, 같은 사용자, 같은 파일에 allowLocalInfile=true 인 연결을 직접 열어 같은 문장이 행 하나를 읽는 것을 확인한다.
   * 그러니 서버는 LOCAL INFILE 을 허용하고, 런타임 풀의 연결에서 같은 문장이 실패하는 것은 클라이언트(드라이버) 설정 때문이다.
   */
  @Test
  void applicationConnectionsRefuseLocalInfile(@TempDir Path directory) throws Exception {
    Path file = Files.writeString(directory.resolve("local-infile-probe.csv"), "probe\n");
    String path = file.toAbsolutePath().toString();
    assertThat(path).as("문장에 그대로 넣는 경로").doesNotContain("'", "\\");
    String load = "LOAD DATA LOCAL INFILE '" + path + "' INTO TABLE local_infile_probe";

    try (Connection control =
            DriverManager.getConnection(
                "jdbc:mariadb://"
                    + MetadataMariaDb.host()
                    + ":"
                    + MetadataMariaDb.port()
                    + "/"
                    + MetadataMariaDb.DATABASE
                    + "?allowLocalInfile=true",
                MetadataMariaDb.USERNAME,
                MetadataMariaDb.PASSWORD);
        Statement statement = control.createStatement()) {
      statement.execute(CREATE_PROBE_TABLE);
      assertThat(statement.executeUpdate(load))
          .as("대조군: allowLocalInfile=true 면 서버가 LOCAL INFILE 을 받아들인다")
          .isEqualTo(1);
    }

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(CREATE_PROBE_TABLE);
      try {
        assertThatThrownBy(() -> statement.execute(load))
            .as("런타임 풀의 연결")
            .isInstanceOf(SQLException.class)
            .hasMessageContaining(LOCAL_INFILE_REFUSED);
        try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM local_infile_probe")) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getInt(1)).as("읽힌 행").isZero();
        }
      } finally {
        statement.execute("DROP TEMPORARY TABLE local_infile_probe");
      }
    }
  }
}
