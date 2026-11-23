package com.queryhub.adapter.metadata.store;

import static com.queryhub.adapter.metadata.support.TestSqlite.chmod;
import static com.queryhub.adapter.metadata.support.TestSqlite.mode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.queryhub.adapter.metadata.support.TestSqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * P1-06 AC6, AC7, AC8, AC9, AC11 (부트스트랩 연결): 실제 파일 시스템과 실제 SQLite 로 {@link StoreBootstrap} 을 확인한다.
 *
 * <p>거부한 경우마다 데이터 디렉터리 아래의 모든 항목(내용, 권한, 링크 대상)이 그대로인지 비교한다. POSIX 파일 시스템(macOS, Linux)에서 돈다.
 */
class StoreBootstrapTest {

  @TempDir private Path tmp;

  @BeforeAll
  static void loadNative() throws SQLException {
    TestSqlite.loadNativeOnce();
  }

  @Test
  void freshStartCreatesPrivateDirectoriesAndAnOwnerOnlyDatabase() throws Exception {
    Path dataDir = tmp.resolve("missing/parent/data");
    StoreLayout layout = StoreLayout.resolve(dataDir.toString(), null);

    try (OpenedMetadataStore store = StoreBootstrap.open(layout)) {
      assertThat(store.isLocked()).isTrue();
      assertThat(mode(dataDir)).isEqualTo("rwx------");
      assertThat(mode(layout.nativeDir())).isEqualTo("rwx------");
      assertThat(mode(layout.dbFile())).isEqualTo("rw-------");
      assertThat(mode(layout.lockFile())).isEqualTo("rw-------");
      assertThat(System.getProperty(StoreBootstrap.SQLITE_TMPDIR_PROPERTY))
          .isEqualTo(layout.nativeDir().toString());

      try (Connection connection = DriverManager.getConnection(store.jdbcUrl());
          Statement statement = connection.createStatement()) {
        statement.execute("CREATE TABLE probe (v TEXT NOT NULL) STRICT");
        statement.execute("INSERT INTO probe (v) VALUES ('x')");
        // 연결이 열려 있는 동안 WAL 과 공유 메모리 파일이 있다. SQLite 는 DB 파일의 권한을 그대로 준다.
        assertThat(mode(layout.walFile())).isEqualTo("rw-------");
        assertThat(mode(layout.shmFile())).isEqualTo("rw-------");
      }
    }
    assertThat(TestSqlite.lockIsFree(layout.lockFile())).as("닫으면 잠금을 푼다").isTrue();
  }

