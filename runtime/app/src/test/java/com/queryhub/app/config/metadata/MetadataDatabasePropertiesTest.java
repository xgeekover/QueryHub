package com.queryhub.app.config.metadata;

import static com.queryhub.app.config.metadata.MetadataDatabaseProperties.ENV_HOST;
import static com.queryhub.app.config.metadata.MetadataDatabaseProperties.ENV_NAME;
import static com.queryhub.app.config.metadata.MetadataDatabaseProperties.ENV_PASSWORD;
import static com.queryhub.app.config.metadata.MetadataDatabaseProperties.ENV_PORT;
import static com.queryhub.app.config.metadata.MetadataDatabaseProperties.ENV_USERNAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * AC-07: 환경변수 바인딩, 기본값, 검사 규칙, JDBC URL, 비밀번호 가림. Spring 컨텍스트 없이 Boot 의 Binder 로 확인한다.
 *
 * <p>테스트 값의 비밀번호는 표식 문자열이다. 어떤 메시지와 toString 에도 나오면 안 된다.
 */
class MetadataDatabasePropertiesTest {

  private static final String PASSWORD_MARKER = "secret-marker-props-91b0e4";

  private static final String OPTIONS =
      "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true"
          + "&allowLocalInfile=false";

  /*
   * 거부 메시지에서 출처에 따라 바뀌는 부분. 비밀번호 메시지의 고정 문구("명령행 인자나 JVM 시스템 속성(spring.application.json 포함)")
   * 에는 이 문자열들이 없으므로, 출처를 잘못 말하면 이 검사가 실패한다.
   */
  private static final String FROM_COMMAND_LINE = "가 명령행 인자로 넘어와";
  private static final String FROM_SYSTEM_PROPERTIES = "가 JVM 시스템 속성(-D)로 넘어와";
  private static final String FROM_COMMAND_LINE_JSON = "가 명령행 인자의 spring.application.json로 넘어와";
  private static final String FROM_SYSTEM_PROPERTIES_JSON =
      "가 JVM 시스템 속성(-D)의 spring.application.json로 넘어와";

