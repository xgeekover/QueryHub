package com.queryhub.application.secret;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 비밀 값이 속한 곳 (P1-04). 암호문에 인증 데이터(AAD)로 묶이므로, 한 레코드의 암호문을 다른 레코드나 다른 용도로 옮기면 복호화가 실패한다.
 *
 * <p>어떤 필드를 레코드 ID 로 묶을지는 저장 기능(접속 정보 저장 작업)이 정한다. 레코드 ID 만 묶으면 같은 행의 다른 열(예: 호스트)을 바꾸는 것은 막지 못한다.
 *
 * @param purpose 용도. {@code [a-z][a-z0-9_.]{0,63}}
 * @param recordId 레코드 ID. 1-256 바이트(UTF-8)
 */
public record SecretContext(String purpose, String recordId) {

  /** 업무 DB 접속 정보의 비밀번호. */
  public static final String DATABASE_CONNECTION_PASSWORD = "database_connection.password";

  /** 레코드 ID 의 최대 길이(UTF-8 바이트). */
  public static final int MAX_RECORD_ID_BYTES = 256;

  private static final Pattern PURPOSE = Pattern.compile("[a-z][a-z0-9_.]{0,63}");

  /**
   * 값을 검사한다.
   *
   * @throws IllegalArgumentException 용도나 레코드 ID 가 규칙에 맞지 않으면. 메시지에 값은 없다.
   */
  public SecretContext {
    if (purpose == null || !PURPOSE.matcher(purpose).matches()) {
      throw new IllegalArgumentException("비밀 값 문맥의 용도(purpose)는 [a-z][a-z0-9_.]{0,63} 이어야 한다.");
    }
    if (recordId == null) {
      throw new IllegalArgumentException("비밀 값 문맥의 레코드 ID(recordId)가 없다.");
    }
    int bytes = utf8Length(recordId);
    if (bytes < 1 || bytes > MAX_RECORD_ID_BYTES) {
      throw new IllegalArgumentException(
          "비밀 값 문맥의 레코드 ID(recordId)는 1-" + MAX_RECORD_ID_BYTES + " 바이트(UTF-8)여야 한다.");
    }
  }

  /** 업무 DB 접속 정보 비밀번호의 문맥. */
  public static SecretContext databaseConnectionPassword(String recordId) {
    return new SecretContext(DATABASE_CONNECTION_PASSWORD, recordId);
  }

  /** 짝이 맞지 않는 서로게이트가 있으면 -1 (규칙 위반으로 다룬다). */
  private static int utf8Length(String text) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(text));
      return encoded.remaining();
    } catch (CharacterCodingException e) {
      return -1;
    }
  }
}
