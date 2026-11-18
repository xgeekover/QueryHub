package com.queryhub.app.config.metadata;

import static com.queryhub.app.config.metadata.MetadataDatabaseProperties.ENV_HOST;
import static com.queryhub.app.config.metadata.MetadataDatabaseProperties.ENV_NAME;
import static com.queryhub.app.config.metadata.MetadataDatabaseProperties.ENV_PASSWORD;
import static com.queryhub.app.config.metadata.MetadataDatabaseProperties.ENV_PORT;
import static com.queryhub.app.config.metadata.MetadataDatabaseProperties.ENV_USERNAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.queryhub.app.QueryHubApplication;
import com.queryhub.app.support.TestComponentScanExclusion;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.BeanCreationException;
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
 * AC-06, AC-08, AC-09: Metadata DB 설정이 없거나 틀리면, DB 에 닿지 않으면, 비밀번호가 명령행·시스템 속성(그 둘로 넘긴
 * spring.application.json 포함)으로 오면, Hikari 접속 키가 명령행으로 오면 기동은 웹 서버가 포트를 열기 전에 실패한다. Flyway 를 끈 경우의
 * 연결 확인도 같다. spring.flyway.url·user·password 가 있어도 Flyway 는 검사한 주 풀로 연결하므로 실패는 검사한 대상에서 난다(R10).
 * Docker 없이 돈다.
 *
 * <p>실제 QueryHubApplication 을 Metadata DB 제외 없이 띄운다(servlet, desktop, 127.0.0.1, 포트 0). 컴포넌트 스캔에서
 * 테스트 전용 빈만 뺀다({@link TestComponentScanExclusion}). 접속 값은 기본 속성(defaultProperties)으로 넣는다. 명령행과 시스템
 * 속성은 비밀번호 거부 규칙의 대상이기 때문이다. 모든 경우를 일반 초기화와 lazy 초기화로 확인한다. 회귀로 기동이 성공해도 루프백의 임의 포트에만 열고 바로 닫는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class MetadataStartupFailureTest {

  /** 비밀번호 자리의 표식. 예외 메시지와 출력 어디에도 나오면 안 된다. */
  private static final String PASSWORD_MARKER = "secret-marker-unit-7c41d2";

  private static final Duration STARTUP_LIMIT = Duration.ofSeconds(30);

  /*
   * 거부 메시지에서 출처에 따라 바뀌는 부분. 비밀번호 메시지의 고정 문구("명령행 인자나 JVM 시스템 속성(spring.application.json 포함)")
   * 에는 이 문자열들이 없으므로, 출처를 잘못 말하면 이 검사가 실패한다.
   */
  private static final String FROM_COMMAND_LINE = "가 명령행 인자로 넘어와";
  private static final String FROM_SYSTEM_PROPERTIES = "가 JVM 시스템 속성(-D)로 넘어와";
  private static final String FROM_COMMAND_LINE_JSON = "가 명령행 인자의 spring.application.json로 넘어와";
  private static final String FROM_SYSTEM_PROPERTIES_JSON =
      "가 JVM 시스템 속성(-D)의 spring.application.json로 넘어와";

  @ParameterizedTest(name = "lazy={0}")
  @ValueSource(booleans = {false, true})
  void missingVariablesFailBeforeTheWebServerStarts(boolean lazy) {
    StartupAttempt attempt = start(lazy, Map.of());

    assertThat(attempt.failure()).as("설정 없이 기동하면 실패해야 한다").isNotNull();
    assertThat(attempt.failure())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ENV_HOST)
        .hasMessageContaining(ENV_NAME)
        .hasMessageContaining(ENV_USERNAME)
        .hasMessageContaining(ENV_PASSWORD)
        .message()
        .doesNotContain(ENV_PORT);
    assertThat(attempt.webServerStarted()).as("포트를 열기 전에 실패해야 한다").isFalse();
  }

  @ParameterizedTest(name = "lazy={0}")
  @ValueSource(booleans = {false, true})
  void onlyTheMissingPasswordIsNamed(boolean lazy) {
    Map<String, Object> properties = validProperties(3306);
    properties.remove("queryhub.metadata.db.password");

    StartupAttempt attempt = start(lazy, properties);

    assertThat(attempt.failure())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ENV_PASSWORD)
        .message()
        .doesNotContain(ENV_HOST, ENV_PORT, ENV_NAME, ENV_USERNAME);
    assertThat(attempt.webServerStarted()).isFalse();
  }

  @ParameterizedTest(name = "lazy={0}")
  @ValueSource(booleans = {false, true})
  void unreachableDatabaseFailsBeforeTheWebServerStarts(boolean lazy, CapturedOutput output)
      throws IOException {
    int closedPort = closedLoopbackPort();

    StartupAttempt attempt =
        assertTimeoutPreemptively(STARTUP_LIMIT, () -> start(lazy, validProperties(closedPort)));

    assertThat(attempt.failure()).as("닫힌 포트의 DB 로는 기동할 수 없어야 한다").isNotNull();
    assertThat(causes(attempt.failure()))
        .as("원인에 JDBC 연결 실패가 있어야 한다")
        .anySatisfy(cause -> assertThat(cause).isInstanceOf(SQLException.class));
    assertThat(attempt.webServerStarted()).isFalse();
    assertNoPasswordLeak(attempt, output);
  }

  @ParameterizedTest(name = "lazy={0}")
  @ValueSource(booleans = {false, true})
  void passwordOnTheCommandLineIsRejected(boolean lazy, CapturedOutput output) throws IOException {
    Map<String, Object> properties = validProperties(closedLoopbackPort());
    properties.remove("queryhub.metadata.db.password");

    StartupAttempt attempt =
        start(lazy, properties, "--queryhub.metadata.db.password=" + PASSWORD_MARKER);

    assertThat(attempt.failure())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ENV_PASSWORD)
        .hasMessageContaining(FROM_COMMAND_LINE);
    assertThat(attempt.webServerStarted()).isFalse();
    assertNoPasswordLeak(attempt, output);
  }

  @ParameterizedTest(name = "lazy={0}")
  @ValueSource(booleans = {false, true})
  void passwordInSystemPropertiesIsRejected(boolean lazy, CapturedOutput output)
      throws IOException {
    Map<String, Object> properties = validProperties(closedLoopbackPort());
    properties.remove("queryhub.metadata.db.password");
    String previous = System.getProperty(MetadataDatabaseProperties.PASSWORD_PROPERTY);
    assertThat(previous).as("테스트 JVM 에 원래 값이 없어야 한다").isNull();

    StartupAttempt attempt;
    System.setProperty(MetadataDatabaseProperties.PASSWORD_PROPERTY, PASSWORD_MARKER);
    try {
      attempt = start(lazy, properties);
    } finally {
      System.clearProperty(MetadataDatabaseProperties.PASSWORD_PROPERTY);
    }

    assertThat(attempt.failure())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ENV_PASSWORD)
        .hasMessageContaining(FROM_SYSTEM_PROPERTIES);
    assertThat(attempt.webServerStarted()).isFalse();
    assertNoPasswordLeak(attempt, output);
  }

  /** R12: Boot 는 느슨한 바인딩의 다른 표기(spring.Application.JSON)도 펼친다. 그 표기도 거부해야 한다. */
  @ParameterizedTest(name = "lazy={0} {1}")
  @CsvSource({
    "false, spring.application.json",
    "true, spring.application.json",
    "false, spring.Application.JSON",
    "true, spring.Application.JSON"
  })
  void passwordInSpringApplicationJsonOnTheCommandLineIsRejected(
      boolean lazy, String key, CapturedOutput output) throws IOException {
    Map<String, Object> properties = validProperties(closedLoopbackPort());
    properties.remove("queryhub.metadata.db.password");

    StartupAttempt attempt = start(lazy, properties, "--" + key + "=" + passwordJson());

    assertRejectedBeforeAnyConnection(attempt, ENV_PASSWORD, FROM_COMMAND_LINE_JSON);
    assertNoPasswordLeak(attempt, output);
  }

  @ParameterizedTest(name = "lazy={0} {1}")
  @CsvSource({
    "false, spring.application.json",
    "true, spring.application.json",
    "false, spring.APPLICATION.json",
    "true, spring.APPLICATION.json"
  })
  void passwordInSpringApplicationJsonSystemPropertyIsRejected(
      boolean lazy, String key, CapturedOutput output) throws IOException {
    Map<String, Object> properties = validProperties(closedLoopbackPort());
    properties.remove("queryhub.metadata.db.password");
    assertThat(System.getProperty(key)).as("테스트 JVM 에 원래 값이 없어야 한다").isNull();

    StartupAttempt attempt;
    System.setProperty(key, passwordJson());
    try {
      attempt = start(lazy, properties);
    } finally {
      System.clearProperty(key);
    }

    assertRejectedBeforeAnyConnection(attempt, ENV_PASSWORD, FROM_SYSTEM_PROPERTIES_JSON);
    assertNoPasswordLeak(attempt, output);
  }

  @ParameterizedTest(name = "lazy={0} {1}")
  @CsvSource({
    "false, spring.datasource.hikari.password",
    "true, spring.datasource.hikari.password",
    "false, spring.datasource.hikari.jdbc-url",
    "true, spring.datasource.hikari.jdbc-url"
  })
  void hikariPasswordOrJdbcUrlOnTheCommandLineIsRejected(
      boolean lazy, String key, CapturedOutput output) throws IOException {
    int port = closedLoopbackPort();
    String value =
        key.endsWith("password")
            ? PASSWORD_MARKER
            : "jdbc:mariadb://127.0.0.1:" + port + "/queryhub?password=" + PASSWORD_MARKER;

    StartupAttempt attempt = start(lazy, validProperties(port), "--" + key + "=" + value);

    assertRejectedBeforeAnyConnection(attempt, key, FROM_COMMAND_LINE);
    assertThat(attempt.failure()).rootCause().hasMessageContaining("QUERYHUB_METADATA_DB_*");
    assertNoPasswordLeak(attempt, output);
  }

  /** R2(a): Flyway 를 끄면 기동 검사가 연결 하나를 열어 확인한다. 닫힌 포트면 그 확인이 기동을 멈춘다. */
  @ParameterizedTest(name = "lazy={0}")
  @ValueSource(booleans = {false, true})
  void withFlywayDisabledTheStartupCheckStillFailsOnAnUnreachableDatabase(
      boolean lazy, CapturedOutput output) throws IOException {
    int closedPort = closedLoopbackPort();

    StartupAttempt attempt =
        assertTimeoutPreemptively(
            STARTUP_LIMIT,
            () -> start(lazy, validProperties(closedPort), "--spring.flyway.enabled=false"));

    assertThat(attempt.failure()).as("Flyway 를 꺼도 닫힌 포트로는 기동할 수 없어야 한다").isNotNull();
    List<Throwable> causes = causes(attempt.failure());
    assertThat(causes)
        .as("원인에 JDBC 연결 실패가 있어야 한다")
        .anySatisfy(cause -> assertThat(cause).isInstanceOf(SQLException.class));
    assertThat(causes)
        .as("실패한 빈은 기동 검사다")
        .anySatisfy(
            cause ->
                assertThat(cause)
                    .isInstanceOfSatisfying(
                        BeanCreationException.class,
                        creation ->
                            assertThat(creation.getBeanName())
                                .isEqualTo("metadataDatabaseStartupCheck")));
    assertThat(causes)
        .as("Flyway 경로가 아니라 연결 확인 경로여야 한다")
        .noneSatisfy(
            cause ->
                assertThat(cause)
                    .isInstanceOfSatisfying(
                        BeanCreationException.class,
                        creation ->
                            assertThat(creation.getBeanName()).containsIgnoringCase("flyway")));
    assertThat(causes)
        .as("연결 확인은 MetadataDatabaseStartupCheck.afterPropertiesSet 에서 일어난다")
        .anySatisfy(
            cause ->
                assertThat(cause.getStackTrace())
                    .anySatisfy(
                        frame -> {
                          assertThat(frame.getClassName())
                              .isEqualTo(MetadataDatabaseStartupCheck.class.getName());
                          assertThat(frame.getMethodName()).isEqualTo("afterPropertiesSet");
                        }));
    assertThat(attempt.webServerStarted()).isFalse();
    assertNoPasswordLeak(attempt, output);
  }

  /**
   * R10: 명령행의 spring.flyway.user·password 는 Flyway 연결에 쓰이지 않는다. Flyway 는 검사한 주 Hikari 풀로 마이그레이션하므로,
   * 닫힌 포트에서 그 풀의 첫 연결이 웹 서버가 뜨기 전에 기동을 멈춘다.
   */
  @ParameterizedTest(name = "lazy={0}")
  @ValueSource(booleans = {false, true})
  void flywayUserAndPasswordOnTheCommandLineDoNotReplaceTheCheckedConnection(
      boolean lazy, CapturedOutput output) throws IOException {
    int closedPort = closedLoopbackPort();

    StartupAttempt attempt =
        assertTimeoutPreemptively(
            STARTUP_LIMIT,
            () ->
                start(
                    lazy,
                    validProperties(closedPort),
                    "--spring.flyway.user=migrator",
                    "--spring.flyway.password=" + PASSWORD_MARKER));

    assertFailedOnTheCheckedTargetThroughTheMainPool(attempt, closedPort);
    assertNoPasswordLeak(attempt, output);
  }

  /**
   * R10: -D 의 spring.flyway.url 은 Flyway 연결에 쓰이지 않는다. 그 URL 의 포트에는 연결을 받는 덫을 두고 연결이 하나도 오지 않았음을
   * 확인한다. 실패는 검사한 대상(닫힌 포트)에서 주 Hikari 풀로 난다.
   */
  @ParameterizedTest(name = "lazy={0}")
  @ValueSource(booleans = {false, true})
  void flywayUrlInSystemPropertiesDoesNotReplaceTheCheckedConnection(
      boolean lazy, CapturedOutput output) throws IOException {
    int closedPort = closedLoopbackPort();
    String key = "spring.flyway.url";
    assertThat(System.getProperty(key)).as("테스트 JVM 에 원래 값이 없어야 한다").isNull();

    StartupAttempt attempt;
    try (ConnectionTrap trap = ConnectionTrap.open()) {
      System.setProperty(
          key,
          "jdbc:mariadb://127.0.0.1:"
              + trap.port()
              + "/otherdb?allowLocalInfile=true&password="
              + PASSWORD_MARKER);
      try {
        attempt =
            assertTimeoutPreemptively(
                STARTUP_LIMIT, () -> start(lazy, validProperties(closedPort)));
      } finally {
        System.clearProperty(key);
      }
      assertThat(trap.connections()).as("Flyway 의 URL(포트 %d)로는 연결하지 않는다", trap.port()).isZero();
    }

    assertFailedOnTheCheckedTargetThroughTheMainPool(attempt, closedPort);
    assertNoPasswordLeak(attempt, output);
  }

  /**
   * 기동이 웹 서버 전에 JDBC 연결 실패로 멈췄고, 그 연결은 검사한 대상(127.0.0.1:port)으로 주 Hikari 풀을 거쳐 열렸다. Flyway 가 따로 만드는
   * SimpleDriverDataSource 는 거치지 않았다.
   */
  private static void assertFailedOnTheCheckedTargetThroughTheMainPool(
      StartupAttempt attempt, int checkedPort) {
    assertThat(attempt.failure()).as("닫힌 포트의 DB 로는 기동할 수 없어야 한다").isNotNull();
    List<Throwable> causes = causes(attempt.failure());
    assertThat(causes)
        .as("원인에 JDBC 연결 실패가 있어야 한다")
        .anySatisfy(cause -> assertThat(cause).isInstanceOf(SQLException.class));
    assertThat(causes)
        .as("연결 대상은 검사한 접속 정보의 포트다")
        .anySatisfy(
            cause ->
                assertThat(String.valueOf(cause.getMessage()))
                    .contains("127.0.0.1:" + checkedPort));
    assertThat(causes)
        .as("연결은 주 Hikari 풀(HikariDataSource.getConnection)에서 열린다")
        .anySatisfy(
            cause ->
                assertThat(cause.getStackTrace())
                    .anySatisfy(
                        frame ->
                            assertThat(frame.getClassName())
                                .isEqualTo("com.zaxxer.hikari.HikariDataSource")));
    assertThat(causes)
        .as("Flyway 전용 DataSource(SimpleDriverDataSource)는 쓰이지 않는다")
        .allSatisfy(
            cause ->
                assertThat(cause.getStackTrace())
                    .noneSatisfy(
                        frame ->
                            assertThat(frame.getClassName())
                                .isIn(
                                    "org.springframework.jdbc.datasource.SimpleDriverDataSource",
                                    "org.springframework.jdbc.datasource"
                                        + ".AbstractDriverBasedDataSource")));
    assertThat(attempt.webServerStarted()).as("포트를 열기 전에 실패해야 한다").isFalse();
  }

  /**
   * 루프백의 임의 포트에서 연결을 받자마자 닫고 수를 센다. 수를 먼저 세고 닫으므로, 클라이언트가 연결 끊김을 보았다면 그 연결은 이미 세어졌다. 닫힌 연결을 받은
   * 클라이언트(드라이버)는 곧바로 실패하므로 회귀가 있어도 테스트가 오래 걸리지 않는다.
   */
  private static final class ConnectionTrap implements AutoCloseable {

    private final ServerSocket server;
    private final AtomicInteger connections = new AtomicInteger();
    private final Thread acceptor;

    private ConnectionTrap(ServerSocket server) {
      this.server = server;
      this.acceptor = new Thread(this::acceptUntilClosed, "flyway-url-trap");
      this.acceptor.setDaemon(true);
    }

    static ConnectionTrap open() throws IOException {
      ConnectionTrap trap =
          new ConnectionTrap(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
      trap.acceptor.start();
      return trap;
    }

    int port() {
      return server.getLocalPort();
    }

    int connections() {
      return connections.get();
    }

    private void acceptUntilClosed() {
      while (!server.isClosed()) {
        try {
          Socket accepted = server.accept();
          connections.incrementAndGet();
          accepted.close();
        } catch (IOException closed) {
          return;
        }
      }
    }

    @Override
    public void close() throws IOException {
      server.close();
      try {
        acceptor.join(Duration.ofSeconds(5));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static String passwordJson() {
    return "{\"queryhub\":{\"metadata\":{\"db\":{\"password\":\"" + PASSWORD_MARKER + "\"}}}}";
  }

  /** 거부는 접속 정보 빈을 만들 때 일어난다. JDBC 연결을 시도하기 전이고 웹 서버도 뜨지 않는다. */
  private static void assertRejectedBeforeAnyConnection(
      StartupAttempt attempt, String named, String sourceDescription) {
    assertThat(attempt.failure())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(named)
        .hasMessageContaining(sourceDescription);
    assertThat(causes(attempt.failure()))
        .as("연결을 시도하기 전에 거부해야 한다")
        .noneSatisfy(cause -> assertThat(cause).isInstanceOf(SQLException.class));
    assertThat(attempt.webServerStarted()).as("포트를 열기 전에 실패해야 한다").isFalse();
  }

  private static void assertNoPasswordLeak(StartupAttempt attempt, CapturedOutput output) {
    assertThat(causes(attempt.failure()))
        .allSatisfy(
            cause ->
                assertThat(String.valueOf(cause.getMessage())).doesNotContain(PASSWORD_MARKER));
    assertThat(output.getAll()).as("로그와 출력").doesNotContain(PASSWORD_MARKER);
  }

  private static Map<String, Object> validProperties(int port) {
    Map<String, Object> properties = new HashMap<>();
    properties.put("queryhub.metadata.db.host", "127.0.0.1");
    properties.put("queryhub.metadata.db.port", String.valueOf(port));
    properties.put("queryhub.metadata.db.name", "queryhub");
    properties.put("queryhub.metadata.db.username", "queryhub");
    properties.put("queryhub.metadata.db.password", PASSWORD_MARKER);
    return properties;
  }

  /** 루프백에서 방금 비운 포트. 아무것도 듣고 있지 않으므로 연결은 곧바로 거부된다. */
  private static int closedLoopbackPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static List<Throwable> causes(@Nullable Throwable failure) {
    List<Throwable> causes = new ArrayList<>();
    for (Throwable cause = failure;
        cause != null && !causes.contains(cause);
        cause = cause.getCause()) {
      causes.add(cause);
    }
    return causes;
  }

  private static StartupAttempt start(
      boolean lazy, Map<String, Object> defaultProperties, String... extraArgs) {
    AtomicBoolean webServerStarted = new AtomicBoolean();
    ApplicationListener<ApplicationEvent> webServerListener =
        event -> {
          if (event instanceof WebServerInitializedEvent) {
            webServerStarted.set(true);
          }
        };
    SpringApplication application =
        new SpringApplicationBuilder(QueryHubApplication.class)
            .web(WebApplicationType.SERVLET)
            .lazyInitialization(lazy)
            .profiles("desktop")
            .properties(defaultProperties)
            .initializers(TestComponentScanExclusion::register)
            .listeners(webServerListener)
            .build();
    List<String> args = new ArrayList<>(List.of("--server.address=127.0.0.1", "--server.port=0"));
    args.addAll(List.of(extraArgs));

    Throwable failure =
        catchThrowable(
            () -> {
              try (ConfigurableApplicationContext context =
                  application.run(args.toArray(String[]::new))) {
                assertThat(context.isActive()).isTrue();
              }
            });
    return new StartupAttempt(failure, webServerStarted.get());
  }

  private record StartupAttempt(@Nullable Throwable failure, boolean webServerStarted) {}
}
