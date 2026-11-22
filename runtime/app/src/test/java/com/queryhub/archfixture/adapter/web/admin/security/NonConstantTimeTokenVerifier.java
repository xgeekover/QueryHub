package com.queryhub.archfixture.adapter.web.admin.security;

/** R9 고의 위반: 토큰을 String.equals 로 비교하고 MessageDigest.isEqual 을 쓰지 않는다(공통 접두사 길이에 따라 시간이 달라진다). */
public class NonConstantTimeTokenVerifier {

  private final String token;

  public NonConstantTimeTokenVerifier(String token) {
    this.token = token;
  }

  public boolean matches(String presented) {
    return token.equals(presented);
  }
}
