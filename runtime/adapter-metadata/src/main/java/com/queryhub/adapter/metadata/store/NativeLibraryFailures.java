package com.queryhub.adapter.metadata.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * SQLite 네이티브 라이브러리를 불러오지 못한 실패를 알아보고, 고치는 방법을 담은 메시지로 바꾼다.
 *
 * <p>sqlite-jdbc 는 첫 연결 때 JAR 안의 {@code org/sqlite/native/<OS>/<arch>/} 라이브러리를 {@code
 * org.sqlite.tmpdir} (런타임이 네이티브 디렉터리로 정한다)에 풀고 불러온다. 실패하면 원인 사슬에 {@link UnsatisfiedLinkError} 나
 * 드라이버의 {@code org.sqlite.NativeLibraryNotFoundException} 이 있다. 드라이버 클래스는 이름(문자열)으로만 알아본다(ArchUnit
 * R6).
 */
public final class NativeLibraryFailures {

  static final String NOT_FOUND_EXCEPTION = "org.sqlite.NativeLibraryNotFoundException";

  private NativeLibraryFailures() {}

  /** 원인 사슬에 네이티브 라이브러리 실패가 있으면 메시지를, 없으면 빈 값을 돌려준다. */
  public static Optional<String> describe(Throwable failure, Path nativeDir) {
    for (Throwable cause : causes(failure)) {
      if (cause instanceof UnsatisfiedLinkError
          || NOT_FOUND_EXCEPTION.equals(cause.getClass().getName())
          || String.valueOf(cause.getMessage()).contains("No native library found")) {
        return Optional.of(
            message(nativeDir, System.getProperty("os.name"), System.getProperty("os.arch")));
      }
    }
    return Optional.empty();
  }

  static String message(Path nativeDir, @Nullable String osName, @Nullable String osArch) {
    return "SQLite 네이티브 라이브러리를 불러오지 못해 런타임을 시작하지 않는다 (os.name="
        + osName
        + ", os.arch="
        + osArch
        + "). sqlite-jdbc 는 JAR 안의 네이티브 라이브러리를 "
        + nativeDir
        + " 에 풀어 불러온다. 원인 후보: 그 디렉터리가 noexec 로 마운트된 파일 시스템에 있다, Windows 백신이 풀린 DLL 을 막는다,"
        + " 이 OS·아키텍처의 네이티브 라이브러리가 sqlite-jdbc 에 없다(docs/DATABASE_SUPPORT.md). 실행이 허용된 로컬 디렉터리를 "
        + StoreLayout.ENV_NATIVE_DIR
        + " 환경변수("
        + StoreLayout.PROPERTY_NATIVE_DIR
        + " 설정)로 지정하라.";
  }

  private static List<Throwable> causes(Throwable failure) {
    List<Throwable> causes = new ArrayList<>();
    for (Throwable cause = failure;
        cause != null && !causes.contains(cause);
        cause = cause.getCause()) {
      causes.add(cause);
    }
    return causes;
  }
}
