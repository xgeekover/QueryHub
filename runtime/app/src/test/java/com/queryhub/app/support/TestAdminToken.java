package com.queryhub.app.support;

/**
 * 테스트 전용 관리자 토큰. 실제 비밀 값이 아니다(누구나 볼 수 있는 고정 픽스처).
 *
 * <p>테스트 컨텍스트는 이 값을 프로세스 목록에 보이지 않는 속성 소스로만 넘긴다:
 * {@code @SpringBootTest(properties=...)}·{@code @TestPropertySource} 의 인라인 속성(QueryHubWebTest) 또는
 * {@code SpringApplicationBuilder.properties(...)} 의 기본 속성. 명령행 인자나 -D 로 넘기면 AdminTokenSource 가 기동을
 * 거부한다(그 거부는 AdminSecurityStartupTest 가 검사한다). 실행 JAR 을 띄우는 통합 테스트는 자식 프로세스의 환경변수
 * QUERYHUB_ADMIN_TOKEN 으로 넘긴다.
 */
public final class TestAdminToken {

  /** 고정 테스트 토큰(44 자, b64token 문자). */
  public static final String VALUE = "test-only-admin-token-0123456789-NOT-A-SECRET";

  /** {@code key=value} 형식의 속성. */
  public static final String PROPERTY = "queryhub.admin.token=" + VALUE;

  /** Authorization 헤더 값. */
  public static final String AUTHORIZATION = "Bearer " + VALUE;

  private TestAdminToken() {}
}