  @Test
  void bindsTheQueryHubEnvironmentVariables() {
    MetadataDatabaseProperties properties =
        bindFromEnvironment(
            env(
                ENV_HOST, "db.internal.example",
                ENV_PORT, "3307",
                ENV_NAME, "queryhub_meta",
                ENV_USERNAME, "qh_app",
                ENV_PASSWORD, PASSWORD_MARKER));

    assertThat(properties.host()).isEqualTo("db.internal.example");
    assertThat(properties.port()).isEqualTo("3307");
    assertThat(properties.name()).isEqualTo("queryhub_meta");
    assertThat(properties.username()).isEqualTo("qh_app");
    assertThat(properties.password()).isEqualTo(PASSWORD_MARKER);

    MetadataJdbcConnectionDetails details = properties.toConnectionDetails();
    assertThat(details.getJdbcUrl())
        .isEqualTo("jdbc:mariadb://db.internal.example:3307/queryhub_meta?" + OPTIONS);
    assertThat(details.getUsername()).isEqualTo("qh_app");
    assertThat(details.getPassword()).isEqualTo(PASSWORD_MARKER);
    assertThat(details.getDriverClassName()).isEqualTo("org.mariadb.jdbc.Driver");
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent", "", "  "})
  void portDefaultsTo3306WhenAbsentOrBlank(String port) {
    Map<String, Object> env = validEnv();
    if (port.equals("absent")) {
      env.remove(ENV_PORT);
    } else {
      env.put(ENV_PORT, port);
    }

    MetadataJdbcConnectionDetails details = bindFromEnvironment(env).toConnectionDetails();

    assertThat(details.getJdbcUrl()).isEqualTo("jdbc:mariadb://127.0.0.1:3306/queryhub?" + OPTIONS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "65536", "99999", "-1", "abc", "33o6", "3306 ", "+3306", "1e3"})
  void rejectsPortsOutsideTheRangeOrNotNumeric(String port) {
    Map<String, Object> env = validEnv();
    env.put(ENV_PORT, port);

    assertRejectedNaming(bindFromEnvironment(env), ENV_PORT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "3306", "65535"})
  void acceptsPortsInTheRange(String port) {
    Map<String, Object> env = validEnv();
    env.put(ENV_PORT, port);

    assertThat(bindFromEnvironment(env).toConnectionDetails().getJdbcUrl())
        .startsWith("jdbc:mariadb://127.0.0.1:" + port + "/queryhub?");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "db/x",
        "db?x",
        "db&x",
        "db#x",
        "user@db",
        "db;x",
        "db=x",
        "db x",
        "db\tx",
        "db:3306",
        "::1",
        "-db",
        "db.",
        "[::1",
        "[db.example]"
      })
  void rejectsHostsThatCouldChangeTheUrl(String host) {
    Map<String, Object> env = validEnv();
    env.put(ENV_HOST, host);

    assertRejectedNaming(bindFromEnvironment(env), ENV_HOST);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"localhost", "127.0.0.1", "db-1.internal.example", "[::1]", "[2001:db8::7]"})
  void acceptsHostNamesIpv4AndBracketedIpv6(String host) {
    Map<String, Object> env = validEnv();
    env.put(ENV_HOST, host);

    assertThat(bindFromEnvironment(env).toConnectionDetails().getJdbcUrl())
        .isEqualTo("jdbc:mariadb://" + host + ":3306/queryhub?" + OPTIONS);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "queryhub?allowLocalInfile=true",
        "queryhub&allowLocalInfile=true",
        "queryhub#x",
        "query/hub",
        "query@hub",
        "query;hub",
        "query=hub",
        "query hub",
        "query-hub",
        "query.hub"
      })
  void rejectsDatabaseNamesOutsideTheSafeSet(String name) {
    Map<String, Object> env = validEnv();
    env.put(ENV_NAME, name);

    assertRejectedNaming(bindFromEnvironment(env), ENV_NAME);
  }

  @Test
  void databaseNameMayHaveUpTo64Characters() {
    Map<String, Object> env = validEnv();
    env.put(ENV_NAME, "q".repeat(64));
    assertThatCode(() -> bindFromEnvironment(env).toConnectionDetails()).doesNotThrowAnyException();

    env.put(ENV_NAME, "q".repeat(65));
    assertRejectedNaming(bindFromEnvironment(env), ENV_NAME);
  }

  @Test
  void missingRequiredVariablesAreAllNamedAndBlankCountsAsMissing() {
    MetadataDatabaseProperties properties =
        bindFromEnvironment(env(ENV_HOST, " ", ENV_USERNAME, "", ENV_PASSWORD, "   "));

    IllegalStateException failure =
        catchThrowableOfType(IllegalStateException.class, properties::toConnectionDetails);

    assertThat(failure.getMessage())
        .contains(ENV_HOST, ENV_NAME, ENV_USERNAME, ENV_PASSWORD)
        .doesNotContain(ENV_PORT);
  }

  @Test
  void validationMessagesNeverContainThePassword() {
    // 다른 값이 모두 틀려도 메시지에 비밀번호가 나오지 않는다. 다른 값도 되풀이하지 않는다.
    MetadataDatabaseProperties properties =
        bindFromEnvironment(
            env(
                ENV_HOST, "evil host",
                ENV_PORT, "0",
                ENV_NAME, "x?y",
                ENV_USERNAME, " ",
                ENV_PASSWORD, PASSWORD_MARKER));

    IllegalStateException failure =
        catchThrowableOfType(IllegalStateException.class, properties::toConnectionDetails);

    assertThat(failure.getMessage())
        .contains(ENV_HOST, ENV_PORT, ENV_NAME, ENV_USERNAME)
        .doesNotContain(ENV_PASSWORD, PASSWORD_MARKER, "evil host", "x?y");
  }

  @Test
  void urlContainsNeitherUsernameNorPassword() {
    MetadataJdbcConnectionDetails details = bindFromEnvironment(validEnv()).toConnectionDetails();

    assertThat(details.getJdbcUrl())
        .doesNotContain("qh_user")
        .doesNotContain(PASSWORD_MARKER)
        .doesNotContainIgnoringCase("password")
        .doesNotContainIgnoringCase("user=");
  }

  @Test
  void toStringMasksThePassword() {
    MetadataDatabaseProperties properties = bindFromEnvironment(validEnv());

    assertThat(properties.toString())
        .doesNotContain(PASSWORD_MARKER)
        .contains("password=******")
        .contains("host=127.0.0.1");
    assertThat(properties.toConnectionDetails().toString())
        .doesNotContain(PASSWORD_MARKER)
        .contains("password=******");
    assertThat(new MetadataDatabaseProperties(null, null, null, null, null).toString())
        .contains("password=null");
  }

  @Test
  void passwordFromCommandLineOrSystemPropertiesIsRejectedInAnyRelaxedForm() {
    for (String argument :
        new String[] {
          "--queryhub.metadata.db.password=" + PASSWORD_MARKER,
          "--queryhub.metadata.db.PASSWORD=" + PASSWORD_MARKER,
          "--queryhub.metadata.db[password]=" + PASSWORD_MARKER
        }) {
      StandardEnvironment environment = new StandardEnvironment();
      environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(argument));

      assertThatThrownBy(
              () ->
                  MetadataDatabaseAutoConfiguration.rejectPasswordOutsideEnvironmentVariables(
                      environment))
          .as(argument)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(ENV_PASSWORD)
          .hasMessageContaining(FROM_COMMAND_LINE)
          .hasMessageNotContaining(PASSWORD_MARKER);
    }

    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
            new MapPropertySource(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                Map.of(MetadataDatabaseProperties.PASSWORD_PROPERTY, PASSWORD_MARKER)));
    assertThatThrownBy(
            () ->
                MetadataDatabaseAutoConfiguration.rejectPasswordOutsideEnvironmentVariables(
                    environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ENV_PASSWORD)
        .hasMessageContaining(FROM_SYSTEM_PROPERTIES)
        .hasMessageNotContaining(PASSWORD_MARKER);
  }

  @Test
  void passwordFromEnvironmentVariablesOrOtherSourcesIsAccepted() {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of(ENV_PASSWORD, PASSWORD_MARKER)));
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "defaultProperties",
                Map.of(MetadataDatabaseProperties.PASSWORD_PROPERTY, PASSWORD_MARKER)));

    assertThatCode(
            () ->
                MetadataDatabaseAutoConfiguration.rejectPasswordOutsideEnvironmentVariables(
                    environment))
        .doesNotThrowAnyException();
    assertThat(environment.getProperty(MetadataDatabaseProperties.PASSWORD_PROPERTY))
        .isEqualTo(PASSWORD_MARKER);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "--spring.application.json=",
        "--SPRING_APPLICATION_JSON=",
        "-Dspring.application.json=",
        "-DSPRING_APPLICATION_JSON=",
        // R12: Boot 는 붙어 있는 configurationProperties 소스로 느슨한 바인딩의 다른 표기도 펼친다
        "--spring.Application.JSON=",
        "-Dspring.APPLICATION.json="
      })
  void passwordInSpringApplicationJsonFromCommandLineOrSystemPropertiesIsRejected(String prefix) {
    for (String json :
        new String[] {
          "{\"queryhub\":{\"metadata\":{\"db\":{\"password\":\"" + PASSWORD_MARKER + "\"}}}}",
          "{\"queryhub.metadata.db.PASSWORD\":\"" + PASSWORD_MARKER + "\"}"
        }) {
      StandardEnvironment environment = processVisible(prefix + json);
      applySpringApplicationJson(environment);
      // Boot 4.1.2 는 펼친 JSON 을 이 이름의 속성 소스로 넣는다. 이름이 바뀌면 검사가 그 소스를 놓친다.
      assertThat(environment.getPropertySources().contains("spring.application.json"))
          .as("Boot 가 만든 JSON 속성 소스 이름")
          .isTrue();

      assertThatThrownBy(
              () ->
                  MetadataDatabaseAutoConfiguration.rejectPasswordOutsideEnvironmentVariables(
                      environment))
          .as(prefix + json)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(ENV_PASSWORD)
          .hasMessageContaining(
              prefix.startsWith("--") ? FROM_COMMAND_LINE_JSON : FROM_SYSTEM_PROPERTIES_JSON)
          .hasMessageNotContaining(PASSWORD_MARKER);
    }
  }

  @Test
  void springApplicationJsonFromTheOsEnvironmentIsAccepted() {
    String json =
        "{\"queryhub\":{\"metadata\":{\"db\":{\"password\":\"" + PASSWORD_MARKER + "\"}}}}";
    for (String commandLine :
        new String[] {
          // 명령행에 아무것도 없을 때
          "--server.port=0",
          // 빈 값은 Boot 도 없는 것으로 보고 환경변수의 JSON 을 쓴다
          "--spring.application.json=",
          // 느슨한 바인딩의 다른 표기로 준 빈 값도 마찬가지다(R12)
          "--spring.Application.JSON=",
          // 명령행 JSON 에 보호하는 키가 없을 때 (Boot 는 이 JSON 하나만 쓴다)
          "--spring.application.json={\"queryhub\":{\"note\":\"x\"}}"
        }) {
      StandardEnvironment environment = new StandardEnvironment();
      environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(commandLine));
      environment
          .getPropertySources()
          .replace(
              StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
              new SystemEnvironmentPropertySource(
                  StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                  Map.of("SPRING_APPLICATION_JSON", json)));
      applySpringApplicationJson(environment);

      assertThatCode(
              () ->
                  MetadataDatabaseAutoConfiguration.rejectPasswordOutsideEnvironmentVariables(
                      environment))
          .as(commandLine)
          .doesNotThrowAnyException();
      assertThat(environment.getPropertySources().contains("spring.application.json")).isTrue();
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "spring.datasource.hikari.password | "
            + PASSWORD_MARKER
            + " | spring.datasource.hikari.password",
        "spring.datasource.hikari.PASSWORD | "
            + PASSWORD_MARKER
            + " | spring.datasource.hikari.password",
        "spring.datasource.hikari.jdbc-url | jdbc:mariadb://127.0.0.1:3306/qh?password="
            + PASSWORD_MARKER
            + " | spring.datasource.hikari.jdbc-url",
        "spring.datasource.hikari.jdbcUrl | jdbc:mariadb://127.0.0.1:3306/qh?password="
            + PASSWORD_MARKER
            + " | spring.datasource.hikari.jdbc-url",
        "spring.datasource.hikari.data-source-properties.password | "
            + PASSWORD_MARKER
            + " | spring.datasource.hikari.data-source-properties.*",
        "spring.datasource.hikari.dataSourceProperties.allowLocalInfile | true"
            + " | spring.datasource.hikari.data-source-properties.*",
        "spring.datasource.hikari.data-source-properties[allowLocalInfile] | true"
            + " | spring.datasource.hikari.data-source-properties.*"
      })
  void hikariConnectionOverridesFromProcessVisibleSourcesAreRejected(
      String key, String value, String expectedKey) {
    String json = "{\"" + key + "\":\"" + value + "\"}";
    for (String argument :
        new String[] {
          "--" + key + "=" + value, "-D" + key + "=" + value, "--spring.application.json=" + json
        }) {
      StandardEnvironment environment = processVisible(argument);
      applySpringApplicationJson(environment);

      assertThatThrownBy(
              () ->
                  MetadataDatabaseAutoConfiguration.rejectPasswordOutsideEnvironmentVariables(
                      environment))
          .as(argument)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(expectedKey)
          .hasMessageContaining(
              argument.startsWith("--spring.application.json=")
                  ? FROM_COMMAND_LINE_JSON
                  : argument.startsWith("--") ? FROM_COMMAND_LINE : FROM_SYSTEM_PROPERTIES)
          .hasMessageContaining("QUERYHUB_METADATA_DB_*")
          .hasMessageNotContaining(PASSWORD_MARKER);
    }
  }

  @Test
  void otherHikariSettingsAndHikariKeysFromTheEnvironmentAreAccepted() {
    StandardEnvironment environment =
        processVisible(
            "--spring.datasource.hikari.maximum-pool-size=3",
            "--spring.datasource.hikari.username=qh_user");
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("SPRING_DATASOURCE_HIKARI_PASSWORD", PASSWORD_MARKER)));

    assertThatCode(
            () ->
                MetadataDatabaseAutoConfiguration.rejectPasswordOutsideEnvironmentVariables(
                    environment))
        .doesNotThrowAnyException();
  }

  /** "--" 로 시작하면 명령행 인자, "-D" 로 시작하면 시스템 속성 하나로 넣는다. 실제 JVM 시스템 속성은 건드리지 않고 그 자리의 소스를 바꾼다. */
  private static StandardEnvironment processVisible(String... arguments) {
    StandardEnvironment environment = new StandardEnvironment();
    List<String> commandLine = new ArrayList<>();
    Map<String, Object> systemProperties = new HashMap<>();
    for (String argument : arguments) {
      if (argument.startsWith("-D")) {
        int equals = argument.indexOf('=');
        systemProperties.put(argument.substring(2, equals), argument.substring(equals + 1));
      } else {
        commandLine.add(argument);
      }
    }
    environment
        .getPropertySources()
        .addFirst(new SimpleCommandLinePropertySource(commandLine.toArray(String[]::new)));
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
            new MapPropertySource(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, systemProperties));
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of()));
    return environment;
  }

  /**
   * 기동 때처럼 Boot 의 후처리기로 spring.application.json 을 펼친다. SpringApplication 은 후처리기를 부르기 전에
   * configurationProperties 소스를 붙이므로(ConfigurationPropertySources.attach) 여기서도 먼저 붙인다. 그래야 Boot 처럼
   * 느슨한 바인딩의 다른 표기(spring.Application.JSON 등)도 찾는다.
   */
  private static void applySpringApplicationJson(StandardEnvironment environment) {
    ConfigurationPropertySources.attach(environment);
    new SpringApplicationJsonEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());
  }

  @Test
  void testJvmDoesNotInheritQueryHubVariablesFromTheShell() {
    // runtime/build.gradle 이 모든 Test 태스크에서 QUERYHUB_* 를 지운다(FR-13). AC-03 은 셸에 값을 넣고 이 테스트를 돌린다.
    assertThat(System.getenv().keySet()).noneMatch(name -> name.startsWith("QUERYHUB_"));
  }

  private static void assertRejectedNaming(MetadataDatabaseProperties properties, String variable) {
    assertThatThrownBy(properties::toConnectionDetails)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(variable)
        .hasMessageNotContaining(PASSWORD_MARKER);
  }

  private static Map<String, Object> validEnv() {
    return env(
        ENV_HOST, "127.0.0.1",
        ENV_NAME, "queryhub",
        ENV_USERNAME, "qh_user",
        ENV_PASSWORD, PASSWORD_MARKER);
  }

  private static Map<String, Object> env(String... keyValues) {
    Map<String, Object> env = new HashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      env.put(keyValues[i], keyValues[i + 1]);
    }
    return env;
  }

  /** 운영과 같은 이름(systemEnvironment)의 환경변수 소스에서 바인딩한다. 느슨한 바인딩이 QUERYHUB_* 를 연결해야 한다. */
  private static MetadataDatabaseProperties bindFromEnvironment(Map<String, Object> env) {
    SystemEnvironmentPropertySource source =
        new SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env);
    return new Binder(ConfigurationPropertySources.from(source))
        .bindOrCreate(MetadataDatabaseProperties.PREFIX, MetadataDatabaseProperties.class);
  }
}
