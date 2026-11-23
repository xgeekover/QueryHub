package com.queryhub.archfixture.adapter.dbprovider;

import com.queryhub.archfixture.adapter.dbprovider.provider.beta.BetaProviderFixture;

/** 아키텍처 자기 검사용 고의 위반(R11 나): DB Provider 공통 코드가 SPI 대신 특정 Provider(beta)를 직접 쓴다. 운영 코드가 아니다. */
public final class CommonToProviderFixture {

  public String provider() {
    return new BetaProviderFixture().name();
  }
}
