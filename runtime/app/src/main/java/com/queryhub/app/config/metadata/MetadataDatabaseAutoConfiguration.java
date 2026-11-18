package com.queryhub.app.config.metadata;

import com.queryhub.adapter.metadata.setting.JdbcAppSettingRepository;
import com.queryhub.application.setting.AppSettingRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Metadata DB(MariaDB) 조립. desktop 과 server 프로파일이 같은 규칙을 쓴다.
 *
 * <ol>
 *   <li>{@link JdbcConnectionDetails} 빈: 환경변수(QUERYHUB_METADATA_DB_*)에서 읽고 검사한 접속 정보다. Boot 의
 *       DataSource 자동 구성보다 먼저 등록되므로({@code before}), Boot 가 이 빈으로 Hikari 풀, Flyway, JdbcClient, db
 *       health 를 만든다.
 *   <li>{@link FlywayConnectionDetails} 빈({@link MetadataFlywayConnectionDetails}): URL 과 사용자를 비워
 *       두어 Flyway 가 따로 접속하지 않고 위 Hikari 풀로 마이그레이션하게 한다. spring.flyway.url·user·password 는 어디서 넘겨도
 *       접속에 쓰이지 않는다. DataSource 자동 구성보다 먼저이므로 그 뒤에 오는 Flyway 자동 구성의
 *       {@code @ConditionalOnMissingBean} 이 이 빈을 본다.
 *   <li>{@link AppSettingRepository} 빈: adapter-metadata 의 JDBC 구현.
 *   <li>기동 검사 빈({@code @Lazy(false)}): 접속 정보를 검사하고 Flyway 마이그레이션을 강제로 실행한다. spring-boot-flyway
 *       4.1.2 에는 LazyInitializationExcludeFilter 가 없어서, spring.main.lazy-initialization=true 이면
 *       Flyway 가 스스로 돌지 않는다. 이 검사가 있으므로 설정이 없거나 틀렸을 때, DB 에 닿지 않을 때, 자격 증명이 거부될 때 기동은 웹 서버가 포트를 열기
 *       전에 실패한다.
 * </ol>
 *
 * <p>비밀번호는 환경변수로만 받는다. 명령행 인자(--queryhub.metadata.db.password=...)나 JVM 시스템 속성(-D...)에 있으면 프로세스 목록에
 * 보이므로 기동을 거부한다. 그 두 곳에서 넘긴 spring.application.json 안의 비밀번호와, 비밀 값이나 드라이버 옵션을 담는
 * spring.datasource.hikari.password·jdbc-url·data-source-properties.* 도 같은 이유로 거부한다. 그 밖의
 * spring.datasource.hikari.* 설정은 운영자 설정이라 거부하지 않는다. 이 클래스는
 * META-INF/spring/...AutoConfiguration.imports 에 등록되어 있어 컴포넌트 스캔에서는 빠진다.
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(MetadataDatabaseProperties.class)
public class MetadataDatabaseAutoConfiguration {

