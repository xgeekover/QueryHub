package com.queryhub.adapter.metadata.store;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 비공개 디렉터리·파일 규칙. 데스크톱 셸의 master_key.rs(키 디렉터리 0700, 키 파일 0600)와 같은 규칙이다.
 *
 * <ul>
 *   <li>없는 디렉터리는 만든다(부모는 createDirectories, 마지막 디렉터리는 POSIX 에서 0700).
 *   <li>있는 디렉터리는 심볼릭 링크가 아니고(링크를 따라가지 않고 본다), 디렉터리이며, 런타임을 실행한 사용자의 것이고, 그룹·기타 권한 비트가 없고, 쓰고 들어갈 수
 *       있어야 한다.
 *   <li>있는 파일은 심볼릭 링크가 아닌 일반 파일이고, 런타임을 실행한 사용자의 것이며, 그룹·기타 권한 비트가 없고, 소유자가 읽고 쓸 수 있어야 한다(0600).
 *       소유자가 쓸 수 없는 -shm 이나 DB 파일이면 SQLite 가 저장소를 조용히 읽기 전용으로 열고 쓰기마다 실패한다(P1-06 리뷰 N4).
 *   <li>느슨한 권한을 조용히 고치지 않는다. 메시지가 chmod 명령을 알려 준다. 거부한 경로는 바꾸거나, 자르거나, 이름을 바꾸거나, 지우지 않는다.
 *   <li>POSIX 권한이 없는 파일 시스템(Windows)에서는 소유자와 권한 비트를 보지 않는다. 사용자 프로필의 ACL 에 기댄다(미검증, PLAT-1). 심볼릭
 *       링크와 종류 검사는 그대로 하고, 쓸 수 없는 디렉터리와 파일(읽기 전용 속성 등)은 거부한다.
 * </ul>
 */
final class PrivatePaths {

  static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
      PosixFilePermissions.fromString("rwx------");

  static final Set<PosixFilePermission> OWNER_ONLY_FILE =
      PosixFilePermissions.fromString("rw-------");

  private static final Set<PosixFilePermission> OWNER_READ_WRITE =
      EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private static final Set<PosixFilePermission> GROUP_OR_OTHER =
      EnumSet.of(
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.GROUP_WRITE,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.OTHERS_WRITE,
          PosixFilePermission.OTHERS_EXECUTE);

  /** 검사 대상의 종류. */
  enum Kind {
    DIRECTORY,
    FILE
  }

  /**
   * 링크를 따라가지 않고 읽은 경로의 상태. POSIX 가 아니면 {@code owner}, {@code currentUser}, {@code permissions} 는
   * null 이다. {@code writableAndSearchable} 은 디렉터리면 쓰고 들어갈 수 있는지, 파일이면 쓸 수 있는지다.
   */
  record Facts(
      boolean symbolicLink,
      boolean directory,
      boolean regularFile,
      @Nullable UserPrincipal owner,
      @Nullable UserPrincipal currentUser,
      @Nullable Set<PosixFilePermission> permissions,
      boolean writableAndSearchable) {}

  private PrivatePaths() {}

