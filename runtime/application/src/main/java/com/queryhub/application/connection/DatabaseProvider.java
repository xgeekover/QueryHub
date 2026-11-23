package com.queryhub.application.connection;

import com.queryhub.domain.connection.ConnectionPassword;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.ConnectionTestResult;
import com.queryhub.domain.connection.DatabaseType;

/**
 * 출력 포트: 한 DB 종류의 접속 기능 (REQUIREMENTS 4장의 DatabaseProvider). 구현은 adapter-dbprovider 와 provider-*
 * 모듈에 있다.
 *
 * <p>JDBC 타입을 쓰지 않는다 (ArchUnit R4). 결과와 예외에는 드라이버 메시지와 비밀번호가 없다.
 */
public interface DatabaseProvider {

  /** 이 Provider 가 다루는 DB 종류. */
  DatabaseType type();

  /**
   * 접속해 확인 쿼리를 한 번 실행하고 닫는다. 실패도 예외가 아닌 결과로 돌려준다.
   *
   * <p>설정의 접속 제한 시간에 작은 여유를 더한 시간 안에 반드시 돌아온다 (드라이버가 멈추어도).
   *
   * @param password 호출한 쪽이 소유한다. 구현은 사본을 쓰고 원본을 지우지 않는다
   * @throws IllegalArgumentException settings 의 종류가 {@link #type()} 과 다르면
   */
  ConnectionTestResult testConnection(ConnectionSettings settings, ConnectionPassword password);
}
