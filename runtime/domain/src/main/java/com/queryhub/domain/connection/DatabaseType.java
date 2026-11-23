package com.queryhub.domain.connection;

/**
 * 업무 DB 종류 (REQUIREMENTS 4장의 필수 지원 4종).
 *
 * <p>값이 있다고 지원되는 것은 아니다. 실제로 접속할 수 있는 종류는 등록된 DatabaseProvider 가 정한다 (P1-07 은 MariaDB 와 PostgreSQL
 * 만 등록한다. Oracle 은 P1-10, MSSQL 은 P1-11).
 */
public enum DatabaseType {
  POSTGRESQL,
  MARIADB,
  ORACLE,
  MSSQL
}