  /** 프로세스 목록에 보이는 속성 소스. Boot 가 보는 순서(앞이 우선)다. */
  private static final List<String> PROCESS_VISIBLE_SOURCES =
      List.of(
          CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME,
          StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

  /**
   * Boot 가 펼친 JSON 을 넣는 속성 소스의 이름. Boot 4.1.2 의
   * SpringApplicationJsonEnvironmentPostProcessor.JsonPropertySource 가 이 이름(속성 이름과 같은 문자열)으로
   * MapPropertySource 를 만든다.
   */
  private static final String JSON_PROPERTY_SOURCE_NAME =
      SpringApplicationJsonEnvironmentPostProcessor.SPRING_APPLICATION_JSON_PROPERTY;

  /** Boot 가 JSON 을 찾는 두 이름. */
  private static final List<String> JSON_PROPERTY_NAMES =
      List.of(
          SpringApplicationJsonEnvironmentPostProcessor.SPRING_APPLICATION_JSON_PROPERTY,
          SpringApplicationJsonEnvironmentPostProcessor
              .SPRING_APPLICATION_JSON_ENVIRONMENT_VARIABLE);

  private static final ConfigurationPropertyName JSON_PROPERTY =
      ConfigurationPropertyName.of(
          SpringApplicationJsonEnvironmentPostProcessor.SPRING_APPLICATION_JSON_PROPERTY);

  private static final ConfigurationPropertyName PASSWORD =
      ConfigurationPropertyName.of(MetadataDatabaseProperties.PASSWORD_PROPERTY);

  private static final List<ConfigurationPropertyName> PROTECTED_HIKARI_KEYS =
      List.of(
          ConfigurationPropertyName.of("spring.datasource.hikari.password"),
          ConfigurationPropertyName.of("spring.datasource.hikari.jdbc-url"));

  private static final ConfigurationPropertyName HIKARI_DATA_SOURCE_PROPERTIES =
      ConfigurationPropertyName.of("spring.datasource.hikari.data-source-properties");

  @Bean
  public JdbcConnectionDetails metadataJdbcConnectionDetails(
      Environment environment, MetadataDatabaseProperties properties) {
    rejectPasswordOutsideEnvironmentVariables(environment);
    return properties.toConnectionDetails();
  }

  @Bean
  public FlywayConnectionDetails metadataFlywayConnectionDetails() {
    return new MetadataFlywayConnectionDetails();
  }

  @Bean
  public AppSettingRepository appSettingRepository(JdbcClient jdbcClient, Clock clock) {
    return new JdbcAppSettingRepository(jdbcClient, clock);
  }

  @Bean
  @Lazy(false)
  public MetadataDatabaseStartupCheck metadataDatabaseStartupCheck(
      JdbcConnectionDetails metadataJdbcConnectionDetails,
      ObjectProvider<FlywayMigrationInitializer> flywayInitializer,
      ObjectProvider<DataSource> dataSource) {
    return new MetadataDatabaseStartupCheck(
        metadataJdbcConnectionDetails, flywayInitializer, dataSource);
  }

  /**
   * 보호하는 키가 프로세스 목록에 보이는 곳에서 들어왔으면 기동을 거부한다. 메시지는 키나 변수 이름만 말하고 값은 넣지 않는다.
   *
   * <p>프로세스 목록에 보이는 곳: 명령행 인자, JVM 시스템 속성, 그리고 spring.application.json 속성 소스 중 JSON 이 명령행 인자나 시스템
   * 속성에서 온 것. Boot 4.1.2 의 {@link SpringApplicationJsonEnvironmentPostProcessor} 는 모든 속성 소스를 앞에서부터
   * 보며 각 소스에 {@code spring.application.json} 과 {@code SPRING_APPLICATION_JSON} 을 묻고, 처음 나온 비어 있지 않은
   * JSON 하나만 펼쳐서 {@code spring.application.json} 이라는 이름의 속성 소스로 넣는다. 이때 맨 앞에 붙어 있는 {@code
   * configurationProperties} 소스(ConfigurationPropertySources.attach)는 느슨한 바인딩으로 뒤의 모든 소스를 순서대로
   * 찾으므로, {@code --spring.Application.JSON=...} 이나 {@code -Dspring.APPLICATION.json=...} 같은 다른 표기도
   * 펼쳐진다. 그래서 여기서도 정확한 두 이름과 느슨한 바인딩의 다른 표기를 모두 본다. 명령행 인자와 시스템 속성은 OS 환경변수보다 앞이므로, 둘 중 하나에 비어 있지
   * 않은 JSON 이 있으면 그 속성 소스는 거기서 온 것이다. OS 환경변수의 SPRING_APPLICATION_JSON 은 허용한다.
   *
   * <p>보호하는 키(느슨한 바인딩의 다른 표기도 같은 키로 본다): {@value MetadataDatabaseProperties#PASSWORD_PROPERTY},
   * {@code spring.datasource.hikari.password}, {@code spring.datasource.hikari.jdbc-url}, {@code
   * spring.datasource.hikari.data-source-properties} 아래의 모든 키. 이 Hikari 키들은 비밀 값(비밀번호, URL 안의
   * 비밀번호)이나 드라이버 옵션(예: allowLocalInfile)을 담는다. Boot 4.1.2 는 바인딩 뒤에 Hikari 의
   * jdbcUrl·username·password·driverClassName 을 검사한 접속 정보로
   * 되돌리므로(HikariJdbcConnectionDetailsBeanPostProcessor) hikari.password 와 jdbc-url 은 실제 접속에 쓰이지
   * 않지만, 값이 프로세스 목록에 보이므로 방어적으로 거부한다. 그 밖의 spring.datasource.hikari.* 설정은 운영자 설정이다.
   * spring.flyway.url·user·password 는 {@link MetadataFlywayConnectionDetails} 때문에 접속에 쓰이지 않으므로 여기서
   * 보지 않는다. 설정 파일은 대상이 아니다.
   */
  static void rejectPasswordOutsideEnvironmentVariables(Environment environment) {
    if (!(environment instanceof ConfigurableEnvironment configurable)) {
      return;
    }
    MutablePropertySources sources = configurable.getPropertySources();
    Map<String, String> processVisible = new LinkedHashMap<>();
    for (String sourceName : PROCESS_VISIBLE_SOURCES) {
      processVisible.put(sourceName, describe(sourceName));
    }
    jsonOrigin(sources)
        .ifPresent(
            origin ->
                processVisible.put(
                    JSON_PROPERTY_SOURCE_NAME,
                    describe(origin) + "의 " + JSON_PROPERTY_SOURCE_NAME));
    for (Map.Entry<String, String> entry : processVisible.entrySet()) {
      PropertySource<?> source = sources.get(entry.getKey());
      ConfigurationPropertySource adapted =
          source == null ? null : ConfigurationPropertySource.from(source);
      if (adapted != null) {
        rejectProtectedKeys(adapted, entry.getValue());
      }
    }
  }

  private static void rejectProtectedKeys(ConfigurationPropertySource source, String description) {
    if (source.getConfigurationProperty(PASSWORD) != null) {
      throw new IllegalStateException(
          "Metadata DB 비밀번호("
              + MetadataDatabaseProperties.PASSWORD_PROPERTY
              + ")가 "
              + description
              + "로 넘어와 런타임을 시작하지 않는다. 비밀 값은 프로세스 목록에 보이는 명령행 인자나 JVM 시스템 속성(spring.application.json"
              + " 포함)으로 넘기지 않는다. "
              + MetadataDatabaseProperties.ENV_PASSWORD
              + " 환경변수로 넘겨라.");
    }
    for (ConfigurationPropertyName key : PROTECTED_HIKARI_KEYS) {
      if (source.getConfigurationProperty(key) != null) {
        throw rejectedHikariKey(key.toString(), description);
      }
    }
    if (source.getConfigurationProperty(HIKARI_DATA_SOURCE_PROPERTIES) != null
        || source.containsDescendantOf(HIKARI_DATA_SOURCE_PROPERTIES)
            == ConfigurationPropertyState.PRESENT) {
      throw rejectedHikariKey(HIKARI_DATA_SOURCE_PROPERTIES + ".*", description);
    }
  }

  private static IllegalStateException rejectedHikariKey(String key, String description) {
    return new IllegalStateException(
        "Metadata DB 접속 설정 "
            + key
            + " 가 "
            + description
            + "로 넘어와 런타임을 시작하지 않는다. 이 키는 비밀 값이나 드라이버 옵션을 담으므로 프로세스 목록에 보이는 곳에서 받지 않는다. 이 키를 빼고 Metadata DB"
            + " 접속 정보는 "
            + MetadataDatabaseProperties.ENV_PREFIX
            + "* 환경변수("
            + MetadataDatabaseProperties.ENV_HOST
            + ", "
            + MetadataDatabaseProperties.ENV_PASSWORD
            + " 등)로 넘겨라.");
  }

  /**
   * spring.application.json 속성 소스가 명령행 인자나 시스템 속성에서 왔으면 그 출처(속성 소스 이름)를 돌려준다. Boot 와 같은 순서(명령행 인자,
   * 시스템 속성)로 보고, Boot 처럼 값이 빈 문자열이면 없는 것으로 본다. {@code spring.application.json} 은 느슨한 바인딩의 다른 표기도
   * 찾는다.
   */
  private static Optional<String> jsonOrigin(MutablePropertySources sources) {
    if (!sources.contains(JSON_PROPERTY_SOURCE_NAME)) {
      return Optional.empty();
    }
    for (String sourceName : PROCESS_VISIBLE_SOURCES) {
      PropertySource<?> source = sources.get(sourceName);
      if (source != null && definesJson(source)) {
        return Optional.of(sourceName);
      }
    }
    return Optional.empty();
  }

  private static boolean definesJson(PropertySource<?> source) {
    for (String name : JSON_PROPERTY_NAMES) {
      if (hasLength(source.getProperty(name))) {
        return true;
      }
    }
    ConfigurationPropertySource adapted = ConfigurationPropertySource.from(source);
    ConfigurationProperty relaxed =
        adapted == null ? null : adapted.getConfigurationProperty(JSON_PROPERTY);
    return relaxed != null && hasLength(relaxed.getValue());
  }

  private static boolean hasLength(@Nullable Object value) {
    return value instanceof String text && !text.isEmpty();
  }

  private static String describe(String sourceName) {
    return CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME.equals(sourceName)
        ? "명령행 인자"
        : "JVM 시스템 속성(-D)";
  }
}
