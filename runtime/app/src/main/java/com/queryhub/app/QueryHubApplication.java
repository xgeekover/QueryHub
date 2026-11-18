package com.queryhub.app;

import com.queryhub.app.desktop.ParentProcessWatch;
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

  /** 부모 프로세스 감시 설정이 잘못되어 기동하지 않을 때의 종료 코드. */
  static final int EXIT_INVALID_PARENT_WATCH = 2;

  public static void main(String[] args) {
    // 부모 프로세스 감시(ParentProcessWatch)는 Spring 컨텍스트보다 먼저 시작한다. 기동이 멈춰 있어도 셸이 끝나면 런타임이 끝난다.
    // 값이 잘못되면 포트를 열기 전에 변수 이름을 말하고 0 이 아닌 코드로 끝난다.
    try {
      ParentProcessWatch.start(System.getenv(), ParentProcessWatch.exitJvm());
    } catch (IllegalStateException invalid) {
      System.err.println("QueryHub 런타임을 기동하지 않는다: " + invalid.getMessage());
      System.exit(EXIT_INVALID_PARENT_WATCH);
      return;
    }
    SpringApplication.run(QueryHubApplication.class, args);
  }
}
