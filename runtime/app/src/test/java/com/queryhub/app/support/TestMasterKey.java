package com.queryhub.app.support;

/**
 * 테스트 전용 마스터 키 (P1-04). NOT A SECRET: 누구나 볼 수 있는 고정 픽스처이고 운영에서 쓰지 않는다.
 *
 * <p>{@link TestAdminToken} 과 같은 방식으로 넘긴다: {@code @SpringBootTest(properties=...)} 의 인라인
 * 속성(QueryHubWebTest) 또는 {@code SpringApplicationBuilder.properties(...)} 의 기본 속성. 명령행 인자나 -D 로 넘기면
 * MasterKeySource 가 기동을 거부한다(MasterKeyStartupTest 가 검사한다). 실행 JAR 을 띄우는 통합 테스트는 자식 프로세스의 환경변수
 * QUERYHUB_MASTER_KEY 로 넘긴다(통합 테스트의 IntegrationHttp.MASTER_KEY).
 */
public final class TestMasterKey {

  /** 고정 테스트 키(64 자리 16진수). 모든 바이트가 같지 않고 테스트 관리자 토큰과 다르다. NOT A SECRET. */
  public static final String VALUE =
      "0123456789abcdeffedcba98765432100123456789abcdeffedcba9876543210";

  /** {@code key=value} 형식의 속성. */
  public static final String PROPERTY = "queryhub.master-key=" + VALUE;

  private TestMasterKey() {}
}
