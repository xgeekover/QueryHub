package com.queryhub.adapter.metadata.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 네트워크 파일 시스템 거부 (최선 노력). SQLite 의 잠금과 WAL 공유 메모리는 네트워크 파일 시스템에서 믿을 수 없다.
 *
 * <p>{@link java.nio.file.FileStore#type()} 이 알려진 네트워크 종류이면 거부한다(대소문자 무관). 모르는 종류는 받아들인다: 이름이 플랫폼마다
 * 다르고, Windows 의 네트워크 드라이브는 보통 원격 파일 시스템 이름(예: NTFS)을 보고하므로 여기서 가려낼 수 없다.
 *
 * <p>Windows UNC 경로({@code \\server\share\...})는 경로의 뿌리만 보고 거부한다({@link #refuseUncPath}). 아무것도 만들기
 * 전에 본다. 네트워크 드라이브에 매핑한 드라이브 문자(예: {@code Z:\})는 여전히 가려내지 못한다. 로컬 디스크만 쓴다는 규칙은 문서(DEPLOYMENT)가 말한다.
 */
public final class NetworkFileSystems {

  /** 거부하는 FileStore 종류 (소문자). macOS: nfs, smbfs, afpfs, webdav. Linux: nfs, nfs4, cifs, smb3 등. */
  static final Set<String> NETWORK_TYPES =
      Set.of(
          "nfs",
          "nfs4",
          "cifs",
          "smbfs",
          "smb2",
          "smb3",
          "afpfs",
          "webdav",
          "davfs",
          "fuse.sshfs",
          "9p",
          "ceph",
          "glusterfs",
          "fuse.glusterfs");

  private NetworkFileSystems() {}

  /** 알려진 네트워크 파일 시스템 종류이면 {@code true}. */
  public static boolean isNetworkType(String type) {
    return NETWORK_TYPES.contains(type.strip().toLowerCase(Locale.ROOT));
  }

  /**
   * 경로의 뿌리({@link Path#getRoot()} 의 문자열)가 Windows UNC 뿌리이면 {@code true}: 구분자 두 개로 시작한다({@code
   * \\server\share\}, Windows 의 Java 는 {@code //server/share/} 도 같은 뿌리로 읽는다). 드라이브 문자({@code C:\}),
   * 드라이브 없는 뿌리({@code \}), unix 의 {@code /} 는 아니다. unix 의 Java 는 {@code //} 를 {@code /} 로 줄이므로 unix
   * 에서는 늘 {@code false} 다.
   */
  static boolean isUncRoot(@Nullable String root) {
    return root != null
        && root.length() >= 2
        && isSeparator(root.charAt(0))
        && isSeparator(root.charAt(1));
  }

  /** UNC 경로이면 거부 메시지, 아니면 빈 값 (입출력 없음). */
  static Optional<String> uncProblem(String path, @Nullable String root, String role) {
    if (!isUncRoot(root)) {
      return Optional.empty();
    }
    return Optional.of(
        role
            + "("
            + path
            + ")가 Windows 네트워크 공유(UNC 경로 "
            + root
            + ")에 있어 런타임을 시작하지 않는다. SQLite 의 잠금과 WAL 은 네트워크 파일 시스템에서 안전하지 않다. 로컬 디스크의 디렉터리를 지정하라.");
  }

  /**
   * Windows UNC 경로이면 거부한다. 경로 문자열만 본다(파일 시스템에 묻지 않는다).
   *
   * @throws MetadataStoreException UNC 경로일 때
   */
  static void refuseUncPath(Path directory, String role) {
    Path root = directory.getRoot();
    Optional<String> problem =
        uncProblem(directory.toString(), root == null ? null : root.toString(), role);
    if (problem.isPresent()) {
      throw new MetadataStoreException(problem.get());
    }
  }

  private static boolean isSeparator(char c) {
    return c == '\\' || c == '/';
  }

  /**
   * 데이터 디렉터리가 알려진 네트워크 파일 시스템에 있으면 거부한다. 종류를 읽지 못하면 모르는 종류로 보고 받아들인다.
   *
   * @throws MetadataStoreException 네트워크 파일 시스템일 때
   */
  static void requireLocal(Path dataDir, String role) {
    String type;
    try {
      type = Files.getFileStore(dataDir).type();
    } catch (IOException unknown) {
      return;
    }
    if (isNetworkType(type)) {
      throw new MetadataStoreException(
          role
              + "("
              + dataDir
              + ")가 네트워크 파일 시스템("
              + type
              + ")에 있어 런타임을 시작하지 않는다. SQLite 의 잠금과 WAL 은 네트워크 파일 시스템에서 안전하지 않다. 로컬 디스크의 디렉터리를 지정하라.");
    }
  }
}
