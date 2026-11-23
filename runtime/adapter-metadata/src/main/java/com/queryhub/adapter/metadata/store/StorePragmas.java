package com.queryhub.adapter.metadata.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 런타임이 정하는 SQLite 연결 설정과 그 확인.
 *
 * <ul>
 *   <li>journal_mode=WAL: 읽기와 쓰기 하나가 함께 돈다.
 *   <li>synchronous=FULL(2): 전원이 꺼져도 커밋한 쓰기를 잃지 않는다(메타데이터 쓰기는 드물고, 암호화한 비밀번호를 담게 된다).
 *   <li>foreign_keys=ON(1).
 *   <li>busy_timeout=5000: 다른 연결이 쓰기 잠금을 쥐고 있으면 최대 5 초 기다린 뒤 실패한다.
 *   <li>transaction_mode=IMMEDIATE (드라이버 설정): 트랜잭션은 처음부터 쓰기 잠금을 잡아, 읽다가 쓰기로 올리는 교착(SQLITE_BUSY)이
 *       없다.
 * </ul>
 *
 * <p>설정은 JDBC URL 에 있다({@link StoreLayout#URL_OPTIONS}). 기동 검사는 부트스트랩 연결과 풀 연결에서 실제 값을 다시 읽는다. 그래서
 * spring.datasource.hikari.connection-init-sql 같은 다른 길로 값이 바뀌어도 기동이 멈춘다. 풀 연결은 연 파일도 확인한다({@link
 * #verifyMainFile}).
 */
public final class StorePragmas {

  public static final int BUSY_TIMEOUT_MILLIS = 5000;

  /**
   * 런타임이 정하는 설정 이름. 이 이름을 spring.datasource.hikari.data-source-properties.* 로 넘기면 기동을 거부한다 (app 의
   * 기동 검사).
   */
  public static final List<String> MANAGED_KEYS =
      List.of("journal_mode", "synchronous", "foreign_keys", "busy_timeout", "transaction_mode");

  private StorePragmas() {}

  /** 실제 연결의 PRAGMA 값이 런타임 값과 다른 항목을 돌려준다. 같으면 빈 목록이다. */
  public static List<String> mismatches(Connection connection) throws SQLException {
    List<String> mismatches = new ArrayList<>();
    String journalMode = single(connection, "PRAGMA journal_mode");
    if (!"wal".equals(journalMode.toLowerCase(Locale.ROOT))) {
      mismatches.add("journal_mode=" + journalMode + " (기대 wal)");
    }
    String foreignKeys = single(connection, "PRAGMA foreign_keys");
    if (!"1".equals(foreignKeys)) {
      mismatches.add("foreign_keys=" + foreignKeys + " (기대 1)");
    }
    String synchronous = single(connection, "PRAGMA synchronous");
    if (!"2".equals(synchronous)) {
      mismatches.add("synchronous=" + synchronous + " (기대 2, FULL)");
    }
    String busyTimeout = single(connection, "PRAGMA busy_timeout");
    if (!String.valueOf(BUSY_TIMEOUT_MILLIS).equals(busyTimeout)) {
      mismatches.add("busy_timeout=" + busyTimeout + " (기대 " + BUSY_TIMEOUT_MILLIS + ")");
    }
    return mismatches;
  }

  /**
   * 연결의 PRAGMA 값을 확인한다.
   *
   * @param where 메시지에 쓰는 연결 이름 (예: "부트스트랩 연결", "풀 연결 2/4")
   * @throws MetadataStoreException 값이 다를 때
   */
  public static void verify(Connection connection, String where) throws SQLException {
    List<String> mismatches = mismatches(connection);
    if (!mismatches.isEmpty()) {
      throw new MetadataStoreException(
          "Metadata 저장소 "
              + where
              + "의 SQLite 설정이 런타임이 정한 값과 달라 런타임을 시작하지 않는다: "
              + String.join(", ", mismatches)
              + ". 이 값은 런타임이 정한다. spring.datasource.hikari.data-source-properties.* 나"
              + " spring.datasource.hikari.connection-init-sql 로 바꾸지 말고 그 설정을 지워라.");
    }
  }

  /**
   * 연결이 연 주 DB 파일({@code PRAGMA database_list} 의 main)이 검사한 DB 파일인지 확인한다. 두 경로를 {@link
   * Files#isSameFile} 로 비교한다(unix 는 장치와 inode): 상위 디렉터리의 심볼릭 링크나 다른 표기는 같은 파일로 본다. 그래서
   * spring.datasource.hikari.jdbc-url 같은 다른 길로 풀이 다른 파일(같은 PRAGMA 를 단 URL 이라도)을 열면 기동이 멈춘다.
   *
   * @param where 메시지에 쓰는 연결 이름 (예: "풀 연결 2/4 (Flyway 전)")
   * @throws MetadataStoreException 다른 파일이거나, 메모리·임시 DB 이거나, 확인할 수 없을 때
   */
  public static void verifyMainFile(Connection connection, Path expectedDbFile, String where)
      throws SQLException {
    String file = mainFile(connection);
    if (file.isEmpty() || !sameFile(file, expectedDbFile)) {
      throw new MetadataStoreException(
          "Metadata 저장소 "
              + where
              + "이 검사한 DB 파일("
              + realPathOrAsIs(expectedDbFile)
              + ")이 아닌 "
              + (file.isEmpty() ? "메모리·임시 DB" : realPathOrAsIs(Path.of(file)).toString())
              + " 를 열어 런타임을 시작하지 않는다. 연결 풀의 접속 URL 은 런타임이 데이터 디렉터리로 정한다."
              + " spring.datasource.url, spring.datasource.hikari.jdbc-url 같은 접속 설정을 지워라.");
    }
  }

  /** {@code PRAGMA database_list} 에서 main 의 파일 경로. 메모리·임시 DB 이면 빈 문자열이다. */
  static String mainFile(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("PRAGMA database_list")) {
      while (rs.next()) {
        if ("main".equals(rs.getString("name"))) {
          String file = rs.getString("file");
          return file == null ? "" : file;
        }
      }
    }
    return "";
  }

  private static boolean sameFile(String file, Path expected) {
    try {
      return Files.isSameFile(Path.of(file), expected);
    } catch (IOException | InvalidPathException unknown) {
      return false;
    }
  }

  private static Path realPathOrAsIs(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException | InvalidPathException unknown) {
      return path;
    }
  }

  private static String single(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      return rs.next() ? String.valueOf(rs.getString(1)) : "(없음)";
    }
  }
}
