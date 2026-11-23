package com.queryhub.app.config.metadata;

import com.queryhub.adapter.metadata.store.OpenedMetadataStore;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * 주 DataSource(Metadata 저장소의 Hikari 풀) 빈이 초기화된 직후, 누구도 그 풀을 쓰기 전에 SQLite 설정을 확인한다.
 *
 * <p>Boot 는 JdbcClient·JdbcTemplate 를 쓰는 빈이 Flyway 마이그레이션 초기화 빈에 기대게 하므로, Flyway 는 기동 검사 빈보다 먼저 돌 수
 * 있다. 그래도 Flyway 는 이 풀을 쓰므로 이 검사가 먼저 끝난다: data-source-properties 로 넘긴 런타임 설정을 거부하고, 최대 풀 크기만큼 연결을
 * 동시에 빌려 각 연결이 검사한 DB 파일을 열었는지와 PRAGMA 를 확인한다({@link MetadataDatabaseStartupCheck}). 실패하면 빈 생성이
 * 실패하고 기동은 웹 서버가 포트를 열기 전에 멈춘다. 대상은 Boot 가 만드는 이름 {@value #DATA_SOURCE_BEAN} 의 빈뿐이다.
 */
final class MetadataPoolCheck implements BeanPostProcessor {

  static final String DATA_SOURCE_BEAN = "dataSource";

  private final ObjectProvider<OpenedMetadataStore> metadataStore;

  /**
   * @param metadataStore 검사한 저장소. DataSource 가 만들어질 때는 이미 있다(DataSource 가 기대는 접속 정보 빈이 저장소 빈에 기댄다).
   *     BeanPostProcessor 는 일찍 만들어지므로 늦게 꺼낸다.
   */
  MetadataPoolCheck(ObjectProvider<OpenedMetadataStore> metadataStore) {
    this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore");
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    if (bean instanceof DataSource pool && DATA_SOURCE_BEAN.equals(beanName)) {
      MetadataDatabaseStartupCheck.rejectManagedDataSourceProperties(pool);
      Path dbFile = metadataStore.getObject().layout().dbFile();
      try {
        MetadataDatabaseStartupCheck.verifyPoolConnections(pool, dbFile, "Flyway 전");
      } catch (SQLException failure) {
        throw new BeanCreationException(
            beanName,
            "Metadata 저장소 풀 연결을 확인할 수 없어 런타임을 시작하지 않는다: " + failure.getMessage(),
            failure);
      }
    }
    return bean;
  }
}
