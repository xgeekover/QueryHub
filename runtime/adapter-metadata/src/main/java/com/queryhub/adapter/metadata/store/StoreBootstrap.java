package com.queryhub.adapter.metadata.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * Metadata 저장소(SQLite)를 연다 (F3-F9). JDBC 연결 풀과 Flyway 보다 먼저, 웹 서버가 포트를 열기 전에 돈다(app 의
 * JdbcConnectionDetails 빈을 만들 때).
 *
 * <ol>
 *   <li>데이터 디렉터리나 네이티브 디렉터리가 Windows UNC 경로(네트워크 공유)이거나, 그 경로에서 가장 가까운 있는 조상의 실제 경로가 UNC 경로이면(공유를
 *       가리키는 정션·심볼릭 링크) 아무것도 만들기 전에 거부한다({@link NetworkFileSystems}).
 *   <li>데이터 디렉터리: 없으면 0700 으로 만들고, 있으면 비공개 디렉터리 규칙({@link PrivatePaths})을 확인한다.
 *   <li>알려진 네트워크 파일 시스템이면 거부한다({@link NetworkFileSystems}).
 *   <li>{@code queryhub.lock} 배타 잠금({@link StoreLock}).
 *   <li>네이티브 디렉터리를 준비하고 JVM 시스템 속성 {@value #SQLITE_TMPDIR_PROPERTY} 를 그 디렉터리로 정한다. sqlite-jdbc 는
 *       java.io.tmpdir 대신 여기에 네이티브 라이브러리를 푼다.
 *   <li>DB 파일: 있으면 파일 규칙을 확인한다. -wal, -shm, -journal 도 있으면 같은 규칙이다. DB 파일이 없거나 비었는데 데이터가 든 -wal 이나
 *       -journal 이 남아 있으면 거부한다(SQLite 는 빈 DB 옆의 WAL 을 읽기 전용 연결에서도 지운다). 그다음 없으면 런타임이 0600 빈 파일로
 *       만든다(CREATE_NEW, SQLite 가 umask 로 만들지 않는다). 비어 있지 않은데 SQLite 머리글({@code "SQLite format
 *       3\0"})이 없으면 열지 않고 거부한다. 여기까지는 파일을 읽기만 한다.
 *   <li>내용 검사 연결({@link #checkContent}, <b>읽기 전용</b>): 네이티브 라이브러리를 불러오고 {@code PRAGMA quick_check}
 *       가 ok 인지 본다. 읽기 전용이라 닫을 때 WAL 을 DB 파일로 옮기지(체크포인트) 않고, -wal·-shm 을 지우지 않고, 롤백 저널을 재생하지 않는다.
 *       되돌려야 하는 롤백 저널이 있으면 거부한다. 그때 저널 머리글(28 바이트)을 읽기만 해서 원래 DB 크기가 0 페이지(커밋한 데이터가 없는 새 저장소)인지 알려
 *       준다.
 *   <li>부트스트랩 연결(런타임 URL, 읽기·쓰기, DB 파일을 만들지 않는 {@code open_mode=2}): 내용 검사를 통과한 파일만 연다. 새 DB 나 롤백
 *       저널 모드 DB 는 이때 SQLite 가 롤백 저널을 한 번 써서 WAL 로 바꾼다. PRAGMA 값을 확인한다({@link StorePragmas}).
 * </ol>
 *
 * <p>거부한 DB 파일, -wal, -journal 은 바꾸거나, 자르거나, 이름을 바꾸거나, 지우지 않는다. -shm 은 데이터가 없는 WAL 색인이라 SQLite 가 읽기
 * 전용 연결에서도 만들거나 고쳐 쓴다(DB 파일과 같은 0600). WAL 모드 파일에 -wal 이 없으면 SQLite 가 빈 -wal 을 만든다. 잠금을 잡은 뒤 실패하면
 * 잠금을 푼다. 메시지는 비밀 값을 담지 않는다.
 *
 * <p>드라이버 클래스는 이름으로만 불러온다(ArchUnit R6: 운영 코드는 org.sqlite 를 참조하지 않는다).
 */
public final class StoreBootstrap {

  /** sqlite-jdbc 가 네이티브 라이브러리를 푸는 디렉터리를 정하는 JVM 시스템 속성. */
  public static final String SQLITE_TMPDIR_PROPERTY = "org.sqlite.tmpdir";

  static final String DRIVER_CLASS = "org.sqlite.JDBC";

  static final byte[] SQLITE_HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

  static final String DATA_DIR_ROLE = "데이터 디렉터리(" + StoreLayout.ENV_DATA_DIR + ")";

  static final String NATIVE_DIR_ROLE =
      "SQLite 네이티브 라이브러리 디렉터리(" + StoreLayout.ENV_NATIVE_DIR + ")";

  private static final String DB_ROLE = "Metadata 저장소 DB 파일";

  /**
   * sqlite-jdbc 의 연결 속성 이름과 값: 열기 플래그 SQLITE_OPEN_READONLY(0x1). 드라이버가 SQLITE_OPEN_URI 를 더한다.
   * SQLITE_OPEN_CREATE 가 없으므로 DB 파일을 만들지도 않는다. org.sqlite 클래스를 참조하지 않으려고 이름과 값을 문자열로 준다.
   */
  static final String OPEN_MODE_PROPERTY = "open_mode";

  static final String OPEN_READ_ONLY = "1";

  /** SQLite 결과 코드(기본 코드). */
  private static final int SQLITE_CORRUPT = 11;

  private static final int SQLITE_NOTADB = 26;

  /** 롤백 저널 머리글의 매직 바이트 (SQLite 파일 형식, 저널 머리글 0-7 바이트). */
  static final byte[] JOURNAL_MAGIC = {
    (byte) 0xd9,
    (byte) 0xd5,
    (byte) 0x05,
    (byte) 0xf9,
    (byte) 0x20,
    (byte) 0xa1,
    (byte) 0x63,
    (byte) 0xd7
  };

  /** 롤백 저널 머리글의 길이: 매직 8, 페이지 기록 수 4, 체크섬 난수 4, 원래 DB 크기(페이지) 4, 섹터 크기 4, 페이지 크기 4. */
  static final int JOURNAL_HEADER_LENGTH = 28;

  private static final int JOURNAL_INITIAL_PAGES_OFFSET = 16;

  private static final String JOURNAL_ROLE = "Metadata 저장소 롤백 저널";

  private StoreBootstrap() {}

  /**
   * 저장소를 검사하고 연다.
   *
   * @throws MetadataStoreException 어느 단계든 문제가 있을 때 (기동 거부)
   */
  public static OpenedMetadataStore open(StoreLayout layout) {
    return open(layout, NetworkFileSystems.REAL_ROOTS);
  }

  /** {@link #open(StoreLayout)} 와 같고 실제 경로의 뿌리를 {@code realRoots} 로 읽는다(시험이 UNC 뿌리를 넣는다). */
  static OpenedMetadataStore open(StoreLayout layout, NetworkFileSystems.RealRoots realRoots) {
    NetworkFileSystems.refuseUncPath(layout.dataDir(), DATA_DIR_ROLE);
    NetworkFileSystems.refuseUncPath(layout.nativeDir(), NATIVE_DIR_ROLE);
    NetworkFileSystems.refuseUncRealPath(layout.dataDir(), DATA_DIR_ROLE, realRoots);
    NetworkFileSystems.refuseUncRealPath(layout.nativeDir(), NATIVE_DIR_ROLE, realRoots);
    PrivatePaths.prepareDirectory(layout.dataDir(), DATA_DIR_ROLE);
    NetworkFileSystems.requireLocal(layout.dataDir(), DATA_DIR_ROLE);
    StoreLock lock = StoreLock.acquire(layout);
    try {
      prepareNativeDirectory(layout);
      prepareDatabaseFile(layout);
      loadDriver();
      checkContent(layout);
      verifyBootstrapConnection(layout);
      return new OpenedMetadataStore(layout, lock);
    } catch (RuntimeException | Error failure) {
      try {
        lock.close();
      } catch (IOException suppressed) {
        failure.addSuppressed(suppressed);
      }
      throw failure;
    }
  }

  static void prepareNativeDirectory(StoreLayout layout) {
    PrivatePaths.prepareDirectory(layout.nativeDir(), NATIVE_DIR_ROLE);
    System.setProperty(SQLITE_TMPDIR_PROPERTY, layout.nativeDir().toString());
  }

  static void prepareDatabaseFile(StoreLayout layout) {
    Path db = layout.dbFile();
    boolean exists = PrivatePaths.checkExistingFile(db, DB_ROLE);
    PrivatePaths.checkExistingFile(layout.walFile(), "Metadata 저장소 WAL 파일");
    PrivatePaths.checkExistingFile(layout.shmFile(), "Metadata 저장소 공유 메모리 파일");
    PrivatePaths.checkExistingFile(layout.journalFile(), JOURNAL_ROLE);
    if (!exists || size(db) == 0) {
      refuseLeftoverData(layout, exists, layout.walFile());
      refuseLeftoverData(layout, exists, layout.journalFile());
    }
    if (!exists) {
      PrivatePaths.createPrivateFile(db, DB_ROLE);
      PrivatePaths.checkExistingFile(db, DB_ROLE);
    }
    if (!hasSqliteHeaderOrIsEmpty(db)) {
      throw new MetadataStoreException(
          DB_ROLE
              + "("
              + db
              + ")가 SQLite DB 파일이 아니다 (파일 머리글이 'SQLite format 3' 이 아니다). 런타임을 시작하지 않으며 파일을 고치거나 지우지 않는다."
              + " 다른 파일이 그 자리에 있으면 옮기고, 백업에서 복원하려면 docs/DEPLOYMENT.md 의 백업·복구 절차를 따르라.");
    }
  }

  /**
   * DB 파일이 없거나 비었는데 {@code leftover}(-wal 또는 -journal)에 데이터가 있으면 거부한다. SQLite 는 빈 DB 옆의 WAL 을 이전 DB
   * 의 찌꺼기로 보고 지운다(읽기 전용 연결도). 그 WAL 에 저장소의 데이터가 있을 수 있다.
   */
  private static void refuseLeftoverData(StoreLayout layout, boolean dbExists, Path leftover) {
    if (size(leftover) == 0) {
      return;
    }
    throw new MetadataStoreException(
        DB_ROLE
            + "("
            + layout.dbFile()
            + ")가 "
            + (dbExists ? "비어 있는데" : "없는데")
            + " "
            + leftover
            + " 에 데이터가 남아 있어 런타임을 시작하지 않으며 파일을 고치거나 지우지 않는다. SQLite 는 빈 DB 옆의 이 파일을 지운다."
            + " DB 파일을 옮기거나 지웠다면 그 파일과 함께 되돌려 놓거나, 런타임을 멈춘 채 백업에서 DB 파일과 마스터 키를 함께 복원하라"
            + " (docs/DEPLOYMENT.md 의 백업·복구). 그 데이터가 필요 없으면 이 파일을 다른 곳으로 옮긴 뒤 다시 시작하라.");
  }

  /** 링크를 따라가지 않은 파일 크기. 없으면 0 이다. */
  private static long size(Path file) {
    try {
      return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
          .size();
    } catch (NoSuchFileException absent) {
      return 0;
    } catch (IOException failure) {
      throw new MetadataStoreException(
          file + " 를 확인할 수 없어 런타임을 시작하지 않는다: " + failure + ". 데이터 디렉터리의 권한을 확인하라.", failure);
    }
  }

  /** 빈 파일(새 저장소)이거나 첫 16 바이트가 SQLite 머리글이면 {@code true}. 파일을 링크를 따라가지 않고 읽기만 한다. */
  static boolean hasSqliteHeaderOrIsEmpty(Path db) {
    byte[] head;
    try (InputStream in = Files.newInputStream(db, LinkOption.NOFOLLOW_LINKS)) {
      head = in.readNBytes(SQLITE_HEADER.length);
    } catch (IOException failure) {
      throw new MetadataStoreException(
          DB_ROLE + "(" + db + ")를 읽을 수 없어 런타임을 시작하지 않는다: " + failure, failure);
    }
    return head.length == 0 || Arrays.equals(head, SQLITE_HEADER);
  }

  static void loadDriver() {
    try {
      Class.forName(DRIVER_CLASS, true, StoreBootstrap.class.getClassLoader());
    } catch (ClassNotFoundException | LinkageError missing) {
      throw new MetadataStoreException(
          "SQLite JDBC 드라이버(" + DRIVER_CLASS + ")가 클래스패스에 없어 런타임을 시작하지 않는다. 실행 JAR 을 다시 빌드하라.",
          missing);
    }
  }

  /**
   * 내용 검사: 읽기 전용 연결({@link #readOnlyProperties()})로 {@code PRAGMA quick_check} 를 본다. 읽기·쓰기 연결이면 닫을
   * 때 SQLite 가 WAL 을 DB 파일로 옮기고 -wal·-shm 을 지우며, 열 때 롤백 저널을 재생한다. 거부할 파일이 그렇게 바뀌면 안 된다(P1-06 리뷰
   * R1). 읽기 전용 연결은 WAL 을 읽어 런타임이 볼 내용을 그대로 검사한다.
   */
  static void checkContent(StoreLayout layout) {
    Path db = layout.dbFile();
    List<String> problems = new ArrayList<>();
    try (Connection connection =
            DriverManager.getConnection(layout.plainJdbcUrl(), readOnlyProperties());
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("PRAGMA quick_check")) {
      while (rs.next() && problems.size() < 5) {
        problems.add(String.valueOf(rs.getString(1)));
      }
    } catch (SQLException | LinkageError failure) {
      throw openFailure(layout, failure);
    }
    if (!problems.equals(List.of("ok"))) {
      throw corrupt(db, String.join("; ", problems), null);
    }
  }

  /** 내용 검사 연결의 JDBC 속성: 읽기 전용, 파일을 만들지 않는다. */
  static Properties readOnlyProperties() {
    Properties properties = new Properties();
    properties.setProperty(OPEN_MODE_PROPERTY, OPEN_READ_ONLY);
    return properties;
  }

  static void verifyBootstrapConnection(StoreLayout layout) {
    try (Connection connection = DriverManager.getConnection(layout.jdbcUrl())) {
      StorePragmas.verify(connection, "부트스트랩 연결");
    } catch (SQLException | LinkageError failure) {
      throw openFailure(layout, failure);
    }
  }

  private static MetadataStoreException openFailure(StoreLayout layout, Throwable failure) {
    Optional<String> nativeProblem = NativeLibraryFailures.describe(failure, layout.nativeDir());
    if (nativeProblem.isPresent()) {
      return new MetadataStoreException(nativeProblem.get(), failure);
    }
    if (failure instanceof SQLException sql && isCorruption(sql)) {
      return corrupt(layout.dbFile(), String.valueOf(sql.getMessage()), sql);
    }
    if (failure instanceof SQLException sql && needsRollback(sql)) {
      return new MetadataStoreException(hotJournalMessage(layout), sql);
    }
    return new MetadataStoreException(
        DB_ROLE
            + "("
            + layout.dbFile()
            + ")를 열 수 없어 런타임을 시작하지 않는다: "
            + failure.getMessage()
            + ". 데이터 디렉터리와 파일의 권한, 디스크 공간을 확인하라.",
        failure);
  }

  private static boolean isCorruption(SQLException failure) {
    int primary = failure.getErrorCode() & 0xff;
    String message = String.valueOf(failure.getMessage());
    return primary == SQLITE_CORRUPT
        || primary == SQLITE_NOTADB
        || message.contains("SQLITE_CORRUPT")
        || message.contains("SQLITE_NOTADB");
  }

  /**
   * 읽기 전용 연결이 되돌려야 하는 롤백 저널(hot journal)을 만났다(확장 코드 SQLITE_READONLY_ROLLBACK). sqlite-jdbc 는 기본 코드
   * (SQLITE_READONLY)만 getErrorCode 로 주므로 메시지의 확장 코드 이름을 본다.
   */
  private static boolean needsRollback(SQLException failure) {
    return String.valueOf(failure.getMessage()).contains("SQLITE_READONLY_ROLLBACK");
  }

  /**
   * 되돌려야 하는 롤백 저널의 거부 메시지. 런타임도 새 DB 나 롤백 저널 모드로 되살린 DB(VACUUM INTO 사본)를 처음 WAL 로 바꿀 때 롤백 저널을 한 번
   * 쓴다(P1-06 리뷰 N1). 저널 머리글의 원래 DB 크기가 0 페이지이면 그 트랜잭션 전에 DB 가 비어 있었으므로 커밋한 데이터가 없는 새 저장소다: 파일을 함께
   * 옮기고 다시 시작하면 된다. 그 밖에는(1 페이지 이상이거나 머리글을 읽지 못하면) 데이터가 있을 수 있다.
   */
  static String hotJournalMessage(StoreLayout layout) {
    Path db = layout.dbFile();
    Path journal = layout.journalFile();
    String refusal =
        DB_ROLE
            + "("
            + db
            + ") 옆에 되돌리지 않은 롤백 저널("
            + journal
            + ")이 있어 런타임을 시작하지 않으며 파일을 고치거나 지우지 않는다. 런타임은 새 DB 나 롤백 저널 모드로 되살린 DB(예: VACUUM INTO 사본)를"
            + " 처음 WAL 로 바꿀 때 롤백 저널을 한 번 쓴다. 그때 런타임이 멈췄거나 다른 도구가 남긴 저널이다. 런타임은 내용을 읽기 전용으로 검사해 저널을"
            + " 재생하지 않는다.";
    OptionalLong initialPages = journalInitialPageCount(journal);
    if (initialPages.isPresent() && initialPages.getAsLong() == 0) {
      return refusal
          + " 저널 머리글의 원래 DB 크기가 0 페이지라서 이 저장소는 커밋한 데이터가 없는 새 저장소다. 런타임을 멈춘 채 "
          + StoreLayout.DB_FILE_NAME
          + " 와 "
          + journal.getFileName()
          + "(있으면 "
          + layout.walFile().getFileName()
          + "·"
          + layout.shmFile().getFileName()
          + " 도)를 함께 데이터 디렉터리("
          + layout.dataDir()
          + ") 밖으로 옮긴 뒤 다시 시작하라. 런타임이 새 저장소를 만든다.";
    }
    String size =
        initialPages.isPresent()
            ? " 저널 머리글의 원래 DB 크기가 " + initialPages.getAsLong() + " 페이지라서 커밋한 데이터가 있을 수 있다."
            : " 저널 머리글이 SQLite 롤백 저널 머리글이 아니라서 커밋한 데이터가 있는지 알 수 없다.";
    return refusal
        + size
        + " 런타임을 멈춘 채 "
        + StoreLayout.DB_FILE_NAME
        + " 와 "
        + journal.getFileName()
        + " 를 함께 다른 곳에 복사해 둔 뒤 sqlite3 \""
        + db
        + "\" \"PRAGMA quick_check\" 로 열면 SQLite 가 저널을 되돌린다. 그다음 다시 시작하라. 또는 런타임을 멈춘 채 백업에서 DB 파일과 마스터 키를"
        + " 함께 복원하라 (docs/DEPLOYMENT.md 의 백업·복구).";
  }

  /**
   * 롤백 저널 머리글의 원래 DB 크기(페이지 수, 16-19 바이트, big-endian). 머리글 28 바이트를 링크를 따라가지 않고 읽기만 한다. 파일이 짧거나, 매직
   * 바이트가 다르거나, 읽을 수 없으면 빈 값이다.
   */
  static OptionalLong journalInitialPageCount(Path journal) {
    byte[] header;
    try (InputStream in = Files.newInputStream(journal, LinkOption.NOFOLLOW_LINKS)) {
      header = in.readNBytes(JOURNAL_HEADER_LENGTH);
    } catch (IOException unreadable) {
      return OptionalLong.empty();
    }
    if (header.length < JOURNAL_HEADER_LENGTH
        || !Arrays.equals(
            header, 0, JOURNAL_MAGIC.length, JOURNAL_MAGIC, 0, JOURNAL_MAGIC.length)) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(
        Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt(JOURNAL_INITIAL_PAGES_OFFSET)));
  }

  private static MetadataStoreException corrupt(Path db, String detail, @Nullable Throwable cause) {
    String message =
        DB_ROLE
            + "("
            + db
            + ")가 손상되었다 (PRAGMA quick_check: "
            + detail
            + "). 런타임을 시작하지 않으며 파일을 고치거나 지우지 않는다. 런타임을 멈춘 채 백업에서 DB 파일과 마스터 키를 함께 복원하라"
            + " (docs/DEPLOYMENT.md 의 백업·복구).";
    return cause == null
        ? new MetadataStoreException(message)
        : new MetadataStoreException(message, cause);
  }
}
