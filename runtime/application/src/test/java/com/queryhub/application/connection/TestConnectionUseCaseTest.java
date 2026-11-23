package com.queryhub.application.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryhub.domain.connection.ConnectionFailureKind;
import com.queryhub.domain.connection.ConnectionPassword;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.ConnectionTestResult;
import com.queryhub.domain.connection.DatabaseType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestConnectionUseCaseTest {

  private static final ConnectionPassword PASSWORD = ConnectionPassword.of("pw".toCharArray());

  /** 받은 설정을 기록하고 정해진 결과를 돌려주는 Provider. */
  private static final class RecordingProvider implements DatabaseProvider {
    private final DatabaseType type;
    private final List<ConnectionSettings> calls = new ArrayList<>();

    RecordingProvider(DatabaseType type) {
      this.type = type;
    }

    @Override
    public DatabaseType type() {
      return type;
    }

    @Override
    public ConnectionTestResult testConnection(
        ConnectionSettings settings, ConnectionPassword password) {
      calls.add(settings);
      return ConnectionTestResult.success(type.name(), "1", Duration.ofMillis(3));
    }
  }

  private static ConnectionSettings settings(DatabaseType type) {
    return new ConnectionSettings(
        type, "db.example.com", 1000, "app", "user", Duration.ofSeconds(3), 2, Map.of());
  }

  @Test
  void picksTheProviderByType() {
    RecordingProvider postgres = new RecordingProvider(DatabaseType.POSTGRESQL);
    RecordingProvider mariadb = new RecordingProvider(DatabaseType.MARIADB);
    TestConnectionUseCase useCase = new TestConnectionUseCase(List.of(postgres, mariadb));

    ConnectionTestResult result = useCase.test(settings(DatabaseType.MARIADB), PASSWORD);

    assertThat(result.succeeded()).isTrue();
    assertThat(result.productName()).isEqualTo("MARIADB");
    assertThat(mariadb.calls).hasSize(1);
    assertThat(postgres.calls).isEmpty();
    assertThat(useCase.supportedTypes())
        .containsExactlyInAnyOrder(DatabaseType.POSTGRESQL, DatabaseType.MARIADB);
  }

  @Test
  void oracleAndMssqlAreReportedAsUnsupportedInP107() {
    RecordingProvider postgres = new RecordingProvider(DatabaseType.POSTGRESQL);
    TestConnectionUseCase useCase =
        new TestConnectionUseCase(List.of(postgres, new RecordingProvider(DatabaseType.MARIADB)));

    for (DatabaseType type : List.of(DatabaseType.ORACLE, DatabaseType.MSSQL)) {
      ConnectionTestResult result = useCase.test(settings(type), PASSWORD);
      assertThat(result.succeeded()).isFalse();
      assertThat(result.failureKind()).isEqualTo(ConnectionFailureKind.UNSUPPORTED_DATABASE_TYPE);
      assertThat(result.message()).isEqualTo("이 버전은 아직 이 DB 종류에 접속하지 못합니다.");
    }
    assertThat(postgres.calls).isEmpty();
  }

  @Test
  void refusesTwoProvidersForOneType() {
    assertThatThrownBy(
            () ->
                new TestConnectionUseCase(
                    List.of(
                        new RecordingProvider(DatabaseType.MARIADB),
                        new RecordingProvider(DatabaseType.MARIADB))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new TestConnectionUseCase(List.of()).supportedTypes()).isEmpty();
  }
}
