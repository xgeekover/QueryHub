package com.queryhub.app.config.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryhub.adapter.metadata.store.MetadataStoreException;
import com.queryhub.adapter.metadata.store.StoreLayout;
import com.queryhub.app.support.TestSqlite;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P1-06 리뷰 R3(a): 풀 연결 검사({@link MetadataDatabaseStartupCheck#verifyPoolConnections})는 PRAGMA 값만이
 * 아니라 각 연결이 연 파일({@code PRAGMA database_list} 의 main)이 검사한 DB 파일인지 본다. 같은 PRAGMA 를 단 URL 로 다른 파일을
 * 열면 PRAGMA 검사로는 잡을 수 없다.
 */
class MetadataPoolConnectionCheckTest {

  @TempDir private Path tmp;

  @BeforeAll
  static void loadNative() throws Exception {
    TestSqlite.loadNativeOnce();
  }

  @Test
  void poolOnAnotherSqliteFileWithTheRuntimePragmasIsRefused() throws Exception {
    Path checked = checkedDatabase();
    Path other = tmp.resolve("other.db");

    try (HikariDataSource pool = pool("jdbc:sqlite:" + other + "?" + StoreLayout.URL_OPTIONS)) {
      assertThatThrownBy(
              () -> MetadataDatabaseStartupCheck.verifyPoolConnections(pool, checked, "Flyway 전"))
          .isInstanceOf(MetadataStoreException.class)
          .hasMessageContaining("풀 연결 1/2 (Flyway 전)")
          .hasMessageContaining(checked.toRealPath().toString())
          .hasMessageContaining(other.toRealPath().toString())
          .hasMessageContaining("spring.datasource.hikari.jdbc-url");
    }
  }

  @Test
  void inMemoryPoolIsRefused() {
    Path checked = tmp.resolve("data/queryhub.db");

    try (HikariDataSource pool = pool("jdbc:sqlite::memory:?" + StoreLayout.URL_OPTIONS)) {
      assertThatThrownBy(
              () -> MetadataDatabaseStartupCheck.verifyPoolConnections(pool, checked, "Flyway 후"))
          .isInstanceOf(MetadataStoreException.class)
          .hasMessageContaining("풀 연결 1/2 (Flyway 후)")
          .hasMessageContaining("메모리·임시 DB");
    }
  }

  @Test
  void poolOnTheCheckedFileIsAcceptedEvenThroughAnotherSpellingOfItsPath() throws Exception {
    Path checked = checkedDatabase();
    Path alias = Files.createSymbolicLink(tmp.resolve("alias"), checked.getParent());

    try (HikariDataSource direct = pool("jdbc:sqlite:" + checked + "?" + StoreLayout.URL_OPTIONS);
        HikariDataSource viaLink =
            pool("jdbc:sqlite:" + alias.resolve("queryhub.db") + "?" + StoreLayout.URL_OPTIONS)) {
      assertThat(MetadataDatabaseStartupCheck.verifyPoolConnections(direct, checked, "Flyway 전"))
          .isEqualTo(2);
      assertThat(MetadataDatabaseStartupCheck.verifyPoolConnections(viaLink, checked, "Flyway 전"))
          .as("상위 디렉터리의 링크로 쓴 같은 파일은 같은 파일이다")
          .isEqualTo(2);
    }
  }

  private Path checkedDatabase() throws Exception {
    Path dataDir = Files.createDirectory(tmp.resolve("data"));
    return Files.createFile(dataDir.resolve("queryhub.db"));
  }

  private static HikariDataSource pool(String url) {
    HikariDataSource pool = new HikariDataSource();
    pool.setJdbcUrl(url);
    pool.setMaximumPoolSize(2);
    pool.setMinimumIdle(0);
    pool.setInitializationFailTimeout(-1);
    return pool;
  }
}
