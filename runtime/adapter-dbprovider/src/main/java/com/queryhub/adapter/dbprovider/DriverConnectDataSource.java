package com.queryhub.adapter.dbprovider;

import com.queryhub.domain.connection.ConnectionFailureKind;
import com.queryhub.domain.connection.ConnectionPassword;
import com.queryhub.domain.connection.ConnectionSettings;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;

/**
 * Provider 의 드라이버로 {@link Driver#connect} 를 부르는 DataSource. 접속 테스트와 커넥션 풀이 함께 쓰는 유일한 접속 경로다.
 *
 * <ul>
 *   <li>JDBC 전역 드라이버 등록부를 쓰지 않는다: 클래스패스의 다른 드라이버가 URL 을 가로채지 못한다.
 *   <li>비밀번호는 char[] 사본으로 들고 있다가 접속하는 순간에만 속성에 넣고, 호출이 끝나면 속성을 비운다. {@link #close()} 가 사본을 지운다.
 *   <li>{@link Driver#connect} 의 드라이버 예외는 {@link ConnectionFailedException}(고정 메시지, 원인 없음)으로 바꾼다.
 *       로그에는 분류, SQLState, 오류 코드, 예외 클래스 이름만 남긴다. 이 정리는 connect 실패에만 해당한다: 돌려준 연결은 드라이버의 연결 그대로라서,
 *       풀 (HikariCP)이 그 연결에 부르는 setReadOnly, isValid, setNetworkTimeout 이 실패하면 드라이버 예외가 그대로 Hikari
 *       에 가고 그 메시지가 풀의 로그에 남을 수 있다. 연결을 감싸는 일은 P1-12 로 미룬다 (BACKLOG).
 * </ul>
 */
final class DriverConnectDataSource implements DataSource, AutoCloseable {

  private static final org.slf4j.Logger LOG =
      LoggerFactory.getLogger(DriverConnectDataSource.class);

  private static final String CONNECT_FAILURE = "업무 DB 접속 실패";
  private static final String VALIDATION_FAILURE = "업무 DB 확인 쿼리 실패";

  private final JdbcProviderSpi spi;
  private final Driver driver;
  private final String url;
  private final Properties baseProperties;
  private final String username;
  private final ConnectionPassword password;
  private volatile int loginTimeoutSeconds;

  private DriverConnectDataSource(
      JdbcProviderSpi spi,
      Driver driver,
      String url,
      Properties baseProperties,
      String username,
      ConnectionPassword password) {
    this.spi = spi;
    this.driver = driver;
    this.url = url;
    this.baseProperties = baseProperties;
    this.username = username;
    this.password = password;
  }

  /**
   * 옵션을 검사하고 URL 과 속성을 준비한다. 아직 접속하지 않는다.
   *
   * @throws com.queryhub.domain.connection.InvalidConnectionSettingsException 허용되지 않은 옵션이나 옵션 값이
   *     있으면
   */
  static DriverConnectDataSource create(
      JdbcProviderSpi spi, ConnectionSettings settings, ConnectionPassword password) {
    Objects.requireNonNull(spi, "spi");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(password, "password");
    if (settings.type() != spi.type()) {
      throw new IllegalArgumentException(
          "접속 설정의 DB 종류(" + settings.type() + ")가 Provider(" + spi.type() + ")와 다르다.");
    }
    Properties properties = ConnectionProperties.build(spi, settings);
    String url = Objects.requireNonNull(spi.jdbcUrl(settings), "jdbcUrl");
    Driver driver = Objects.requireNonNull(spi.driver(), "driver");
    return new DriverConnectDataSource(
        spi, driver, url, properties, settings.username(), password.copy());
  }

  @Override
  public Connection getConnection() throws SQLException {
    Properties properties = new Properties();
    properties.putAll(baseProperties);
    properties.setProperty(ConnectionProperties.USER, username);
    char[] secret = password.copyChars();
    try {
      properties.setProperty(ConnectionProperties.PASSWORD, new String(secret));
    } finally {
      Arrays.fill(secret, '\0');
    }
    Connection connection;
    try {
      connection = driver.connect(url, properties);
    } catch (SQLException failure) {
      throw sanitized(failure);
    } catch (RuntimeException failure) {
      log(ConnectionFailureKind.CONNECTION_FAILED, null, 0, failure);
      throw new ConnectionFailedException(ConnectionFailureKind.CONNECTION_FAILED, null, 0);
    } finally {
      // 드라이버가 속성 객체를 들고 있더라도 비밀번호 문자열을 더는 가리키지 않게 한다.
      properties.clear();
    }
    if (connection == null) {
      // Driver.connect 는 URL 이 자기 것이 아니면 null 을 돌려준다 (Provider 의 URL 과 드라이버가 맞지 않는다).
      LOG.warn("업무 DB 드라이버가 접속 URL 을 받아들이지 않았다: type={}", spi.type());
      throw new ConnectionFailedException(ConnectionFailureKind.CONNECTION_FAILED, null, 0);
    }
    return connection;
  }

