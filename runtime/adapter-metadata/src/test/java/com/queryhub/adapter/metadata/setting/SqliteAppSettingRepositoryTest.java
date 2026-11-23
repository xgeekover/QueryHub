package com.queryhub.adapter.metadata.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryhub.adapter.metadata.store.OpenedMetadataStore;
import com.queryhub.adapter.metadata.store.StoreBootstrap;
import com.queryhub.adapter.metadata.store.StoreLayout;
import com.queryhub.adapter.metadata.store.StorePragmas;
import com.queryhub.adapter.metadata.support.TestSqlite;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * P1-06 AC4, AC12: 실제 SQLite 저장소(임시 데이터 디렉터리, V1 마이그레이션, 연결 4 개 Hikari 풀)에서 app_setting 의미와 동시 쓰기.
 *
 * <p>JVM 기본 시간대는 Asia/Seoul 로, 시계의 시간대도 Asia/Seoul 로 둔다. 저장 값이 UTC 가 아니면 9 시간 차이로 드러난다.
 */
class SqliteAppSettingRepositoryTest {

  private static final Instant T0 = Instant.parse("2026-01-02T03:04:05.123456789Z");

  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  private static final int POOL_SIZE = 4;

  private static TimeZone originalZone;

  @TempDir private Path tmp;

  private OpenedMetadataStore store;
  private HikariDataSource dataSource;
  private JdbcClient jdbcClient;

