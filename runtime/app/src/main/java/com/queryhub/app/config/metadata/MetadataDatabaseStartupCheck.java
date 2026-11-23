package com.queryhub.app.config.metadata;

import com.queryhub.adapter.metadata.store.OpenedMetadataStore;
import com.queryhub.adapter.metadata.store.StorePragmas;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/**
 * Metadata 저장소 기동 검사. {@link MetadataDatabaseAutoConfiguration} 이 {@code @Lazy(false)} 빈으로 만든다.
 *
 * <p>생성자 인자인 접속 정보 빈이 만들어질 때 저장소 검사(데이터 디렉터리, 잠금, 네이티브 라이브러리, DB 파일, quick_check, 부트스트랩 연결의
 * PRAGMA)가 끝난다. 초기화 단계에서는 다음을 차례로 한다. 이 빈은 싱글톤 생성 단계에서 만들어지므로 웹 서버가 포트를 여는 단계(컨텍스트 refresh 의 마지막)보다
 * 먼저다.
 *
 * <ol>
 *   <li>Flyway 전 검사는 {@link MetadataPoolCheck} 가 한다: DataSource 빈을 만든 직후(Flyway 를 포함해 누구도 풀을 쓰기 전)
 *       spring.datasource.hikari.data-source-properties 의 런타임 설정({@link
 *       StorePragmas#MANAGED_KEYS})을 거부하고({@link #rejectManagedDataSourceProperties}), 최대 풀 크기만큼
 *       연결을 동시에 빌려(서로 다른 물리 연결이다) 각 연결이 검사한 DB 파일을 열었는지({@code PRAGMA database_list})와 PRAGMA 를
 *       확인한다({@link #verifyPoolConnections}). connection-init-sql 같은 다른 길로 바뀐 값과 다른 파일로 돌린 접속 URL 도
 *       여기서 잡힌다.
 *   <li>이 빈은 Flyway 마이그레이션 초기화 빈을 꺼내 마이그레이션을 실행한다(lazy 초기화에서도). Flyway 는 {@link
 *       MetadataFlywayConnectionDetails} 때문에 항상 주 풀로 마이그레이션한다(ADR-012). 체크섬이 다르면 여기서 기동이 멈춘다.
 *       Flyway 를 꺼 두었으면 건너뛴다.
 *   <li>마이그레이션 뒤에 풀 연결을 한 번 더 확인한다.
 * </ol>
 */
final class MetadataDatabaseStartupCheck implements InitializingBean {

  private static final Log LOG = LogFactory.getLog(MetadataDatabaseStartupCheck.class);

  private final Path dbFile;
  private final ObjectProvider<FlywayMigrationInitializer> flywayInitializer;
  private final ObjectProvider<DataSource> dataSource;

  MetadataDatabaseStartupCheck(
      OpenedMetadataStore metadataStore,
      JdbcConnectionDetails connectionDetails,
      ObjectProvider<FlywayMigrationInitializer> flywayInitializer,
      ObjectProvider<DataSource> dataSource) {
    Objects.requireNonNull(connectionDetails, "connectionDetails");
    this.dbFile = metadataStore.layout().dbFile();
    this.flywayInitializer = Objects.requireNonNull(flywayInitializer, "flywayInitializer");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public void afterPropertiesSet() throws SQLException {
    // DataSource 를 꺼내면 MetadataPoolCheck 가 Flyway 전 검사를 마친다(이미 만들어졌으면 이미 마쳤다).
    DataSource pool = dataSource.getObject();
    flywayInitializer.getIfAvailable();
    int checked = verifyPoolConnections(pool, dbFile, "Flyway 후");
    if (LOG.isInfoEnabled()) {
      LOG.info("Metadata 저장소 풀 연결 " + checked + " 개의 SQLite 설정(PRAGMA)을 확인했다");
    }
  }

  /**
   * 런타임이 정하는 SQLite 설정을 Hikari 데이터 소스 속성으로 넘겼으면 거부한다. 값은 메시지에 넣지 않는다. 메시지의 설정 목록은 {@link
   * StorePragmas#MANAGED_KEYS} 에서 만든다(P1-07: open_mode 가 더해져도 목록이 어긋나지 않는다).
   */
  static void rejectManagedDataSourceProperties(DataSource pool) {
    if (!(pool instanceof HikariDataSource hikari)) {
      return;
    }
    Properties properties = hikari.getDataSourceProperties();
    List<String> managed = new ArrayList<>();
    for (String name : properties.stringPropertyNames()) {
      String normalized = name.toLowerCase(Locale.ROOT).replace('-', '_').replace('.', '_');
      if (StorePragmas.MANAGED_KEYS.contains(normalized)) {
        managed.add("spring.datasource.hikari.data-source-properties." + name);
      }
    }
    if (!managed.isEmpty()) {
      throw new IllegalStateException(
          "Metadata 저장소의 SQLite 설정 "
              + String.join(", ", managed)
              + " 는 런타임이 정하는 값이라 바꿀 수 없어 런타임을 시작하지 않는다 (런타임이 정하는 설정: "
              + String.join(", ", StorePragmas.MANAGED_KEYS)
              + "). 이 설정을 지워라.");
    }
  }

  /**
   * 풀 크기만큼 연결을 동시에 빌려 각 연결이 검사한 DB 파일을 열었는지({@code PRAGMA database_list}, {@link
   * StorePragmas#verifyMainFile})와 PRAGMA 값을 확인하고 돌려준다. 확인한 연결 수를 돌려준다.
   *
   * @param dbFile 저장소 검사를 통과한 DB 파일 ({@code StoreLayout#dbFile()})
   */
  static int verifyPoolConnections(DataSource pool, Path dbFile, String stage) throws SQLException {
    int size = pool instanceof HikariDataSource hikari ? hikari.getMaximumPoolSize() : 1;
    List<Connection> borrowed = new ArrayList<>();
    try {
      for (int i = 1; i <= size; i++) {
        Connection connection = pool.getConnection();
        borrowed.add(connection);
        String where = "풀 연결 " + i + "/" + size + " (" + stage + ")";
        StorePragmas.verifyMainFile(connection, dbFile, where);
        StorePragmas.verify(connection, where);
      }
    } finally {
      SQLException closeFailure = null;
      for (Connection connection : borrowed) {
        try {
          connection.close();
        } catch (SQLException failure) {
          closeFailure = failure;
        }
      }
      if (closeFailure != null) {
        throw closeFailure;
      }
    }
    return size;
  }
}
