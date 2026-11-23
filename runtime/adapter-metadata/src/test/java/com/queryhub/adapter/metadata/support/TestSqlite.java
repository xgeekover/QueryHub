package com.queryhub.adapter.metadata.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/** 테스트 도우미: 실제 SQLite 파일과 권한. */
public final class TestSqlite {

  private static boolean nativeLoaded;

  private TestSqlite() {}

  /**
   * 이 테스트 JVM 에서 sqlite-jdbc 네이티브 라이브러리를 한 번 불러 둔다. sqlite-jdbc 는 JVM 마다 한 번만 풀고 불러오므로, 앞선 테스트가
   * org.sqlite.tmpdir 를 이미 지워진 임시 디렉터리로 두었더라도 뒤의 테스트가 그 디렉터리로 풀려다 실패하지 않게 한다. 이때는 기본 위치
   * (java.io.tmpdir)에 푼다. 추출 위치 검사는 새 JVM(실행 JAR 자식 프로세스)이 한다.
   */
  public static synchronized void loadNativeOnce() throws SQLException {
    if (!nativeLoaded) {
      System.clearProperty("org.sqlite.tmpdir");
      DriverManager.getConnection("jdbc:sqlite::memory:").close();
      nativeLoaded = true;
    }
  }

  /** 링크를 따라가지 않은 권한 문자열 (예: rwx------). */
  public static String mode(Path path) throws IOException {
    return PosixFilePermissions.toString(
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
  }

  public static void chmod(Path path, String mode) throws IOException {
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
  }

  public static boolean runningAsRoot() {
    return "root".equals(System.getProperty("user.name"));
  }

  /**
   * 여러 페이지를 쓴 SQLite 파일(journal_mode=DELETE)을 만들고 2-4 페이지(테이블 b-tree)를 덮어써 망가뜨린다. 1 페이지(머리글과 스키마)는
   * 그대로라서 파일은 열리지만 PRAGMA quick_check 는 ok 가 아니다.
   */
  public static void writeDamagedDatabase(Path db) throws IOException, SQLException {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
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
      java.util.Arrays.fill(garbage, (byte) 0x5A);
      channel.write(java.nio.ByteBuffer.wrap(garbage), pageSize);
    }
  }

  /** 'recent' 테이블에 넣는 행 수 ({@link #writeStoreWithLiveWal}). 이 행들은 WAL 에만 있다. */
  public static final int RECENT_ROWS = 20;

