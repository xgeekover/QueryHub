package com.queryhub.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.adapter.metadata.setting.JdbcAppSettingRepository;
import com.queryhub.app.it.support.MetadataDatabaseIntegrationTest;
import com.queryhub.app.it.support.QueryHubIntegrationTest;
import com.queryhub.application.setting.AppSettingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * AC-15, P1-06 AC4: AppSettingRepository 의 실제 SQLite 저장소 왕복(운영과 같은 조립의 컨텍스트). 시각은 저장된 TEXT 값을 그대로
 * 비교한다(드라이버의 변환을 거치지 않는다).
 *
 * <p>테스트 JVM 의 기본 시간대는 Asia/Seoul 이다. 저장 값이 UTC 가 아니면 9 시간 차이로 드러난다.
 */
@QueryHubIntegrationTest
class AppSettingRepositoryIntegrationTest extends MetadataDatabaseIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-01-02T03:04:05.123456Z");

  /** 한글과 4 바이트 UTF-8 문자(U+1F600, U+1D11E)를 담은 값. */
  private static final String UNICODE_VALUE = "런타임 설정 값 😀 𝄞";

  private static final List<String> KEYS =
      List.of("runtime.sample", "runtime.absent", "k", "k ", "K");

  @Autowired private AppSettingRepository contextRepository;

  @Autowired private JdbcClient jdbcClient;

  @BeforeEach
  void removeRowsOfThisTest() {
    jdbcClient
        .sql("DELETE FROM app_setting WHERE setting_key IN (:keys)")
        .param("keys", KEYS)
        .update();
  }

  @Test
  void contextRepositoryIsTheJdbcImplementation() {
    assertThat(contextRepository).isInstanceOf(JdbcAppSettingRepository.class);
  }

  @Test
  void storesAndReadsValuesWithUtcTimestamps() {
    repository(T0).putValue("runtime.sample", UNICODE_VALUE);

    assertThat(contextRepository.findValue("runtime.sample")).contains(UNICODE_VALUE);
    assertThat(rawTimestamps("runtime.sample"))
        .containsExactly("2026-01-02T03:04:05.123456Z", "2026-01-02T03:04:05.123456Z");
  }

  @Test
  void secondPutChangesOnlyTheValueAndUpdatedAt() {
    repository(T0).putValue("runtime.sample", "first");
    repository(T0.plus(Duration.ofHours(1))).putValue("runtime.sample", UNICODE_VALUE);

    assertThat(contextRepository.findValue("runtime.sample")).contains(UNICODE_VALUE);
    assertThat(rawTimestamps("runtime.sample"))
        .containsExactly("2026-01-02T03:04:05.123456Z", "2026-01-02T04:04:05.123456Z");
    assertThat(rowCount("runtime.sample")).isEqualTo(1);
  }

  @Test
  void keysAreCaseAndTrailingSpaceSensitive() {
    JdbcAppSettingRepository repository = repository(T0);
    repository.putValue("k", "lower");
    repository.putValue("k ", "lower with space");
    repository.putValue("K", "upper");

    assertThat(rowCount("k") + rowCount("k ") + rowCount("K")).isEqualTo(3);
    assertThat(repository.findValue("k")).contains("lower");
    assertThat(repository.findValue("k ")).contains("lower with space");
    assertThat(repository.findValue("K")).contains("upper");
  }

  @Test
  void absentKeyIsEmpty() {
    assertThat(contextRepository.findValue("runtime.absent")).isEmpty();
  }

  /** 컨텍스트의 JdbcClient 와 고정 시계로 만든 저장소. 시계의 시간대는 Asia/Seoul 이다(저장 값에 영향이 없어야 한다). */
  private JdbcAppSettingRepository repository(Instant now) {
    return new JdbcAppSettingRepository(jdbcClient, Clock.fixed(now, ZoneId.of("Asia/Seoul")));
  }

  private List<String> rawTimestamps(String key) {
    return jdbcClient
        .sql("SELECT created_at, updated_at FROM app_setting WHERE setting_key = :key")
        .param("key", key)
        .query((rs, rowNum) -> List.of(rs.getString(1), rs.getString(2)))
        .single();
  }

  private int rowCount(String key) {
    return jdbcClient
        .sql("SELECT COUNT(*) FROM app_setting WHERE setting_key = :key")
        .param("key", key)
        .query(Integer.class)
        .single();
  }
}
