package com.queryhub.archfixture.adapter.metadata;

import com.queryhub.archfixture.adapter.web.admin.AdminFixture;

/** 아키텍처 자기 검사용 고의 위반(R2): 한 어댑터(metadata)가 다른 어댑터(web)에 의존한다. 운영 코드가 아니며 Spring 스테레오타입이 없다. */
public final class CrossAdapterFixture {

  public String read(AdminFixture admin) {
    return admin.describe();
  }
}
