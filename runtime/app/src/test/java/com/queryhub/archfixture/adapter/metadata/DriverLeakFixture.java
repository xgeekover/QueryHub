package com.queryhub.archfixture.adapter.metadata;

import java.sql.Driver;

/**
 * 아키텍처 자기 검사용 고의 위반(R6): DB Provider 밖(메타데이터 어댑터)에서 JDBC 드라이버 클래스를 직접 쓴다. 운영 코드가 아니며 Spring 스테레오타입이
 * 없다. java.sql 만 함께 쓰므로 R8 은 어기지 않는다.
 */
public final class DriverLeakFixture {

  public Driver driver() {
    return new org.mariadb.jdbc.Driver();
  }
}
