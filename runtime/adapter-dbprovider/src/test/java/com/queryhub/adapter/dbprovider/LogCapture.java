package com.queryhub.adapter.dbprovider;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

/** 루트 로거의 모든 로그(HikariCP 포함, 모든 수준)를 붙잡는다. 메시지, 예외 클래스, 예외 메시지, 원인 사슬을 글로 돌려준다. */
final class LogCapture implements AutoCloseable {

  private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  LogCapture() {
    appender.start();
    root.addAppender(appender);
  }

  List<ILoggingEvent> events() {
    synchronized (appender) {
      return new ArrayList<>(appender.list);
    }
  }

  String text() {
    StringBuilder text = new StringBuilder();
    for (ILoggingEvent event : events()) {
      text.append(event.getLevel()).append(' ').append(event.getFormattedMessage()).append('\n');
      for (IThrowableProxy proxy = event.getThrowableProxy();
          proxy != null;
          proxy = proxy.getCause()) {
        text.append(proxy.getClassName()).append(": ").append(proxy.getMessage()).append('\n');
        for (IThrowableProxy suppressed : proxy.getSuppressed()) {
          text.append(suppressed.getClassName())
              .append(": ")
              .append(suppressed.getMessage())
              .append('\n');
        }
      }
    }
    return text.toString();
  }

  @Override
  public void close() {
    root.detachAppender(appender);
    appender.stop();
  }
}
