package com.queryhub.app.it.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 통합 테스트에서 실제 Tomcat 을 무작위 포트로 띄우는 유일한 진입점. 실제 Metadata 저장소(SQLite, 공유 임시 데이터 디렉터리)를 쓴다.
 *
 * <ul>
 *   <li>server.address=127.0.0.1 로 고정한다. 테스트가 외부 인터페이스에 포트를 여는 일이 없다.
 *   <li>desktop 프로파일. Metadata 저장소를 제외하지 않는다(운영과 같은 조립).
 *   <li>데이터 디렉터리는 {@link MetadataDatabaseIntegrationTest} 의 {@code @DynamicPropertySource} 가 넣는다.
 *       그래서 이 애너테이션을 쓰는 클래스는 그 클래스를 상속한다. 설정이 같으므로 애플리케이션 컨텍스트 하나를 공유한다.
 * </ul>
 *
 * <p>무작위 포트 설정은 이 파일에만 쓴다(AC-18 이 grep 으로 검사한다). 다른 프로파일이나 다른 데이터 디렉터리로 띄우는 테스트는
 * SpringApplicationBuilder 로 루프백의 포트 0 에 띄운다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "server.address=127.0.0.1",
      IntegrationHttp.ADMIN_TOKEN_PROPERTY,
      IntegrationHttp.MASTER_KEY_PROPERTY
    })
@ActiveProfiles("desktop")
public @interface QueryHubIntegrationTest {}
