package com.queryhub.adapter.metadata.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * AC-15 (단위): 입력 검사는 SQL 보다 먼저 일어나고, 시각은 UTC LocalDateTime 으로 바인딩된다.
 *
 * <p>DB 없이 돈다. JDBC 호출을 기록하는 DataSource 로 "SQL 이 실행되었는가"와 "무엇이 바인딩되었는가"만 본다. 실제 저장과 조회(정렬 규칙,
 * DATETIME(6) 값)는 app 의 통합 테스트(AppSettingRepositoryIntegrationTest)가 MariaDB 에서 확인한다.
 */
class JdbcAppSettingRepositoryTest {

  /** 2026-01-02T03:04:05.123456789Z. 시계의 시간대(Asia/Seoul)는 저장 값에 영향을 주지 않아야 한다. */
  private static final Clock SEOUL_CLOCK =
      Clock.fixed(Instant.parse("2026-01-02T03:04:05.123456789Z"), ZoneId.of("Asia/Seoul"));

  private final RecordingDataSource dataSource = new RecordingDataSource();

  private final JdbcAppSettingRepository repository =
      new JdbcAppSettingRepository(JdbcClient.create(dataSource.proxy()), SEOUL_CLOCK);

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
  void rejectsNullAndBlankKeysBeforeAnySql(String key) {
    assertThatThrownBy(() -> repository.findValue(key))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.putValue(key, "value"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(dataSource.connections).as("SQL 실행 전에 거부해야 한다").isZero();
  }

  @Test
  void rejectsKeysLongerThan100CodePointsBeforeAnySql() {
    String asciiKey = "k".repeat(101);
    // 4 바이트 UTF-8 문자 101 개 = UTF-16 202 자. 코드 포인트로 센다.
    String emojiKey = "😀".repeat(101);

    for (String key : List.of(asciiKey, emojiKey)) {
      assertThatThrownBy(() -> repository.findValue(key))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("100");
      assertThatThrownBy(() -> repository.putValue(key, "value"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("100");
    }
    assertThat(dataSource.connections).isZero();
  }

  @Test
  void acceptsKeysOfExactly100CodePoints() {
    repository.putValue("k".repeat(100), "value");
    repository.putValue("😀".repeat(100), "value");

    assertThat(dataSource.connections).isEqualTo(2);
  }

  @Test
  void rejectsNullValueBeforeAnySqlButAcceptsEmptyValue() {
    assertThatThrownBy(() -> repository.putValue("runtime.sample", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(dataSource.connections).isZero();

    repository.putValue("runtime.sample", "");
    assertThat(dataSource.connections).isEqualTo(1);
  }

  @Test
  void putValueBindsTimestampsAsUtcLocalDateTimeTruncatedToMicros() {
    repository.putValue("runtime.sample", "값");

    assertThat(dataSource.statements).hasSize(1);
    RecordedStatement statement = dataSource.statements.get(0);
    assertThat(statement.sql())
        .contains("INSERT INTO app_setting")
        .contains("ON DUPLICATE KEY UPDATE");
    LocalDateTime expected = LocalDateTime.parse("2026-01-02T03:04:05.123456");
    // INSERT 의 created_at, updated_at 과 UPDATE 절의 updated_at: 세 번 모두 UTC 벽시계 값이다(서울 시각 12:04 가
    // 아니다).
    assertThat(statement.parameters())
        .filteredOn(value -> value instanceof LocalDateTime)
        .hasSize(3)
        .allSatisfy(value -> assertThat(value).isEqualTo(expected));
    // Instant 나 java.sql.Timestamp 는 바인딩하지 않는다(Connector/J 가 JVM 시간대로 바꾼다).
    assertThat(statement.parameters())
        .noneMatch(value -> value instanceof Instant || value instanceof java.util.Date);
    assertThat(statement.parameters()).contains("runtime.sample", "값");
  }

  @Test
  void keysAreBoundAsGivenWithoutTrimmingOrCaseFolding() {
    repository.findValue("k ");
    repository.findValue("K");

    assertThat(dataSource.statements)
        .extracting(RecordedStatement::parameters)
        .containsExactly(List.of("k "), List.of("K"));
  }

  @Test
  void findValueIsEmptyWhenNoRowIsReturned() {
    assertThat(repository.findValue("absent.key")).isEmpty();
  }

  @Test
  void utcNowIgnoresTheClockZone() {
    assertThat(repository.utcNow()).isEqualTo(LocalDateTime.parse("2026-01-02T03:04:05.123456"));
  }

  private record RecordedStatement(String sql, List<Object> parameters) {}

  /**
   * 연결을 열 때마다 세고, 준비된 문장의 SQL 과 바인딩 값(set* 호출 순서대로)을 기록한다. 갱신은 1 행, 조회는 빈 결과를 돌려준다. 그 밖의 호출은
   * 기본값(null, 0, false)을 돌려준다.
   */
  private static final class RecordingDataSource {

    int connections;
    final List<RecordedStatement> statements = new ArrayList<>();

    DataSource proxy() {
      return proxy(
          DataSource.class,
          (target, method, args) -> {
            if (method.getName().equals("getConnection")) {
              connections++;
              return connection();
            }
            return defaultValue(method.getReturnType());
          });
    }

    private Connection connection() {
      return proxy(
          Connection.class,
          (target, method, args) -> {
            if (method.getName().equals("prepareStatement")) {
              RecordedStatement statement =
                  new RecordedStatement((String) args[0], new ArrayList<>());
              statements.add(statement);
              return preparedStatement(statement);
            }
            return defaultValue(method.getReturnType());
          });
    }

    private static PreparedStatement preparedStatement(RecordedStatement statement) {
      return proxy(
          PreparedStatement.class,
          (target, method, args) -> {
            String name = method.getName();
            if (name.startsWith("set")
                && args != null
                && args.length >= 2
                && args[0] instanceof Integer) {
              statement.parameters().add(args[1]);
              return null;
            }
            if (name.equals("executeUpdate")) {
              return 1;
            }
            if (name.equals("executeQuery")) {
              return emptyResultSet();
            }
            return defaultValue(method.getReturnType());
          });
    }

    private static ResultSet emptyResultSet() {
      return proxy(ResultSet.class, (target, method, args) -> defaultValue(method.getReturnType()));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
      InvocationHandler withObjectMethods =
          (target, method, args) ->
              switch (method.getName()) {
                case "toString" -> "Recording" + type.getSimpleName();
                case "hashCode" -> System.identityHashCode(target);
                case "equals" -> target == args[0];
                default -> handler.invoke(target, method, args);
              };
      return type.cast(
          Proxy.newProxyInstance(
              JdbcAppSettingRepositoryTest.class.getClassLoader(),
              new Class<?>[] {type},
              withObjectMethods));
    }

    private static Object defaultValue(Class<?> returnType) {
      if (returnType == boolean.class) {
        return false;
      }
      if (returnType == int.class) {
        return 0;
      }
      if (returnType == long.class) {
        return 0L;
      }
      return null;
    }
  }
}