  /**
   * 규칙 판단(입출력 없음). 문제가 없으면 빈 값이다.
   *
   * @param role 메시지에 쓰는 이름 (예: "데이터 디렉터리(QUERYHUB_DATA_DIR)")
   */
  static Optional<String> problem(Path path, String role, Kind kind, Facts facts) {
    String subject = role + "(" + path + ")";
    if (facts.symbolicLink()) {
      return Optional.of(
          subject
              + "가 심볼릭 링크라서 런타임을 시작하지 않는다. 링크는 따라가지 않는다. 링크를 지우고 실제 "
              + (kind == Kind.DIRECTORY ? "디렉터리" : "파일")
              + "를 두거나, 설정에 실제 경로를 지정하라.");
    }
    if (kind == Kind.DIRECTORY && !facts.directory()) {
      return Optional.of(subject + "가 디렉터리가 아니라서 런타임을 시작하지 않는다. 그 경로의 파일을 옮기거나 다른 디렉터리를 지정하라.");
    }
    if (kind == Kind.FILE && !facts.regularFile()) {
      return Optional.of(
          subject + "가 일반 파일이 아니라서(디렉터리 등) 런타임을 시작하지 않는다. 그 경로의 항목을 옮기거나 다른 데이터 디렉터리를 지정하라.");
    }
    if (facts.permissions() == null) {
      // POSIX 권한이 없는 파일 시스템(Windows): 소유자와 권한 비트는 보지 않는다(PLAT-1). 쓸 수 없으면 거부한다.
      if (facts.writableAndSearchable()) {
        return Optional.empty();
      }
      return Optional.of(
          kind == Kind.DIRECTORY
              ? subject + "에 쓸 수 없어 런타임을 시작하지 않는다. 런타임을 실행하는 사용자에게 쓰기 권한을 주라."
              : subject
                  + "에 쓸 수 없어(읽기 전용) 런타임을 시작하지 않으며 권한을 대신 고치지 않는다. 파일의 읽기 전용 속성을 끄거나 런타임을 실행하는 사용자에게"
                  + " 쓰기 권한을 주라.");
    }
    if (facts.currentUser() == null) {
      return Optional.of(
          subject + "의 소유자를 확인할 수 없어 런타임을 시작하지 않는다 (런타임을 실행한 사용자를 알 수 없다, user.name).");
    }
    if (!facts.currentUser().equals(facts.owner())) {
      return Optional.of(
          subject
              + "의 소유자("
              + (facts.owner() == null ? "알 수 없음" : facts.owner().getName())
              + ")가 런타임을 실행한 사용자("
              + facts.currentUser().getName()
              + ")가 아니라서 런타임을 시작하지 않는다. 이 사용자의 "
              + (kind == Kind.DIRECTORY ? "디렉터리를 지정하거나" : "파일만 두거나")
              + " 소유자를 바꿔라(chown).");
    }
    String mode = PosixFilePermissions.toString(facts.permissions());
    String chmod = (kind == Kind.DIRECTORY ? "chmod 700 " : "chmod 600 ") + path;
    if (facts.permissions().stream().anyMatch(GROUP_OR_OTHER::contains)) {
      return Optional.of(
          subject
              + "의 권한이 "
              + mode
              + " 라서 그룹이나 다른 사용자가 접근할 수 있다. 런타임을 시작하지 않으며 권한을 대신 고치지 않는다. 고치는 방법: "
              + chmod);
    }
    if (kind == Kind.DIRECTORY && !facts.writableAndSearchable()) {
      return Optional.of(
          subject + "에 쓰거나 들어갈 수 없어(권한 " + mode + ") 런타임을 시작하지 않는다. 고치는 방법: " + chmod);
    }
    if (kind == Kind.FILE && !facts.permissions().containsAll(OWNER_READ_WRITE)) {
      return Optional.of(
          subject
              + "의 권한이 "
              + mode
              + " 라서 소유자(런타임을 실행한 사용자)가 읽고 쓸 수 없다. SQLite 가 저장소를 읽기 전용으로 열어 쓰기가 실패하므로 런타임을 시작하지 않으며 권한을"
              + " 대신 고치지 않는다. 고치는 방법: "
              + chmod);
    }
    return Optional.empty();
  }

  /** 경로의 상태를 링크를 따라가지 않고 읽는다. 경로가 없으면 빈 값이다. */
  static Optional<Facts> facts(Path path) throws IOException {
    BasicFileAttributes basic;
    try {
      basic = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException absent) {
      return Optional.empty();
    }
    boolean searchable =
        basic.isDirectory() && !basic.isSymbolicLink()
            ? Files.isWritable(path) && Files.isExecutable(path)
            : Files.isWritable(path);
    if (!posix()) {
      return Optional.of(
          new Facts(
              basic.isSymbolicLink(),
              basic.isDirectory(),
              basic.isRegularFile(),
              null,
              null,
              null,
              searchable));
    }
    PosixFileAttributes attributes =
        Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    return Optional.of(
        new Facts(
            attributes.isSymbolicLink(),
            attributes.isDirectory(),
            attributes.isRegularFile(),
            attributes.owner(),
            currentUser(),
            attributes.permissions(),
            searchable));
  }

