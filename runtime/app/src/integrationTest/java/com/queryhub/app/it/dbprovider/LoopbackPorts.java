package com.queryhub.app.it.dbprovider;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.testcontainers.containers.GenericContainer;

/**
 * 컨테이너 포트를 호스트의 127.0.0.1 에만 연다 (P1-07 AC19, BACKLOG P0B-R6). Testcontainers 는 기본으로 노출 포트를 모든 호스트
 * 인터페이스(0.0.0.0)의 무작위 포트에 연다. 컨테이너를 만들기 직전에 포트 바인딩을 127.0.0.1 의 무작위 포트로 바꾸고 publishAllPorts 를 끈다.
 */
final class LoopbackPorts {

  static final String LOOPBACK = "127.0.0.1";

  private LoopbackPorts() {}

  /** {@code withCreateContainerCmdModifier} 에 넘긴다. */
  static Consumer<CreateContainerCmd> only(int containerPort) {
    return command -> {
      HostConfig hostConfig =
          Objects.requireNonNullElseGet(command.getHostConfig(), HostConfig::newHostConfig);
      hostConfig
          .withPublishAllPorts(false)
          .withPortBindings(
              new PortBinding(Ports.Binding.bindIp(LOOPBACK), ExposedPort.tcp(containerPort)));
      command.withHostConfig(hostConfig);
    };
  }

  /** 실행 중인 컨테이너가 실제로 연 바인딩의 HostIp 들 (docker inspect 의 NetworkSettings.Ports). */
  static List<String> publishedHostIps(GenericContainer<?> container) {
    List<String> hostIps = new ArrayList<>();
    Map<ExposedPort, Ports.Binding[]> bindings =
        container.getContainerInfo().getNetworkSettings().getPorts().getBindings();
    for (Ports.Binding[] perPort : bindings.values()) {
      if (perPort == null) {
        continue;
      }
      for (Ports.Binding binding : perPort) {
        hostIps.add(String.valueOf(binding.getHostIp()));
      }
    }
    return hostIps;
  }
}
