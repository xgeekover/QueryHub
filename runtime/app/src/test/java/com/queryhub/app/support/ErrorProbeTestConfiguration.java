package com.queryhub.app.support;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * 오류 형식 테스트용 프로브. {@link QueryHubWebTest} 가 가져오며 운영 코드에는 없다.
 *
 * <p>프로브 컨트롤러({@link ErrorProbeController}, {@value #BASE_PATH})와 예외를 던지는 서블릿 필터를 {@code @Bean} 으로
 * 등록한다.
 *
 * <p>컨트롤러를 이 클래스 안에 중첩하지 않는다. 가져온(@Import) 설정 클래스의 {@code @Component} 중첩 클래스는 Spring 이 자동으로 빈으로
 * 등록하므로, {@code @Bean} 과 함께 두 번 등록되어 매핑이 충돌한다. 필터는 스테레오타입이 없는 중첩 클래스라 괜찮다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ErrorProbeTestConfiguration {

  public static final String BASE_PATH = "/test/probe";
  public static final String ECHO_PATH = BASE_PATH + "/echo";
  public static final String DOMAIN_ERROR_PATH = BASE_PATH + "/domain-error";
  public static final String UNEXPECTED_PATH = BASE_PATH + "/unexpected";
  public static final String FILTER_ERROR_PATH = BASE_PATH + "/filter-error";

  /** 내부 예외 메시지에 넣는 표식. 응답 본문에 나오면 안 되고 로그에는 나와야 한다. */
  public static final String SECRET_MARKER = "probe-secret-marker-5b8e0d";

  /** QueryHubException 의 클라이언트 메시지. 응답에 그대로 나와야 한다. */
  public static final String DOMAIN_ERROR_MESSAGE = "limit 은 1 이상이어야 합니다.";

  @Bean
  public ErrorProbeController errorProbeController() {
    return new ErrorProbeController();
  }

  @Bean
  public FilterRegistrationBean<Filter> throwingProbeFilter() {
    FilterRegistrationBean<Filter> registration =
        new FilterRegistrationBean<>(new ThrowingFilter());
    registration.addUrlPatterns(FILTER_ERROR_PATH);
    // RequestIdFilter(HIGHEST_PRECEDENCE) 다음에 실행한다. 요청 ID 가 이미 정해진 상태에서 컨테이너 오류가 나야 한다.
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
    return registration;
  }

  /** 서블릿 필터 단계에서 예외를 던진다. Spring MVC 밖이므로 컨테이너의 /error 디스패치로 처리된다. */
  static final class ThrowingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
      throw new IllegalStateException("프로브 필터 실패 " + SECRET_MARKER);
    }
  }
}
