package com.queryhub.adapter.metadata.store;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Metadata 저장소의 파일 배치 (P1-06, ADR-026).
 *
 * <pre>
 * &lt;data-dir&gt;/queryhub.db       SQLite DB 파일 (0600)
 * &lt;data-dir&gt;/queryhub.db-wal   WAL 파일 (SQLite 가 DB 파일과 같은 권한으로 만든다)
 * &lt;data-dir&gt;/queryhub.db-shm   공유 메모리 파일 (같은 권한, 데이터가 없는 WAL 색인)
 * &lt;data-dir&gt;/queryhub.db-journal 롤백 저널 (새 DB 나 롤백 저널 모드 DB 를 처음 WAL 로 바꾸는 동안만 있다)
 * &lt;data-dir&gt;/queryhub.lock     런타임 하나만 이 디렉터리를 쓰게 하는 잠금 파일 (0600)
 * &lt;data-dir&gt;/native/           sqlite-jdbc 가 네이티브 라이브러리를 푸는 디렉터리 (0700, QUERYHUB_SQLITE_NATIVE_DIR 로 옮긴다)
 * </pre>
 *
 * <p>데이터 디렉터리는 필수이고 기본값이 없다. 절대 경로여야 하고 {@code '?'} 를 담을 수 없다(sqlite-jdbc 가 JDBC URL 의 파일 이름에서
 * {@code '?'} 뒤를 URL 옵션으로 읽는다). 경로는 비밀이 아니므로 메시지에 그대로 넣는다.
 *
 * @param dataDir 데이터 디렉터리 (절대 경로)
 * @param nativeDir 네이티브 라이브러리 디렉터리 (절대 경로)
 * @param nativeDirFromSetting {@code true} 이면 {@value #ENV_NATIVE_DIR} 로 지정한 디렉터리다
 */
public record StoreLayout(Path dataDir, Path nativeDir, boolean nativeDirFromSetting) {

  /** 데이터 디렉터리 환경변수. Spring 의 느슨한 바인딩이 {@value #PROPERTY_DATA_DIR} 로 연결한다. */
  public static final String ENV_DATA_DIR = "QUERYHUB_DATA_DIR";

  /** 데이터 디렉터리 속성. 외부 설정 파일, 명령행 인자로도 줄 수 있다(비밀이 아니다). */
  public static final String PROPERTY_DATA_DIR = "queryhub.data-dir";

  /** 네이티브 라이브러리 디렉터리 환경변수 (선택). */
  public static final String ENV_NATIVE_DIR = "QUERYHUB_SQLITE_NATIVE_DIR";

  /** 네이티브 라이브러리 디렉터리 속성 (선택). */
  public static final String PROPERTY_NATIVE_DIR = "queryhub.sqlite.native-dir";

  public static final String DB_FILE_NAME = "queryhub.db";
  public static final String LOCK_FILE_NAME = "queryhub.lock";
  public static final String NATIVE_DIR_NAME = "native";

  /**
   * 런타임이 붙이는 SQLite 연결 설정. sqlite-jdbc 가 연결을 열 때 PRAGMA 로 적용한다. transaction_mode 는 PRAGMA 가 아니라
   * 드라이버 설정이다(트랜잭션을 BEGIN IMMEDIATE 로 연다). 값은 {@link StorePragmas} 가 실제 연결에서 다시 확인한다.
   */
  public static final String URL_OPTIONS =
      "journal_mode=WAL&synchronous=FULL&foreign_keys=true&busy_timeout="
          + StorePragmas.BUSY_TIMEOUT_MILLIS
          + "&transaction_mode=IMMEDIATE";

  /**
   * 런타임 URL 의 열기 플래그: sqlite-jdbc 연결 속성 {@code open_mode} 의 값 SQLITE_OPEN_READWRITE(0x2). 드라이버가
   * SQLITE_OPEN_URI 를 더한다. SQLITE_OPEN_CREATE 가 없으므로 DB 파일이 없으면 만들지 않고 연결이 실패한다(P1-06 리뷰 N5). DB
   * 파일은 런타임이 연결 전에 0600 으로 만든다({@link StoreBootstrap}). -wal, -shm, 롤백 저널은 SQLite 가 이 플래그와 상관없이
   * 만든다. PRAGMA 목록({@link #URL_OPTIONS})과 따로 둔다: 시험이 다른 파일이나 메모리 DB 로 같은 PRAGMA 를 단 풀을 만든다.
   */
  static final String OPEN_READ_WRITE = "2";

  public StoreLayout {
    Objects.requireNonNull(dataDir, "dataDir");
    Objects.requireNonNull(nativeDir, "nativeDir");
  }

  /**
   * 설정 값으로 배치를 만든다.
   *
   * @param dataDirSetting {@value #PROPERTY_DATA_DIR} 값 (필수)
   * @param nativeDirSetting {@value #PROPERTY_NATIVE_DIR} 값 (선택, 비었으면 {@code <data-dir>/native})
   * @throws MetadataStoreException 값이 없거나, 비었거나, 상대 경로이거나, {@code '?'} 를 담을 때
   */
  public static StoreLayout resolve(
      @Nullable String dataDirSetting, @Nullable String nativeDirSetting) {
    Path dataDir =
        absolutePath(
            dataDirSetting, ENV_DATA_DIR, PROPERTY_DATA_DIR, "Metadata 저장소 데이터 디렉터리", true);
    if (nativeDirSetting == null || nativeDirSetting.isEmpty()) {
      return new StoreLayout(dataDir, dataDir.resolve(NATIVE_DIR_NAME), false);
    }
    Path nativeDir =
        absolutePath(
            nativeDirSetting, ENV_NATIVE_DIR, PROPERTY_NATIVE_DIR, "SQLite 네이티브 라이브러리 디렉터리", false);
    return new StoreLayout(dataDir, nativeDir, true);
  }

  public Path dbFile() {
    return dataDir.resolve(DB_FILE_NAME);
  }

  public Path walFile() {
    return dataDir.resolve(DB_FILE_NAME + "-wal");
  }

  public Path shmFile() {
    return dataDir.resolve(DB_FILE_NAME + "-shm");
  }

  /**
   * 롤백 저널. 런타임은 새 DB 나 롤백 저널 모드로 되살린 DB(예: VACUUM INTO 사본)를 처음 WAL 로 바꿀 때 SQLite 를 통해 이 파일을 한 번 쓰고,
   * 바꾸기를 마치면 SQLite 가 지운다. 그 사이에 멈췄거나 다른 도구가 남긴 저널은 저장소 검사가 연결 전에 파일 규칙으로, 내용 검사에서 되돌려야 하는지로
   * 확인한다({@link StoreBootstrap}).
   */
  public Path journalFile() {
    return dataDir.resolve(DB_FILE_NAME + "-journal");
  }

  public Path lockFile() {
    return dataDir.resolve(LOCK_FILE_NAME);
  }

  /**
   * 런타임이 쓰는 JDBC URL(부트스트랩 연결, 연결 풀, Flyway): {@code jdbc:sqlite:<절대 경로>/queryhub.db?}{@link
   * #URL_OPTIONS}{@code &open_mode=2}. {@code open_mode=2}({@link #OPEN_READ_WRITE})는 읽기·쓰기로 열고 DB
   * 파일을 만들지 않는다: 런타임이 도는 동안 DB 파일이 사라지면 새 풀 연결이 빈 DB 를 조용히 만들지 않고 실패한다.
   */
  public String jdbcUrl() {
    return plainJdbcUrl()
        + "?"
        + URL_OPTIONS
        + "&"
        + StoreBootstrap.OPEN_MODE_PROPERTY
        + "="
        + OPEN_READ_WRITE;
  }

  /**
   * 연결 설정이 없는 JDBC URL. 이 URL 만으로 연 연결은 읽기·쓰기이고 DB 파일이 없으면 만든다: journal_mode 는 바꾸지 않지만, 닫을 때 WAL 을
   * DB 파일로 옮기고 -wal·-shm 을 지우며, 열 때 롤백 저널을 재생한다. 그래서 내용 검사는 이 URL 을 읽기 전용 속성과 함께 연다({@link
   * StoreBootstrap#checkContent}).
   */
  public String plainJdbcUrl() {
    return "jdbc:sqlite:" + dbFile();
  }

  private static Path absolutePath(
      @Nullable String value, String env, String property, String what, boolean required) {
    String names = env + " 환경변수나 " + property + " 설정";
    if (value == null || value.isBlank()) {
      if (required) {
        throw new MetadataStoreException(
            what
                + "가 설정되지 않아 런타임을 시작하지 않는다. "
                + names
                + "(외부 설정 파일 또는 명령행 인자)에 로컬 디스크의 절대 경로를 지정하라. 기본값은 없다.");
      }
      throw new MetadataStoreException(
          what + " 설정 값이 공백뿐이라 런타임을 시작하지 않는다. " + names + "에 절대 경로를 지정하거나 설정을 지워라.");
    }
    if (value.indexOf('?') >= 0) {
      throw new MetadataStoreException(
          what
              + " 경로("
              + value
              + ")에 '?' 가 있어 런타임을 시작하지 않는다. SQLite JDBC 는 '?' 뒤를 URL 옵션으로 읽는다. "
              + names
              + "에 '?' 가 없는 절대 경로를 지정하라.");
    }
    Path path;
    try {
      path = Path.of(value);
    } catch (InvalidPathException invalid) {
      throw new MetadataStoreException(
          what + " 경로(" + value + ")가 올바른 경로가 아니라 런타임을 시작하지 않는다. " + names + "을 확인하라.");
    }
    if (!path.isAbsolute()) {
      throw new MetadataStoreException(
          what
              + " 경로("
              + value
              + ")가 절대 경로가 아니라 런타임을 시작하지 않는다. 상대 경로는 작업 디렉터리에 따라 달라진다. "
              + names
              + "에 절대 경로를 지정하라.");
    }
    return path;
  }
}
