package com.queryhub.app.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.queryhub.app.QueryHubApplication;
import com.queryhub.app.it.support.IntegrationHttp;
import com.queryhub.app.it.support.MetadataMariaDb;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * AC-16: 실제 MariaDB 가 자격 증명을 거부하면 기동은 웹 서버가 포트를 열기 전에 실패하고, 틀린 비밀번호는 출력 어디에도 남지 않는다. 일반 초기화와 lazy
 * 초기화 모두 확인한다. 회귀로 기동이 성공해도 루프백의 임의 포트에만 열고 바로 닫는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class MetadataStartupFailureIntegrationTest {

  /** 틀린 비밀번호 자리의 표식. */
  private static final String WRONG_PASSWORD = "secret-marker-wrong-0d3b77";

  @ParameterizedTest(name = "lazy={0}")
  @ValueSource(booleans = {false, true})
  void wrongPasswordFailsBeforeTheWebServerStartsWithoutLoggingIt(
      boolean lazy, CapturedOutput output) {
    AtomicBoolean webServerStarted = new AtomicBoolean();
    ApplicationListener<ApplicationEvent> webServerListener =
        event -> {
          if (event instanceof WebServerInitializedEvent) {
            webServerStarted.set(true);
          }
        };
    SpringApplication application =
        new SpringApplicationBuilder(QueryHubApplication.class)
            .properties(IntegrationHttp.ADMIN_TOKEN_PROPERTY)
            .web(WebApplicationType.SERVLET)
            .lazyInitialization(lazy)
            .profiles("desktop")
            .properties(
                MetadataMariaDb.applicationProperties(MetadataMariaDb.DATABASE, WRONG_PASSWORD))
            .listeners(webServerListener)
            .build();

    Throwable failure =
        catchThrowable(
            () -> {
              try (ConfigurableApplicationContext context =
                  application.run("--server.address=127.0.0.1", "--server.port=0")) {
                assertThat(context.isActive()).isTrue();
              }
            });

    assertThat(failure).as("틀린 비밀번호로는 기동할 수 없어야 한다").isNotNull();
    List<Throwable> causes = causes(failure);
    assertThat(causes)
        .as("DB 가 거부한 연결")
        .anySatisfy(
            cause ->
                assertThat(cause)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Access denied"));
    assertThat(webServerStarted).as("포트를 열기 전에 실패해야 한다").isFalse();
    assertThat(causes)
        .allSatisfy(
            cause -> assertThat(String.valueOf(cause.getMessage())).doesNotContain(WRONG_PASSWORD));
    assertThat(output.getAll()).doesNotContain(WRONG_PASSWORD);
  }

  private static List<Throwable> causes(Throwable failure) {
    List<Throwable> causes = new ArrayList<>();
    for (Throwable cause = failure;
        cause != null && !causes.contains(cause);
        cause = cause.getCause()) {
      causes.add(cause);
    }
    return causes;
  }
}
