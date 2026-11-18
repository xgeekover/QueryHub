package com.queryhub.app.support;

import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * 테스트 클래스패스에서 실제 QueryHubApplication 을 SpringApplication 으로 직접 띄울 때, 컴포넌트 스캔이 테스트 전용
 * 빈({@code @TestComponent}, {@code @TestConfiguration})을 집지 않게 한다.
 *
 * <p>{@code @SpringBootTest} 는 같은 일을 Boot 의 TestTypeExcludeFilter 로 한다(공개 API 가 아니다). 직접 띄우는 컨텍스트에는
 * 그 필터가 없어서, 예를 들어 오류 프로브 컨트롤러가 스캔과 {@code @Bean} 으로 두 번 등록되어 매핑이 충돌한다. 이 클래스는 Metadata DB 자동 구성을
 * 빼지 않는다(운영 규칙을 그대로 검사한다).
 *
 * <p>사용: {@code new
 * SpringApplicationBuilder(...).initializers(TestComponentScanExclusion::register)}.
 */
public final class TestComponentScanExclusion extends TypeExcludeFilter {

  private static final String BEAN_NAME = TestComponentScanExclusion.class.getName();

  /** 컨텍스트에 필터를 등록한다. @SpringBootApplication 의 TypeExcludeFilter 가 이 빈에 판단을 맡긴다. */
  public static void register(ConfigurableApplicationContext context) {
    context.getBeanFactory().registerSingleton(BEAN_NAME, new TestComponentScanExclusion());
  }

  @Override
  public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) {
    return metadataReader.getAnnotationMetadata().isAnnotated(TestComponent.class.getName());
  }

  @Override
  public boolean equals(Object other) {
    return other != null && other.getClass() == getClass();
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
