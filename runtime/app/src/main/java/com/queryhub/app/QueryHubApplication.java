package com.queryhub.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * QueryHub 런타임의 진입점.
 *
 * <p>app 모듈은 모든 모듈을 아는 유일한 모듈이다. 컴포넌트 스캔 범위를 com.queryhub 전체로 넓혀 adapter-web 의 컨트롤러, 필터, 예외 처리기를
 * 등록한다. application 의 유스케이스는 Spring 에 의존하지 않으므로 app 의 설정 클래스가 {@code @Bean} 으로 만든다.
 */
@SpringBootApplication(scanBasePackages = "com.queryhub")
public class QueryHubApplication {

  public static void main(String[] args) {
    SpringApplication.run(QueryHubApplication.class, args);
  }
}
