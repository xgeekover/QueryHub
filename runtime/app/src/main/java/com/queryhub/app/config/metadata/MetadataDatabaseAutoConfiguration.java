package com.queryhub.app.config.metadata;

import com.queryhub.adapter.metadata.setting.JdbcAppSettingRepository;
import com.queryhub.adapter.metadata.store.OpenedMetadataStore;
import com.queryhub.adapter.metadata.store.StoreBootstrap;
import com.queryhub.application.setting.AppSettingRepository;
import java.time.Clock;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Metadata 저장소(내장 SQLite, P1-06, ADR-026) 조립. desktop 과 server 프로파일이 같은 규칙을 쓴다.
 *
 * <ol>
 *   <li>{@link OpenedMetadataStore} 빈: 설정({@link MetadataStoreProperties}, QUERYHUB_DATA_DIR)으로
 *       저장소를 검사하고 연다({@link StoreBootstrap#open}). 데이터 디렉터리·파일 규칙, 네트워크 파일 시스템, {@code
 *       queryhub.lock} 잠금, 네이티브 라이브러리 디렉터리(org.sqlite.tmpdir), DB 파일 머리글, quick_check, 부트스트랩 연결의
 *       PRAGMA 를 본다. 잠금은 컨텍스트가 닫힐 때 푼다(destroy). 이 빈에 기대는 DataSource 가 먼저 닫힌다.
 *   <li>{@link JdbcConnectionDetails} 빈: 위 저장소의 URL({@code jdbc:sqlite:...}). Boot 의 DataSource 자동
 *       구성보다 먼저 등록되므로({@code before}), Boot 가 이 빈으로 Hikari 풀, Flyway, JdbcClient, db health 를 만든다.
 *       그래서 DataSource 나 Flyway 빈이 생기기 전에 저장소 검사가 끝난다.
 *   <li>{@link FlywayConnectionDetails} 빈({@link MetadataFlywayConnectionDetails}): URL 과 사용자를 비워
 *       두어 Flyway 가 따로 접속하지 않고 위 Hikari 풀로 마이그레이션하게 한다(ADR-012). spring.flyway.url·user·password 는
 *       어디서 넘겨도 접속에 쓰이지 않는다.
 *   <li>{@link AppSettingRepository} 빈: adapter-metadata 의 JDBC 구현.
 *   <li>기동 검사 빈({@code @Lazy(false)}, {@link MetadataDatabaseStartupCheck}): 풀 연결이 연 파일과 PRAGMA 를
 *       확인하고 Flyway 마이그레이션을 강제로 실행한다. spring-boot-flyway 4.1.2 에는 LazyInitializationExcludeFilter 가
 *       없어서, spring.main.lazy-initialization=true 이면 Flyway 가 스스로 돌지 않는다.
 * </ol>
 *
 * <p>어느 단계든 실패하면 기동은 웹 서버가 포트를 열기 전에 멈춘다. 메시지는 설정 이름과 경로, 고치는 방법을 말하고 비밀 값은 담지 않는다. 이 클래스는
 * META-INF/spring/...AutoConfiguration.imports 에 등록되어 있어 컴포넌트 스캔에서는 빠진다.
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(MetadataStoreProperties.class)
public class MetadataDatabaseAutoConfiguration {

  private static final Log LOG = LogFactory.getLog(MetadataDatabaseAutoConfiguration.class);

  @Bean(destroyMethod = "close")
  public OpenedMetadataStore metadataStore(MetadataStoreProperties properties) {
    OpenedMetadataStore store = StoreBootstrap.open(properties.toLayout());
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Metadata 저장소를 열었다: "
              + store.layout().dbFile()
              + " (네이티브 라이브러리 디렉터리 "
              + store.layout().nativeDir()
              + ")");
    }
    return store;
  }

  @Bean
  public JdbcConnectionDetails metadataJdbcConnectionDetails(OpenedMetadataStore metadataStore) {
    return new MetadataJdbcConnectionDetails(metadataStore.jdbcUrl());
  }

  @Bean
  public FlywayConnectionDetails metadataFlywayConnectionDetails() {
    return new MetadataFlywayConnectionDetails();
  }

  @Bean
  public AppSettingRepository appSettingRepository(JdbcClient jdbcClient, Clock clock) {
    return new JdbcAppSettingRepository(jdbcClient, clock);
  }

  /** DataSource 빈이 만들어진 직후, Flyway 를 포함해 누구도 풀을 쓰기 전에 SQLite 설정을 확인한다. */
  @Bean
  static MetadataPoolCheck metadataPoolCheck(ObjectProvider<OpenedMetadataStore> metadataStore) {
    return new MetadataPoolCheck(metadataStore);
  }

  @Bean
  @Lazy(false)
  public MetadataDatabaseStartupCheck metadataDatabaseStartupCheck(
      OpenedMetadataStore metadataStore,
      JdbcConnectionDetails metadataJdbcConnectionDetails,
      ObjectProvider<FlywayMigrationInitializer> flywayInitializer,
      ObjectProvider<DataSource> dataSource) {
    return new MetadataDatabaseStartupCheck(
        metadataStore, metadataJdbcConnectionDetails, flywayInitializer, dataSource);
  }
}
