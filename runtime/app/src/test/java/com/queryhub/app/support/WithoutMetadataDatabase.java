package com.queryhub.app.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * 단위 테스트 전용: 애플리케이션 전체를 띄우는 테스트에서 Metadata 저장소를 뺀다. 저장소에서 이 제외를 정의하는 유일한 파일이다.
 *
 * <p>Metadata 저장소 자동 구성(MetadataDatabaseAutoConfiguration)과 Boot 의 DataSource 자동 구성을 제외한다. 그러면
 * Flyway, JdbcClient, db health 는 DataSource 가 없어 스스로 빠진다. 데이터 디렉터리(QUERYHUB_DATA_DIR)도 필요 없다. 운영
 * 코드(src/main)에는 이 제외가 없고, 운영 규칙(데이터 디렉터리가 없거나 저장소 검사가 실패하면 기동 실패)은 MetadataStartupFailureTest 가 제외
 * 없이 확인한다. 실제 SQLite 저장소와의 동작은 통합 테스트(src/integrationTest)도 확인한다.
 *
 * <ul>
 *   <li>Spring 테스트 컨텍스트: 테스트 클래스(또는 합성 애너테이션)에 {@code @WithoutMetadataDatabase} 를 붙인다.
 *   <li>SpringApplicationBuilder 로 직접 띄우는 테스트: {@code
 *       .properties(WithoutMetadataDatabase.PROPERTY)} 를 쓴다.
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@TestPropertySource(properties = WithoutMetadataDatabase.PROPERTY)
public @interface WithoutMetadataDatabase {

  /** {@code key=value} 형식의 제외 속성. */
  String PROPERTY =
      "spring.autoconfigure.exclude="
          + "com.queryhub.app.config.metadata.MetadataDatabaseAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration";
}
