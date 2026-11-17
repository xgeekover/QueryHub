package com.queryhub.app.config;

import com.queryhub.application.runtime.GetRuntimeInfoUseCase;
import com.queryhub.application.runtime.RuntimeInfoService;
import com.queryhub.domain.runtime.RuntimeMode;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

/**
 * 런타임 상태 조회 유스케이스를 조립한다.
 *
 * <p>application 모듈은 Spring 에 의존하지 않으므로 여기서 {@code @Bean} 으로 만든다. 버전은 빌드가 만든
 * META-INF/build-info.properties(BuildProperties)에서 읽는다. build-info 가 없을 때(예: IDE 에서 bootBuildInfo
 * 없이 실행)만 {@value #UNKNOWN_VERSION} 을 쓴다. 실행 모드는 {@link RuntimeModeResolver} 가 정하며, 프로파일이 잘못되면 이 빈을
 * 만들 때 기동이 실패한다.
 *
 * <p>유스케이스 빈은 {@code @Lazy(false)} 다. spring.main.lazy-initialization=true 여도 싱글톤 생성 단계에서 만들어지므로,
 * 모드 검사는 웹 서버가 포트를 여는 단계(컨텍스트 refresh 의 마지막)보다 먼저 실행된다. 프로파일이 잘못되면 포트를 열기 전에 기동이 실패한다.
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeInfoConfiguration {

  /** build-info 가 없을 때의 버전 표시. */
  public static final String UNKNOWN_VERSION = "unknown";

  /** 가동 시간 계산용 시계. 테스트는 고정 시계를 쓸 수 있다. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @Lazy(false)
  public GetRuntimeInfoUseCase getRuntimeInfoUseCase(
      Environment environment, ObjectProvider<BuildProperties> buildProperties, Clock clock) {
    RuntimeMode mode = RuntimeModeResolver.resolve(environment);
    String name = environment.getRequiredProperty("spring.application.name");
    return new RuntimeInfoService(
        name, version(buildProperties.getIfAvailable()), mode, clock.instant(), clock);
  }

  static String version(@Nullable BuildProperties buildProperties) {
    if (buildProperties == null) {
      return UNKNOWN_VERSION;
    }
    String version = buildProperties.getVersion();
    return version == null || version.isBlank() ? UNKNOWN_VERSION : version;
  }
}
