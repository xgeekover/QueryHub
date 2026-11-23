package com.queryhub.adapter.metadata.support;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 테스트용 자식 프로세스: 인자로 받은 잠금 파일을 다른 프로세스로서 잠가 본다. 잠그면 바로 풀고 {@value #FREE}, 다른 프로세스가 쥐고 있으면 {@value
 * #HELD} 로 끝난다. JDK 클래스만 쓴다({@link TestSqlite#lockIsFreeInAnotherProcess}).
 */
public final class LockProbe {

  public static final int FREE = 0;
  public static final int HELD = 3;

  private LockProbe() {}

  public static void main(String[] args) throws IOException {
    try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE)) {
      FileLock lock = channel.tryLock();
      if (lock == null) {
        System.exit(HELD);
      }
      lock.release();
    }
    System.exit(FREE);
  }
}
