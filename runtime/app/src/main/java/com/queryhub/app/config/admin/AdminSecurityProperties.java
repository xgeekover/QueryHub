package com.queryhub.app.config.admin;

import com.queryhub.adapter.web.admin.security.HostHeaderPolicy;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 관리 체인의 네트워크 설정 ({@value #PREFIX}.allow-remote, allowed-hosts).
 *
 * <p>토큰({@value AdminTokenSource#TOKEN_PROPERTY}, {@value AdminTokenSource#TOKEN_FILE_PROPERTY})은 이
 * 레코드에 바인딩하지 않는다. 값이 toString 이나 바인딩 오류 메시지에 실리지 않도록 {@link AdminTokenSource} 가 직접 읽고 검사한다.
 *
 * @param allowRemote true 이면 루프백이 아닌 소켓 주소의 관리 요청도 받는다. 기본은 false(application.yml), server 프로파일은
 *     true 다.
 * @param allowedHosts Host 허용 목록에 더하는 이름. desktop 은 늘 Host 를 검사하고 localhost, 127.0.0.1, [::1] 은 늘
 *     허용한다. server 는 이 목록이 비어 있지 않을 때만 검사한다.
 */
@ConfigurationProperties(prefix = AdminSecurityProperties.PREFIX)
public record AdminSecurityProperties(
    @DefaultValue("false") boolean allowRemote, @Nullable List<String> allowedHosts) {

  /** 설정 접두사. */
  public static final String PREFIX = "queryhub.admin";

  /** 원격 허용 속성 이름. */
  public static final String ALLOW_REMOTE = PREFIX + ".allow-remote";

  /** Host 허용 목록 속성 이름. */
  public static final String ALLOWED_HOSTS = PREFIX + ".allowed-hosts";

  /**
   * 허용 목록을 검사하고 소문자로 바꾼다. 없으면 빈 목록이다.
   *
   * @throws IllegalArgumentException 호스트 이름, IPv4, 대괄호로 감싼 IPv6 가 아닌 항목(와일드카드, 포트, 경로 등)이 있을 때.
   *     메시지는 속성 이름과 문제가 있는 항목을 말한다.
   */
  public AdminSecurityProperties {
    List<String> normalized = new ArrayList<>();
    List<String> invalid = new ArrayList<>();
    for (String entry : allowedHosts == null ? List.<String>of() : allowedHosts) {
      if (entry != null && HostHeaderPolicy.isValidEntry(entry)) {
        normalized.add(HostHeaderPolicy.normalizeEntry(entry));
      } else {
        invalid.add("'" + entry + "'");
      }
    }
    if (!invalid.isEmpty()) {
      throw new IllegalArgumentException(
          ALLOWED_HOSTS
              + " 의 항목은 호스트 이름, IPv4 주소, 대괄호로 감싼 IPv6 주소여야 한다 (와일드카드, 포트, 경로, 끝의 점 불가)."
              + " 규칙에 맞지 않는 값: "
              + String.join(", ", invalid));
    }
    allowedHosts = List.copyOf(normalized);
  }

  @Override
  public List<String> allowedHosts() {
    return allowedHosts == null ? List.of() : allowedHosts;
  }
}
