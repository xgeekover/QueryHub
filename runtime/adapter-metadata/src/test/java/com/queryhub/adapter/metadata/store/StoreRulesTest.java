package com.queryhub.adapter.metadata.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryhub.adapter.metadata.store.PrivatePaths.Facts;
import com.queryhub.adapter.metadata.store.PrivatePaths.Kind;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * P1-06 AC5(설정 값), AC8(네트워크 파일 시스템 분류, 소유자 판단), AC10(네이티브 실패 분류): 입출력 없는 규칙.
 *
 * <p>다른 사용자 소유의 경로는 root 없이 만들 수 없으므로 소유자 판단은 값을 넣어 확인한다.
 */
class StoreRulesTest {

  private static final Path DIR = Path.of("/srv/queryhub/data");

  private static final UserPrincipal ALICE = principal("alice");
  private static final UserPrincipal BOB = principal("bob");

  @Test
  void layoutPlacesEveryFileUnderTheDataDirectory() {
    StoreLayout layout = StoreLayout.resolve("/srv/queryhub/data", null);

    assertThat(layout.dataDir()).isEqualTo(DIR);
    assertThat(layout.dbFile()).isEqualTo(DIR.resolve("queryhub.db"));
    assertThat(layout.walFile()).isEqualTo(DIR.resolve("queryhub.db-wal"));
    assertThat(layout.shmFile()).isEqualTo(DIR.resolve("queryhub.db-shm"));
    assertThat(layout.lockFile()).isEqualTo(DIR.resolve("queryhub.lock"));
    assertThat(layout.nativeDir()).isEqualTo(DIR.resolve("native"));
    assertThat(layout.nativeDirFromSetting()).isFalse();
    assertThat(layout.jdbcUrl())
        .isEqualTo(
            "jdbc:sqlite:/srv/queryhub/data/queryhub.db?journal_mode=WAL&synchronous=FULL"
                + "&foreign_keys=true&busy_timeout=5000&transaction_mode=IMMEDIATE");
    assertThat(layout.plainJdbcUrl()).isEqualTo("jdbc:sqlite:/srv/queryhub/data/queryhub.db");
  }

