package com.queryhub.app.it.dbprovider;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.testcontainers.DockerClientFactory;
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
    return hostIps(container.getContainerInfo().getNetworkSettings().getPorts());
  }

  /** 이 JVM 의 Testcontainers 세션이 쓰는 Ryuk 컨테이너 이름 (Testcontainers 2.0.5 RyukContainer). */
  static String ryukContainerName() {
    return "testcontainers-ryuk-" + DockerClientFactory.SESSION_ID;
  }

  /**
   * 이 시험 세션의 모든 컨테이너 (P1-08a A-AC5): 라벨 {@code org.testcontainers.sessionId} 가 이 JVM 의 {@link
   * DockerClientFactory#SESSION_ID} 인 컨테이너와 이름이 {@link #ryukContainerName()} 인 컨테이너. 멈춘 컨테이너도 넣는다.
   * Testcontainers 의 docker 클라이언트로 docker inspect 한 NetworkSettings.Ports 의 HostIp 들을 이름(앞의 / 포함)
   * 순으로 돌려준다. 다른 세션이나 다른 프로젝트의 컨테이너는 보지 않는다.
   */
  static Map<String, List<String>> sessionContainerHostIps() {
    DockerClient client = DockerClientFactory.instance().client();
    Set<String> ids = new LinkedHashSet<>();
    for (Container listed :
        client
            .listContainersCmd()
            .withShowAll(true)
            .withLabelFilter(
                Map.of(
                    DockerClientFactory.TESTCONTAINERS_SESSION_ID_LABEL,
                    DockerClientFactory.SESSION_ID))
            .exec()) {
      ids.add(listed.getId());
    }
    for (Container listed :
        client
            .listContainersCmd()
            .withShowAll(true)
            .withNameFilter(List.of(ryukContainerName()))
            .exec()) {
      ids.add(listed.getId());
    }
    Map<String, List<String>> published = new TreeMap<>();
    for (String id : ids) {
      InspectContainerResponse info = client.inspectContainerCmd(id).exec();
      Ports ports = info.getNetworkSettings() == null ? null : info.getNetworkSettings().getPorts();
      published.put(info.getName(), hostIps(ports));
    }
    return published;
  }

  private static List<String> hostIps(Ports ports) {
    List<String> hostIps = new ArrayList<>();
    if (ports == null) {
      return hostIps;
    }
    Map<ExposedPort, Ports.Binding[]> bindings = ports.getBindings();
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
