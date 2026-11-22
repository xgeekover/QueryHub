package com.queryhub.adapter.web.admin.cors;

import com.queryhub.adapter.web.common.requestid.RequestIds;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 관리 API({@value #PATH_PATTERN})의 CORS. Tauri WebView(tauri://localhost 등)의 UI 가 HTTP 로 런타임 정보를
 * 읽는다.
 *
 * <p>P1-03 부터 CORS 는 Spring MVC 의 CorsRegistry 가 아니라 관리 SecurityFilterChain 의 CorsFilter 가 처리한다
 * (AdminSecurityConfiguration 이 {@link #adminCorsConfigurationSource()} 를 쓴다). 그래서 preflight 는 토큰
 * 없이 답하고, 토큰이 없어 401 이 된 응답에도 허용된 출처라면 Access-Control-Allow-Origin 이 붙는다. MVC 매핑은 없으므로 CORS 헤더가 두 번
 * 붙지 않는다.
 *
 * <ul>
 *   <li>{@link AdminCorsProperties#ALLOWED_ORIGINS} 가 비어 있으면 설정을 하나도 등록하지 않는다(server 프로파일, 프로파일 공통
 *       설정).
 *   <li>설정은 관리 API 경로에만 둔다. 데이터 API(/api/v1)와 actuator 에는 CORS 가 없다.
 *   <li>메서드는 GET 만, 요청 헤더는 Authorization 만 허용한다(요청한 헤더가 모두 허용될 때만 preflight 를 통과시킨다). 응답 헤더는
 *       X-Request-Id 만 노출하고 자격 증명(쿠키 등)은 허용하지 않는다.
 *   <li>거부된 CORS 요청은 Spring 의 403 "Invalid CORS request" 본문을 받는다(공통 오류 형식이 아니다).
 * </ul>
 *
 * <p>lazy 초기화에서도 설정 검사가 기동 중에 일어나도록 이 빈은 늘 즉시 만든다({@code @Lazy(false)}). 그래서 잘못된 출처는 웹 서버가 포트를 열기
 * 전에 기동을 멈춘다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminCorsProperties.class)
@Lazy(false)
public class AdminCorsConfiguration {

  /** CORS 를 적용하는 경로. */
  public static final String PATH_PATTERN = "/admin/api/v1/**";

  /** 허용하는 메서드(GET 만). */
  static final List<String> ALLOWED_METHODS = List.of("GET");

  /** 허용하는 요청 헤더(Authorization 만). */
  static final List<String> ALLOWED_HEADERS = List.of(HttpHeaders.AUTHORIZATION);

  /** 브라우저가 preflight 결과를 캐시하는 시간(초). Spring 기본값과 같지만 명시해 둔다. */
  static final long MAX_AGE_SECONDS = 1800;

  private final AdminCorsProperties properties;

  public AdminCorsConfiguration(AdminCorsProperties properties) {
    this.properties = properties;
  }

  /** 관리 체인의 CorsFilter 가 쓰는 설정 소스. 허용 출처가 없으면 비어 있다(모든 요청에 CORS 를 적용하지 않는다). */
  @Bean
  public UrlBasedCorsConfigurationSource adminCorsConfigurationSource() {
    return corsConfigurationSource(properties);
  }

  static UrlBasedCorsConfigurationSource corsConfigurationSource(AdminCorsProperties properties) {
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    if (properties.enabled()) {
      source.registerCorsConfiguration(PATH_PATTERN, corsConfiguration(properties));
    }
    return source;
  }

  private static CorsConfiguration corsConfiguration(AdminCorsProperties properties) {
    CorsConfiguration configuration = new AllHeadersMustBeAllowed();
    configuration.setAllowedOrigins(properties.allowedOrigins());
    configuration.setAllowedMethods(ALLOWED_METHODS);
    configuration.setAllowedHeaders(ALLOWED_HEADERS);
    configuration.setExposedHeaders(List.of(RequestIds.HEADER));
    configuration.setAllowCredentials(false);
    configuration.setMaxAge(MAX_AGE_SECONDS);
    return configuration;
  }

  /**
   * Spring 의 {@link CorsConfiguration#checkHeaders} 는 요청한 헤더 중 허용된 것만 골라 preflight 를 통과시킨다(예:
   * {@code authorization, x-foo} 에 {@code authorization} 만 허용). 여기서는 요청한 헤더가 모두 허용될 때만 통과시킨다.
   */
  private static final class AllHeadersMustBeAllowed extends CorsConfiguration {

    @Override
    public @Nullable List<String> checkHeaders(@Nullable List<String> requestHeaders) {
      List<String> allowed = super.checkHeaders(requestHeaders);
      if (allowed == null || requestHeaders == null) {
        return allowed;
      }
      long requested = requestHeaders.stream().filter(StringUtils::hasText).count();
      return allowed.size() == requested ? allowed : null;
    }
  }
}
