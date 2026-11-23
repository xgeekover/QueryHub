package com.queryhub.archfixture.adapter.dbprovider.provider.alpha;

import com.queryhub.archfixture.adapter.dbprovider.provider.beta.BetaProviderFixture;

/** 아키텍처 자기 검사용 고의 위반(R11 가): 한 Provider(alpha)가 다른 Provider(beta)에 의존한다. 운영 코드가 아니다. */
public final class AlphaProviderFixture {

  public String borrowFromBeta() {
    return new BetaProviderFixture().name();
  }
}
