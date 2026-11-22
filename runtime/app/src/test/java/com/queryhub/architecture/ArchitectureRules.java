package com.queryhub.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * 아키텍처 규칙 R1~R9 (P0-A FR-04, P0-B 가 R8 을, P1-03 이 R9 를 더했다).
 *
 * <p>루트 패키지를 받는다. 운영 코드에는 {@code com.queryhub} 를, 자기 검사(ArchitectureRulesSelfTest)에는 고의 위반 클래스가 있는
 * {@code com.queryhub.archfixture} 를 넣어 같은 규칙을 적용한다. 그래서 규칙이 실제로 실패할 수 있음을 증명할 수 있다.
 *
 * <p>Gradle 모듈 선언이 금지된 import 를 이미 컴파일에서 막으므로, 운영 코드에 대한 실행만으로는 규칙이 비어 있지 않다는 증거가 되지 않는다. 자기 검사가 그
 * 증거다.
 */
public final class ArchitectureRules {

  private static final String DOMAIN = "Domain";
  private static final String APPLICATION = "Application";
  private static final String ADAPTER = "Adapter";
  private static final String APP = "App";

  private final String root;

  public ArchitectureRules(String root) {
    this.root = Objects.requireNonNull(root, "root");
  }

  /**
   * R1 계층: App 은 아무도 접근하지 않고, Adapter 는 App 만, Application 은 Adapter 와 App 만, Domain 은 세 계층만 접근한다.
   */
  public ArchRule r1LayeredArchitecture() {
    return layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        // 아직 클래스가 없는 계층(예: 자기 검사의 App)이 있어도 된다.
        .withOptionalLayers(true)
        .layer(DOMAIN)
        .definedBy(domain())
        .layer(APPLICATION)
        .definedBy(application())
        .layer(ADAPTER)
        .definedBy(adapter())
        .layer(APP)
        .definedBy(app())
        .whereLayer(APP)
        .mayNotBeAccessedByAnyLayer()
        .whereLayer(ADAPTER)
        .mayOnlyBeAccessedByLayers(APP)
        .whereLayer(APPLICATION)
        .mayOnlyBeAccessedByLayers(ADAPTER, APP)
        .whereLayer(DOMAIN)
        .mayOnlyBeAccessedByLayers(APPLICATION, ADAPTER, APP)
        .because("R1: 의존은 바깥(app, adapter)에서 안쪽(application, domain)으로만 향한다");
  }

  /** R2 어댑터끼리는 서로 의존하지 않는다 (root.adapter.(*).. 단위). */
  public ArchRule r2AdaptersDoNotDependOnEachOther() {
    return slices()
        .matching(root + ".adapter.(*)..")
        .should()
        .notDependOnEachOther()
        .because("R2: 어댑터는 서로를 모르고 application 의 포트로만 연결된다");
  }

  /** R3 domain 은 java.. 와 domain 자신에만 의존한다. */
  public ArchRule r3DomainDependsOnlyOnJavaAndItself() {
    return classes()
        .that()
        .resideInAPackage(domain())
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("java..", domain())
        .because("R3: domain 은 어떤 프레임워크에도 의존하지 않는다");
  }

