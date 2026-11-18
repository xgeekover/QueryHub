package com.queryhub.adapter.web.admin.cors;

import com.queryhub.adapter.web.common.requestid.RequestIds;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 관리 API({@value #PATH_PATTERN})의 CORS. Tauri WebView(tauri://localhost 등)의 UI 가 HTTP 로 런타임 정보를
 * 읽는다.
 *
 * <ul>
 *   <li>{@link AdminCorsProperties#ALLOWED_ORIGINS} 가 비어 있으면 매핑을 등록하지 않는다(server 프로파일, 프로파일 공통 설정).
 *   <li>매핑은 관리 API 경로에만 둔다. 데이터 API(/api/v1)와 actuator 에는 CORS 가 없다.
 *   <li>Spring 의 CorsRegistration 기본값(GET, HEAD, POST 와 모든 요청 헤더)을 쓰지 않는다. 메서드는 GET 만, 요청 헤더는 하나도
 *       허용하지 않는다. 응답 헤더는 X-Request-Id 만 노출하고 자격 증명(쿠키 등)은 허용하지 않는다.
 *   <li>거부된 요청은 Spring 의 403 "Invalid CORS request" 본문을 받는다(공통 오류 형식이 아니다). Phase 1 에서 Spring
 *       Security 와 관리자 토큰을 넣을 때 다시 본다(계획).
 * </ul>
 *
 * <p>lazy 초기화에서도 설정 검사가 기동 중에 일어나도록 이 빈은 늘 즉시 만든다({@code @Lazy(false)}). 그래서 잘못된 출처는 웹 서버가 포트를 열기
 * 전에 기동을 멈춘다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminCorsProperties.class)
@Lazy(false)
public class AdminCorsConfiguration implements WebMvcConfigurer {

  /** CORS 를 적용하는 경로. */
  public static final String PATH_PATTERN = "/admin/api/v1/**";

  /** 허용하는 메서드(GET 만). */
  static final String[] ALLOWED_METHODS = {"GET"};

  /** 브라우저가 preflight 결과를 캐시하는 시간(초). Spring 기본값과 같지만 명시해 둔다. */
  static final long MAX_AGE_SECONDS = 1800;

  private final AdminCorsProperties properties;

  public AdminCorsConfiguration(AdminCorsProperties properties) {
    this.properties = properties;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (!properties.enabled()) {
      return;
    }
    registry
        .addMapping(PATH_PATTERN)
        .allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
        .allowedMethods(ALLOWED_METHODS)
        // 빈 목록: 요청 헤더를 하나도 허용하지 않는다 (Access-Control-Request-Headers 가 있는 preflight 는 거부된다).
        .allowedHeaders()
        .exposedHeaders(RequestIds.HEADER)
        .allowCredentials(false)
        .maxAge(MAX_AGE_SECONDS);
  }
}
