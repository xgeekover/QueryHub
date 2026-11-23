package com.queryhub.domain.connection;

import com.queryhub.domain.error.ErrorCode;
import com.queryhub.domain.error.QueryHubException;
import java.util.Objects;

/**
 * 접속 설정이 규칙에 맞지 않는다. 메시지는 고정 한국어이고 입력 값을 담지 않는다.
 *
 * <p>예외로 허용 목록 밖의 옵션은 이름을 메시지에 넣는다. 옵션 이름은 {@link ConnectionSettings} 가 이름 형식을 이미 검사했으므로 안전하고, 옵션
 * 값은 넣지 않는다.
 */
public final class InvalidConnectionSettingsException extends QueryHubException {

  private static final long serialVersionUID = 1L;

  private final String field;
  private final String rejectedOption;

  private InvalidConnectionSettingsException(String field, String message, String rejectedOption) {
    super(ErrorCode.INVALID_PARAMETER, message);
    this.field = Objects.requireNonNull(field, "field");
    this.rejectedOption = rejectedOption;
  }

  /** 필드 규칙 위반. message 는 고정 문자열이어야 한다(입력 값을 넣지 않는다). */
  static InvalidConnectionSettingsException field(String field, String message) {
    return new InvalidConnectionSettingsException(field, message, null);
  }

  /** DB 종류의 허용 목록에 없거나 고정 속성과 겹치는 옵션. 값은 메시지에 넣지 않는다. */
  public static InvalidConnectionSettingsException optionNotAllowed(String optionName) {
    if (optionName == null || !ConnectionFieldRules.isOptionName(optionName)) {
      throw new IllegalArgumentException("옵션 이름 형식이 아니다.");
    }
    return new InvalidConnectionSettingsException(
        "options", ConnectionFailureKind.OPTION_REJECTED.message(optionName), optionName);
  }

  /** 등록된 Provider 가 없는 DB 종류. */
  public static InvalidConnectionSettingsException unsupportedType(DatabaseType type) {
    Objects.requireNonNull(type, "type");
    return new InvalidConnectionSettingsException(
        "type", ConnectionFailureKind.UNSUPPORTED_DATABASE_TYPE.message(null), null);
  }

  /**
   * 규칙을 어긴 필드 이름 (type, host, port, databaseName, username, connectTimeout, maxPoolSize, options).
   */
  public String field() {
    return field;
  }

  /** 거부된 옵션 이름. 옵션 거부가 아니면 null. */
  public String rejectedOption() {
    return rejectedOption;
  }
}
