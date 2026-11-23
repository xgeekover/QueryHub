package com.queryhub.archfixture.adapter.web;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;

/** R10 위반: adapter-crypto 밖(웹 어댑터)에서 javax.crypto.Cipher 를 쓴다. */
public class CryptoOutsideAdapterFixture {

  public Cipher cipher() throws GeneralSecurityException {
    return Cipher.getInstance("AES/GCM/NoPadding");
  }
}
