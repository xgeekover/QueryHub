package com.queryhub.app.it.dbprovider;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import org.slf4j.LoggerFactory;

/**
 * 모든 로그를 가장 낮은 수준까지 붙잡는다: SLF4J/Logback(HikariCP, Connector/J, 우리 코드)과 java.util.logging(pgjdbc).
 * 붙잡은 글과 예외 사슬 글에 비밀번호가 없는지 시험이 확인한다.
 */
final class SecretCapture implements AutoCloseable {

  private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  private final Level previousRootLevel = root.getLevel();
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  // JUL 로거는 약한 참조라서 붙잡는 동안 강한 참조를 들고 있는다.
  private final java.util.logging.Logger pgjdbc =
      java.util.logging.Logger.getLogger("org.postgresql");
  private final java.util.logging.Level previousPgjdbcLevel = pgjdbc.getLevel();
  private final StringBuilder julText = new StringBuilder();
  private final Handler julHandler =
      new Handler() {
        private final SimpleFormatter formatter = new SimpleFormatter();

        @Override
        public void publish(LogRecord record) {
          synchronized (julText) {
            julText.append(formatter.format(record)).append('\n');
          }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  SecretCapture() {
    appender.start();
    root.addAppender(appender);
    root.setLevel(Level.TRACE);
    julHandler.setLevel(java.util.logging.Level.ALL);
    pgjdbc.setLevel(java.util.logging.Level.ALL);
    pgjdbc.addHandler(julHandler);
  }

  /** 붙잡은 모든 로그 (메시지, 인자, 예외 스택). */
  String text() {
    StringBuilder text = new StringBuilder();
    for (ILoggingEvent event : appender.list) {
      text.append(event.getLevel())
          .append(' ')
          .append(event.getLoggerName())
          .append(' ')
          .append(event.getFormattedMessage())
          .append('\n');
      IThrowableProxy thrown = event.getThrowableProxy();
      if (thrown != null) {
        text.append(ThrowableProxyUtil.asString(thrown)).append('\n');
      }
    }
    synchronized (julText) {
      text.append(julText);
    }
    return text.toString();
  }

  int eventCount() {
    return appender.list.size();
  }

  /** 예외와 원인, 다음 예외, 억제된 예외의 toString 과 스택 전체. */
  static String describe(Throwable failure) {
    StringWriter text = new StringWriter();
    failure.printStackTrace(new PrintWriter(text));
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sql && sql.getNextException() != null) {
        sql.getNextException().printStackTrace(new PrintWriter(text));
      }
    }
    return text.toString();
  }

  @Override
  public void close() {
    root.detachAppender(appender);
    appender.stop();
    root.setLevel(previousRootLevel);
    pgjdbc.removeHandler(julHandler);
    pgjdbc.setLevel(previousPgjdbcLevel);
  }
}