  @Test
  void nativeDirectorySettingMovesOnlyTheNativeDirectory() {
    StoreLayout layout = StoreLayout.resolve("/srv/queryhub/data", "/opt/queryhub/native");

    assertThat(layout.nativeDir()).isEqualTo(Path.of("/opt/queryhub/native"));
    assertThat(layout.nativeDirFromSetting()).isTrue();
    assertThat(layout.dbFile()).isEqualTo(DIR.resolve("queryhub.db"));
    assertThat(StoreLayout.resolve("/srv/queryhub/data", "").nativeDir())
        .isEqualTo(DIR.resolve("native"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t"})
  void missingOrBlankDataDirectoryIsRefusedNamingBothSettings(String value) {
    for (String setting : new String[] {null, value}) {
      assertThatThrownBy(() -> StoreLayout.resolve(setting, null))
          .isInstanceOf(MetadataStoreException.class)
          .hasMessageContaining("QUERYHUB_DATA_DIR")
          .hasMessageContaining("queryhub.data-dir")
          .hasMessageContaining("절대 경로");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"data", "./data", "relative/dir", "~/data"})
  void relativeDataDirectoryIsRefused(String value) {
    assertThatThrownBy(() -> StoreLayout.resolve(value, null))
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining("절대 경로가 아니라")
        .hasMessageContaining("QUERYHUB_DATA_DIR")
        .hasMessageContaining("queryhub.data-dir");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/srv/data?mode=ro", "/srv/da?ta", "/srv/data/?"})
  void questionMarkIsRefusedBecauseTheDriverReadsItAsUrlOptions(String value) {
    assertThatThrownBy(() -> StoreLayout.resolve(value, null))
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining("'?'")
        .hasMessageContaining("QUERYHUB_DATA_DIR")
        .hasMessageContaining("queryhub.data-dir");
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", "native", "/opt/na?tive"})
  void invalidNativeDirectoryIsRefusedNamingItsSettings(String value) {
    assertThatThrownBy(() -> StoreLayout.resolve("/srv/queryhub/data", value))
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining("QUERYHUB_SQLITE_NATIVE_DIR")
        .hasMessageContaining("queryhub.sqlite.native-dir");
  }

  @ParameterizedTest
  @ValueSource(strings = {"nfs", "NFS", "nfs4", "smbfs", "cifs", "afpfs", "webdav", "fuse.sshfs"})
  void networkFileSystemTypesAreRefused(String type) {
    assertThat(NetworkFileSystems.isNetworkType(type)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"apfs", "APFS", "ext4", "xfs", "ntfs", "NTFS", "btrfs", "tmpfs", "zfs"})
  void localAndUnknownFileSystemTypesAreAccepted(String type) {
    assertThat(NetworkFileSystems.isNetworkType(type)).isFalse();
  }

  /** R4: Windows UNC 뿌리 (Path#getRoot 의 문자열, Windows 의 Java 가 주는 모양). */
  @ParameterizedTest
  @ValueSource(strings = {"\\\\server\\share\\", "//server/share/", "\\\\.\\C:\\", "\\/x\\y\\"})
  void windowsUncRootsAreRefusedWithTheNetworkMessage(String root) {
    assertThat(NetworkFileSystems.isUncRoot(root)).isTrue();
    assertThat(
            NetworkFileSystems.uncProblem(root + "queryhub", root, "데이터 디렉터리(QUERYHUB_DATA_DIR)"))
        .hasValueSatisfying(
            message ->
                assertThat(message)
                    .contains("데이터 디렉터리(QUERYHUB_DATA_DIR)")
                    .contains(root + "queryhub")
                    .contains("UNC")
                    .contains("네트워크")
                    .contains("로컬 디스크"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"C:\\", "c:/", "Z:\\", "\\", "/", ""})
  void driveLetterAndUnixRootsAreNotUnc(String root) {
    assertThat(NetworkFileSystems.isUncRoot(root)).isFalse();
    assertThat(NetworkFileSystems.uncProblem(root + "data", root, "데이터 디렉터리")).isEmpty();
  }

  @Test
  void pathWithoutARootIsNotUncAndUnixDoubleSlashCollapsesToTheLocalRoot() {
    assertThat(NetworkFileSystems.isUncRoot(null)).isFalse();
    // unix 의 Java 는 "//server/share" 를 "/server/share" 로 읽는다. 로컬 경로이므로 거부하지 않는다.
    Path unixDoubleSlash = Path.of("//server/share/queryhub");
    NetworkFileSystems.refuseUncPath(unixDoubleSlash, "데이터 디렉터리");
    assertThat(unixDoubleSlash.getRoot()).hasToString("/");
  }

  @Test
  void ownerOnlyDirectoryOfTheCurrentUserIsAccepted() {
    assertThat(PrivatePaths.problem(DIR, "데이터 디렉터리", Kind.DIRECTORY, directory(ALICE, "rwx------")))
        .isEmpty();
  }

  @Test
  void foreignOwnerIsRefusedForDirectoriesAndFiles() {
    Facts foreignDirectory =
        new Facts(
            false, true, false, BOB, ALICE, PosixFilePermissions.fromString("rwx------"), true);
    Facts foreignFile =
        new Facts(
            false, false, true, BOB, ALICE, PosixFilePermissions.fromString("rw-------"), true);

    assertThat(PrivatePaths.problem(DIR, "데이터 디렉터리", Kind.DIRECTORY, foreignDirectory))
        .hasValueSatisfying(
            message -> assertThat(message).contains("bob", "alice", DIR.toString(), "chown"));
    assertThat(PrivatePaths.problem(DIR.resolve("queryhub.db"), "DB 파일", Kind.FILE, foreignFile))
        .hasValueSatisfying(message -> assertThat(message).contains("bob", "alice"));
  }

  @Test
  void unknownCurrentUserIsRefused() {
    Facts facts =
        new Facts(
            false, true, false, ALICE, null, PosixFilePermissions.fromString("rwx------"), true);

    assertThat(PrivatePaths.problem(DIR, "데이터 디렉터리", Kind.DIRECTORY, facts)).isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {"rwxr-x---", "rwx---r--", "rwx-----x", "rwxrwxrwx"})
  void groupOrOtherBitsOnADirectoryAreRefusedWithAChmod700Hint(String mode) {
    assertThat(PrivatePaths.problem(DIR, "데이터 디렉터리", Kind.DIRECTORY, directory(ALICE, mode)))
        .hasValueSatisfying(message -> assertThat(message).contains(mode, "chmod 700 " + DIR));
  }

  @ParameterizedTest
  @ValueSource(strings = {"rw-r-----", "rw----r--", "rw-rw-rw-"})
  void groupOrOtherBitsOnAFileAreRefusedWithAChmod600Hint(String mode) {
    Path db = DIR.resolve("queryhub.db");
    Facts facts =
        new Facts(false, false, true, ALICE, ALICE, PosixFilePermissions.fromString(mode), true);

    assertThat(PrivatePaths.problem(db, "DB 파일", Kind.FILE, facts))
        .hasValueSatisfying(message -> assertThat(message).contains("chmod 600 " + db));
  }

  @Test
  void symbolicLinksAndWrongKindsAreRefusedBeforeOwnershipIsConsidered() {
    Facts link = new Facts(true, false, false, BOB, ALICE, null, false);
    Facts file =
        new Facts(
            false, false, true, ALICE, ALICE, PosixFilePermissions.fromString("rw-------"), true);

    assertThat(PrivatePaths.problem(DIR, "데이터 디렉터리", Kind.DIRECTORY, link))
        .hasValueSatisfying(message -> assertThat(message).contains("심볼릭 링크"));
    assertThat(PrivatePaths.problem(DIR, "데이터 디렉터리", Kind.DIRECTORY, file))
        .hasValueSatisfying(message -> assertThat(message).contains("디렉터리가 아니라서"));
    assertThat(
            PrivatePaths.problem(
                DIR.resolve("queryhub.db"), "DB 파일", Kind.FILE, directory(ALICE, "rwx------")))
        .hasValueSatisfying(message -> assertThat(message).contains("일반 파일이 아니라서"));
  }

  @Test
  void unwritableDirectoryIsRefused() {
    Facts readOnly =
        new Facts(
            false, true, false, ALICE, ALICE, PosixFilePermissions.fromString("r-x------"), false);

    assertThat(PrivatePaths.problem(DIR, "데이터 디렉터리", Kind.DIRECTORY, readOnly))
        .hasValueSatisfying(message -> assertThat(message).contains("쓰거나", "chmod 700 " + DIR));
  }

  @Test
  void withoutPosixPermissionsOnlyLinkAndKindAreChecked() {
    Facts windowsDirectory = new Facts(false, true, false, null, null, null, true);

    assertThat(PrivatePaths.problem(DIR, "데이터 디렉터리", Kind.DIRECTORY, windowsDirectory)).isEmpty();
  }

  @Test
  void nativeFailureClassifierFindsUnsatisfiedLinkErrorAnywhereInTheCauseChain() {
    Throwable failure =
        new IllegalStateException(
            "context", new SQLException("Error opening connection", new UnsatisfiedLinkError("x")));

    assertThat(NativeLibraryFailures.describe(failure, Path.of("/srv/queryhub/data/native")))
        .hasValueSatisfying(NativeFailureMessage::assertComplete);
  }

  @Test
  void nativeFailureClassifierFindsTheDriversNoNativeLibraryException() throws Exception {
    // 드라이버의 실제 예외 클래스(org.sqlite.NativeLibraryNotFoundException)를 이름으로 만든다. 컴파일 클래스패스에는 드라이버가 없다.
    Class<?> type = Class.forName(NativeLibraryFailures.NOT_FOUND_EXCEPTION);
    Constructor<?> constructor = type.getConstructor(String.class);
    Throwable notFound =
        (Throwable) constructor.newInstance("unsupported platform for this test (not a real one)");
    Throwable failure = new SQLException("Error opening connection", notFound);

    assertThat(NativeLibraryFailures.describe(failure, Path.of("/srv/queryhub/data/native")))
        .hasValueSatisfying(NativeFailureMessage::assertComplete);
  }

  @Test
  void nativeFailureClassifierIgnoresOtherFailures() {
    Throwable failure = new SQLException("[SQLITE_CANTOPEN] unable to open database file");

    assertThat(NativeLibraryFailures.describe(failure, Path.of("/srv/native"))).isEmpty();
  }

  private static final class NativeFailureMessage {
    static void assertComplete(String message) {
      assertThat(message)
          .contains("QUERYHUB_SQLITE_NATIVE_DIR")
          .contains("queryhub.sqlite.native-dir")
          .contains("os.name=" + System.getProperty("os.name"))
          .contains("os.arch=" + System.getProperty("os.arch"))
          .contains("noexec")
          .contains("백신")
          .contains("/srv/queryhub/data/native");
    }
  }

  private static Facts directory(UserPrincipal owner, String mode) {
    return new Facts(false, true, false, owner, ALICE, PosixFilePermissions.fromString(mode), true);
  }

  private static UserPrincipal principal(String name) {
    return new UserPrincipal() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public boolean equals(Object other) {
        return other instanceof UserPrincipal user && user.getName().equals(name);
      }

      @Override
      public int hashCode() {
        return name.hashCode();
      }
    };
  }
}
