package com.queryhub.app.it;

import static com.queryhub.app.it.support.IntegrationSqlite.chmod;
import static com.queryhub.app.it.support.IntegrationSqlite.mode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.queryhub.adapter.metadata.store.MetadataStoreException;
import com.queryhub.app.QueryHubApplication;
import com.queryhub.app.it.support.IntegrationHttp;
import com.queryhub.app.it.support.IntegrationSqlite;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * AC-16, P1-06 AC8 (운영과 같은 조립): Metadata 저장소 문제는 기동을 웹 서버가 포트를 열기 전에 멈춘다. 메시지는 경로와 고치는 방법을 말하고, 있던
 * 파일은 그대로이며, 출력에 관리자 토큰과 마스터 키가 없다. 경우 전체는 adapter-metadata 의 StoreBootstrapTest 가 확인하고, 여기서는 대표
 * 경우가 실제 기동 경로(JdbcConnectionDetails 빈)에서 포트 전에 멈추는지 본다. 일반 초기화와 lazy 초기화 모두 확인한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class MetadataStartupFailureIntegrationTest {

  @TempDir private Path tmp;

  @BeforeAll
  static void loadNative() throws Exception {
    IntegrationSqlite.loadNativeOnce();
  }

  enum Broken {
    DATA_DIR_IS_A_SYMLINK("심볼릭 링크"),
    DATA_DIR_HAS_GROUP_BITS("chmod 700"),
    DB_IS_NOT_SQLITE("SQLite DB 파일이 아니다"),
    DB_IS_DAMAGED("손상"),
    /** 살아 있는 WAL 이 있는 손상된 저장소: 거부 뒤에도 DB 파일과 -wal 이 바이트 하나 바뀌지 않는다(리뷰 R1). */
    DB_IS_DAMAGED_WITH_A_LIVE_WAL("손상"),
    NATIVE_DIR_HAS_GROUP_BITS("chmod 700");

    final String expected;

    Broken(String expected) {
      this.expected = expected;
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Broken.class)
  void brokenStoreStopsStartupBeforeThePortOpensAndIsLeftAsItWas(
      Broken broken, CapturedOutput output) throws Exception {
    Path dataDir = tmp.resolve("data");
    Path touched = arrange(broken, dataDir);
    String modeBefore = mode(touched);
    byte[] contentBefore = Files.isRegularFile(touched) ? Files.readAllBytes(touched) : null;
    Path db = dataDir.resolve("queryhub.db");
    byte[] dbBefore =
        Files.isRegularFile(db, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(db) : null;

    for (boolean lazy : new boolean[] {false, true}) {
      AtomicBoolean webServerStarted = new AtomicBoolean();
      Throwable failure = catchThrowable(() -> start(lazy, dataDir, webServerStarted));

      assertThat(failure).as("lazy=%s", lazy).isNotNull();
      // 저장소 예외가 드라이버 예외(예: SQLITE_CORRUPT)를 원인으로 담을 수 있으므로 원인 사슬에서 찾는다.
      assertThat(causes(failure))
          .filteredOn(MetadataStoreException.class::isInstance)
          .first()
          .satisfies(
              store ->
                  assertThat(store.getMessage())
                      .contains(broken.expected)
                      .contains(tmp.toString()));
      assertThat(webServerStarted).as("포트를 열기 전에 실패해야 한다").isFalse();
    }

    assertThat(Files.exists(touched, LinkOption.NOFOLLOW_LINKS)).as("지우지 않는다").isTrue();
    assertThat(mode(touched)).as("권한을 대신 고치지 않는다").isEqualTo(modeBefore);
    if (contentBefore != null) {
      assertThat(Arrays.equals(Files.readAllBytes(touched), contentBefore))
          .as("파일을 고치거나 자르지 않는다")
          .isTrue();
    }
    if (dbBefore != null) {
      assertThat(Arrays.equals(Files.readAllBytes(db), dbBefore))
          .as("DB 파일을 고치지 않는다 (WAL 을 옮겨 담지 않는다)")
          .isTrue();
    }
    assertThat(output.getAll())
        .doesNotContain(IntegrationHttp.ADMIN_TOKEN)
        .doesNotContainIgnoringCase(IntegrationHttp.MASTER_KEY);
  }

  private static java.util.List<Throwable> causes(Throwable failure) {
    java.util.List<Throwable> causes = new java.util.ArrayList<>();
    for (Throwable cause = failure;
        cause != null && !causes.contains(cause);
        cause = cause.getCause()) {
      causes.add(cause);
    }
    return causes;
  }

  /** 깨진 상태를 만들고, 거부 뒤에 그대로인지 볼 경로를 돌려준다. */
  private Path arrange(Broken broken, Path dataDir) throws Exception {
    switch (broken) {
      case DATA_DIR_IS_A_SYMLINK -> {
        Path real = Files.createDirectory(tmp.resolve("real-data"));
        chmod(real, "rwx------");
        Files.createSymbolicLink(dataDir, real);
        return real;
      }
      case DATA_DIR_HAS_GROUP_BITS -> {
        Files.createDirectory(dataDir);
        chmod(dataDir, "rwxr-x---");
        return dataDir;
      }
      case DB_IS_NOT_SQLITE -> {
        Path db = privateDataDir(dataDir).resolve("queryhub.db");
        IntegrationSqlite.writeNotSqlite(db);
        chmod(db, "rw-------");
        return db;
      }
      case DB_IS_DAMAGED -> {
        Path db = privateDataDir(dataDir).resolve("queryhub.db");
        IntegrationSqlite.writeDamagedDatabase(db);
        chmod(db, "rw-------");
        return db;
      }
      case DB_IS_DAMAGED_WITH_A_LIVE_WAL -> {
        IntegrationSqlite.writeDamagedStoreWithLiveWal(
            tmp.resolve("source"), privateDataDir(dataDir));
        return dataDir.resolve("queryhub.db-wal");
      }
      case NATIVE_DIR_HAS_GROUP_BITS -> {
        Path nativeDir = Files.createDirectory(privateDataDir(dataDir).resolve("native"));
        chmod(nativeDir, "rwxr-xr-x");
        return nativeDir;
      }
      default -> throw new IllegalArgumentException(broken.name());
    }
  }

  private static Path privateDataDir(Path dataDir) throws Exception {
    Files.createDirectory(dataDir);
    chmod(dataDir, "rwx------");
    assertThat(Files.isDirectory(dataDir, LinkOption.NOFOLLOW_LINKS)).isTrue();
    return dataDir;
  }

  private static void start(boolean lazy, Path dataDir, AtomicBoolean webServerStarted) {
    ApplicationListener<ApplicationEvent> webServerListener =
        event -> {
          if (event instanceof WebServerInitializedEvent) {
            webServerStarted.set(true);
          }
        };
    SpringApplication application =
        new SpringApplicationBuilder(QueryHubApplication.class)
            .properties(IntegrationHttp.ADMIN_TOKEN_PROPERTY, IntegrationHttp.MASTER_KEY_PROPERTY)
            .web(WebApplicationType.SERVLET)
            .lazyInitialization(lazy)
            .profiles("desktop")
            .listeners(webServerListener)
            .build();
    try (ConfigurableApplicationContext context =
        application.run(
            "--server.address=127.0.0.1", "--server.port=0", "--queryhub.data-dir=" + dataDir)) {
      assertThat(context.isActive()).isTrue();
    }
  }
}
