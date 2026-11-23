package com.queryhub.domain.connection;

import java.time.Duration;
import java.util.Objects;

/**
 * 접속 테스트 결과. 드라이버나 서버의 메시지는 담지 않는다.
 *
 * <p>성공이면 DB 제품 이름과 버전(DatabaseMetaData)을 담는다. 제어 문자를 빼고 {@link #MAX_PRODUCT_TEXT} 자로 자른다. 실패면 분류만
 * 담고, 사용자에게 보일 메시지는 {@link #message()} 가 분류에서 만든다.
 *
 * @param outcome 성공 또는 실패
 * @param failureKind 실패 분류. 성공이면 null
 * @param productName DB 제품 이름. 실패면 null
 * @param productVersion DB 제품 버전. 실패면 null
 * @param elapsed 걸린 시간
 * @param rejectedOption 옵션 거부({@link ConnectionFailureKind#OPTION_REJECTED})일 때 옵션 이름. 아니면 null
 */
public record ConnectionTestResult(
    ConnectionTestOutcome outcome,
    ConnectionFailureKind failureKind,
    String productName,
    String productVersion,
    Duration elapsed,
    String rejectedOption) {

  /** 제품 이름과 버전의 최대 길이. */
  public static final int MAX_PRODUCT_TEXT = 128;

  private static final String SUCCESS_MESSAGE = "연결에 성공했습니다.";

  public ConnectionTestResult {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(elapsed, "elapsed");
    if (elapsed.isNegative()) {
      throw new IllegalArgumentException("걸린 시간은 음수가 아니어야 한다.");
    }
    if (outcome == ConnectionTestOutcome.SUCCESS) {
      if (failureKind != null || rejectedOption != null) {
        throw new IllegalArgumentException("성공 결과에는 실패 분류가 없다.");
      }
      productName = productText(productName);
      productVersion = productText(productVersion);
    } else {
      Objects.requireNonNull(failureKind, "failureKind");
      if (productName != null || productVersion != null) {
        throw new IllegalArgumentException("실패 결과에는 제품 정보가 없다.");
      }
      if (rejectedOption != null
          && (failureKind != ConnectionFailureKind.OPTION_REJECTED
              || !ConnectionFieldRules.isOptionName(rejectedOption))) {
        throw new IllegalArgumentException("거부된 옵션 이름은 옵션 거부 결과에만 있다.");
      }
    }
  }

  public static ConnectionTestResult success(
      String productName, String productVersion, Duration elapsed) {
    return new ConnectionTestResult(
        ConnectionTestOutcome.SUCCESS, null, productName, productVersion, elapsed, null);
  }

  public static ConnectionTestResult failure(ConnectionFailureKind kind, Duration elapsed) {
    return new ConnectionTestResult(ConnectionTestOutcome.FAILURE, kind, null, null, elapsed, null);
  }

  public static ConnectionTestResult optionRejected(String optionName, Duration elapsed) {
    return new ConnectionTestResult(
        ConnectionTestOutcome.FAILURE,
        ConnectionFailureKind.OPTION_REJECTED,
        null,
        null,
        elapsed,
        optionName);
  }

  public boolean succeeded() {
    return outcome == ConnectionTestOutcome.SUCCESS;
  }

  /** 사용자에게 보여도 되는 고정 메시지. */
  public String message() {
    return succeeded() ? SUCCESS_MESSAGE : failureKind.message(rejectedOption);
  }

  private static String productText(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder clean = new StringBuilder(Math.min(text.length(), MAX_PRODUCT_TEXT));
    text.codePoints()
        .filter(codePoint -> !Character.isISOControl(codePoint))
        .limit(MAX_PRODUCT_TEXT)
        .forEach(clean::appendCodePoint);
    return clean.toString().strip();
  }
}
