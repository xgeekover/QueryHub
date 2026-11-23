package com.queryhub.adapter.metadata.store;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 데이터 디렉터리 하나에 런타임 하나 (F5). {@code <data-dir>/queryhub.lock} 에 배타 잠금({@link
 * FileChannel#tryLock()})을 잡고 런타임이 끝날 때({@link #close()}, Spring 컨텍스트 종료) 푼다. 프로세스가 죽으면 OS 가 잠금을
 * 푼다. 잠금 파일은 지우지 않는다.
 *
 * <p>다른 프로세스가 잡고 있으면 tryLock 이 null 을 돌려주고 기동을 거부한다. 같은 JVM 이 잡고 있으면 채널을 열기 전에 거부한다: macOS 와 Linux
 * 에서는 한 프로세스가 같은 파일의 어떤 파일 기술자를 닫든 그 프로세스의 잠금이 모두 풀린다(POSIX fcntl 잠금). 그래서 두 번째 채널을 열었다 닫으면 첫 런타임의
 * OS 잠금이 조용히 사라지고, {@link FileLock#isValid()} 는 계속 {@code true} 다(P1-06 리뷰 R2). 이를 막으려고 이 클래스가 잡은
 * 잠금 파일을 JVM 전체 표({@link #HELD_IN_THIS_JVM})에 둔다. 열쇠는 파일의 정체(unix 는 장치와 inode 인 fileKey, 그것이 없는
 * Windows 는 실제 경로)라서 상위 디렉터리의 심볼릭 링크나 대소문자가 다른 표기로 같은 파일을 가리켜도 같은 열쇠다.
 *
 * <p>이 표는 이 클래스가 잡은 잠금만 안다. 같은 JVM 의 다른 코드가 이 파일을 직접 잠그면 tryLock 이 {@link
 * OverlappingFileLockException} 을 내고, 그때도 기동을 거부한다.
 */
public final class StoreLock implements Closeable {

  /** 이 JVM 에서 지금 잡고 있는 잠금 파일의 정체. 닫으면 뺀다. */
  private static final Set<Object> HELD_IN_THIS_JVM = ConcurrentHashMap.newKeySet();

  private static final String ROLE = "Metadata 저장소 잠금 파일";

  private final Path lockFile;
  private final Object identity;
  private final FileChannel channel;
  private final FileLock lock;
  private final AtomicBoolean closed = new AtomicBoolean();

  private StoreLock(Path lockFile, Object identity, FileChannel channel, FileLock lock) {
    this.lockFile = lockFile;
    this.identity = identity;
    this.channel = channel;
    this.lock = lock;
  }

  /**
   * 잠금을 잡는다. 잠금 파일이 없으면 0600 으로 만들고, 있으면 파일 규칙을 확인한다.
   *
   * @throws MetadataStoreException 다른 런타임(다른 프로세스나 같은 JVM)이 잡고 있거나, 파일 규칙에 어긋나거나, 열 수 없을 때
   */
  static StoreLock acquire(StoreLayout layout) {
    Path lockFile = layout.lockFile();
    PrivatePaths.createPrivateFile(lockFile, ROLE);
    PrivatePaths.checkExistingFile(lockFile, ROLE);
    Object identity = identity(lockFile);
    if (!HELD_IN_THIS_JVM.add(identity)) {
      // 채널을 열지 않는다: 열었다 닫기만 해도 첫 런타임의 OS 잠금이 풀린다.
      throw held(layout, lockFile);
    }
    try {
      return lock(layout, lockFile, identity);
    } catch (RuntimeException failure) {
      HELD_IN_THIS_JVM.remove(identity);
      throw failure;
    }
  }

  private static StoreLock lock(StoreLayout layout, Path lockFile, Object identity) {
    FileChannel channel;
    try {
      channel = FileChannel.open(lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException failure) {
      throw new MetadataStoreException(
          ROLE + "(" + lockFile + ")를 열 수 없어 런타임을 시작하지 않는다: " + failure, failure);
    }
    FileLock lock;
    try {
      lock = channel.tryLock();
    } catch (OverlappingFileLockException sameJvm) {
      closeQuietly(channel);
      throw held(layout, lockFile);
    } catch (IOException failure) {
      closeQuietly(channel);
      throw new MetadataStoreException(
          ROLE + "(" + lockFile + ")를 잠글 수 없어 런타임을 시작하지 않는다: " + failure, failure);
    }
    if (lock == null) {
      closeQuietly(channel);
      throw held(layout, lockFile);
    }
    return new StoreLock(lockFile, identity, channel, lock);
  }

  /** 파일의 정체: fileKey(unix: 장치와 inode), 없으면 실제 경로. 파일을 열지 않는다(stat 만 한다). */
  static Object identity(Path lockFile) {
    try {
      Object fileKey =
          Files.readAttributes(lockFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
              .fileKey();
      return fileKey != null ? fileKey : lockFile.toRealPath().toString();
    } catch (IOException failure) {
      throw new MetadataStoreException(
          ROLE + "(" + lockFile + ")를 확인할 수 없어 런타임을 시작하지 않는다: " + failure, failure);
    }
  }

  /** 잠금을 쥐고 있으면 {@code true}. */
  public boolean isHeld() {
    return !closed.get() && lock.isValid();
  }

  public Path lockFile() {
    return lockFile;
  }

  /** 잠금을 풀고 JVM 표에서 뺀다. 여러 번 불러도 된다(두 번째부터는 아무것도 하지 않는다). */
  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    try {
      try {
        if (lock.isValid()) {
          lock.release();
        }
      } finally {
        channel.close();
      }
    } finally {
      HELD_IN_THIS_JVM.remove(identity);
    }
  }

  private static MetadataStoreException held(StoreLayout layout, Path lockFile) {
    return new MetadataStoreException(
        "다른 QueryHub 런타임이 데이터 디렉터리("
            + layout.dataDir()
            + ")를 쓰고 있어 런타임을 시작하지 않는다 (잠금 파일 "
            + lockFile
            + "). 데이터 디렉터리 하나에는 런타임 하나만 띄운다. 먼저 띄운 런타임을 끝내거나 다른 데이터 디렉터리를 지정하라.");
  }

  private static void closeQuietly(FileChannel channel) {
    try {
      channel.close();
    } catch (IOException ignored) {
      // 이미 실패를 보고하는 중이다.
    }
  }
}
