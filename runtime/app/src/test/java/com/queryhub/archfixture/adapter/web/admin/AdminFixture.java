package com.queryhub.archfixture.adapter.web.admin;

import com.queryhub.archfixture.adapter.web.api.DataApiFixture;

/** 아키텍처 자기 검사용 고의 위반(R5): 관리 API 가 데이터 API 에 의존한다. 운영 코드가 아니며 Spring 스테레오타입이 없다. */
public final class AdminFixture {

  public String describe() {
    return new DataApiFixture().name();
  }
}
