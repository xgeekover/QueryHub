package com.queryhub.app.support;

import java.sql.DriverManager;
import java.sql.SQLException;

/** 단위 테스트 도우미: 실제 SQLite 저장소를 쓰는 테스트. */
public final class TestSqlite {

  private static boolean nativeLoaded;

  private TestSqlite() {}

  /**
   * 이 테스트 JVM 에서 sqlite-jdbc 네이티브 라이브러리를 한 번 불러 둔다. sqlite-jdbc 는 JVM 마다 한 번만 풀고 불러오므로, 앞선 테스트가
   * org.sqlite.tmpdir 를 이미 지워진 임시 디렉터리로 두었더라도 뒤의 테스트가 그 디렉터리로 풀려다 실패하지 않게 한다(기본 위치에 푼다). 추출 위치 검사는
   * 새 JVM(실행 JAR 자식 프로세스, BootJarIntegrationTest)이 한다.
   */
  public static synchronized void loadNativeOnce() throws SQLException {
    if (!nativeLoaded) {
      System.clearProperty("org.sqlite.tmpdir");
      DriverManager.getConnection("jdbc:sqlite::memory:").close();
      nativeLoaded = true;
    }
  }
}
