package com.queryhub.adapter.web.admin.security;

import java.util.Objects;

/**
 * 관리 체인의 설정. app 모듈이 기동 중에(웹 서버가 포트를 열기 전에) 검사를 마치고 만든다.
 *
 * @param tokenVerifier 설정된 토큰의 상수 시간 비교기
 * @param allowRemote false 이면 소켓 원격 주소가 루프백인 요청만 받는다
 * @param hostCheck true 이면 {@link HostHeaderPolicy} 로 Host 를 검사한다 (desktop 은 늘 true)
 * @param hosts Host 허용 목록
 */
public record AdminAccessPolicy(
    AdminTokenVerifier tokenVerifier,
    boolean allowRemote,
    boolean hostCheck,
    HostHeaderPolicy hosts) {

  public AdminAccessPolicy {
    Objects.requireNonNull(tokenVerifier, "tokenVerifier");
    Objects.requireNonNull(hosts, "hosts");
  }
}
