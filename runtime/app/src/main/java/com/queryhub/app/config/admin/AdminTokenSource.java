package com.queryhub.app.config.admin;

import com.queryhub.app.config.ProcessVisibleSources;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.core.env.Environment;

/**
 * 관리자 토큰을 읽고 검사한다 (ADR-021). desktop 과 server 가 같은 규칙을 쓴다. 규칙에 맞지 않으면 기동을 거부한다(fail closed).
 *
 * <ul>
 *   <li>출처는 둘 중 정확히 하나: {@value #ENV_TOKEN} 환경변수(느슨한 바인딩으로 {@value #TOKEN_PROPERTY}) 또는 {@value
 *       #ENV_TOKEN_FILE} 가 가리키는 파일({@value #TOKEN_FILE_PROPERTY}). 둘 다 없거나 둘 다 있으면 거부한다.
 *   <li>{@value #TOKEN_PROPERTY} 가 명령행 인자, JVM 시스템 속성, 그 둘에서 넘긴 spring.application.json 에 있으면 프로세스
 *       목록에 보이므로 거부한다({@link ProcessVisibleSources}). 테스트의 인라인 속성과 기본
 *       속성(SpringApplication.setDefaultProperties)은 프로세스 목록에 보이지 않으므로 받는다.
 *   <li>파일: 일반 파일이고 읽을 수 있으며 비어 있지 않고 {@value #MAX_FILE_BYTES} 바이트 이하여야 한다. 끝의 LF 또는 CRLF 하나는 뺀다.
 *   <li>형식: {@value #MIN_LENGTH}-{@value #MAX_LENGTH} 자, RFC 6750 b64token 문자({@code
 *       [A-Za-z0-9-._~+/]+=*}).
 * </ul>
 *
 * <p>오류 메시지는 변수나 속성 이름만 말하고 값(토큰, 파일 경로)은 넣지 않는다.
 */
public final class AdminTokenSource {

  /** 토큰을 담는 환경변수. */
  public static final String ENV_TOKEN = "QUERYHUB_ADMIN_TOKEN";

  /** 토큰 파일 경로를 담는 환경변수. */
  public static final String ENV_TOKEN_FILE = "QUERYHUB_ADMIN_TOKEN_FILE";

  /** 토큰 속성 이름 ({@value #ENV_TOKEN} 의 느슨한 바인딩). */
  public static final String TOKEN_PROPERTY = "queryhub.admin.token";

  /** 토큰 파일 속성 이름 ({@value #ENV_TOKEN_FILE} 의 느슨한 바인딩). */
  public static final String TOKEN_FILE_PROPERTY = "queryhub.admin.token-file";

  /** 가장 짧은 토큰 길이. */
  public static final int MIN_LENGTH = 32;

  /** 가장 긴 토큰 길이. */
  public static final int MAX_LENGTH = 512;

  /** 토큰 파일의 최대 크기(바이트). */
  public static final int MAX_FILE_BYTES = 4096;

  /** RFC 6750 b64token. 길이는 따로 검사한다. */
  static final Pattern B64TOKEN = Pattern.compile("[A-Za-z0-9\\-._~+/]+=*");

  private static final ConfigurationPropertyName TOKEN_KEY =
      ConfigurationPropertyName.of(TOKEN_PROPERTY);

  private static final String HOW_TO =
      " 서버는 "
          + ENV_TOKEN
          + " 환경변수나 "
          + ENV_TOKEN_FILE
          + " (비밀 파일 경로) 중 하나만 설정한다. desktop 은 셸이 실행마다 만들어 넘긴다.";

  private AdminTokenSource() {}

