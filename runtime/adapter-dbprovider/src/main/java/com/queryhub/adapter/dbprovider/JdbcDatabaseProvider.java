package com.queryhub.adapter.dbprovider;

import com.queryhub.application.connection.DatabaseProvider;
import com.queryhub.domain.connection.ConnectionFailureKind;
import com.queryhub.domain.connection.ConnectionPassword;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.ConnectionTestResult;
import com.queryhub.domain.connection.DatabaseType;
import com.queryhub.domain.connection.InvalidConnectionSettingsException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC 공통 DatabaseProvider. 한 {@link JdbcProviderSpi} 로 접속을 시험한다.
 *
 * <p>제한 시간: 접속과 확인 쿼리를 데몬 작업 스레드(이름 {@value #THREAD_NAME_PREFIX}N)에서 돌리고, 호출한 스레드는 접속 제한 시간 +
 * {@link #DEADLINE_MARGIN} 까지만 기다린다. 드라이버가 멈추면 TIMEOUT 을 돌려주고 작업 스레드를 인터럽트하며 실행기를 닫는다. 인터럽트를 무시하는
 * 드라이버라도 작업 스레드는 데몬이고 드라이버가 돌아오는 즉시 끝나며, 늦게 생긴 연결은 바로 닫는다.
 */
public final class JdbcDatabaseProvider implements DatabaseProvider {

  /** 접속 제한 시간에 더하는 여유. */
  public static final Duration DEADLINE_MARGIN = Duration.ofSeconds(2);

  static final String THREAD_NAME_PREFIX = "queryhub-conn-test-";

  private static final Logger LOG = LoggerFactory.getLogger(JdbcDatabaseProvider.class);

  private static final AtomicLong THREAD_SEQUENCE = new AtomicLong();

  private static final ThreadFactory THREADS =
      runnable -> {
        Thread thread =
            new Thread(runnable, THREAD_NAME_PREFIX + THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
      };

  private final JdbcProviderSpi spi;
  private final Duration margin;

  public JdbcDatabaseProvider(JdbcProviderSpi spi) {
    this(spi, DEADLINE_MARGIN);
  }

  /** 테스트용: 여유 시간을 바꾼다. */
  JdbcDatabaseProvider(JdbcProviderSpi spi, Duration margin) {
    this.spi = Objects.requireNonNull(spi, "spi");
    this.margin = Objects.requireNonNull(margin, "margin");
    Objects.requireNonNull(spi.type(), "spi.type()");
  }

  @Override
  public DatabaseType type() {
    return spi.type();
  }

  @Override
  public ConnectionTestResult testConnection(
      ConnectionSettings settings, ConnectionPassword password) {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(password, "password");
    long start = System.nanoTime();
    DriverConnectDataSource source;
    try {
      source = DriverConnectDataSource.create(spi, settings, password);
    } catch (InvalidConnectionSettingsException refused) {
      LOG.info("업무 DB 접속 테스트: type={}, kind={}", spi.type(), ConnectionFailureKind.OPTION_REJECTED);
      return ConnectionTestResult.optionRejected(refused.rejectedOption(), elapsedSince(start));
    }
    ConnectionTestResult result;
    try (source) {
      result = runWithDeadline(source, settings, start);
    }
    LOG.info(
        "업무 DB 접속 테스트: type={}, outcome={}, kind={}, elapsedMs={}",
        spi.type(),
        result.outcome(),
        result.failureKind(),
        result.elapsed().toMillis());
    return result;
  }

  private ConnectionTestResult runWithDeadline(
      DriverConnectDataSource source, ConnectionSettings settings, long start) {
    AtomicBoolean abandoned = new AtomicBoolean();
    ExecutorService executor = Executors.newSingleThreadExecutor(THREADS);
    Future<ConnectionTestResult> attempt = null;
    try {
      attempt = executor.submit(() -> attempt(source, settings, start, abandoned));
      long deadlineNanos = settings.connectTimeout().plus(margin).toNanos();
      ConnectionTestResult result = attempt.get(deadlineNanos, TimeUnit.NANOSECONDS);
      return result != null
          ? result
          : ConnectionTestResult.failure(ConnectionFailureKind.TIMEOUT, elapsedSince(start));
    } catch (TimeoutException timedOut) {
      abandoned.set(true);
      attempt.cancel(true);
      return ConnectionTestResult.failure(ConnectionFailureKind.TIMEOUT, elapsedSince(start));
    } catch (ExecutionException unexpected) {
      // attempt 는 예외를 결과로 바꾸므로 여기에 오는 것은 Error 뿐이다. 메시지는 남기지 않는다.
      LOG.warn(
          "업무 DB 접속 테스트 작업이 예외로 끝났다: type={}, exception={}",
          spi.type(),
          unexpected.getCause() == null ? null : unexpected.getCause().getClass().getName());
      return ConnectionTestResult.failure(
          ConnectionFailureKind.CONNECTION_FAILED, elapsedSince(start));
    } catch (InterruptedException interrupted) {
      abandoned.set(true);
      if (attempt != null) {
        attempt.cancel(true);
      }
      Thread.currentThread().interrupt();
      return ConnectionTestResult.failure(
          ConnectionFailureKind.CONNECTION_FAILED, elapsedSince(start));
    } finally {
      executor.shutdownNow();
    }
  }

  /** 작업 스레드: 접속, 확인 쿼리, 제품 정보. 호출한 쪽이 기다리기를 그만두었으면(abandoned) 결과 없이 연결을 닫는다. */
  private ConnectionTestResult attempt(
      DriverConnectDataSource source,
      ConnectionSettings settings,
      long start,
      AtomicBoolean abandoned) {
    Connection connection;
    try {
      connection = source.getConnection();
    } catch (ConnectionFailedException failure) {
      return ConnectionTestResult.failure(failure.kind(), elapsedSince(start));
    } catch (SQLException | RuntimeException failure) {
      return ConnectionTestResult.failure(
          ConnectionFailureKind.CONNECTION_FAILED, elapsedSince(start));
    }
    try {
      if (abandoned.get()) {
        return null;
      }
      try (Statement statement = connection.createStatement()) {
        statement.setQueryTimeout(querySeconds(settings.connectTimeout()));
        statement.execute(spi.validationQuery());
      }
      DatabaseMetaData metaData = connection.getMetaData();
      return ConnectionTestResult.success(
          metaData.getDatabaseProductName(),
          metaData.getDatabaseProductVersion(),
          elapsedSince(start));
    } catch (SQLException failure) {
      return ConnectionTestResult.failure(source.validationFailure(failure), elapsedSince(start));
    } catch (RuntimeException failure) {
      return ConnectionTestResult.failure(
          ConnectionFailureKind.VALIDATION_FAILED, elapsedSince(start));
    } finally {
      closeQuietly(connection);
    }
  }

  private void closeQuietly(Connection connection) {
    try {
      connection.close();
    } catch (SQLException | RuntimeException failure) {
      LOG.debug(
          "업무 DB 테스트 연결을 닫지 못했다: type={}, exception={}", spi.type(), failure.getClass().getName());
    }
  }

  private static int querySeconds(Duration connectTimeout) {
    long seconds = Math.max(1, (connectTimeout.toMillis() + 999) / 1000);
    return (int) Math.min(Integer.MAX_VALUE, seconds);
  }

  private static Duration elapsedSince(long start) {
    return Duration.ofNanos(Math.max(0, System.nanoTime() - start));
  }
}
