package com.queryhub.archfixture.application;

import com.queryhub.archfixture.adapter.web.admin.AdminFixture;
import org.springframework.http.HttpStatus;

/**
 * 아키텍처 자기 검사용 고의 위반: application 이 HTTP 타입(R4)과 어댑터(R1, R4)에 의존한다. 운영 코드가 아니며 Spring 스테레오타입이 없다.
 */
public final class LeakyUseCase {

  public HttpStatus status() {
    return HttpStatus.OK;
  }

  public String describe(AdminFixture admin) {
    return admin.describe();
  }
}