  /**
   * 살아 있는 WAL 이 있는 저장소를 {@code dataDir} 에 놓는다(queryhub.db, queryhub.db-wal, 고르면 queryhub.db-shm, 모두
   * 0600). 런타임이 끝나지 않은 채(크래시, 전원 끊김) 남은 모습이다.
   *
   * <p>{@code scratch} 에서 WAL 모드 DB 를 만든다: 'filler' 테이블을 쓰고 체크포인트해 DB 파일로 옮긴 뒤, 자동 체크포인트를 끄고
   * 'recent' 테이블({@value #RECENT_ROWS} 행)을 쓴다. 그 연결이 열려 있는 동안 파일을 복사하므로 'recent' 와 스키마 변경은 WAL 에만
   * 있다. {@code damageMainFile} 이면 복사한 DB 파일의 3-5 페이지('filler' 의 페이지, WAL 에 없다)를 덮어써 quick_check 가
   * ok 가 아니게 한다.
   */
  public static void writeStoreWithLiveWal(
      Path scratch, Path dataDir, boolean withShm, boolean damageMainFile)
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
      for (int i = 0; i < RECENT_ROWS; i++) {
        statement.execute("INSERT INTO recent (v) VALUES ('r" + i + "')");
      }
      copyPrivate(source, dataDir.resolve("queryhub.db"));
      copyPrivate(scratch.resolve("queryhub.db-wal"), dataDir.resolve("queryhub.db-wal"));
      if (withShm) {
        copyPrivate(scratch.resolve("queryhub.db-shm"), dataDir.resolve("queryhub.db-shm"));
      }
    }
    if (damageMainFile) {
      overwritePages(dataDir.resolve("queryhub.db"), 2, 3);
    }
  }

  /** 행이 몇 개 있는 건강한 SQLite 파일(journal_mode=DELETE). */
  public static void writeHealthyDatabase(Path db) throws SQLException {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE probe (v TEXT NOT NULL)");
      statement.execute("INSERT INTO probe (v) VALUES ('a'), ('b')");
    }
  }

  /** 파일 내용의 SHA-256 (16진수). 링크를 따라가지 않는다. */
  public static String sha256(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void copyPrivate(Path from, Path to) throws IOException {
    Files.copy(from, to);
    chmod(to, "rw-------");
  }

  private static void overwritePages(Path db, int firstPageIndex, int pages) throws IOException {
    int pageSize = 4096;
    try (FileChannel channel = FileChannel.open(db, StandardOpenOption.WRITE)) {
      byte[] garbage = new byte[pageSize * pages];
      java.util.Arrays.fill(garbage, (byte) 0x5A);
      channel.write(java.nio.ByteBuffer.wrap(garbage), (long) pageSize * firstPageIndex);
    }
  }

  /**
   * 다른 프로세스(새 JVM, {@link LockProbe})가 잠금 파일을 잠글 수 있으면 {@code true}. 같은 JVM 의 검사({@link
   * #lockIsFree})는 OS 잠금이 풀렸는지 볼 수 없다: Java 는 JVM 안의 잠금 표를 따로 두기 때문이다.
   */
  public static boolean lockIsFreeInAnotherProcess(Path lockFile) throws Exception {
    Path classes =
        Path.of(LockProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    Process process =
        new ProcessBuilder(
                java.toString(),
                "-cp",
                classes.toString(),
                LockProbe.class.getName(),
                lockFile.toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("LockProbe 가 60 초 안에 끝나지 않았다");
    }
    return switch (process.exitValue()) {
      case LockProbe.FREE -> true;
      case LockProbe.HELD -> false;
      default ->
          throw new IllegalStateException(
              "LockProbe 실패 (exit " + process.exitValue() + "): " + output);
    };
  }

  /** SQLite 페이지 크기(기본값). */
  public static final int PAGE_SIZE = 4096;

  /**
   * 새 저장소를 처음 WAL 로 바꾸다 멈춘 모습의 DB 파일(P1-06 리뷰 N1): SQLite 가 WAL 로 바꾼 1 페이지(4096 바이트, 올바른 SQLite
   * 머리글)만 있는 파일을 {@code scratch} 에서 만들어 {@code db} 로 복사한다(0600).
   */
  public static void writeOnePageWalModeDatabase(Path scratch, Path db)
      throws IOException, SQLException {
    Files.createDirectories(scratch);
    Path source = scratch.resolve("one-page.db");
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + source + "?journal_mode=WAL");
        Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA user_version");
    }
    copyPrivate(source, db);
  }

  /**
   * 실제 롤백 저널 머리글을 단 512 바이트 저널을 쓴다(0600): 매직 d9 d5 05 f9 20 a1 63 d7, 페이지 기록 수 0, 체크섬 난수, 원래 DB 크기
   * {@code initialPages} 페이지, 섹터 크기 512, 페이지 크기 4096(모두 big-endian). 나머지는 0 이다.
   */
  public static void writeRollbackJournal(Path journal, int initialPages) throws IOException {
    java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(512);
    header.put(
        new byte[] {
          (byte) 0xd9, (byte) 0xd5, (byte) 0x05, (byte) 0xf9,
          (byte) 0x20, (byte) 0xa1, (byte) 0x63, (byte) 0xd7
        });
    header.putInt(0);
    header.putInt(0x2c5e7a13);
    header.putInt(initialPages);
    header.putInt(512);
    header.putInt(PAGE_SIZE);
    Files.write(journal, header.array());
    chmod(journal, "rw-------");
  }

  /** DB 파일 머리글의 18, 19 바이트(파일 형식 쓰기·읽기 판): 롤백 저널 모드는 1, 1, WAL 모드는 2, 2 다. 링크를 따라가지 않는다. */
  public static byte[] formatVersionBytes(Path db) throws IOException {
    try (InputStream in = Files.newInputStream(db, LinkOption.NOFOLLOW_LINKS)) {
      byte[] head = in.readNBytes(20);
      return new byte[] {head[18], head[19]};
    }
  }

  /** SQLite 가 아닌 내용의 파일. */
  public static void writeNotSqlite(Path file) throws IOException {
    try (OutputStream out = Files.newOutputStream(file)) {
      out.write(
          "this is a plain text file, not a database\n"
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  /** 다른 잠금이 없으면 잠갔다가 바로 풀고 {@code true} 를 돌려준다. 같은 JVM 의 잠금이 남아 있으면 예외가 난다. */
  public static boolean lockIsFree(Path lockFile) throws IOException {
    try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
      FileLock lock = channel.tryLock();
      if (lock == null) {
        return false;
      }
      lock.release();
      return true;
    }
  }
}