  /**
   * 디렉터리를 준비한다: 없으면 만들고(마지막 디렉터리 0700), 있으면 규칙을 확인한다.
   *
   * @throws MetadataStoreException 규칙에 어긋나거나 만들 수 없을 때
   */
  static void prepareDirectory(Path directory, String role) {
    try {
      Optional<Facts> existing = facts(directory);
      if (existing.isEmpty()) {
        createPrivateDirectory(directory);
        existing = facts(directory);
      }
      if (existing.isEmpty()) {
        throw new MetadataStoreException(role + "(" + directory + ")를 만들었지만 찾을 수 없다.");
      }
      Optional<String> problem = problem(directory, role, Kind.DIRECTORY, existing.get());
      if (problem.isPresent()) {
        throw new MetadataStoreException(problem.get());
      }
    } catch (IOException failure) {
      throw new MetadataStoreException(
          role
              + "("
              + directory
              + ")를 확인하거나 만들 수 없어 런타임을 시작하지 않는다: "
              + failure
              + ". 경로와 상위 디렉터리의 권한을 확인하라.",
          failure);
    }
  }

  /**
   * 파일이 있으면 규칙을 확인한다. 없으면 아무것도 하지 않는다.
   *
   * @return 파일이 있었으면 {@code true}
   * @throws MetadataStoreException 규칙에 어긋날 때
   */
  static boolean checkExistingFile(Path file, String role) {
    try {
      Optional<Facts> existing = facts(file);
      if (existing.isEmpty()) {
        return false;
      }
      Optional<String> problem = problem(file, role, Kind.FILE, existing.get());
      if (problem.isPresent()) {
        throw new MetadataStoreException(problem.get());
      }
      return true;
    } catch (IOException failure) {
      throw new MetadataStoreException(
          role + "(" + file + ")를 확인할 수 없어 런타임을 시작하지 않는다: " + failure, failure);
    }
  }

  /**
   * 빈 파일을 새로 만든다(CREATE_NEW, POSIX 에서 0600). 이미 있으면 만들지 않고 {@code false} 를 돌려준다. 있는 항목(심볼릭 링크 포함)은
   * 건드리지 않는다.
   */
  static boolean createPrivateFile(Path file, String role) {
    try {
      if (posix()) {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE));
        // umask 와 관계없이 정확히 0600 으로 둔다(방금 만든 파일이다).
        Files.setPosixFilePermissions(file, OWNER_ONLY_FILE);
      } else {
        Files.createFile(file);
      }
      return true;
    } catch (FileAlreadyExistsException exists) {
      return false;
    } catch (IOException failure) {
      throw new MetadataStoreException(
          role + "(" + file + ")를 만들 수 없어 런타임을 시작하지 않는다: " + failure + ". 데이터 디렉터리의 권한을 확인하라.",
          failure);
    }
  }

  private static void createPrivateDirectory(Path directory) throws IOException {
    Path parent = directory.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try {
      if (posix()) {
        Files.createDirectory(
            directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
        // umask 와 관계없이 정확히 0700 으로 둔다(방금 만든 디렉터리다).
        Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY);
      } else {
        Files.createDirectory(directory);
      }
    } catch (FileAlreadyExistsException raced) {
      // 다른 프로세스가 먼저 만들었다. 호출하는 쪽이 규칙을 다시 확인한다.
    }
  }

  static boolean posix() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  /**
   * 런타임을 실행한 사용자. Linux 에서는 /proc/self 의 소유자(실효 uid)이고, 그 밖에서는 user.name 으로 찾은 사용자다. 찾지 못하면 null
   * 이다(호출하는 쪽이 거부한다).
   */
  static @Nullable UserPrincipal currentUser() {
    Path procSelf = Path.of("/proc/self");
    try {
      if (Files.isDirectory(procSelf)) {
        return Files.getOwner(procSelf);
      }
    } catch (IOException | UnsupportedOperationException ignored) {
      // user.name 으로 찾는다.
    }
    String name = System.getProperty("user.name");
    if (name == null || name.isBlank()) {
      return null;
    }
    try {
      return FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(name);
    } catch (IOException | UnsupportedOperationException notFound) {
      return null;
    }
  }
}
