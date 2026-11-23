package com.queryhub.archfixture.adapter.dbprovider.provider.beta;

/**
 * 아키텍처 자기 검사용 Provider(R11). 스스로는 규칙을 어기지 않는다: AlphaProviderFixture 와 CommonToProviderFixture 가 이
 * 클래스에 의존해 규칙을 어긴다. 운영 코드가 아니다.
 */
public final class BetaProviderFixture {

  public String name() {
    return "beta";
  }
}
