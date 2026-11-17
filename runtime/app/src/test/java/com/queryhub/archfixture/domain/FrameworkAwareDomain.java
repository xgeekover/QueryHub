package com.queryhub.archfixture.domain;

import org.springframework.util.StringUtils;

/** 아키텍처 자기 검사용 고의 위반(R3): domain 이 Spring 에 의존한다. 운영 코드가 아니며 Spring 스테레오타입이 없다. */
public final class FrameworkAwareDomain {

  private FrameworkAwareDomain() {}

  public static boolean hasName(String name) {
    return StringUtils.hasText(name);
  }
}
