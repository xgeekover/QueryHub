package com.queryhub.app.config.secret;

import com.queryhub.adapter.crypto.AesGcmSecretCipher;
import com.queryhub.adapter.crypto.MasterKey;
import com.queryhub.application.secret.SecretCipher;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

/**
 * 비밀 값 암호기 조립 (P1-04, ADR-023, ADR-024). desktop 과 server 두 프로파일 모두 마스터 키가 있어야 한다.
 *
 * <p>빈은 지연 초기화(spring.main.lazy-initialization)를 켜도 바로 만든다({@code @Lazy(false)}). 그래서 키가 없거나 틀리면 웹
 * 서버가 포트를 열기 전에 기동이 멈춘다. 로그에는 키 ID 만 남기고 키는 남기지 않는다.
 *
 * <p>이전 키(복호화 전용) 입력은 아직 없다. 접속 정보 저장 작업에서 다시 암호화할 행이 생길 때 더한다(BACKLOG P104-n).
 */
@Configuration(proxyBeanMethods = false)
public class SecretCipherConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SecretCipherConfiguration.class);

  @Bean
  @Lazy(false)
  public SecretCipher secretCipher(Environment environment) {
    MasterKey key = MasterKeySource.load(environment);
    log.info("마스터 키를 읽었다 (키 ID {}).", key.kid());
    return new AesGcmSecretCipher(key, List.of());
  }
}
