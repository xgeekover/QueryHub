package com.queryhub.app.config.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryhub.adapter.metadata.store.MetadataStoreException;
import com.queryhub.adapter.metadata.store.StoreLayout;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * P1-06 AC5 (바인딩): 데이터 디렉터리는 QUERYHUB_DATA_DIR 환경변수나 queryhub.data-dir 속성(설정 파일, 명령행)으로 온다. 네이티브
 * 디렉터리는 QUERYHUB_SQLITE_NATIVE_DIR 이다. 값이 없으면 두 이름을 모두 말하며 거부한다.
 */
class MetadataStorePropertiesTest {

  @Test
  void environmentVariablesBindThroughRelaxedBinding() {
    MetadataStoreProperties properties =
        bind(
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of(
                    "QUERYHUB_DATA_DIR", "/srv/queryhub/data",
                    "QUERYHUB_SQLITE_NATIVE_DIR", "/opt/queryhub/native")));

    assertThat(properties.dataDir()).isEqualTo("/srv/queryhub/data");
    StoreLayout layout = properties.toLayout();
    assertThat(layout.dbFile()).isEqualTo(Path.of("/srv/queryhub/data/queryhub.db"));
    assertThat(layout.nativeDir()).isEqualTo(Path.of("/opt/queryhub/native"));
  }

  @Test
  void configFilePropertiesBindToo() {
    MetadataStoreProperties properties =
        bind(
            new MapPropertySource(
                "Config resource 'file [/etc/queryhub/application.yml]'",
                Map.of("queryhub.data-dir", "/var/lib/queryhub")));

    StoreLayout layout = properties.toLayout();
    assertThat(layout.dataDir()).isEqualTo(Path.of("/var/lib/queryhub"));
    assertThat(layout.nativeDir()).isEqualTo(Path.of("/var/lib/queryhub/native"));
  }

  @Test
  void missingDataDirectoryIsRefusedNamingTheVariableAndTheProperty() {
    MetadataStoreProperties properties = bind(new MapPropertySource("empty", Map.of()));

    assertThatThrownBy(properties::toLayout)
        .isInstanceOf(MetadataStoreException.class)
        .hasMessageContaining("QUERYHUB_DATA_DIR")
        .hasMessageContaining("queryhub.data-dir");
  }

  private static MetadataStoreProperties bind(
      org.springframework.core.env.PropertySource<?> source) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(source);
    ConfigurationPropertySources.attach(environment);
    return Binder.get(environment).bindOrCreate("queryhub", MetadataStoreProperties.class);
  }
}
