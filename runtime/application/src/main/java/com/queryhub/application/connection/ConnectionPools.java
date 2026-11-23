package com.queryhub.application.connection;

import com.queryhub.domain.connection.ConnectionId;
import com.queryhub.domain.connection.ConnectionPassword;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.InvalidConnectionSettingsException;

/**
 * 출력 포트: 업무 DB 접속 정보마다 하나씩 두는 읽기 전용 커넥션 풀 (구현: adapter-dbprovider 의 HikariConnectionPools).
 *
 * <p>풀은 Spring 빈이 아니다. 앱이 종료될 때 {@link #closeAll()} 로 모두 닫는다. 연결을 꺼내는 방법은 JDBC 타입이 필요해서 이 포트가 아닌
 * 어댑터에 있다.
 */
public interface ConnectionPools {

  /**
   * 풀을 연다. 같은 ID 의 풀이 이미 있으면 그 풀을 먼저 닫는다. 여는 순간에는 DB 에 접속하지 않는다.
   *
   * @param password 호출한 쪽이 소유한다. 구현은 사본을 두고 풀을 닫을 때 지운다
   * @throws InvalidConnectionSettingsException 허용 목록 밖의 옵션이 있거나 이 종류의 Provider 가 없으면. 이때 기존 풀은 그대로다
   */
  void open(ConnectionId id, ConnectionSettings settings, ConnectionPassword password);

  /** 풀을 닫는다. 없으면 아무것도 하지 않는다. */
  void close(ConnectionId id);

  boolean isOpen(ConnectionId id);

  /** 모든 풀을 닫는다. */
  void closeAll();
}
