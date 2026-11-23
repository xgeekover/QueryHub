package com.queryhub.adapter.dbprovider;

import com.queryhub.application.connection.ConnectionPools;
import com.queryhub.domain.connection.ConnectionId;
import com.queryhub.domain.connection.ConnectionPassword;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.DatabaseType;
import com.queryhub.domain.connection.InvalidConnectionSettingsException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 업무 DB 접속 정보마다 하나씩 두는 읽기 전용 HikariCP 풀.
 *
 * <ul>
 *   <li>풀 이름 {@value #POOL_NAME_PREFIX}&lt;id&gt;, 최대 크기는 설정의 maxPoolSize(1-20), readOnly=true,
 *       MBean 등록 없음.
 *   <li>여는 순간에는 접속하지 않는다 (minimumIdle=0, initializationFailTimeout=-1). 연결은 {@link #getConnection}
 *       때 만든다.
 *   <li>연결은 {@link DriverConnectDataSource} 로 만든다: Provider 드라이버의 connect, 고정 속성, 정리된 예외.
 *   <li>Spring 빈이 아니다 (앱의 DataSource 는 Metadata 저장소 하나뿐이다). 앱은 종료할 때 {@link #closeAll()} 을 부른다.
 * </ul>
 */
public final class HikariConnectionPools implements ConnectionPools, AutoCloseable {

  public static final String POOL_NAME_PREFIX = "queryhub-conn-";

  /** 연결을 기다리는 시간 = 접속 제한 시간 + 이 여유. */
  static final Duration BORROW_MARGIN = Duration.ofSeconds(2);

  private record Pool(HikariDataSource dataSource, DriverConnectDataSource source) {
    void close() {
      try {
        dataSource.close();
      } finally {
        source.close();
      }
    }
  }

  private final Map<DatabaseType, JdbcProviderSpi> providers;
  private final Duration borrowMargin;
  private final Map<ConnectionId, Pool> pools = new HashMap<>();

  public HikariConnectionPools(Collection<? extends JdbcProviderSpi> providers) {
    this(providers, BORROW_MARGIN);
  }

  /** 테스트용: 연결을 기다리는 여유를 바꾼다. */
  HikariConnectionPools(Collection<? extends JdbcProviderSpi> providers, Duration borrowMargin) {
    Objects.requireNonNull(providers, "providers");
    EnumMap<DatabaseType, JdbcProviderSpi> byType = new EnumMap<>(DatabaseType.class);
    for (JdbcProviderSpi provider : providers) {
      if (byType.putIfAbsent(provider.type(), provider) != null) {
        throw new IllegalArgumentException("DB 종류 " + provider.type() + " 의 Provider 가 둘 이상이다.");
      }
    }
    this.providers = byType;
    this.borrowMargin = Objects.requireNonNull(borrowMargin, "borrowMargin");
  }

  @Override
  public synchronized void open(
      ConnectionId id, ConnectionSettings settings, ConnectionPassword password) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(password, "password");
    JdbcProviderSpi spi = providers.get(settings.type());
    if (spi == null) {
      throw InvalidConnectionSettingsException.unsupportedType(settings.type());
    }
    // 검사(옵션 허용 목록)를 먼저 한다: 실패하면 기존 풀은 그대로다.
    DriverConnectDataSource source = DriverConnectDataSource.create(spi, settings, password);
    Pool previous = pools.remove(id);
    if (previous != null) {
      previous.close();
    }
    HikariDataSource dataSource;
    try {
      dataSource = new HikariDataSource(config(id, settings, source));
    } catch (RuntimeException failure) {
      source.close();
      throw failure;
    }
    pools.put(id, new Pool(dataSource, source));
  }

  private HikariConfig config(
      ConnectionId id, ConnectionSettings settings, DriverConnectDataSource source) {
    long connectMillis = settings.connectTimeout().toMillis();
    HikariConfig config = new HikariConfig();
    config.setPoolName(POOL_NAME_PREFIX + id.value());
    config.setDataSource(source);
    config.setMaximumPoolSize(settings.maxPoolSize());
    config.setMinimumIdle(0);
    config.setReadOnly(true);
    config.setRegisterMbeans(false);
    config.setAllowPoolSuspension(false);
    // 여는 순간 접속하지 않는다: 앱 기동이 업무 DB 에 기대지 않는다.
    config.setInitializationFailTimeout(-1);
    config.setConnectionTimeout(connectMillis + borrowMargin.toMillis());
    config.setValidationTimeout(connectMillis);
    return config;
  }

  /**
   * 풀에서 연결을 꺼낸다 (readOnly). 다 쓰면 닫아 돌려준다.
   *
   * @throws IllegalStateException 열린 풀이 없으면
   * @throws SQLException 제한 시간 안에 연결을 얻지 못하면. 원인은 정리된 {@link ConnectionFailedException} 이다
   */
  public Connection getConnection(ConnectionId id) throws SQLException {
    return dataSource(id).getConnection();
  }

  @Override
  public synchronized void close(ConnectionId id) {
    Pool pool = pools.remove(Objects.requireNonNull(id, "id"));
    if (pool != null) {
      pool.close();
    }
  }

  @Override
  public synchronized boolean isOpen(ConnectionId id) {
    Pool pool = pools.get(Objects.requireNonNull(id, "id"));
    return pool != null && !pool.dataSource().isClosed();
  }

  @Override
  public synchronized void closeAll() {
    List<Pool> open = new ArrayList<>(pools.values());
    pools.clear();
    RuntimeException first = null;
    for (Pool pool : open) {
      try {
        pool.close();
      } catch (RuntimeException failure) {
        if (first == null) {
          first = failure;
        }
      }
    }
    if (first != null) {
      throw first;
    }
  }

  @Override
  public void close() {
    closeAll();
  }

  /** 테스트와 같은 패키지 코드용: 열린 풀. */
  synchronized HikariDataSource dataSource(ConnectionId id) {
    Pool pool = pools.get(Objects.requireNonNull(id, "id"));
    if (pool == null || pool.dataSource().isClosed()) {
      throw new IllegalStateException("열린 접속 풀이 없다: " + id);
    }
    return pool.dataSource();
  }

  /** 테스트용: 풀의 접속 경로. */
  synchronized DriverConnectDataSource source(ConnectionId id) {
    Pool pool = pools.get(Objects.requireNonNull(id, "id"));
    if (pool == null) {
      throw new IllegalStateException("열린 접속 풀이 없다: " + id);
    }
    return pool.source();
  }
}
