package com.queryhub.app.it.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 JVM 하나에 MariaDB 컨테이너 하나. 처음 쓸 때 띄우고(지연 시작 싱글턴), JVM 이 끝나면 Testcontainers 의 Ryuk 가 지운다. 클래스마다
 * 컨테이너를 띄우지 않는다(Testcontainers 의 JUnit 확장과 필드 단위 컨테이너 애너테이션을 쓰지 않는다). 그러면 캐시된 Spring 컨텍스트가 멈춘 컨테이너를
 * 가리키게 된다.
 *
 * <p>이미지는 deploy/compose.dev.yml 의 {@code image:} 와 같은 문자열이다. 서버 설정은 일부러 런타임에 불리하게 둔다: 기본 문자 집합
 * latin1, 정렬 latin1_swedish_ci, 시간대 +09:00. Desktop 사용자가 가져오는 서버의 기본값은 제각각이므로, 런타임(DDL 의 명시적
 * utf8mb4, 세션 시간대 UTC 고정)이 서버 기본값에 기대지 않는다는 것을 이 설정으로 증명한다.
 *
 * <p>접속 정보는 {@code @DynamicPropertySource} 로 {@code queryhub.metadata.db.*} 에 넣는다. Spring Boot 의
 * 서비스 연결(ServiceConnection) 애너테이션은 쓰지 않는다: JDBC URL 을 통째로 바꾸고 런타임의 JdbcConnectionDetails 와 경쟁해서,
 * 런타임이 붙이는 URL 옵션을 검증할 수 없게 된다.
 *
 * <p>비밀번호는 표식 문자열이다. 실제 자격 증명이 아니며 이 컨테이너 밖에서는 쓰이지 않는다.
 */
public final class MetadataMariaDb {

  /** deploy/compose.dev.yml 과 같은 이미지. */
  public static final String IMAGE = "mariadb:12.3";

  public static final String DATABASE = "queryhub_it";
  public static final String USERNAME = "queryhub_it";

  /** 앱 사용자와 root 의 비밀번호(Testcontainers 가 둘을 같게 둔다). */
  public static final String PASSWORD = "secret-marker-it-5e2a90";

  private static final int MARIADB_PORT = 3306;

  /** Docker 가 응답하지 않을 때 기다리는 최대 시간. Gradle 태스크 제한(10 분)보다 짧다. */
  private static final int STARTUP_TIMEOUT_SECONDS = 180;

  private static @Nullable MariaDBContainer container;

  private MetadataMariaDb() {}

  /** 컨테이너를 (처음이면 띄워서) 돌려준다. */
  public static synchronized MariaDBContainer started() {
    MariaDBContainer current = container;
    if (current == null) {
      current =
          new MariaDBContainer(DockerImageName.parse(IMAGE))
              .withDatabaseName(DATABASE)
              .withUsername(USERNAME)
              .withPassword(PASSWORD)
              .withCommand(
                  "--character-set-server=latin1",
                  "--collation-server=latin1_swedish_ci",
                  "--default-time-zone=+09:00")
              .withStartupTimeoutSeconds(STARTUP_TIMEOUT_SECONDS);
      current.start();
      container = current;
    }
    return current;
  }

  public static String host() {
    return started().getHost();
  }

  public static int port() {
    return started().getMappedPort(MARIADB_PORT);
  }

  /** Spring 테스트 컨텍스트에 접속 정보를 넣는다. 값은 컨텍스트가 처음 읽을 때 계산된다. */
  public static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("queryhub.metadata.db.host", MetadataMariaDb::host);
    registry.add("queryhub.metadata.db.port", MetadataMariaDb::port);
    registry.add("queryhub.metadata.db.name", () -> DATABASE);
    registry.add("queryhub.metadata.db.username", () -> USERNAME);
    registry.add("queryhub.metadata.db.password", () -> PASSWORD);
  }

  /**
   * SpringApplicationBuilder 로 직접 띄우는 컨텍스트용 접속 속성(기본 속성으로 넣는다). 명령행 인자와 시스템 속성은 런타임이 비밀번호를 거부하는
   * 곳이므로 쓰지 않는다.
   */
  public static Map<String, Object> applicationProperties(String database, String password) {
    Map<String, Object> properties = new HashMap<>();
    properties.put("queryhub.metadata.db.host", host());
    properties.put("queryhub.metadata.db.port", String.valueOf(port()));
    properties.put("queryhub.metadata.db.name", database);
    properties.put("queryhub.metadata.db.username", USERNAME);
    properties.put("queryhub.metadata.db.password", password);
    return properties;
  }

  /** root 로 서버에 직접 연결한다(데이터베이스 미선택). 테스트가 새 데이터베이스를 만들거나 서버 설정을 볼 때 쓴다. */
  public static Connection rootConnection() throws SQLException {
    return DriverManager.getConnection(
        "jdbc:mariadb://" + host() + ":" + port() + "/?allowLocalInfile=false", "root", PASSWORD);
  }

  /** 앱 사용자로 주어진 데이터베이스에 직접 연결한다(런타임의 풀을 거치지 않는 확인용). */
  public static Connection userConnection(String database) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:mariadb://" + host() + ":" + port() + "/" + database + "?allowLocalInfile=false",
        USERNAME,
        PASSWORD);
  }

  /** 빈 데이터베이스를 새로 만들고 앱 사용자에게 모든 권한을 준다. */
  public static void createEmptyDatabase(String database) throws SQLException {
    if (!database.matches("[a-z0-9_]{1,64}")) {
      throw new IllegalArgumentException("데이터베이스 이름: " + database);
    }
    try (Connection connection = rootConnection();
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + database);
      statement.execute("GRANT ALL PRIVILEGES ON " + database + ".* TO '" + USERNAME + "'@'%'");
    }
  }
}
