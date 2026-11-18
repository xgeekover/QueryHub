package com.queryhub.app.config.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * R10: Flyway 는 spring.flyway.url·user·password 가 어느 속성 소스에 있든 검사한 접속 정보로 만든 주 DataSource(Hikari
 * 풀)로 마이그레이션한다. {@link MetadataFlywayConnectionDetails} 빈이 Boot 의 PropertiesFlywayConnectionDetails
 * 를 이기기 때문이다.
 *
 * <p>DB 없이 돈다. 마이그레이션 전략을 아무것도 하지 않는 것으로 바꿔 연결을 열지 않고, Boot 가 만든 Flyway 의 DataSource 를 본다. 자동 구성은
 * 일부러 Flyway 를 맨 앞에 적는다. 순서는 Boot 의 정렬(before/after)이 정한다.
 */
class MetadataFlywayConnectionTest {

  private static final String PASSWORD_MARKER = "secret-marker-flyway-3d8a51";

  /** 검사한 접속 대상과 다른 포트, 다른 DB, 켠 LOCAL INFILE, URL 안의 비밀번호. */
  private static final String FLYWAY_URL =
      "jdbc:mariadb://127.0.0.1:2/otherdb?allowLocalInfile=true&password=" + PASSWORD_MARKER;

  private static final String FLYWAY_USER = "migrator";

  private static final String CHECKED_URL_PREFIX = "jdbc:mariadb://127.0.0.1:1/queryhub?";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  FlywayAutoConfiguration.class,
                  JdbcClientAutoConfiguration.class,
                  JdbcTemplateAutoConfiguration.class,
                  DataSourceAutoConfiguration.class,
                  MetadataDatabaseAutoConfiguration.class))
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(FlywayMigrationStrategy.class, () -> flyway -> {})
          .withPropertyValues(
              "queryhub.metadata.db.host=127.0.0.1",
              "queryhub.metadata.db.port=1",
              "queryhub.metadata.db.name=queryhub",
              "queryhub.metadata.db.username=queryhub",
              "queryhub.metadata.db.password=" + PASSWORD_MARKER);

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "commandLineArgs",
        "systemProperties",
        "commandLineJson",
        "systemEnvironment",
        "configFile"
      })
  void flywayMigratesThroughTheCheckedMainDataSourceWhateverTheFlywayKeysSay(String source) {
    runner
        .withInitializer(context -> addFlywayKeys(context, source))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getEnvironment().getProperty("spring.flyway.url"))
                  .as("Boot 는 %s 의 spring.flyway.url 을 본다", source)
                  .isEqualTo(FLYWAY_URL);
              assertThat(context.getEnvironment().getProperty("spring.flyway.user"))
                  .isEqualTo(FLYWAY_USER);

              assertThat(context.getBeanNamesForType(FlywayConnectionDetails.class))
                  .as("Boot 의 PropertiesFlywayConnectionDetails 는 만들어지지 않는다")
                  .containsExactly("metadataFlywayConnectionDetails");
              FlywayConnectionDetails details = context.getBean(FlywayConnectionDetails.class);
              assertThat(details).isInstanceOf(MetadataFlywayConnectionDetails.class);
              assertThat(details.getJdbcUrl()).isNull();
              assertThat(details.getUsername()).isNull();
              assertThat(details.getPassword()).isNull();
              assertThat(details.getDriverClassName()).isNull();

              DataSource main = context.getBean(DataSource.class);
              assertThat(main.getClass().getName()).isEqualTo("com.zaxxer.hikari.HikariDataSource");
              assertThat((String) new BeanWrapperImpl(main).getPropertyValue("jdbcUrl"))
                  .as("주 풀은 검사한 접속 정보로 만들어진다")
                  .startsWith(CHECKED_URL_PREFIX)
                  .endsWith("allowLocalInfile=false");
              assertThat(context.getBean(Flyway.class).getConfiguration().getDataSource())
                  .as("Flyway 는 주 풀로 마이그레이션한다")
                  .isSameAs(main);
            });
  }

  /** 대조: 이 빈이 없으면 Boot 는 spring.flyway.url 로 따로 DataSource 를 만든다. 위 테스트가 그 차이를 잡는다는 증거다. */
  @Test
  void withoutTheBeanBootMigratesThroughASeparateDataSource() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(FlywayAutoConfiguration.class, DataSourceAutoConfiguration.class))
        .withBean(FlywayMigrationStrategy.class, () -> flyway -> {})
        .withPropertyValues(
            "spring.datasource.url=" + CHECKED_URL_PREFIX + "allowLocalInfile=false",
            "spring.flyway.url=" + FLYWAY_URL,
            "spring.flyway.user=" + FLYWAY_USER)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(FlywayConnectionDetails.class))
                  .isNotInstanceOf(MetadataFlywayConnectionDetails.class);
              DataSource migration =
                  context.getBean(Flyway.class).getConfiguration().getDataSource();
              assertThat(migration)
                  .isNotSameAs(context.getBean(DataSource.class))
                  .isInstanceOf(SimpleDriverDataSource.class);
              assertThat(((SimpleDriverDataSource) migration).getUrl()).isEqualTo(FLYWAY_URL);
            });
  }

  /**
   * spring.flyway.url·user·password 를 주어진 종류의 속성 소스에 넣는다. 실제 JVM 시스템 속성과 환경변수는 건드리지 않고 그 자리의 소스를
   * 바꾼다.
   */
  private static void addFlywayKeys(ConfigurableApplicationContext context, String source) {
    ConfigurableEnvironment environment = context.getEnvironment();
    MutablePropertySources sources = environment.getPropertySources();
    Map<String, Object> keys =
        Map.of(
            "spring.flyway.url", FLYWAY_URL,
            "spring.flyway.user", FLYWAY_USER,
            "spring.flyway.password", PASSWORD_MARKER);
    switch (source) {
      case "commandLineArgs" ->
          sources.addFirst(
              new SimpleCommandLinePropertySource(
                  "--spring.flyway.url=" + FLYWAY_URL,
                  "--spring.flyway.user=" + FLYWAY_USER,
                  "--spring.flyway.password=" + PASSWORD_MARKER));
      case "systemProperties" ->
          sources.replace(
              StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
              new MapPropertySource(
                  StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, keys));
      case "commandLineJson" -> {
        String json =
            "{\"spring\":{\"flyway\":{\"url\":\""
                + FLYWAY_URL
                + "\",\"user\":\""
                + FLYWAY_USER
                + "\",\"password\":\""
                + PASSWORD_MARKER
                + "\"}}}";
        sources.addFirst(new SimpleCommandLinePropertySource("--spring.application.json=" + json));
        ConfigurationPropertySources.attach(environment);
        new SpringApplicationJsonEnvironmentPostProcessor()
            .postProcessEnvironment(environment, new SpringApplication());
        assertThat(sources.contains("spring.application.json")).isTrue();
      }
      case "systemEnvironment" ->
          sources.replace(
              StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
              new SystemEnvironmentPropertySource(
                  StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                  Map.of(
                      "SPRING_FLYWAY_URL", FLYWAY_URL,
                      "SPRING_FLYWAY_USER", FLYWAY_USER,
                      "SPRING_FLYWAY_PASSWORD", PASSWORD_MARKER)));
      case "configFile" ->
          sources.addLast(
              new MapPropertySource(
                  "Config resource 'class path resource [application.yml]' via location"
                      + " 'optional:classpath:/'",
                  keys));
      default -> throw new IllegalArgumentException(source);
    }
  }
}
