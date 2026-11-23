package com.queryhub.adapter.metadata.store;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;

/**
 * 검사를 통과하고 잠금을 잡은 Metadata 저장소. {@link StoreBootstrap#open(StoreLayout)} 만 만든다. 닫으면 잠금을 푼다(app 은 이
 * 객체를 Spring 빈으로 두고 컨텍스트가 닫힐 때 닫는다. 그 전에 연결 풀이 닫힌다).
 */
public final class OpenedMetadataStore implements Closeable {

  private final StoreLayout layout;
  private final StoreLock lock;

  OpenedMetadataStore(StoreLayout layout, StoreLock lock) {
    this.layout = Objects.requireNonNull(layout, "layout");
    this.lock = Objects.requireNonNull(lock, "lock");
  }

  public StoreLayout layout() {
    return layout;
  }

  /** 연결 풀과 Flyway 가 쓰는 JDBC URL ({@link StoreLayout#jdbcUrl()}). */
  public String jdbcUrl() {
    return layout.jdbcUrl();
  }

  /** 잠금을 쥐고 있으면 {@code true}. */
  public boolean isLocked() {
    return lock.isHeld();
  }

  @Override
  public void close() throws IOException {
    lock.close();
  }

  @Override
  public String toString() {
    return "OpenedMetadataStore[" + layout.dbFile() + "]";
  }
}
