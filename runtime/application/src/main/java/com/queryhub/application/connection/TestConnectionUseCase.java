package com.queryhub.application.connection;

import com.queryhub.domain.connection.ConnectionFailureKind;
import com.queryhub.domain.connection.ConnectionPassword;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.ConnectionTestResult;
import com.queryhub.domain.connection.DatabaseType;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 유스케이스: 접속 설정으로 연결을 시험한다. DB 종류로 Provider 를 고르고, 등록된 Provider 가 없는 종류(P1-07 에서는 ORACLE, MSSQL)는
 * {@link ConnectionFailureKind#UNSUPPORTED_DATABASE_TYPE} 결과를 돌려준다.
 */
public final class TestConnectionUseCase {

  private final Map<DatabaseType, DatabaseProvider> providers;

  /**
   * @throws IllegalArgumentException 같은 DB 종류의 Provider 가 둘 이상이면
   */
  public TestConnectionUseCase(Collection<? extends DatabaseProvider> providers) {
    Objects.requireNonNull(providers, "providers");
    EnumMap<DatabaseType, DatabaseProvider> byType = new EnumMap<>(DatabaseType.class);
    for (DatabaseProvider provider : providers) {
      DatabaseType type = Objects.requireNonNull(provider.type(), "provider.type()");
      if (byType.putIfAbsent(type, provider) != null) {
        throw new IllegalArgumentException("DB 종류 " + type + " 의 Provider 가 둘 이상이다.");
      }
    }
    this.providers = Collections.unmodifiableMap(byType);
  }

  /** 접속을 시험한다. 실패도 결과로 돌려준다. */
  public ConnectionTestResult test(ConnectionSettings settings, ConnectionPassword password) {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(password, "password");
    DatabaseProvider provider = providers.get(settings.type());
    if (provider == null) {
      return ConnectionTestResult.failure(
          ConnectionFailureKind.UNSUPPORTED_DATABASE_TYPE, Duration.ZERO);
    }
    return Objects.requireNonNull(
        provider.testConnection(settings, password), "provider.testConnection()");
  }

  /** 접속할 수 있는 DB 종류. */
  public Set<DatabaseType> supportedTypes() {
    return providers.keySet();
  }
}
