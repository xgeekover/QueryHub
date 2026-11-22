package com.queryhub.app.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 프로세스 목록에 보이는 속성 소스 (ADR-011). 비밀 값은 이 소스들에서 받지 않는다.
 *
 * <p>프로세스 목록에 보이는 곳: 명령행 인자, JVM 시스템 속성, 그리고 spring.application.json 속성 소스 중 JSON 이 명령행 인자나 시스템
 * 속성에서 온 것. Boot 4.1.2 의 {@link SpringApplicationJsonEnvironmentPostProcessor} 는 모든 속성 소스를 앞에서부터 보며
 * 각 소스에 {@code spring.application.json} 과 {@code SPRING_APPLICATION_JSON} 을 묻고, 처음 나온 비어 있지 않은 JSON
 * 하나만 펼쳐서 {@code spring.application.json} 이라는 이름의 속성 소스로 넣는다. 이때 맨 앞에 붙어 있는 {@code
 * configurationProperties} 소스(ConfigurationPropertySources.attach)는 느슨한 바인딩으로 뒤의 모든 소스를 순서대로 찾으므로,
 * {@code --spring.Application.JSON=...} 이나 {@code -Dspring.APPLICATION.json=...} 같은 다른 표기도 펼쳐진다.
 * 그래서 여기서도 정확한 두 이름과 느슨한 바인딩의 다른 표기를 모두 본다. 명령행 인자와 시스템 속성은 OS 환경변수보다 앞이므로, 둘 중 하나에 비어 있지 않은 JSON 이
 * 있으면 그 속성 소스는 거기서 온 것이다. OS 환경변수의 SPRING_APPLICATION_JSON 은 허용한다.
 *
 * <p>Metadata DB 비밀번호 검사(MetadataDatabaseAutoConfiguration)와 관리자 토큰 검사(AdminTokenSource)가 같은 판단을
 * 쓴다.
 */
public final class ProcessVisibleSources {

  /** 프로세스 목록에 보이는 속성 소스. Boot 가 보는 순서(앞이 우선)다. */
  private static final List<String> PROCESS_VISIBLE_SOURCES =
      List.of(
          CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME,
          StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

  /**
   * Boot 가 펼친 JSON 을 넣는 속성 소스의 이름. Boot 4.1.2 의
   * SpringApplicationJsonEnvironmentPostProcessor.JsonPropertySource 가 이 이름(속성 이름과 같은 문자열)으로
   * MapPropertySource 를 만든다.
   */
  private static final String JSON_PROPERTY_SOURCE_NAME =
      SpringApplicationJsonEnvironmentPostProcessor.SPRING_APPLICATION_JSON_PROPERTY;

  /** Boot 가 JSON 을 찾는 두 이름. */
  private static final List<String> JSON_PROPERTY_NAMES =
      List.of(
          SpringApplicationJsonEnvironmentPostProcessor.SPRING_APPLICATION_JSON_PROPERTY,
          SpringApplicationJsonEnvironmentPostProcessor
              .SPRING_APPLICATION_JSON_ENVIRONMENT_VARIABLE);

  private static final ConfigurationPropertyName JSON_PROPERTY =
      ConfigurationPropertyName.of(
          SpringApplicationJsonEnvironmentPostProcessor.SPRING_APPLICATION_JSON_PROPERTY);

  private ProcessVisibleSources() {}

  /**
   * 프로세스 목록에 보이는 속성 소스와 그 설명("명령행 인자" 등). 설정 파일과 OS 환경변수는 들어 있지 않다.
   *
   * @param source 느슨한 바인딩으로 키를 찾을 수 있게 바꾼 소스
   * @param description 오류 메시지에 쓰는 출처 설명. 값은 들어 있지 않다.
   */
  public record VisibleSource(ConfigurationPropertySource source, String description) {

    /** 이 소스에 키(느슨한 바인딩의 다른 표기 포함)가 있는지. */
    public boolean contains(ConfigurationPropertyName key) {
      return source.getConfigurationProperty(key) != null;
    }
  }

  /** 환경에서 프로세스 목록에 보이는 속성 소스를 Boot 가 보는 순서로 돌려준다. */
  public static List<VisibleSource> of(Environment environment) {
    if (!(environment instanceof ConfigurableEnvironment configurable)) {
      return List.of();
    }
    MutablePropertySources sources = configurable.getPropertySources();
    Map<String, String> processVisible = new LinkedHashMap<>();
    for (String sourceName : PROCESS_VISIBLE_SOURCES) {
      processVisible.put(sourceName, describe(sourceName));
    }
    jsonOrigin(sources)
        .ifPresent(
            origin ->
                processVisible.put(
                    JSON_PROPERTY_SOURCE_NAME,
                    describe(origin) + "의 " + JSON_PROPERTY_SOURCE_NAME));
    List<VisibleSource> visible = new ArrayList<>();
    for (Map.Entry<String, String> entry : processVisible.entrySet()) {
      PropertySource<?> source = sources.get(entry.getKey());
      ConfigurationPropertySource adapted =
          source == null ? null : ConfigurationPropertySource.from(source);
      if (adapted != null) {
        visible.add(new VisibleSource(adapted, entry.getValue()));
      }
    }
    return visible;
  }

  /** 키가 프로세스 목록에 보이는 곳에 있으면 처음 찾은 곳의 설명을 돌려준다. */
  public static Optional<String> findKey(Environment environment, ConfigurationPropertyName key) {
    return of(environment).stream()
        .filter(source -> source.contains(key))
        .map(VisibleSource::description)
        .findFirst();
  }

  /**
   * spring.application.json 속성 소스가 명령행 인자나 시스템 속성에서 왔으면 그 출처(속성 소스 이름)를 돌려준다. Boot 와 같은 순서(명령행 인자,
   * 시스템 속성)로 보고, Boot 처럼 값이 빈 문자열이면 없는 것으로 본다. {@code spring.application.json} 은 느슨한 바인딩의 다른 표기도
   * 찾는다.
   */
  private static Optional<String> jsonOrigin(MutablePropertySources sources) {
    if (!sources.contains(JSON_PROPERTY_SOURCE_NAME)) {
      return Optional.empty();
    }
    for (String sourceName : PROCESS_VISIBLE_SOURCES) {
      PropertySource<?> source = sources.get(sourceName);
      if (source != null && definesJson(source)) {
        return Optional.of(sourceName);
      }
    }
    return Optional.empty();
  }

  private static boolean definesJson(PropertySource<?> source) {
    for (String name : JSON_PROPERTY_NAMES) {
      if (hasLength(source.getProperty(name))) {
        return true;
      }
    }
    ConfigurationPropertySource adapted = ConfigurationPropertySource.from(source);
    ConfigurationProperty relaxed =
        adapted == null ? null : adapted.getConfigurationProperty(JSON_PROPERTY);
    return relaxed != null && hasLength(relaxed.getValue());
  }

  private static boolean hasLength(@Nullable Object value) {
    return value instanceof String text && !text.isEmpty();
  }

  private static String describe(String sourceName) {
    return CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME.equals(sourceName)
        ? "명령행 인자"
        : "JVM 시스템 속성(-D)";
  }
}
