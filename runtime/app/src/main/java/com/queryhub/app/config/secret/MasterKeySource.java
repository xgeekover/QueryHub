package com.queryhub.app.config.secret;

import com.queryhub.adapter.crypto.MasterKey;
import com.queryhub.app.config.ProcessVisibleSources;
import com.queryhub.app.config.admin.AdminTokenSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.core.env.Environment;

/**
 * 마스터 키를 읽고 검사한다 (P1-04, ADR-023). {@link AdminTokenSource} 와 같은 방식이고 desktop 과 server 가 같은 규칙을 쓴다.
 * 규칙에 맞지 않으면 기동을 거부한다(fail closed).
 *
 * <ul>
 *   <li>출처는 둘 중 정확히 하나: {@value #ENV_KEY} 환경변수(느슨한 바인딩으로 {@value #KEY_PROPERTY}) 또는 {@value
 *       #ENV_KEY_FILE} 가 가리키는 파일({@value #KEY_FILE_PROPERTY}). 둘 다 없거나 둘 다 있으면 거부한다. desktop 은 셸이 키
 *       파일에서 읽은 값을 자식 환경변수 {@value #ENV_KEY} 로 넘긴다.
 *   <li>{@value #KEY_PROPERTY} 가 명령행 인자, JVM 시스템 속성, 그 둘에서 넘긴 spring.application.json 에 있으면 프로세스
 *       목록에 보이므로 거부한다({@link ProcessVisibleSources}).
 *   <li>파일: 일반 파일이고 읽을 수 있으며 비어 있지 않고 {@value #MAX_FILE_BYTES} 바이트 이하여야 한다. 끝의 LF 또는 CRLF 하나는 뺀다.
 *   <li>형식: 정확히 {@value #HEX_LENGTH} 자리 16진수(대소문자 무관, 32 바이트). 모든 바이트가 같은 키와 관리자 토큰과 같은 값(대소문자 무관)은
 *       거부한다.
 * </ul>
 *
 * <p>값은 어떤 {@code @ConfigurationProperties} 클래스에도 묶지 않는다(Binder 로만 읽는다). 오류 메시지는 변수와 속성 이름만 말하고
 * 값(키, 파일 경로)은 넣지 않는다.
 */
public final class MasterKeySource {

  /** 키(64 자리 16진수)를 담는 환경변수. */
  public static final String ENV_KEY = "QUERYHUB_MASTER_KEY";

  /** 키 파일 경로를 담는 환경변수. */
  public static final String ENV_KEY_FILE = "QUERYHUB_MASTER_KEY_FILE";

  /** 키 속성 이름 ({@value #ENV_KEY} 의 느슨한 바인딩). */
  public static final String KEY_PROPERTY = "queryhub.master-key";

  /** 키 파일 속성 이름 ({@value #ENV_KEY_FILE} 의 느슨한 바인딩). */
  public static final String KEY_FILE_PROPERTY = "queryhub.master-key-file";

  /** 키의 16진수 자릿수 (32 바이트). */
  public static final int HEX_LENGTH = MasterKey.LENGTH * 2;

  /** 키 파일의 최대 크기(바이트). */
  public static final int MAX_FILE_BYTES = 4096;

  private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{" + HEX_LENGTH + "}");

  private static final ConfigurationPropertyName KEY = ConfigurationPropertyName.of(KEY_PROPERTY);

  private static final String KEY_NAME = ENV_KEY + "(" + KEY_PROPERTY + ")";

  private static final String FILE_NAME = ENV_KEY_FILE + "(" + KEY_FILE_PROPERTY + ")";

  private static final String HOW_TO =
      " 서버는 "
          + ENV_KEY
          + " 환경변수나 "
          + ENV_KEY_FILE
          + " (비밀 파일 경로) 중 하나만 설정한다. 키는 "
          + HEX_LENGTH
          + " 자리 16진수다(예: openssl rand -hex 32). desktop 은 셸이 키 파일에서 읽어 넘긴다.";

  private MasterKeySource() {}

