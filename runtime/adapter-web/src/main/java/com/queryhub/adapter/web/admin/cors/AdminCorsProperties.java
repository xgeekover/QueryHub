package com.queryhub.adapter.web.admin.cors;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 관리 API 의 CORS 설정 ({@value #PREFIX}.*).
 *
 * <p>{@code allowed-origins} 가 비어 있으면(기본) CORS 매핑을 하나도 등록하지 않는다. 그래서 server 프로파일과 프로파일 공통 설정의 동작은
 * 그대로다. desktop 프로파일(application-desktop.yml)만 Tauri WebView 의 출처 두 개를 넣는다.
 *
 * <p>출처 하나는 {@code scheme://host[:port]} 형식이어야 한다. scheme 은 http, https, tauri 중 하나이고 경로, 쿼리,
 * 와일드카드, {@code null} 은 허용하지 않는다. 규칙에 맞지 않으면 바인딩이 실패해 웹 서버가 포트를 열기 전에 기동이 멈추고, 메시지가 {@value
 * #ALLOWED_ORIGINS} 를 말한다.
 */
@ConfigurationProperties(prefix = AdminCorsProperties.PREFIX)
public record AdminCorsProperties(@Nullable List<String> allowedOrigins) {

  /** 설정 접두사. */
  public static final String PREFIX = "queryhub.admin.cors";

  /** 허용 출처 목록의 속성 이름(오류 메시지가 이 이름을 말한다). */
  public static final String ALLOWED_ORIGINS = PREFIX + ".allowed-origins";

  /** scheme://host[:port]. host 는 글자, 숫자, '.', '-' 만 쓴다(와일드카드와 IPv6 리터럴은 받지 않는다). */
  private static final Pattern ORIGIN =
      Pattern.compile(
          "(?:http|https|tauri)://[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?(?::([0-9]{1,5}))?");

  private static final int MAX_PORT = 65535;

  /**
   * 값을 검사한다. 없으면 빈 목록이 된다.
   *
   * @throws IllegalArgumentException 규칙에 맞지 않는 출처가 있을 때. 메시지는 속성 이름과 문제가 있는 모든 출처를 말한다.
   */
  public AdminCorsProperties {
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    List<String> invalid = new ArrayList<>();
    for (String origin : allowedOrigins) {
      if (!isValidOrigin(origin)) {
        invalid.add("'" + origin + "'");
      }
    }
    if (!invalid.isEmpty()) {
      throw new IllegalArgumentException(
          ALLOWED_ORIGINS
              + " 의 출처는 scheme://host[:port] 형식이어야 한다 (scheme 은 http, https, tauri; 경로, 쿼리, 와일드카드, null 불가)."
              + " 규칙에 맞지 않는 값: "
              + String.join(", ", invalid));
    }
  }

  @Override
  public List<String> allowedOrigins() {
    // 컴팩트 생성자가 null 을 빈 목록으로 바꾼다.
    return allowedOrigins == null ? List.of() : allowedOrigins;
  }

  /** 허용 출처가 하나라도 있으면 CORS 매핑을 등록한다. */
  public boolean enabled() {
    return !allowedOrigins().isEmpty();
  }

  private static boolean isValidOrigin(String origin) {
    Matcher matcher = ORIGIN.matcher(origin);
    if (!matcher.matches()) {
      return false;
    }
    String port = matcher.group(1);
    if (port == null) {
      return true;
    }
    int value = Integer.parseInt(port);
    return value >= 1 && value <= MAX_PORT;
  }
}
