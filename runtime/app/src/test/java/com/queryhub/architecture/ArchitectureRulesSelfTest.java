package com.queryhub.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryhub.archfixture.adapter.crypto.AllowedCryptoFixture;
import com.queryhub.archfixture.adapter.export.DirectJdbcFixture;
import com.queryhub.archfixture.adapter.metadata.CrossAdapterFixture;
import com.queryhub.archfixture.adapter.metadata.DriverLeakFixture;
import com.queryhub.archfixture.adapter.web.CryptoOutsideAdapterFixture;
import com.queryhub.archfixture.adapter.web.admin.AdminFixture;
import com.queryhub.archfixture.adapter.web.admin.security.NonConstantTimeTokenVerifier;
import com.queryhub.archfixture.adapter.web.api.DataApiFixture;
import com.queryhub.archfixture.application.LeakyUseCase;
import com.queryhub.archfixture.domain.FrameworkAwareDomain;
import com.queryhub.archfixture.stray.StrayFixture;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * AC-07: 규칙이 실제로 실패할 수 있음을 증명한다.
 *
 * <p>com.queryhub.archfixture 의 고의 위반 클래스에 같은 규칙을 적용한다. 각 규칙은 실패해야 하고, 모든 위반 내용이 그 규칙을 위한 픽스처 클래스를
 * 가리켜야 하며, 다른 규칙용 픽스처는 언급하지 않아야 한다. R6 과 R8 의 픽스처는 P0-B 가 더했다(첫 JDBC 드라이버와 Spring JDBC).
 */
class ArchitectureRulesSelfTest {

  private static final String FIXTURE_ROOT = "com.queryhub.archfixture";

  private static final JavaClasses FIXTURES = new ClassFileImporter().importPackages(FIXTURE_ROOT);

  private static final ArchitectureRules RULES = new ArchitectureRules(FIXTURE_ROOT);

  private static final List<Class<?>> ALL_FIXTURES =
      List.of(
          FrameworkAwareDomain.class,
          LeakyUseCase.class,
          AdminFixture.class,
          DataApiFixture.class,
          CrossAdapterFixture.class,
          StrayFixture.class,
          DriverLeakFixture.class,
          DirectJdbcFixture.class,
          NonConstantTimeTokenVerifier.class,
          CryptoOutsideAdapterFixture.class,
          AllowedCryptoFixture.class);

  @Test
  void fixturesAreImported() {
    assertThat(ALL_FIXTURES).allSatisfy(fixture -> assertThat(FIXTURES.contain(fixture)).isTrue());
  }

  @Test
  void r1FailsWhenApplicationAccessesAnAdapter() {
    assertFailsOnlyBecauseOf(RULES.r1LayeredArchitecture(), LeakyUseCase.class, AdminFixture.class);
  }

  @Test
  void r2FailsWhenOneAdapterDependsOnAnother() {
    assertFailsOnlyBecauseOf(
        RULES.r2AdaptersDoNotDependOnEachOther(), CrossAdapterFixture.class, AdminFixture.class);
  }

  @Test
  void r3FailsWhenDomainUsesAFramework() {
    assertFailsOnlyBecauseOf(
        RULES.r3DomainDependsOnlyOnJavaAndItself(), FrameworkAwareDomain.class);
  }

  @Test
  void r4FailsWhenApplicationUsesHttpTypesOrAdapters() {
    EvaluationResult result =
        assertFailsOnlyBecauseOf(
            RULES.r4ApplicationIsFreeOfDeliveryAndInfrastructure(),
            LeakyUseCase.class,
            AdminFixture.class);
    // HTTP 타입 의존과 어댑터 의존 둘 다 잡는다.
    assertThat(result.getFailureReport().getDetails())
        .anySatisfy(detail -> assertThat(detail).contains("org.springframework.http.HttpStatus"))
        .anySatisfy(detail -> assertThat(detail).contains(AdminFixture.class.getName()));
  }

  @Test
  void r5FailsWhenAdminDependsOnDataApi() {
    assertFailsOnlyBecauseOf(
        RULES.r5AdminAndDataApiAreIndependent(), AdminFixture.class, DataApiFixture.class);
  }

  @Test
  void r6FailsWhenAClassOutsideTheProvidersUsesAJdbcDriver() {
    EvaluationResult result =
        assertFailsOnlyBecauseOf(RULES.r6OnlyProvidersUseJdbcDrivers(), DriverLeakFixture.class);
    assertThat(result.getFailureReport().getDetails())
        .anySatisfy(detail -> assertThat(detail).contains("org.sqlite.JDBC"));
  }

  @Test
  void r8FailsWhenAnAdapterWithoutPersistenceUsesSpringJdbc() {
    EvaluationResult result =
        assertFailsOnlyBecauseOf(
            RULES.r8OnlyPersistenceAdaptersAndAppUseJdbc(), DirectJdbcFixture.class);
    assertThat(result.getFailureReport().getDetails())
        .anySatisfy(
            detail ->
                assertThat(detail).contains("org.springframework.jdbc.core.simple.JdbcClient"));
  }

  @Test
  void r9FailsWhenATokenVerifierUsesStringEqualsInsteadOfMessageDigestIsEqual() {
    EvaluationResult result =
        assertFailsOnlyBecauseOf(
            RULES.r9TokenVerifierComparesInConstantTime(), NonConstantTimeTokenVerifier.class);
    String report = String.join("\n", result.getFailureReport().getDetails());
    assertThat(report).contains("java.lang.String.equals(java.lang.Object)");
    assertThat(result.getFailureReport().toString()).contains("isEqual");
  }

  @Test
  void r7FailsForAClassOutsideTheKnownLayers() {
    assertFailsOnlyBecauseOf(RULES.r7EveryClassResidesInAKnownLayer(), StrayFixture.class);
  }

  @Test
  void r10FailsWhenAClassOutsideTheCryptoAdapterUsesJavaxCrypto() {
    EvaluationResult result =
        assertFailsOnlyBecauseOf(
            RULES.r10OnlyTheCryptoAdapterUsesJavaxCrypto(), CryptoOutsideAdapterFixture.class);
    assertThat(result.getFailureReport().getDetails())
        .anySatisfy(detail -> assertThat(detail).contains("javax.crypto.Cipher"))
        .noneSatisfy(detail -> assertThat(detail).contains(AllowedCryptoFixture.class.getName()));
  }

  /**
   * 규칙이 픽스처에서 실패하고(check 가 AssertionError), 모든 위반 내용이 violator 를 가리키며, related(위반의 대상) 외의 다른 픽스처는
   * 언급하지 않는지 검사한다.
   */
  private static EvaluationResult assertFailsOnlyBecauseOf(
      ArchRule rule, Class<?> violator, Class<?>... related) {
    assertThatThrownBy(() -> rule.check(FIXTURES))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(violator.getName());

    EvaluationResult result = rule.evaluate(FIXTURES);
    assertThat(result.hasViolation()).isTrue();
    List<String> details = result.getFailureReport().getDetails();
    assertThat(details)
        .isNotEmpty()
        .allSatisfy(detail -> assertThat(detail).contains(violator.getName()));

    List<String> unrelated =
        ALL_FIXTURES.stream()
            .filter(fixture -> fixture != violator && Stream.of(related).noneMatch(fixture::equals))
            .map(Class::getName)
            .toList();
    String report = String.join("\n", details);
    assertThat(unrelated).allSatisfy(name -> assertThat(report).doesNotContain(name));
    return result;
  }
}