  /**
   * 환경에서 마스터 키를 읽고 검사한다.
   *
   * @throws IllegalStateException 키를 쓸 수 없을 때. 메시지에 값은 없다.
   */
  public static MasterKey load(Environment environment) {
    Optional<String> visible = ProcessVisibleSources.findKey(environment, KEY);
    if (visible.isPresent()) {
      throw refuse(
          "마스터 키("
              + KEY_PROPERTY
              + ")가 "
              + visible.get()
              + "로 넘어왔다. 비밀 값은 프로세스 목록에 보이는 명령행 인자나 JVM 시스템 속성(spring.application.json 포함)으로 넘기지 않는다.");
    }
    Binder binder = Binder.get(environment);
    String key = binder.bind(KEY_PROPERTY, String.class).orElse(null);
    String keyFile = binder.bind(KEY_FILE_PROPERTY, String.class).orElse(null);
    if (key != null && keyFile != null) {
      throw refuse(KEY_NAME + "와 " + FILE_NAME + "가 함께 설정되었다. 하나만 설정한다.");
    }
    if (key == null && keyFile == null) {
      throw refuse("마스터 키가 없다: " + KEY_NAME + "도 " + FILE_NAME + "도 설정되지 않았다.");
    }
    String hex = key != null ? key : readFile(keyFile);
    String origin = key != null ? KEY_NAME : FILE_NAME + " 파일의 키";
    MasterKey masterKey = parse(hex, origin);
    rejectAdminTokenReuse(hex, origin, environment);
    return masterKey;
  }

  /** 키 형식(64 자리 16진수, 모든 바이트가 같지 않음)을 검사하고 키를 만든다. */
  static MasterKey parse(String hex, String origin) {
    if (!HEX.matcher(hex).matches()) {
      throw refuse(origin + " 는 정확히 " + HEX_LENGTH + " 자리 16진수(0-9, a-f, A-F)여야 한다.");
    }
    byte[] bytes = HexFormat.of().parseHex(hex);
    if (MasterKey.allIdentical(bytes)) {
      throw refuse(origin + " 의 모든 바이트가 같다. 무작위로 만든 키를 쓴다.");
    }
    return new MasterKey(bytes);
  }

  /**
   * 관리자 토큰을 마스터 키로 다시 쓰지 않는다. 토큰은 HTTP 요청마다 오가므로 키와 노출 경로가 다르다. 토큰이 없거나 틀리면 비교하지 않는다(그 경우는 관리자 토큰
   * 검사가 기동을 거부한다).
   */
  private static void rejectAdminTokenReuse(String hex, String origin, Environment environment) {
    String adminToken;
    try {
      adminToken = AdminTokenSource.load(environment);
    } catch (IllegalStateException e) {
      return;
    }
    if (hex.equalsIgnoreCase(adminToken)) {
      throw refuse(
          origin + " 가 관리자 토큰(" + AdminTokenSource.ENV_TOKEN + ")과 같다. 마스터 키와 관리자 토큰은 따로 만든다.");
    }
  }

  private static String readFile(@Nullable String location) {
    if (location == null || location.isEmpty()) {
      throw refuse(FILE_NAME + " 가 비어 있다.");
    }
    Path path;
    try {
      path = Path.of(location);
    } catch (InvalidPathException e) {
      throw refuse(FILE_NAME + " 가 올바른 파일 경로가 아니다.");
    }
    if (!Files.isRegularFile(path)) {
      throw refuse(FILE_NAME + " 가 가리키는 파일이 없거나 일반 파일이 아니다.");
    }
    byte[] bytes;
    try (InputStream in = Files.newInputStream(path)) {
      bytes = in.readNBytes(MAX_FILE_BYTES + 1);
    } catch (IOException | SecurityException e) {
      throw refuse(FILE_NAME + " 가 가리키는 파일을 읽을 수 없다.");
    }
    if (bytes.length == 0) {
      throw refuse(FILE_NAME + " 가 가리키는 파일이 비어 있다.");
    }
    if (bytes.length > MAX_FILE_BYTES) {
      throw refuse(FILE_NAME + " 가 가리키는 파일이 " + MAX_FILE_BYTES + " 바이트보다 크다.");
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
    return new IllegalStateException("마스터 키 설정이 잘못되어 런타임을 시작하지 않는다. " + reason + HOW_TO);
  }
}