  @Test
  void restartReusesTheSameDatabaseFile() throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);
    Object firstKey;
    try (OpenedMetadataStore store = StoreBootstrap.open(layout);
        Connection connection = DriverManager.getConnection(store.jdbcUrl());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE probe (v TEXT NOT NULL) STRICT");
      statement.execute("INSERT INTO probe (v) VALUES ('first run')");
      firstKey = fileKey(layout.dbFile());
    }

    try (OpenedMetadataStore store = StoreBootstrap.open(layout);
        Connection connection = DriverManager.getConnection(store.jdbcUrl());
        Statement statement = connection.createStatement();
        var rs = statement.executeQuery("SELECT v FROM probe")) {
      assertThat(fileKey(layout.dbFile())).as("같은 파일(inode)").isEqualTo(firstKey);
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("first run");
    }
  }

  @Test
  void secondOpenOnTheSameDataDirectoryIsRefusedUntilTheFirstCloses() throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);

    try (OpenedMetadataStore first = StoreBootstrap.open(layout)) {
      assertThat(TestSqlite.lockIsFreeInAnotherProcess(layout.lockFile()))
          .as("첫 런타임이 OS 잠금을 쥐고 있다")
          .isFalse();
      assertThatThrownBy(() -> StoreBootstrap.open(layout))
          .isInstanceOf(MetadataStoreException.class)
          .hasMessageContaining(layout.dataDir().toString())
          .hasMessageContaining("queryhub.lock")
          .hasMessageContaining("다른 QueryHub 런타임");
      assertThat(first.isLocked()).as("거부된 두 번째 시도가 첫 잠금을 풀지 않는다").isTrue();
      // FileLock.isValid 는 JVM 안의 표만 본다. 다른 프로세스가 아직 잠글 수 없어야 OS 잠금이 남은 것이다(R2).
      assertThat(TestSqlite.lockIsFreeInAnotherProcess(layout.lockFile()))
          .as("같은 JVM 의 거부가 첫 런타임의 OS 잠금을 떨어뜨리지 않는다")
          .isFalse();
    }

    assertThat(TestSqlite.lockIsFreeInAnotherProcess(layout.lockFile())).isTrue();
    try (OpenedMetadataStore again = StoreBootstrap.open(layout)) {
      assertThat(again.isLocked()).isTrue();
    }
  }

  @Test
  void secondOpenThroughAnotherSpellingOfTheSameDirectoryIsRefusedAndKeepsTheOsLock()
      throws Exception {
    Path real = Files.createDirectory(tmp.resolve("real"));
    Path alias = Files.createSymbolicLink(tmp.resolve("alias"), real);
    StoreLayout direct = StoreLayout.resolve(real.resolve("data").toString(), null);
    StoreLayout viaParentLink = StoreLayout.resolve(alias.resolve("data").toString(), null);

    try (OpenedMetadataStore first = StoreBootstrap.open(direct)) {
      assertThatThrownBy(() -> StoreBootstrap.open(viaParentLink))
          .isInstanceOf(MetadataStoreException.class)
          .hasMessageContaining("다른 QueryHub 런타임");
      assertThat(first.isLocked()).isTrue();
      assertThat(TestSqlite.lockIsFreeInAnotherProcess(direct.lockFile()))
          .as("다른 경로 표기로 거부해도 OS 잠금이 남는다")
          .isFalse();
    }
  }

  @Test
  void closingALockTwiceDoesNotReleaseALaterHolder() throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);
    PrivatePaths.prepareDirectory(layout.dataDir(), StoreBootstrap.DATA_DIR_ROLE);

    StoreLock first = StoreLock.acquire(layout);
    first.close();
    try (StoreLock second = StoreLock.acquire(layout)) {
      first.close();
      assertThat(second.isHeld()).isTrue();
      assertThatThrownBy(() -> StoreLock.acquire(layout))
          .as("앞 잠금을 두 번 닫아도 뒤 잠금의 자리는 그대로다")
          .isInstanceOf(MetadataStoreException.class)
          .hasMessageContaining("다른 QueryHub 런타임");
      assertThat(TestSqlite.lockIsFreeInAnotherProcess(layout.lockFile())).isFalse();
    }
    assertThat(TestSqlite.lockIsFreeInAnotherProcess(layout.lockFile())).isTrue();
  }

  /**
   * R1: 살아 있는 WAL 이 있는 손상된 저장소를 거부해도 DB 파일과 WAL 은 바이트 하나 바뀌지 않고, WAL 은 그대로 남는다. 내용 검사는 읽기 전용 연결이라
   * 닫을 때 WAL 을 DB 로 옮기지(체크포인트) 않고 -wal·-shm 을 지우지 않는다. -shm 은 데이터가 없는 색인이라 SQLite 가 읽기 전용 연결에서도
   * 만들거나 고쳐 쓴다(없으면 DB 파일과 같은 0600 으로 만든다).
   */
  @ParameterizedTest(name = "shm 파일 있음={0}")
  @ValueSource(booleans = {true, false})
  void damagedStoreWithALiveWalIsRefusedAndTheDatabaseAndWalStayByteIdentical(boolean withShm)
      throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);
    Refusal.privateDataDir(layout);
    TestSqlite.writeStoreWithLiveWal(tmp.resolve("source"), layout.dataDir(), withShm, true);
    String db = TestSqlite.sha256(layout.dbFile());
    long dbSize = Files.size(layout.dbFile());
    String wal = TestSqlite.sha256(layout.walFile());
    assertThat(Files.size(layout.walFile())).as("WAL 에 프레임이 있다").isPositive();
    assertThat(Files.exists(layout.shmFile())).isEqualTo(withShm);

    assertThatThrownBy(() -> StoreBootstrap.open(layout).close())
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining("손상")
        .hasMessageContaining(layout.dbFile().toString());

    assertThat(Files.size(layout.dbFile())).as("DB 파일 크기").isEqualTo(dbSize);
    assertThat(TestSqlite.sha256(layout.dbFile())).as("DB 파일 내용").isEqualTo(db);
    assertThat(Files.exists(layout.walFile())).as("WAL 이 남아 있다").isTrue();
    assertThat(TestSqlite.sha256(layout.walFile())).as("WAL 내용").isEqualTo(wal);
    if (Files.exists(layout.shmFile())) {
      assertThat(mode(layout.shmFile())).isEqualTo("rw-------");
    }
    assertThat(TestSqlite.lockIsFreeInAnotherProcess(layout.lockFile())).isTrue();
  }

  /** -wal 은 있고 -shm 은 없는 건강한 저장소(예: -shm 없이 복사한 사본)는 열린다. WAL 에만 있던 행이 보이고 -shm 은 0600 이다. */
  @Test
  void healthyStoreWithALiveWalAndNoShmOpensAndSeesTheRowsInTheWal() throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);
    Refusal.privateDataDir(layout);
    TestSqlite.writeStoreWithLiveWal(tmp.resolve("source"), layout.dataDir(), false, false);
    assertThat(Files.exists(layout.shmFile())).isFalse();

    try (OpenedMetadataStore store = StoreBootstrap.open(layout);
        Connection connection = DriverManager.getConnection(store.jdbcUrl());
        Statement statement = connection.createStatement();
        var rs = statement.executeQuery("SELECT count(*) FROM recent")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(TestSqlite.RECENT_ROWS);
      assertThat(mode(layout.shmFile())).isEqualTo("rw-------");
      assertThat(mode(layout.walFile())).isEqualTo("rw-------");
    }
  }

  @Test
  void bootstrapConnectionCarriesTheRuntimePragmasAndTheCheckSeesDeviations() throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);
    try (OpenedMetadataStore store = StoreBootstrap.open(layout)) {
      try (Connection connection = DriverManager.getConnection(store.jdbcUrl())) {
        assertThat(StorePragmas.mismatches(connection)).isEmpty();
      }
      try (Connection weakened =
          DriverManager.getConnection(
              layout.plainJdbcUrl() + "?synchronous=OFF&busy_timeout=100")) {
        assertThat(StorePragmas.mismatches(weakened))
            .as("URL 옵션이 없는 연결은 foreign_keys 도 기본값(0)이다")
            .containsExactly(
                "foreign_keys=0 (기대 1)",
                "synchronous=0 (기대 2, FULL)",
                "busy_timeout=100 (기대 5000)");
        assertThatThrownBy(() -> StorePragmas.verify(weakened, "풀 연결 1/4"))
            .isInstanceOf(MetadataStoreException.class)
            .hasMessageContaining("풀 연결 1/4")
            .hasMessageContaining("synchronous=0")
            .hasMessageContaining("spring.datasource.hikari.data-source-properties")
            .hasMessageContaining("connection-init-sql");
      }
    }
  }

  @Test
  void transactionModeImmediateTakesTheWriteLockWhenTheTransactionBegins() throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);
    try (OpenedMetadataStore store = StoreBootstrap.open(layout);
        Connection runtime = DriverManager.getConnection(store.jdbcUrl());
        Connection other = DriverManager.getConnection(layout.plainJdbcUrl() + "?busy_timeout=0")) {
      runtime.setAutoCommit(false);
      try (Statement read = runtime.createStatement()) {
        read.executeQuery("SELECT 1").close();
      }
      // DEFERRED 였다면 읽기만 한 트랜잭션은 쓰기 잠금이 없어 아래 BEGIN IMMEDIATE 가 성공한다.
      try (Statement statement = other.createStatement()) {
        assertThatThrownBy(() -> statement.execute("BEGIN IMMEDIATE"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("SQLITE_BUSY");
      }
      runtime.rollback();
      runtime.setAutoCommit(true);
      try (Statement statement = other.createStatement()) {
        statement.execute("BEGIN IMMEDIATE");
        statement.execute("ROLLBACK");
      }
    }
  }

  /** AC8: 거부할 상태를 만드는 방법과 메시지에 있어야 할 말. */
  enum Refusal {
    DATA_DIR_IS_A_SYMLINK("심볼릭 링크") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        Path real = Files.createDirectory(tmp.resolve("real-data"));
        chmod(real, "rwx------");
        Files.createSymbolicLink(layout.dataDir(), real);
      }
    },
    DATA_DIR_IS_A_FILE("디렉터리가 아니라서") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        Files.writeString(layout.dataDir(), "not a directory");
        chmod(layout.dataDir(), "rw-------");
      }
    },
    DATA_DIR_HAS_GROUP_BITS("chmod 700") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        Files.createDirectory(layout.dataDir());
        chmod(layout.dataDir(), "rwxr-x---");
      }
    },
    DATA_DIR_IS_NOT_WRITABLE("쓰거나 들어갈 수 없어") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        assumeThat(TestSqlite.runningAsRoot()).as("root 는 권한과 관계없이 쓸 수 있다").isFalse();
        Files.createDirectory(layout.dataDir());
        chmod(layout.dataDir(), "r-x------");
      }
    },
    DB_IS_A_SYMLINK("심볼릭 링크") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        privateDataDir(layout);
        Path target = Files.createFile(tmp.resolve("elsewhere.db"));
        chmod(target, "rw-------");
        Files.createSymbolicLink(layout.dbFile(), target);
      }
    },
    DB_IS_A_DIRECTORY("일반 파일이 아니라서") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        privateDataDir(layout);
        Files.createDirectory(layout.dbFile());
        chmod(layout.dbFile(), "rwx------");
      }
    },
    DB_HAS_GROUP_BITS("chmod 600") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        privateDataDir(layout);
        Files.createFile(layout.dbFile());
        chmod(layout.dbFile(), "rw-r--r--");
      }
    },
    WAL_IS_A_SYMLINK("심볼릭 링크") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        privateDataDir(layout);
        Files.createFile(layout.dbFile());
        chmod(layout.dbFile(), "rw-------");
        Path target = Files.createFile(tmp.resolve("elsewhere.wal"));
        Files.createSymbolicLink(layout.walFile(), target);
      }
    },
    DB_IS_NOT_SQLITE("SQLite DB 파일이 아니다") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        privateDataDir(layout);
        TestSqlite.writeNotSqlite(layout.dbFile());
        chmod(layout.dbFile(), "rw-------");
      }
    },
    DB_IS_DAMAGED("손상") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        privateDataDir(layout);
        TestSqlite.writeDamagedDatabase(layout.dbFile());
        chmod(layout.dbFile(), "rw-------");
      }
    },
    /** 되돌리지 않은 롤백 저널. 읽기·쓰기 연결이면 SQLite 가 저널을 재생하거나 지운다. */
    HOT_ROLLBACK_JOURNAL("queryhub.db-journal") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        privateDataDir(layout);
        TestSqlite.writeHealthyDatabase(layout.dbFile());
        chmod(layout.dbFile(), "rw-------");
        Path journal = layout.dataDir().resolve(StoreLayout.DB_FILE_NAME + "-journal");
        Files.writeString(journal, "leftover rollback journal");
        chmod(journal, "rw-------");
      }
    },
    /** DB 파일은 비었는데 데이터가 든 WAL 이 남았다. 어떤 연결이든(읽기 전용도) SQLite 가 그 WAL 을 지운다. */
    EMPTY_DB_WITH_A_LEFTOVER_WAL("queryhub.db-wal") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        privateDataDir(layout);
        leftoverWal(tmp, layout);
        Files.createFile(layout.dbFile());
        chmod(layout.dbFile(), "rw-------");
      }
    },
    /** DB 파일은 없는데 데이터가 든 WAL 이 남았다. 빈 DB 를 새로 만들면 위와 같다. */
    MISSING_DB_WITH_A_LEFTOVER_WAL("queryhub.db-wal") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        privateDataDir(layout);
        leftoverWal(tmp, layout);
      }
    },
    NATIVE_DIR_IS_A_SYMLINK("심볼릭 링크") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        privateDataDir(layout);
        Path real = Files.createDirectory(tmp.resolve("real-native"));
        chmod(real, "rwx------");
        Files.createSymbolicLink(layout.nativeDir(), real);
      }
    },
    NATIVE_DIR_HAS_GROUP_BITS("chmod 700") {
      @Override
      void arrange(Path tmp, StoreLayout layout) throws Exception {
        privateDataDir(layout);
        Files.createDirectory(layout.nativeDir());
        chmod(layout.nativeDir(), "rwxr-xr-x");
      }
    };

    final String expected;

    Refusal(String expected) {
      this.expected = expected;
    }

    abstract void arrange(Path tmp, StoreLayout layout) throws Exception;

    static void privateDataDir(StoreLayout layout) throws IOException {
      Files.createDirectory(layout.dataDir());
      chmod(layout.dataDir(), "rwx------");
    }

    /** 살아 있는 WAL 을 다른 곳에서 만들어 -wal 파일만 데이터 디렉터리에 둔다(0600). */
    static void leftoverWal(Path tmp, StoreLayout layout) throws Exception {
      Path elsewhere = Files.createDirectory(tmp.resolve("wal-source-data"));
      TestSqlite.writeStoreWithLiveWal(tmp.resolve("wal-source"), elsewhere, false, false);
      Files.copy(elsewhere.resolve(StoreLayout.DB_FILE_NAME + "-wal"), layout.walFile());
      chmod(layout.walFile(), "rw-------");
    }
  }

  @ParameterizedTest
  @EnumSource(Refusal.class)
  void refusalNamesThePathAndTheFixAndLeavesEverythingAsItWas(Refusal refusal) throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);
    refusal.arrange(tmp, layout);
    Map<String, String> before = snapshot(tmp);

    try {
      assertThatThrownBy(() -> StoreBootstrap.open(layout).close())
          .isInstanceOf(MetadataStoreException.class)
          .hasMessageContaining(refusal.expected)
          .hasMessageContaining(tmp.toString());

      // 있던 항목은 모두 그대로다(내용, 권한, 링크 대상). 앞 단계가 새로 만든 항목(잠금 파일, 네이티브 디렉터리)은 생길 수 있다.
      assertThat(snapshot(tmp)).as("거부한 항목은 그대로다").containsAllEntriesOf(before);
      assertThat(Files.exists(layout.dbFile(), LinkOption.NOFOLLOW_LINKS))
          .as("DB 파일이 없던 경우에 새로 만들지 않는다")
          .isEqualTo(before.containsKey(layout.dbFile().toString()));
      if (Files.exists(layout.lockFile(), LinkOption.NOFOLLOW_LINKS)) {
        assertThat(TestSqlite.lockIsFree(layout.lockFile())).as("실패하면 잠금을 푼다").isTrue();
      }
    } finally {
      if (refusal == Refusal.DATA_DIR_IS_NOT_WRITABLE) {
        chmod(layout.dataDir(), "rwx------");
      }
    }
  }

  /** 디렉터리 아래 모든 항목의 종류·권한·내용(파일)·링크 대상(링크). 링크는 따라가지 않는다. */
  private static Map<String, String> snapshot(Path root) throws IOException {
    Map<String, String> entries = new TreeMap<>();
    List<Path> paths = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root)) {
      walk.forEach(paths::add);
    }
    for (Path path : paths) {
      String state;
      if (Files.isSymbolicLink(path)) {
        state = "link->" + Files.readSymbolicLink(path);
      } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        state = "dir " + mode(path);
      } else {
        state =
            "file "
                + mode(path)
                + " "
                + java.util.Arrays.hashCode(Files.readAllBytes(path))
                + " "
                + Files.size(path);
      }
      entries.put(path.toString(), state);
    }
    return entries;
  }

  private static Object fileKey(Path file) throws IOException {
    return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
        .fileKey();
  }
}
