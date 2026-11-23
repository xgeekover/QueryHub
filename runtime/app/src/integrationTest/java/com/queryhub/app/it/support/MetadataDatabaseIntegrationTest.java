package com.queryhub.app.it.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link QueryHubIntegrationTest} 클래스의 공통 부모. 공유 컨텍스트의 Metadata 저장소(SQLite) 데이터 디렉터리를
 * {@code @DynamicPropertySource} 로 queryhub.data-dir 에 넣는다.
 *
 * <p>메서드가 이 클래스 하나에만 있으므로 상속한 모든 클래스의 컨텍스트 캐시 키가 같다(컨텍스트 하나, Hikari 풀 하나, 잠금 하나). 데이터 디렉터리는 테스트 JVM
 * 하나에 하나다: 시스템 임시 디렉터리 아래의 새 디렉터리 안에 아직 없는 {@code data} 를 주므로 런타임이 0700 으로 만든다. 같은 디렉터리로 다른 컨텍스트를
 * 띄우면 queryhub.lock 때문에 거부되므로, 다른 컨텍스트는 자기 임시 디렉터리를 쓴다.
 */
public abstract class MetadataDatabaseIntegrationTest {

  private static final Path DATA_DIR = createDataDir();

  @DynamicPropertySource
  static void metadataStore(DynamicPropertyRegistry registry) {
    registry.add("queryhub.data-dir", DATA_DIR::toString);
  }

  /** 공유 컨텍스트의 데이터 디렉터리. */
  public static Path sharedDataDir() {
    return DATA_DIR;
  }

  private static Path createDataDir() {
    try {
      IntegrationSqlite.loadNativeOnce();
      return Files.createTempDirectory("queryhub-it-").resolve("data");
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
