package com.queryhub.app.it.support;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 통합 테스트 도우미: 실제 SQLite 파일, 권한, Flyway 이력. */
public final class IntegrationSqlite {

  private static boolean nativeLoaded;

  private IntegrationSqlite() {}

  /**
   * 이 테스트 JVM 에서 sqlite-jdbc 네이티브 라이브러리를 한 번 불러 둔다(기본 위치). sqlite-jdbc 는 JVM 마다 한 번만 풀고 불러오므로, 앞선
   * 테스트가 org.sqlite.tmpdir 를 지워진 임시 디렉터리로 두었더라도 뒤의 테스트가 실패하지 않게 한다. 추출 위치는 실행 JAR 자식
   * 프로세스(BootJarIntegrationTest)가 확인한다.
   */
  public static synchronized void loadNativeOnce() throws SQLException {
    if (!nativeLoaded) {
      System.clearProperty("org.sqlite.tmpdir");
      DriverManager.getConnection("jdbc:sqlite::memory:").close();
      nativeLoaded = true;
    }
  }

  public static Connection open(Path db) throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + db);
  }

  /** flyway_schema_history 의 (version, success) 목록. */
  public static List<Map<String, Object>> flywayHistory(Path db) throws SQLException {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Connection connection = open(db);
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
      while (rs.next()) {
        rows.add(Map.of("version", rs.getString(1), "success", rs.getLong(2)));
      }
    }
    return rows;
  }

  /** 링크를 따라가지 않은 권한 문자열 (예: rwx------). */
  public static String mode(Path path) throws IOException {
    return PosixFilePermissions.toString(
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
  }

  public static void chmod(Path path, String mode) throws IOException {
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
  }

  public static Object fileKey(Path file) throws IOException {
    return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
        .fileKey();
  }

  /** 디렉터리 아래(하위 포함)에서 이름에 sqlitejdbc 가 들어간 파일(풀린 네이티브 라이브러리와 그 .lck). */
  public static List<Path> nativeLibraryFiles(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(path -> path.getFileName().toString().contains("sqlitejdbc")).toList();
    }
  }

  /** 여러 페이지를 쓴 SQLite 파일을 만들고 2-4 페이지를 덮어써 망가뜨린다. 열리기는 하지만 quick_check 가 ok 가 아니다. */
  public static void writeDamagedDatabase(Path db) throws IOException, SQLException {
    try (Connection connection = open(db);
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE filler (v TEXT NOT NULL)");
      try (PreparedStatement insert =
          connection.prepareStatement("INSERT INTO filler (v) VALUES (?)")) {
        for (int i = 0; i < 64; i++) {
          insert.setString(1, ("row-" + i + "-").repeat(60));
          insert.executeUpdate();
        }
      }
    }
    int pageSize = 4096;
    try (FileChannel channel = FileChannel.open(db, StandardOpenOption.WRITE)) {
      byte[] garbage = new byte[pageSize * 3];
      Arrays.fill(garbage, (byte) 0x5A);
      channel.write(ByteBuffer.wrap(garbage), pageSize);
    }
  }

  /**
   * 살아 있는 WAL 이 있는 손상된 저장소(queryhub.db, queryhub.db-wal, queryhub.db-shm, 0600)를 {@code dataDir} 에
   * 놓는다. {@code scratch} 에서 WAL 모드 DB 를 만들어 'filler' 를 체크포인트로 DB 파일에 옮기고, 자동 체크포인트를 끈 채 'recent' 를
   * 쓴 뒤 연결이 열린 동안 복사한다. 그다음 DB 파일의 3-5 페이지('filler', WAL 에 없다)를 덮어쓴다(P1-06 리뷰 R1).
   */
  public static void writeDamagedStoreWithLiveWal(Path scratch, Path dataDir)
      throws IOException, SQLException {
    Files.createDirectories(scratch);
    Path source = scratch.resolve("queryhub.db");
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + source + "?journal_mode=WAL");
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE filler (v TEXT NOT NULL)");
      try (PreparedStatement insert =
          connection.prepareStatement("INSERT INTO filler (v) VALUES (?)")) {
        for (int i = 0; i < 200; i++) {
          insert.setString(1, ("row-" + i + "-").repeat(60));
          insert.executeUpdate();
        }
      }
      statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
      statement.execute("PRAGMA wal_autocheckpoint=0");
      statement.execute("CREATE TABLE recent (v TEXT NOT NULL)");
      statement.execute("INSERT INTO recent (v) VALUES ('only in the wal')");
      for (String suffix : List.of("", "-wal", "-shm")) {
        Path copy = dataDir.resolve("queryhub.db" + suffix);
        Files.copy(scratch.resolve("queryhub.db" + suffix), copy);
        chmod(copy, "rw-------");
      }
    }
    int pageSize = 4096;
    try (FileChannel channel =
        FileChannel.open(dataDir.resolve("queryhub.db"), StandardOpenOption.WRITE)) {
      byte[] garbage = new byte[pageSize * 3];
      Arrays.fill(garbage, (byte) 0x5A);
      channel.write(ByteBuffer.wrap(garbage), pageSize * 2L);
    }
  }

  public static void writeNotSqlite(Path file) throws IOException {
    Files.writeString(file, "this is a plain text file, not a database\n", StandardCharsets.UTF_8);
  }
}
