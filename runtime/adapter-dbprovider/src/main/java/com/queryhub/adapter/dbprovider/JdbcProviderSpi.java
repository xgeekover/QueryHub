package com.queryhub.adapter.dbprovider;

import com.queryhub.domain.connection.ConnectionFailureKind;
import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.DatabaseType;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

/**
 * Provider SPI: 한 DB 종류의 JDBC 차이를 준다. provider-*
 * 모듈(com.queryhub.adapter.dbprovider.provider.&lt;db&gt;)이 구현하고, 공통 코드({@link
 * JdbcDatabaseProvider}, {@link HikariConnectionPools})는 이 인터페이스만 안다.
 *
 * <p>공통 코드가 맡는 것: 옵션 이름과 값의 허용 목록 검사, 고정 속성 우선, user/password 속성, Driver.connect 호출과 제한 시간, 오류 메시지
 * 정리. 그래서 구현은 아래 값만 돌려주면 되고 비밀번호를 보지 않는다.
 */
public interface JdbcProviderSpi {

  /** 다루는 DB 종류. */
  DatabaseType type();

  /** 드라이버 인스턴스. JDBC 전역 드라이버 등록부에 올리지 않아도 된다: 공통 코드는 {@link Driver#connect} 만 부른다. */
  Driver driver();

  /**
   * 검사된 필드로 만든 JDBC URL. 사용자가 준 문자열을 URL 에 붙이지 않는다. 옵션, 사용자, 비밀번호는 URL 이 아닌 속성으로 간다.
   *
   * <p>호스트 부분은 {@link JdbcUrls#host(ConnectionSettings)} 로 만든다 (IPv6 리터럴은 괄호로 감싼다).
   */
  String jdbcUrl(ConnectionSettings settings);

  /**
   * 고정 속성 (예: 접속·소켓 제한 시간, allowLocalInfile=false, 읽기 전용 모드). 옵션보다 우선하고, 같은 이름(대소문자 무시)의 옵션은 거부된다.
   * user 와 password 는 넣지 않는다.
   */
  Map<String, String> fixedProperties(ConnectionSettings settings);

  /**
   * 사용자가 줄 수 있는 옵션 이름과 각 옵션의 허용 값. 이름과 값 모두 대소문자를 구분해 정확히 같아야 한다 (드라이버가 받아 주는 별칭이나 대소문자 변형도 받지
   * 않는다). 목록에 없는 이름이나 허용 값이 아닌 값은 옵션 이름만 밝혀 거부되고 값은 어디에도 밝히지 않는다.
   *
   * <p>모든 옵션은 비어 있지 않은 값 집합을 가져야 한다. 값 집합이 없거나(null) 비어 있으면 공통 코드가 {@link IllegalStateException} 을
   * 던진다 (값을 검사하지 않는 옵션은 둘 수 없다).
   */
  Map<String, Set<String>> allowedOptions();

  /** 접속 테스트의 확인 쿼리 (예: SELECT 1). */
  String validationQuery();

  /**
   * 드라이버 예외의 분류. 메시지가 아닌 SQLState, 오류 코드, 예외 종류로만 정한다. 기본은 {@link
   * StandardFailureClassifier#classify(SQLException)}.
   */
  default ConnectionFailureKind classify(SQLException failure) {
    return StandardFailureClassifier.classify(failure);
  }
}
