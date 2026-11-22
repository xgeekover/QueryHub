package com.queryhub.app.config.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.AccessLogValve;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.web.server.WebServer;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** P1-03 R1: 원격 주소 재작성 검사의 단위 테스트. 시작하지 않은 Tomcat 컨테이너 트리와 직접 만든 Environment 로 본다(포트를 열지 않는다). */
class TomcatRemoteAddressGuardTest {

  @Test
  void standardValvesAreAllowed() {
    Tree tree = new Tree();
    tree.host.getPipeline().addValve(new ErrorReportValve());
    tree.engine.getPipeline().addValve(new AccessLogValve());

    assertThat(TomcatRemoteAddressGuard.disallowedValves(tree.engine)).isEmpty();
  }

  @Test
  void remoteIpValveIsFoundAtEveryLevel() {
    Tree tree = new Tree();
    tree.engine.getPipeline().addValve(new RemoteIpValve());
    tree.host.getPipeline().addValve(new RemoteIpValve());
    tree.context.getPipeline().addValve(new RemoteIpValve());

    assertThat(TomcatRemoteAddressGuard.disallowedValves(tree.engine))
        .containsExactly(
            "StandardEngine[qh-engine]: org.apache.catalina.valves.RemoteIpValve",
            "StandardHost[localhost]: org.apache.catalina.valves.RemoteIpValve",
            "StandardContext[qh-context]: org.apache.catalina.valves.RemoteIpValve");
  }

  @Test
  void subclassOfAnAllowedValveIsNotAllowed() {
    Tree tree = new Tree();
    tree.context.getPipeline().addValve(new ErrorReportValve() {});

    assertThat(TomcatRemoteAddressGuard.disallowedValves(tree.engine)).hasSize(1);
  }

  @Test
  void aWebServerThatIsNotTomcatIsRefused() {
    WebServer other =
        new WebServer() {
          @Override
          public void start() {}

          @Override
          public void stop() {}

          @Override
          public int getPort() {
            return -1;
          }
        };

    assertThatThrownBy(() -> TomcatRemoteAddressGuard.verify(other))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Tomcat");
    assertThatCode(() -> TomcatRemoteAddressGuard.verify(null)).doesNotThrowAnyException();
  }

  /** 리뷰 재현과 같은 환경변수 형태(SERVER_TOMCAT_REMOTEIP_...)도 접두사 검사가 잡는다. */
  @Test
  void remoteIpPropertyFromAnEnvironmentVariableIsRefused() {
    StandardEnvironment environment =
        environment(Map.of("SERVER_TOMCAT_REMOTEIP_PROTOCOL_HEADER", "x-qh-probe-proto"));

    assertThatThrownBy(
            () ->
                AdminAccessConfiguration.rejectRemoteIpPropertiesWithoutRemote(environment, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("server.tomcat.remoteip")
        .hasMessageNotContaining("x-qh-probe-proto");
    assertThatCode(
            () -> AdminAccessConfiguration.rejectRemoteIpPropertiesWithoutRemote(environment, true))
        .doesNotThrowAnyException();
  }

  @Test
  void blankRemoteIpPropertiesAreAccepted() {
    StandardEnvironment environment =
        environment(Map.of("SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER", " "));

    assertThatCode(
            () ->
                AdminAccessConfiguration.rejectRemoteIpPropertiesWithoutRemote(environment, false))
        .doesNotThrowAnyException();
  }

  private static StandardEnvironment environment(Map<String, Object> variables) {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .addLast(
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
    ConfigurationPropertySources.attach(environment);
    return environment;
  }

  /** engine → host → context (시작하지 않는다). */
  private static final class Tree {
    final StandardEngine engine = new StandardEngine();
    final StandardHost host = new StandardHost();
    final StandardContext context = new StandardContext();

    Tree() {
      engine.setName("qh-engine");
      host.setName("localhost");
      context.setName("qh-context");
      context.setPath("");
      engine.addChild(host);
      host.addChild(context);
    }
  }
}
