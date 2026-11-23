package com.queryhub.archfixture.adapter.crypto;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;

/** R10 허용: 암호 어댑터 패키지 안에서는 javax.crypto 를 써도 된다(자기 검사가 이 클래스를 위반으로 보고하지 않아야 한다). */
public class AllowedCryptoFixture {

  public Cipher cipher() throws GeneralSecurityException {
    return Cipher.getInstance("AES/GCM/NoPadding");
  }
}
