package com.queryhub.adapter.web.admin.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리 API 와 health 밖의 actuator 를 지키는 단 하나의 SecurityFilterChain (ADR-021, ADR-022).
 *
 * <p>순서: RequestIdFilter(서블릿 필터, 가장 먼저) → FilterChainProxy 의 StrictHttpFirewall(모든 경로) → 이
 * 체인({@link AdminRequestMatcher} 에 맞는 요청만): {@link AdminNetworkGuardFilter} → CorsFilter → {@link
 * AdminTokenFilter} → 인가(anyRequest authenticated).
 *
 * <ul>
 *   <li>상태가 없다: 세션을 만들지 않고, CSRF(쿠키를 쓰지 않는 Bearer 전용), request cache, form login, HTTP Basic,
 *       logout, anonymous 는 끈다. Spring Security 기본 보안 헤더(Cache-Control no-store 등)는 둔다.
 *   <li>다른 SecurityFilterChain 은 없다. 그래서 health, /error, 데이터 API(/api/v1/**)는 firewall 만 거치고 인증 없이
 *       지나간다.
 *   <li>401 은 {@code WWW-Authenticate: Bearer} 와 UNAUTHORIZED, 403 은 FORBIDDEN 을 공통 오류 형식으로
 *       쓴다({@link SecurityErrorResponses}).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class AdminSecurityConfiguration {

  /** 모든 요청에 적용되는 방화벽: StrictHttpFirewall 과 관리 경로의 퍼센트 인코딩 거부({@link AdminPathFirewall}). */
  @Bean
  public WebSecurityCustomizer adminPathFirewallCustomizer() {
    return web -> web.httpFirewall(AdminPathFirewall.strict());
  }

  @Bean
  public SecurityFilterChain adminSecurityFilterChain(
      HttpSecurity http,
      AdminAccessPolicy adminAccessPolicy,
      UrlBasedCorsConfigurationSource adminCorsConfigurationSource,
      JsonMapper jsonMapper)
      throws Exception {
    SecurityErrorResponses errors = new SecurityErrorResponses(jsonMapper);
    http.securityMatcher(new AdminRequestMatcher())
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .anonymous(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(adminCorsConfigurationSource))
        .exceptionHandling(
            exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
        .addFilterBefore(new AdminNetworkGuardFilter(adminAccessPolicy, errors), CorsFilter.class)
        .addFilterAfter(
            new AdminTokenFilter(adminAccessPolicy.tokenVerifier(), errors), CorsFilter.class)
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
    return http.build();
  }
}