  @BeforeAll
  static void seoulDefaultZone() throws Exception {
    TestSqlite.loadNativeOnce();
    originalZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone(SEOUL));
  }

  @AfterAll
  static void restoreZone() {
    TimeZone.setDefault(originalZone);
  }

  @BeforeEach
  void openStore() {
    store = StoreBootstrap.open(StoreLayout.resolve(tmp.resolve("data").toString(), null));
    dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(store.jdbcUrl());
    dataSource.setPoolName("sqlite-repository-test");
    dataSource.setMaximumPoolSize(POOL_SIZE);
    dataSource.setConnectionTimeout(10_000);
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbcClient = JdbcClient.create(dataSource);
  }

  @AfterEach
  void closeStore() throws Exception {
    dataSource.close();
    store.close();
  }

  @Test
  void keysAreCaseAndTrailingSpaceSensitive() {
    JdbcAppSettingRepository repository = repository(T0);
    repository.putValue("k", "lower");
    repository.putValue("k ", "lower with space");
    repository.putValue("K", "upper");

    assertThat(rowCount()).isEqualTo(3);
    assertThat(repository.findValue("k")).contains("lower");
    assertThat(repository.findValue("k ")).contains("lower with space");
    assertThat(repository.findValue("K")).contains("upper");
    assertThat(repository.findValue("k  ")).isEmpty();
  }

  @Test
  void emptyValueIsStoredAndReadBack() {
    repository(T0).putValue("runtime.empty", "");

    assertThat(repository(T0).findValue("runtime.empty")).contains("");
  }

  @Test
  void hundredCodePointKeyWithSupplementaryCharactersIsStoredAndLongerKeysNeverReachSql() {
    // 코드 포인트 100 개, UTF-16 으로는 150 자 (U+1F600 은 대리 쌍이다).
    String key = "😀".repeat(50) + "k".repeat(50);
    JdbcAppSettingRepository repository = repository(T0);

    repository.putValue(key, "값 😀 𝄞");

    assertThat(repository.findValue(key)).contains("값 😀 𝄞");
    assertThat(
            jdbcClient
                .sql("SELECT length(setting_key) FROM app_setting WHERE setting_key = :key")
                .param("key", key)
                .query(Integer.class)
                .single())
        .as("SQLite 도 코드 포인트로 센다")
        .isEqualTo(100);
    assertThatThrownBy(() -> repository.putValue(key + "k", "value"))
        .as("DB 의 CHECK 가 아니라 저장소가 SQL 전에 거부한다")
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("100");
    assertThat(rowCount()).isEqualTo(1);
  }

  @Test
  void secondPutKeepsCreatedAtAndChangesValueAndUpdatedAt() {
    repository(T0).putValue("runtime.sample", "first");
    repository(T0.plus(Duration.ofHours(1))).putValue("runtime.sample", "second");

    assertThat(repository(T0).findValue("runtime.sample")).contains("second");
    assertThat(rawRow("runtime.sample"))
        .containsExactly(
            "second", "2026-01-02T03:04:05.123456Z", "2026-01-02T04:04:05.123456Z", "text");
    assertThat(rowCount()).isEqualTo(1);
  }

  @Test
  void storedTimestampsAreExactlyTheUtcTextOfTheClockInstant() {
    repository(T0).putValue("runtime.sample", "value");

    // 서울 시각(12:04)도, 밀리초 정수(sqlite-jdbc 기본 날짜 변환)도 아니다.
    assertThat(rawRow("runtime.sample"))
        .containsExactly(
            "value", "2026-01-02T03:04:05.123456Z", "2026-01-02T03:04:05.123456Z", "text");
  }

  @Test
  void schemaRejectsWrongTimestampTextEvenWithoutTheRepository() {
    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql(
                        "INSERT INTO app_setting VALUES ('raw', 'v', '2026-01-02 03:04:05',"
                            + " '2026-01-02T03:04:05.123456Z')")
                    .update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("CHECK constraint failed");
  }

  @Test
  @Timeout(60)
  void concurrentWritersThroughTheFourConnectionPoolAllSucceed() throws Exception {
    int threads = 8;
    int writesPerThread = 50;
    JdbcAppSettingRepository repository = repository(T0);
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int t = 0; t < threads; t++) {
        String key = "thread." + t;
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  for (int i = 0; i < writesPerThread; i++) {
                    repository.putValue(key, "write-" + i);
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        // SQLITE_BUSY 나 다른 실패가 있으면 여기서 ExecutionException 이 난다.
        future.get(50, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(rowCount()).isEqualTo(threads);
    for (int t = 0; t < threads; t++) {
      assertThat(repository.findValue("thread." + t)).contains("write-" + (writesPerThread - 1));
    }
  }

  @Test
  @Timeout(30)
  void writerWaitsForAnotherWriteLockAndThenSucceeds() throws Exception {
    JdbcAppSettingRepository repository = repository(T0);
    try (Connection holder = DriverManager.getConnection(store.layout().plainJdbcUrl());
        Statement statement = holder.createStatement()) {
      statement.execute("BEGIN IMMEDIATE");
      Thread releaser =
          new Thread(
              () -> {
                try {
                  Thread.sleep(1_000);
                  statement.execute("COMMIT");
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
              });
      long started = System.nanoTime();
      releaser.start();
      repository.putValue("busy.key", "after wait");
      Duration waited = Duration.ofNanos(System.nanoTime() - started);
      releaser.join(10_000L);

      assertThat(waited)
          .isGreaterThanOrEqualTo(Duration.ofMillis(800))
          .isLessThan(Duration.ofSeconds(5));
    }
    assertThat(repository.findValue("busy.key")).contains("after wait");
  }

  @Test
  @Timeout(30)
  void writerFailsAfterTheBusyTimeoutInsteadOfHangingAndTheStoreStaysUsable() throws Exception {
    JdbcAppSettingRepository repository = repository(T0);
    try (Connection holder = DriverManager.getConnection(store.layout().plainJdbcUrl());
        Statement statement = holder.createStatement()) {
      statement.execute("BEGIN IMMEDIATE");
      long started = System.nanoTime();

      assertThatThrownBy(() -> repository.putValue("busy.key", "never"))
          .isInstanceOf(DataAccessException.class)
          .hasStackTraceContaining("SQLITE_BUSY");
      Duration waited = Duration.ofNanos(System.nanoTime() - started);
      assertThat(waited)
          .as("busy_timeout=5000 만큼 기다린다")
          .isGreaterThanOrEqualTo(Duration.ofMillis(StorePragmas.BUSY_TIMEOUT_MILLIS))
          .isLessThan(Duration.ofSeconds(15));
      statement.execute("ROLLBACK");
    }

    repository.putValue("busy.key", "usable again");
    assertThat(repository.findValue("busy.key")).contains("usable again");
  }

  private JdbcAppSettingRepository repository(Instant now) {
    return new JdbcAppSettingRepository(jdbcClient, Clock.fixed(now, SEOUL));
  }

  private int rowCount() {
    return jdbcClient.sql("SELECT COUNT(*) FROM app_setting").query(Integer.class).single();
  }

  private List<String> rawRow(String key) {
    return jdbcClient
        .sql(
            "SELECT setting_value, created_at, updated_at, typeof(created_at) FROM app_setting"
                + " WHERE setting_key = :key")
        .param("key", key)
        .query(
            (rs, rowNum) ->
                List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
        .single();
  }
}
