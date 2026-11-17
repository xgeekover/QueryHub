package com.queryhub.app.config;

import com.queryhub.domain.runtime.RuntimeMode;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import org.springframework.core.env.Environment;

/**
 * Spring 프로파일로 실행 모드({@link RuntimeMode})를 정한다.
 *
 * <p>활성 프로파일을 보고, 활성 프로파일이 하나도 없으면 기본 프로파일(application.yml 의 spring.profiles.default=desktop)을 본다.
 * desktop 과 server 중 정확히 하나가 있어야 한다. 둘 다 있거나 둘 다 없으면 바인딩 주소를 정할 수 없으므로 기동 단계에서 {@link
 * IllegalStateException} 으로 실패한다.
 */
public final class RuntimeModeResolver {

  private RuntimeModeResolver() {}

  /** 환경의 활성 프로파일(없으면 기본 프로파일)로 모드를 정한다. */
  public static RuntimeMode resolve(Environment environment) {
    Objects.requireNonNull(environment, "environment");
    String[] active = environment.getActiveProfiles();
    String[] profiles = active.length > 0 ? active : environment.getDefaultProfiles();
    return resolve(Arrays.asList(profiles));
  }

  /** 프로파일 이름 목록에서 desktop 또는 server 를 정확히 하나 찾는다. */
  public static RuntimeMode resolve(Collection<String> profiles) {
    Objects.requireNonNull(profiles, "profiles");
    EnumSet<RuntimeMode> modes = EnumSet.noneOf(RuntimeMode.class);
    for (String profile : profiles) {
      RuntimeMode.findById(profile).ifPresent(modes::add);
    }
    if (modes.size() != 1) {
      throw new IllegalStateException(
          "실행 모드 프로파일은 "
              + modeIds()
              + " 중 정확히 하나여야 한다 (현재 프로파일: "
              + profiles
              + "). SPRING_PROFILES_ACTIVE 를 확인하라.");
    }
    return modes.iterator().next();
  }

  private static List<String> modeIds() {
    return Arrays.stream(RuntimeMode.values()).map(RuntimeMode::id).toList();
  }
}
