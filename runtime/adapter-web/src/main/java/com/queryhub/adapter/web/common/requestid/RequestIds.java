package com.queryhub.adapter.web.common.requestid;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

/**
 * 요청 ID 의 이름과 규칙.
 *
 * <p>요청이 보낸 X-Request-Id 는 {@link #VALID_PATTERN} 에 맞을 때만 쓴다. 헤더·로그 주입을 막기 위해 영문자, 숫자, 점, 밑줄, 하이픈으로
 * 된 1~64 자만 허용하고, 그 밖에는 새 UUID 를 만든다.
 */
public final class RequestIds {

  /** 요청·응답 헤더 이름. */
  public static final String HEADER = "X-Request-Id";

  /** 요청 ID 를 담는 요청 속성 이름. 오류 디스패치(/error)에서도 같은 값을 찾을 수 있다. */
  public static final String ATTRIBUTE = RequestIds.class.getName() + ".REQUEST_ID";

  /** 로그 MDC 키. */
  public static final String MDC_KEY = "requestId";

  /** 받아들이는 요청 ID 형식. */
  public static final Pattern VALID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

  private RequestIds() {}

  /** 요청이 보낸 값이 형식에 맞는지 확인한다. */
  public static boolean isValid(String candidate) {
    return candidate != null && VALID_PATTERN.matcher(candidate).matches();
  }

  /** 새 요청 ID(무작위 UUID). */
  public static String generate() {
    return UUID.randomUUID().toString();
  }

  /** 형식에 맞으면 요청이 보낸 값을, 아니면 새 ID 를 돌려준다. */
  public static String resolve(String incoming) {
    return isValid(incoming) ? incoming : generate();
  }

  /** 필터가 요청 속성에 저장한 현재 요청 ID. */
  public static Optional<String> current(HttpServletRequest request) {
    return asId(request.getAttribute(ATTRIBUTE));
  }

  /** 현재 요청 ID. 없으면(필터를 거치지 않은 요청) 새로 만들어 요청 속성에 저장한다. */
  public static String currentOrGenerate(HttpServletRequest request) {
    return current(request)
        .orElseGet(
            () -> {
              String generated = generate();
              request.setAttribute(ATTRIBUTE, generated);
              return generated;
            });
  }

  /** {@link WebRequest} 용 {@link #currentOrGenerate(HttpServletRequest)}. */
  public static String currentOrGenerate(WebRequest request) {
    return asId(request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST))
        .orElseGet(
            () -> {
              String generated = generate();
              request.setAttribute(ATTRIBUTE, generated, RequestAttributes.SCOPE_REQUEST);
              return generated;
            });
  }

  private static Optional<String> asId(Object value) {
    if (value instanceof String id && !id.isEmpty()) {
      return Optional.of(id);
    }
    return Optional.empty();
  }
}