  /** R4 application 은 웹, HTTP, JDBC, Boot, 서블릿, SQL, 어댑터, app 에 의존하지 않는다. */
  public ArchRule r4ApplicationIsFreeOfDeliveryAndInfrastructure() {
    return noClasses()
        .that()
        .resideInAPackage(application())
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.web..",
            "org.springframework.http..",
            "org.springframework.jdbc..",
            "org.springframework.boot..",
            "jakarta.servlet..",
            "java.sql..",
            "javax.sql..",
            adapter(),
            app())
        .because("R4: 유스케이스는 전달 방식과 저장 기술을 모른다");
  }

  /** R5 관리 API(admin)와 데이터 API(api)는 서로 의존하지 않는다. */
  public ArchRule r5AdminAndDataApiAreIndependent() {
    String admin = root + ".adapter.web.admin..";
    String api = root + ".adapter.web.api..";
    return CompositeArchRule.of(
            noClasses()
                .that()
                .resideInAPackage(admin)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(api))
        .and(
            noClasses()
                .that()
                .resideInAPackage(api)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(admin)
                // 데이터 API 패키지는 Phase 3/4 에서 생긴다. 그 전에는 검사할 클래스가 없다.
                .allowEmptyShould(true))
        .because("R5: 관리 API 와 데이터 API 는 보안 경계가 달라 서로를 참조하지 않는다");
  }

  /** R6 JDBC 드라이버 패키지는 root.adapter.dbprovider.provider.. 만 쓴다. */
  public ArchRule r6OnlyProvidersUseJdbcDrivers() {
    return noClasses()
        .that()
        .resideOutsideOfPackage(root + ".adapter.dbprovider.provider..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.microsoft.sqlserver..", "oracle.jdbc..", "org.mariadb.jdbc..", "org.postgresql..")
        .because("R6: DB 별 차이는 provider 모듈 안에 가둔다");
  }

  /**
   * R8 JDBC, Flyway, javax.sql, 커넥션 풀(Hikari)은 root.adapter.metadata.., root.adapter.dbprovider..,
   * root.app.. 만 쓴다. java.sql 은 허용한다.
   *
   * <p>Gradle 모듈 규칙은 어떤 adapter-* 든 spring-jdbc 를 선언하게 둔다. 이 규칙이 저장 기술을 메타데이터 저장소와 DB Provider, 조립
   * 지점에 가두는 두 번째 방어선이다.
   */
  public ArchRule r8OnlyPersistenceAdaptersAndAppUseJdbc() {
    return noClasses()
        .that()
        .resideInAPackage(root + "..")
        .and()
        .resideOutsideOfPackages(
            root + ".adapter.metadata..", root + ".adapter.dbprovider..", app())
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.jdbc..", "org.flywaydb..", "javax.sql..", "com.zaxxer.hikari..")
        .because("R8: 저장 기술(JDBC, Flyway, 커넥션 풀)은 메타데이터 저장소, DB Provider, app 조립에만 둔다");
  }

  /**
   * R9 관리자 토큰 비교기(root.adapter.web.admin.security.. 의 이름이 TokenVerifier 로 끝나는 클래스)는 {@code
   * MessageDigest.isEqual(byte[], byte[])} 를 부르고, 내용에 따라 일찍 끝나는 비교(String.equals, equalsIgnoreCase,
   * contentEquals, compareTo, compareToIgnoreCase, regionMatches, Objects.equals,
   * Arrays.equals/compare/mismatch)는 부르지 않는다.
   *
   * <p>상수 시간은 단위 테스트로 잴 수 없으므로 구조로 증명한다. 대상 클래스가 하나도 없으면 ArchUnit 이 실패한다(빈 should 금지).
   */
  public ArchRule r9TokenVerifierComparesInConstantTime() {
    DescribedPredicate<JavaClass> verifier =
        DescribedPredicate.describe(
            "admin token verifiers",
            javaClass ->
                javaClass.getPackageName().startsWith(root + ".adapter.web.admin.security")
                    && javaClass.getSimpleName().endsWith("TokenVerifier"));
    DescribedPredicate<JavaMethodCall> earlyExitComparison =
        DescribedPredicate.describe(
            "an early-exit comparison (String, Objects or Arrays)",
            call -> {
              String owner = call.getTargetOwner().getName();
              String name = call.getName();
              return (owner.equals(String.class.getName()) && STRING_COMPARISONS.contains(name))
                  || (owner.equals(Objects.class.getName()) && name.equals("equals"))
                  || (owner.equals(Arrays.class.getName()) && ARRAY_COMPARISONS.contains(name));
            });
    ArchRule callsIsEqual =
        classes()
            .that(verifier)
            .should()
            .callMethod(MessageDigest.class, "isEqual", byte[].class, byte[].class);
    ArchRule noEarlyExit = noClasses().that(verifier).should().callMethodWhere(earlyExitComparison);
    return CompositeArchRule.of(callsIsEqual)
        .and(noEarlyExit)
        .because("R9: 관리자 토큰은 상수 시간(SHA-256 다이제스트와 MessageDigest.isEqual)으로만 비교한다");
  }

  private static final Set<String> STRING_COMPARISONS =
      Set.of(
          "equals",
          "equalsIgnoreCase",
          "contentEquals",
          "compareTo",
          "compareToIgnoreCase",
          "regionMatches");

  private static final Set<String> ARRAY_COMPARISONS = Set.of("equals", "compare", "mismatch");

  /** R7 루트 아래의 모든 클래스는 domain, application, adapter, app 중 한 곳에 있다. */
  public ArchRule r7EveryClassResidesInAKnownLayer() {
    return classes()
        .that()
        .resideInAPackage(root + "..")
        .should()
        .resideInAnyPackage(domain(), application(), adapter(), app())
        .because("R7: 계층 밖에 놓인 클래스는 R1~R6 의 검사를 피해 간다");
  }

  private String domain() {
    return root + ".domain..";
  }

  private String application() {
    return root + ".application..";
  }

  private String adapter() {
    return root + ".adapter..";
  }

  private String app() {
    return root + ".app..";
  }
}
