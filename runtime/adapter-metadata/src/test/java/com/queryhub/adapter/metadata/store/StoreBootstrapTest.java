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

  /**
   * P1-07 AC2 (리뷰 N1): 새 저장소를 처음 WAL 로 바꾸다 멈춘 모습. 1 페이지 DB 옆에 원래 DB 크기 0 페이지인 실제 저널 머리글이 있다. 거부
   * 메시지는 커밋한 데이터가 없는 새 저장소라고 말하고 파일을 함께 옮기라고 한다. 두 파일은 바이트 하나 바뀌지 않고, 그 방법대로 옮기면 열린다.
   */
  @Test
  void hotJournalOfANewStoreIsRefusedWithTheNewStoreRemedyAndTheRemedyWorks() throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);
    Refusal.privateDataDir(layout);
    TestSqlite.writeOnePageWalModeDatabase(tmp.resolve("source"), layout.dbFile());
    TestSqlite.writeRollbackJournal(layout.journalFile(), 0);
    assertThat(Files.size(layout.dbFile())).isEqualTo(TestSqlite.PAGE_SIZE);
    assertThat(StoreBootstrap.hasSqliteHeaderOrIsEmpty(layout.dbFile())).isTrue();
    assertThat(Files.size(layout.journalFile())).isEqualTo(512);
    String db = TestSqlite.sha256(layout.dbFile());
    String journal = TestSqlite.sha256(layout.journalFile());

    assertThatThrownBy(() -> StoreBootstrap.open(layout).close())
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining(layout.dbFile().toString())
        .hasMessageContaining(layout.journalFile().toString())
        .hasMessageContaining("처음 WAL 로 바꿀 때 롤백 저널을 한 번 쓴다")
        .hasMessageContaining("원래 DB 크기가 0 페이지")
        .hasMessageContaining("커밋한 데이터가 없는 새 저장소")
        .hasMessageContaining("런타임을 멈춘 채 queryhub.db 와 queryhub.db-journal")
        .hasMessageContaining("queryhub.db-wal·queryhub.db-shm")
        .hasMessageContaining("데이터 디렉터리(" + layout.dataDir() + ") 밖으로 옮긴 뒤 다시 시작하라")
        .message()
        .doesNotContain("sqlite3");
    assertThat(TestSqlite.sha256(layout.dbFile())).as("DB 파일 내용").isEqualTo(db);
    assertThat(TestSqlite.sha256(layout.journalFile())).as("저널 내용").isEqualTo(journal);

    // 메시지가 말한 방법: 런타임을 멈춘 채 DB 파일과 저널(있으면 -wal·-shm)을 함께 데이터 디렉터리 밖으로 옮긴다.
    Path aside = Files.createDirectory(tmp.resolve("moved-aside"));
    for (Path file :
        List.of(layout.dbFile(), layout.journalFile(), layout.walFile(), layout.shmFile())) {
      if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        Files.move(file, aside.resolve(file.getFileName()));
      }
    }
    try (OpenedMetadataStore store = StoreBootstrap.open(layout)) {
      assertThat(store.isLocked()).isTrue();
      assertThat(mode(layout.dbFile())).isEqualTo("rw-------");
    }
    assertThat(TestSqlite.sha256(aside.resolve("queryhub.db"))).isEqualTo(db);
    assertThat(TestSqlite.sha256(aside.resolve("queryhub.db-journal"))).isEqualTo(journal);
  }

  /** P1-07 AC2: 원래 DB 크기가 1 페이지 이상인 저널은 커밋한 데이터가 있을 수 있다. 새 저장소라고 하지 않고 sqlite3 와 백업 복원을 알려 준다. */
  @ParameterizedTest(name = "원래 DB 크기 {0} 페이지")
  @ValueSource(ints = {1, 3})
  void hotJournalOfAStoreThatHadDataGetsTheSqlite3AndBackupRemedy(int initialPages)
      throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);
    Refusal.privateDataDir(layout);
    TestSqlite.writeOnePageWalModeDatabase(tmp.resolve("source"), layout.dbFile());
    TestSqlite.writeRollbackJournal(layout.journalFile(), initialPages);
    String db = TestSqlite.sha256(layout.dbFile());
    String journal = TestSqlite.sha256(layout.journalFile());

    assertThatThrownBy(() -> StoreBootstrap.open(layout).close())
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining(layout.journalFile().toString())
        .hasMessageContaining("원래 DB 크기가 " + initialPages + " 페이지")
        .hasMessageContaining("커밋한 데이터가 있을 수 있다")
        .hasMessageContaining("sqlite3 \"" + layout.dbFile() + "\" \"PRAGMA quick_check\"")
        .hasMessageContaining("백업에서 DB 파일과 마스터 키를 함께 복원하라")
        .message()
        .doesNotContain("새 저장소")
        .doesNotContain("커밋한 데이터가 없");
    assertThat(TestSqlite.sha256(layout.dbFile())).isEqualTo(db);
    assertThat(TestSqlite.sha256(layout.journalFile())).isEqualTo(journal);
  }

  @Test
  void journalHeaderReaderReadsTheInitialPageCountOnlyFromARealHeader() throws Exception {
    Path journal = tmp.resolve("queryhub.db-journal");
    TestSqlite.writeRollbackJournal(journal, 0);
    assertThat(StoreBootstrap.journalInitialPageCount(journal)).hasValue(0);
    TestSqlite.writeRollbackJournal(journal, -1);
    assertThat(StoreBootstrap.journalInitialPageCount(journal))
        .as("부호 없는 32 비트")
        .hasValue(4_294_967_295L);
    Files.writeString(journal, "leftover rollback journal, not a real header");
    assertThat(StoreBootstrap.journalInitialPageCount(journal)).as("매직 바이트가 다르다").isEmpty();
    Files.write(journal, java.util.Arrays.copyOf(StoreBootstrap.JOURNAL_MAGIC, 20));
    assertThat(StoreBootstrap.journalInitialPageCount(journal)).as("28 바이트보다 짧다").isEmpty();
    assertThat(StoreBootstrap.journalInitialPageCount(tmp.resolve("missing"))).isEmpty();
  }

  /** P1-07 AC3: -journal 도 연결 전에 파일 규칙으로 본다. 심볼릭 링크인 저널은 롤백 저널 메시지가 아니라 링크 메시지로 거부하고 링크는 그대로 둔다. */
  @Test
  void symlinkedRollbackJournalIsRefusedByTheFileRuleBeforeAnyConnection() throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);
    Refusal.privateDataDir(layout);
    TestSqlite.writeHealthyDatabase(layout.dbFile());
    chmod(layout.dbFile(), "rw-------");
    Path target = tmp.resolve("elsewhere.db-journal");
    TestSqlite.writeRollbackJournal(target, 1);
    Files.createSymbolicLink(layout.journalFile(), target);
    Map<String, String> before = snapshot(tmp);

    assertThatThrownBy(() -> StoreBootstrap.open(layout).close())
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining("Metadata 저장소 롤백 저널(" + layout.journalFile() + ")")
        .hasMessageContaining("심볼릭 링크")
        .message()
        .doesNotContain("되돌리지 않은 롤백 저널");
    assertThat(snapshot(tmp)).containsAllEntriesOf(before);
    assertThat(Files.readSymbolicLink(layout.journalFile())).isEqualTo(target);
    assertThat(layout.shmFile()).as("연결하지 않았다").doesNotExist();
  }

  /** P1-07 AC4 (리뷰 N3): 잠금 채널을 열다 Error 가 나도 JVM 표에 정체가 남지 않아 같은 데이터 디렉터리를 다시 잠글 수 있다. */
  @Test
  void anErrorWhileOpeningTheLockChannelLeavesNoEntryInTheJvmTable() throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);
    PrivatePaths.prepareDirectory(layout.dataDir(), StoreBootstrap.DATA_DIR_ROLE);
    InternalError injected = new InternalError("injected at channel open (test)");

    assertThatThrownBy(
            () ->
                StoreLock.acquire(
                    layout,
                    lockFile -> {
                      throw injected;
                    }))
        .isSameAs(injected);

    try (StoreLock lock = StoreLock.acquire(layout)) {
      assertThat(lock.isHeld()).isTrue();
    }
    assertThat(TestSqlite.lockIsFreeInAnotherProcess(layout.lockFile())).isTrue();
  }

  /**
   * P1-07 AC5 (리뷰 N4): 소유자가 쓸 수 없는(0400) 파일이 있는 건강한 WAL 저장소는 연결 전에 거부한다. 있던 파일의 내용·권한은 그대로이고, 연결하지
   * 않았으므로 -shm 이 없던 경우 생기지 않는다.
   */
  @ParameterizedTest
  @ValueSource(strings = {"queryhub.db-shm", "queryhub.db", "queryhub.db-wal"})
  void ownerReadOnlyStoreFileIsRefusedBeforeAnyConnection(String name) throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);
    Refusal.privateDataDir(layout);
    boolean withShm = name.endsWith("-shm");
    TestSqlite.writeStoreWithLiveWal(tmp.resolve("source"), layout.dataDir(), withShm, false);
    Path readOnly = layout.dataDir().resolve(name);
    chmod(readOnly, "r--------");
    Map<String, String> before = snapshot(tmp);

    assertThatThrownBy(() -> StoreBootstrap.open(layout).close())
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining(readOnly.toString())
        .hasMessageContaining("r--------")
        .hasMessageContaining("읽고 쓸 수 없다")
        .hasMessageContaining("chmod 600 " + readOnly);
    assertThat(snapshot(tmp)).as("거부한 항목은 그대로다").containsAllEntriesOf(before);
    assertThat(mode(readOnly)).isEqualTo("r--------");
    assertThat(Files.exists(layout.shmFile())).as("연결하지 않았다").isEqualTo(withShm);
  }

  /**
   * P1-07 AC6 (리뷰 N5): 런타임 URL 은 open_mode=2(SQLITE_OPEN_CREATE 없음)다. 런타임이 도는 동안 DB 파일이 사라지면 새 연결은
   * 빈 DB 를 만들지 않고 실패한다.
   */
  @Test
  void runtimeUrlDoesNotRecreateADatabaseFileThatVanishedWhileTheStoreIsOpen() throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);
    try (OpenedMetadataStore store = StoreBootstrap.open(layout)) {
      for (Path file : List.of(layout.dbFile(), layout.walFile(), layout.shmFile())) {
        Files.deleteIfExists(file);
      }

      assertThatThrownBy(() -> DriverManager.getConnection(store.jdbcUrl()).close())
          .isInstanceOf(SQLException.class);
      assertThat(layout.dbFile()).as("런타임 URL 이 DB 파일을 만들지 않는다").doesNotExist();
    }
    assertThat(layout.dbFile()).doesNotExist();
  }

  /**
   * P1-07 AC7: 롤백 저널 모드 파일(머리글 18-19 바이트 1, 1: VACUUM INTO 사본)은 open_mode=2 로도 열리고, 부트스트랩 연결이 WAL
   * 모드(2, 2)로 바꾼다. 그때 쓴 롤백 저널은 남지 않는다.
   */
  @Test
  void rollbackModeDatabaseLikeAVacuumIntoCopyOpensAndIsSwitchedToWal() throws Exception {
    StoreLayout layout = StoreLayout.resolve(tmp.resolve("data").toString(), null);
    Refusal.privateDataDir(layout);
    Path source = Files.createDirectory(tmp.resolve("source")).resolve("live.db");
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + source + "?journal_mode=WAL");
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE probe (v TEXT NOT NULL)");
      statement.execute("INSERT INTO probe (v) VALUES ('a'), ('b')");
    }
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
        Statement statement = connection.createStatement()) {
      statement.execute("VACUUM INTO '" + layout.dbFile() + "'");
    }
    chmod(layout.dbFile(), "rw-------");
    assertThat(TestSqlite.formatVersionBytes(layout.dbFile())).containsExactly(1, 1);

    try (OpenedMetadataStore store = StoreBootstrap.open(layout);
        Connection connection = DriverManager.getConnection(store.jdbcUrl());
        Statement statement = connection.createStatement();
        var rs = statement.executeQuery("SELECT count(*) FROM probe")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(2);
    }
    assertThat(TestSqlite.formatVersionBytes(layout.dbFile())).containsExactly(2, 2);
    assertThat(layout.journalFile()).doesNotExist();
    assertThat(mode(layout.dbFile())).isEqualTo("rw-------");
  }

  /**
   * P1-07 AC8 (리뷰 N6): 데이터 디렉터리에서 가장 가까운 있는 조상의 실제 경로가 UNC 뿌리이면(공유를 가리키는 정션·링크) 아무것도 만들기 전에 거부한다.
   * 실제 경로 뿌리는 넣은 것이 돌려준다(Windows 에서는 시험하지 않았다, PLAT-1).
   */
  @Test
  void
      dataDirectoryWhoseNearestExistingAncestorIsReallyOnAUncShareIsRefusedBeforeAnythingIsCreated()
          throws Exception {
    Path dataDir = tmp.resolve("missing/parent/data");
    StoreLayout layout = StoreLayout.resolve(dataDir.toString(), null);
    Map<String, String> before = snapshot(tmp);
    List<Path> asked = new ArrayList<>();

    assertThatThrownBy(
            () ->
                StoreBootstrap.open(
                        layout,
                        existing -> {
                          asked.add(existing);
                          return "\\\\server\\share\\";
                        })
                    .close())
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining(StoreBootstrap.DATA_DIR_ROLE + "(" + dataDir + ")")
        .hasMessageContaining("상위 경로 " + tmp + " ")
        .hasMessageContaining("UNC 경로 \\\\server\\share\\")
        .hasMessageContaining("네트워크")
        .hasMessageContaining("로컬 디스크");
    assertThat(asked).as("가장 가까운 있는 조상").containsExactly(tmp);
    assertThat(snapshot(tmp)).as("아무것도 만들지 않는다").isEqualTo(before);
  }

  @Test
  void
      nativeDirectoryWhoseNearestExistingAncestorIsReallyOnAUncShareIsRefusedBeforeAnythingIsCreated()
          throws Exception {
    Path shareLink = Files.createDirectory(tmp.resolve("junction-to-share"));
    StoreLayout layout =
        StoreLayout.resolve(
            tmp.resolve("data").toString(), shareLink.resolve("queryhub/native").toString());
    Map<String, String> before = snapshot(tmp);

    assertThatThrownBy(
            () ->
                StoreBootstrap.open(
                        layout,
                        existing ->
                            existing.equals(shareLink)
                                ? "//server/share/"
                                : NetworkFileSystems.REAL_ROOTS.realRoot(existing))
                    .close())
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining(StoreBootstrap.NATIVE_DIR_ROLE)
        .hasMessageContaining("UNC 경로 //server/share/");
    assertThat(snapshot(tmp)).as("데이터 디렉터리도 만들지 않는다").isEqualTo(before);
  }

  /** P1-07 AC8: unix 에서 가장 가까운 있는 조상 찾기와 실제 경로 읽기(링크를 따라간다). */
  @Test
  void nearestExistingAncestorSkipsMissingDescendantsAndTheRealPathFollowsLinks() throws Exception {
    String role = StoreBootstrap.DATA_DIR_ROLE;
    assertThat(NetworkFileSystems.nearestExistingAncestor(tmp.resolve("a/b/c"))).contains(tmp);
    assertThat(NetworkFileSystems.nearestExistingAncestor(tmp)).contains(tmp);

    Path real = Files.createDirectory(tmp.resolve("real"));
    Path alias = Files.createSymbolicLink(tmp.resolve("alias"), real);
    assertThat(NetworkFileSystems.nearestExistingAncestor(alias.resolve("x/y"))).contains(alias);
    assertThat(NetworkFileSystems.REAL_ROOTS.realRoot(alias)).isEqualTo("/");
    NetworkFileSystems.refuseUncRealPath(alias.resolve("x/y"), role, NetworkFileSystems.REAL_ROOTS);

    // 끊긴 링크는 링크 자체가 가장 가까운 있는 조상이고, 실제 경로를 읽을 수 없어 거부한다.
    Path dangling = Files.createSymbolicLink(tmp.resolve("dangling"), tmp.resolve("nowhere"));
    assertThat(NetworkFileSystems.nearestExistingAncestor(dangling.resolve("x")))
        .contains(dangling);
    assertThatThrownBy(
            () ->
                NetworkFileSystems.refuseUncRealPath(
                    dangling.resolve("x"), role, NetworkFileSystems.REAL_ROOTS))
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining(dangling.toString())
        .hasMessageContaining("실제 경로를 확인할 수 없어");
    assertThat(tmp.resolve("nowhere")).doesNotExist();
    assertThat(alias.resolve("x")).doesNotExist();
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
