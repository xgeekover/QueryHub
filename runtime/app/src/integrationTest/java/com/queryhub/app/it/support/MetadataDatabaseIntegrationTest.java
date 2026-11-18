package com.queryhub.app.it.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link QueryHubIntegrationTest} 클래스의 공통 부모. 공유 MariaDB 컨테이너의 접속 정보를
 * {@code @DynamicPropertySource} 로 queryhub.metadata.db.* 에 넣는다.
 *
 * <p>메서드가 이 클래스 하나에만 있으므로 상속한 모든 클래스의 컨텍스트 캐시 키가 같다(컨텍스트 하나, Hikari 풀 하나).
 */
public abstract class MetadataDatabaseIntegrationTest {

  @DynamicPropertySource
  static void metadataDatabase(DynamicPropertyRegistry registry) {
    MetadataMariaDb.registerProperties(registry);
  }
}