  /** 드라이버 예외를 분류하고 정리된 예외로 바꾼다. 원래 예외의 메시지와 원인은 어디에도 남지 않는다. */
  ConnectionFailedException sanitized(SQLException failure) {
    if (failure instanceof ConnectionFailedException already) {
      return already;
    }
    ConnectionFailureKind kind = classify(spi, failure);
    log(kind, failure.getSQLState(), failure.getErrorCode(), failure);
    return new ConnectionFailedException(kind, failure.getSQLState(), failure.getErrorCode());
  }

  /**
   * 확인 쿼리(연결은 됨)의 실패 분류: 드라이버 분류가 TIMEOUT 이면 TIMEOUT, 아니면 VALIDATION_FAILED. 로그에도 결과와 같은 분류를 남긴다.
   */
  ConnectionFailureKind validationFailure(SQLException failure) {
    ConnectionFailureKind kind =
        classify(spi, failure) == ConnectionFailureKind.TIMEOUT
            ? ConnectionFailureKind.TIMEOUT
            : ConnectionFailureKind.VALIDATION_FAILED;
    log(VALIDATION_FAILURE, kind, failure.getSQLState(), failure.getErrorCode(), failure);
    return kind;
  }

  static ConnectionFailureKind classify(JdbcProviderSpi spi, SQLException failure) {
    try {
      ConnectionFailureKind kind = spi.classify(failure);
      return kind == null ? ConnectionFailureKind.CONNECTION_FAILED : kind;
    } catch (RuntimeException classifierFailure) {
      return ConnectionFailureKind.CONNECTION_FAILED;
    }
  }

  private void log(ConnectionFailureKind kind, String sqlState, int vendorCode, Throwable failure) {
    log(CONNECT_FAILURE, kind, sqlState, vendorCode, failure);
  }

  private void log(
      String what, ConnectionFailureKind kind, String sqlState, int vendorCode, Throwable failure) {
    // 예외 객체를 로그 인자로 넘기지 않는다: 스택 트레이스에 드라이버 메시지가 찍힌다.
    LOG.warn(
        "{}: type={}, kind={}, sqlState={}, vendorCode={}, exception={}",
        what,
        spi.type(),
        kind,
        ConnectionFailedException.safeSqlState(sqlState),
        vendorCode,
        failure.getClass().getName());
  }

  /** 비밀번호 사본을 0 으로 덮는다. 이후 {@link #getConnection()} 은 실패한다. */
  @Override
  public void close() {
    password.destroy();
  }

  boolean isClosed() {
    return password.isDestroyed();
  }

  @Override
  public Connection getConnection(String user, String pass) throws SQLException {
    throw new SQLFeatureNotSupportedException("이 DataSource 는 설정된 사용자로만 접속한다.");
  }

  @Override
  public PrintWriter getLogWriter() {
    return null;
  }

  @Override
  public void setLogWriter(PrintWriter out) {
    // 드라이버 로그 출력은 쓰지 않는다.
  }

  /** 풀이 넣는 값을 기억만 한다. 드라이버의 제한 시간은 Provider 의 고정 속성이 정한다. */
  @Override
  public void setLoginTimeout(int seconds) {
    this.loginTimeoutSeconds = seconds;
  }

  @Override
  public int getLoginTimeout() {
    return loginTimeoutSeconds;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException("java.util.logging 을 쓰지 않는다.");
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface != null && iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("감싼 객체가 없다.");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface != null && iface.isInstance(this);
  }

  /** 로그와 풀 설정 출력에 쓰인다. URL 은 검사된 필드로만 만들었고 비밀번호는 없다. */
  @Override
  public String toString() {
    return "DriverConnectDataSource[type=" + spi.type() + ", url=" + url + "]";
  }
}