  /**
   * 환경에서 토큰을 읽고 검사한다.
   *
   * @throws IllegalStateException 토큰을 쓸 수 없을 때. 메시지에 값은 없다.
   */
  public static String load(Environment environment) {
    Optional<String> visible = ProcessVisibleSources.findKey(environment, TOKEN_KEY);
    if (visible.isPresent()) {
      throw refuse(
          "관리자 토큰("
              + TOKEN_PROPERTY
              + ")이 "
              + visible.get()
              + "로 넘어왔다. 비밀 값은 프로세스 목록에 보이는 명령행 인자나 JVM 시스템 속성(spring.application.json 포함)으로 넘기지 않는다.");
    }
    Binder binder = Binder.get(environment);
    String token = binder.bind(TOKEN_PROPERTY, String.class).orElse(null);
    String tokenFile = binder.bind(TOKEN_FILE_PROPERTY, String.class).orElse(null);
    if (token != null && tokenFile != null) {
      throw refuse(
          ENV_TOKEN
              + "("
              + TOKEN_PROPERTY
              + ")와 "
              + ENV_TOKEN_FILE
              + "("
              + TOKEN_FILE_PROPERTY
              + ")가 함께 설정되었다. 하나만 설정한다.");
    }
    if (token == null && tokenFile == null) {
      throw refuse(
          "관리자 토큰이 없다: "
              + ENV_TOKEN
              + "("
              + TOKEN_PROPERTY
              + ")도 "
              + ENV_TOKEN_FILE
              + "("
              + TOKEN_FILE_PROPERTY
              + ")도 설정되지 않았다.");
    }
    if (token != null) {
      return validated(token, ENV_TOKEN + "(" + TOKEN_PROPERTY + ")");
    }
    return validated(readFile(tokenFile), ENV_TOKEN_FILE + "(" + TOKEN_FILE_PROPERTY + ") 파일의 토큰");
  }

  /** 토큰 형식(길이와 b64token 문자)을 검사한다. */
  static String validated(String token, String origin) {
    if (token.length() < MIN_LENGTH || token.length() > MAX_LENGTH) {
      throw refuse(origin + " 의 길이는 " + MIN_LENGTH + "-" + MAX_LENGTH + " 자여야 한다.");
    }
    if (!B64TOKEN.matcher(token).matches()) {
      throw refuse(
          origin + " 에 RFC 6750 b64token 이 아닌 문자가 있다 (허용: A-Z a-z 0-9 - . _ ~ + / 와 끝의 =).");
    }
    return token;
  }

  private static String readFile(@Nullable String location) {
    String name = ENV_TOKEN_FILE + "(" + TOKEN_FILE_PROPERTY + ")";
    if (location == null || location.isEmpty()) {
      throw refuse(name + " 가 비어 있다.");
    }
    Path path;
    try {
      path = Path.of(location);
    } catch (InvalidPathException e) {
      throw refuse(name + " 가 올바른 파일 경로가 아니다.");
    }
    if (!Files.isRegularFile(path)) {
      throw refuse(name + " 가 가리키는 파일이 없거나 일반 파일이 아니다.");
    }
    byte[] bytes;
    try (InputStream in = Files.newInputStream(path)) {
      bytes = in.readNBytes(MAX_FILE_BYTES + 1);
    } catch (IOException | SecurityException e) {
      throw refuse(name + " 가 가리키는 파일을 읽을 수 없다.");
    }
    if (bytes.length == 0) {
      throw refuse(name + " 가 가리키는 파일이 비어 있다.");
    }
    if (bytes.length > MAX_FILE_BYTES) {
      throw refuse(name + " 가 가리키는 파일이 " + MAX_FILE_BYTES + " 바이트보다 크다.");
    }
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (text.endsWith("\r\n")) {
      return text.substring(0, text.length() - 2);
    }
    if (text.endsWith("\n")) {
      return text.substring(0, text.length() - 1);
    }
    return text;
  }

  private static IllegalStateException refuse(String reason) {
    return new IllegalStateException("관리자 토큰 설정이 잘못되어 런타임을 시작하지 않는다. " + reason + HOW_TO);
  }
}
