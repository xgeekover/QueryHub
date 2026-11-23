package com.queryhub.app.config.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.adapter.metadata.store.OpenedMetadataStore;
import com.queryhub.app.support.QueryHubWebTest;
import com.queryhub.app.support.WithoutMetadataDatabase;
import com.queryhub.application.setting.AppSettingRepository;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.ApplicationContext;

/**
 * AC-05: Metadata 저장소 자동 구성의 등록과, 단위 테스트 전용 제외가 실제로 저장소를 빼는지.
 *
 * <p>{@code @QueryHubWebTest} 컨텍스트(HTTP 테스트들과 공유)에는 DataSource 와 Metadata 저장소 빈이 없다. 운영 코드에는 제외가 없고
 * 자동 구성은 AutoConfiguration.imports 로 등록된다.
 */
@QueryHubWebTest
class MetadataAutoConfigurationRegistrationTest {

  @Autowired private ApplicationContext context;

  @Test
  void autoConfigurationIsListedInTheImportsFile() {
    // Boot 가 자동 구성을 찾는 방식 그대로(META-INF/spring/...AutoConfiguration.imports) 읽는다.
    List<String> candidates =
        ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates();

    assertThat(candidates).contains(MetadataDatabaseAutoConfiguration.class.getName());
    assertThat(
            MetadataDatabaseAutoConfiguration.class.getAnnotation(AutoConfiguration.class).before())
        .contains(DataSourceAutoConfiguration.class);
  }

  @Test
  void webTestContextHasNoDataSourceAndNoMetadataBeans() {
    assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
    assertThat(context.getBeanNamesForType(JdbcConnectionDetails.class)).isEmpty();
    assertThat(context.getBeanNamesForType(OpenedMetadataStore.class)).isEmpty();
    assertThat(context.getBeanNamesForType(MetadataStoreProperties.class)).isEmpty();
    assertThat(context.getBeanNamesForType(FlywayMigrationInitializer.class)).isEmpty();
    assertThat(context.getBeanNamesForType(AppSettingRepository.class)).isEmpty();
    assertThat(context.getBeanNamesForType(MetadataDatabaseAutoConfiguration.class)).isEmpty();
  }

  @Test
  void testOnlyExclusionNamesRealAutoConfigurationClasses() throws ClassNotFoundException {
    // 제외 값은 WithoutMetadataDatabase 한 곳에만 적는다. 여기서는 그 값을 읽어 검사만 한다.
    String property = WithoutMetadataDatabase.PROPERTY;
    List<String> excluded = Arrays.asList(property.substring(property.indexOf('=') + 1).split(","));

    assertThat(excluded)
        .containsExactly(
            MetadataDatabaseAutoConfiguration.class.getName(),
            DataSourceAutoConfiguration.class.getName());
    for (String name : excluded) {
      assertThat(Class.forName(name).isAnnotationPresent(AutoConfiguration.class))
          .as(name)
          .isTrue();
    }
  }
}
