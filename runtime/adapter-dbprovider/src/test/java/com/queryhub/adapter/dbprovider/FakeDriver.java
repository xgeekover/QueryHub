package com.queryhub.adapter.dbprovider;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 단위 테스트용 java.sql.Driver. DriverManager 에 등록하지 않는다: 운영 코드가 DriverManager 를 쓰면 "No suitable driver"
 * 로 실패한다. 연결, 문장, 메타데이터는 Proxy 로 만든다.
 */
final class FakeDriver implements Driver {

  static final String URL_PREFIX = "jdbc:fake:";

  enum Mode {
    SUCCEED,
    THROW,
    RETURN_NULL,
    THROW_RUNTIME,
    /** 인터럽트되면 끝난다 (소켓이 아닌 대기). */
    BLOCK_INTERRUPTIBLY,
    /** 인터럽트를 무시하고 {@link #release} 까지 기다린 뒤 연결을 돌려준다. */
    BLOCK_IGNORING_INTERRUPTS
  }

  volatile Mode mode = Mode.SUCCEED;
  volatile Supplier<SQLException> failure = () -> new SQLException("fail", "08001");
  volatile SQLException validationFailure;

  final AtomicInteger connects = new AtomicInteger();
  final List<Properties> seenProperties = new CopyOnWriteArrayList<>();
  final List<String> seenUrls = new CopyOnWriteArrayList<>();
  final List<FakeConnection> connections = new CopyOnWriteArrayList<>();
  final CountDownLatch entered = new CountDownLatch(1);
  final CountDownLatch release = new CountDownLatch(1);
  final AtomicBoolean interruptIgnored = new AtomicBoolean();

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return null;
    }
    connects.incrementAndGet();
    seenUrls.add(url);
    Properties copy = new Properties();
    copy.putAll(info);
    seenProperties.add(copy);
    entered.countDown();
    switch (mode) {
      case THROW -> throw failure.get();
      case RETURN_NULL -> {
        return null;
      }
      case THROW_RUNTIME ->
          throw new IllegalStateException("runtime " + info.getProperty("password"));
      case BLOCK_INTERRUPTIBLY -> {
        try {
          new CountDownLatch(1).await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new SQLException("interrupted", "08000");
        }
      }
      case BLOCK_IGNORING_INTERRUPTS -> {
        boolean released = false;
        while (!released) {
          try {
            release.await();
            released = true;
          } catch (InterruptedException ignored) {
            interruptIgnored.set(true);
          }
        }
      }
      default -> {}
    }
    FakeConnection connection = new FakeConnection(this);
    connections.add(connection);
    return connection.proxy;
  }

  @Override
  public boolean acceptsURL(String url) {
    return url != null && url.startsWith(URL_PREFIX);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
    return new DriverPropertyInfo[0];
  }

  @Override
  public int getMajorVersion() {
    return 1;
  }

  @Override
  public int getMinorVersion() {
    return 0;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException();
  }

  /** Proxy 연결의 상태. */
  static final class FakeConnection {
    final Connection proxy;
    final AtomicBoolean closed = new AtomicBoolean();
    final List<Boolean> readOnlyCalls = new CopyOnWriteArrayList<>();
    final List<String> executed = new CopyOnWriteArrayList<>();
    volatile boolean readOnly;
    volatile boolean autoCommit = true;
    private final FakeDriver driver;

    FakeConnection(FakeDriver driver) {
      this.driver = driver;
      this.proxy =
          (Connection)
              Proxy.newProxyInstance(
                  FakeDriver.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (self, method, args) -> connectionCall(self, method, args));
    }

    private Object connectionCall(Object self, Method method, Object[] args) throws Throwable {
      switch (method.getName()) {
        case "close" -> {
          closed.set(true);
          return null;
        }
        case "isClosed" -> {
          return closed.get();
        }
        case "isValid" -> {
          return !closed.get();
        }
        case "setReadOnly" -> {
          readOnly = (Boolean) args[0];
          readOnlyCalls.add(readOnly);
          return null;
        }
        case "isReadOnly" -> {
          return readOnly;
        }
        case "setAutoCommit" -> {
          autoCommit = (Boolean) args[0];
          return null;
        }
        case "getAutoCommit" -> {
          return autoCommit;
        }
        case "getTransactionIsolation" -> {
          return Connection.TRANSACTION_READ_COMMITTED;
        }
        case "createStatement" -> {
          return statement();
        }
        case "getMetaData" -> {
          return metaData();
        }
        case "toString" -> {
          return "FakeConnection";
        }
        case "hashCode" -> {
          return System.identityHashCode(self);
        }
        case "equals" -> {
          return self == args[0];
        }
        default -> {
          return defaultValue(method.getReturnType());
        }
      }
    }

    private Statement statement() {
      return (Statement)
          Proxy.newProxyInstance(
              FakeDriver.class.getClassLoader(),
              new Class<?>[] {Statement.class},
              (self, method, args) -> {
                if (method.getName().equals("execute")) {
                  executed.add((String) args[0]);
                  SQLException failure = driver.validationFailure;
                  if (failure != null) {
                    throw failure;
                  }
                  return true;
                }
                if (method.getName().equals("toString")) {
                  return "FakeStatement";
                }
                return defaultValue(method.getReturnType());
              });
    }

    private DatabaseMetaData metaData() {
      return (DatabaseMetaData)
          Proxy.newProxyInstance(
              FakeDriver.class.getClassLoader(),
              new Class<?>[] {DatabaseMetaData.class},
              (self, method, args) ->
                  switch (method.getName()) {
                    case "getDatabaseProductName" -> "FakeDB";
                    case "getDatabaseProductVersion" -> "1.2.3";
                    case "toString" -> "FakeMetaData";
                    default -> defaultValue(method.getReturnType());
                  });
    }
  }

  static Object defaultValue(Class<?> type) {
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type.isPrimitive() && type != void.class) {
      return 0;
    }
    return null;
  }
}
