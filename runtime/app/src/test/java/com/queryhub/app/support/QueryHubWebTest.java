package com.queryhub.app.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 Tomcat 을 무작위 포트로 띄우는 테스트의 유일한 진입점.
 *
 * <ul>
 *   <li>server.address=127.0.0.1 로 고정한다. 테스트가 외부 인터페이스에 포트를 여는 일이 없다.
 *   <li>desktop 프로파일과 오류 프로브({@link ErrorProbeTestConfiguration})를 함께 쓴다.
 *   <li>관리자 토큰은 고정 테스트 값({@link TestAdminToken})을 인라인 속성으로 넘긴다(프로세스 목록에 보이지 않는 소스).
 *   <li>이 애너테이션을 쓰는 테스트 클래스는 설정이 같으므로 애플리케이션 컨텍스트 하나를 공유한다.
 * </ul>
 *
 * <p>무작위 포트 테스트는 반드시 이 애너테이션을 쓴다. 다른 테스트 파일에 무작위 포트 설정을 직접 쓰지 않는다 (AC-12 가 grep 으로 검사한다).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.address=127.0.0.1", TestAdminToken.PROPERTY})
@ActiveProfiles("desktop")
@Import(ErrorProbeTestConfiguration.class)
@WithoutMetadataDatabase
public @interface QueryHubWebTest {}
