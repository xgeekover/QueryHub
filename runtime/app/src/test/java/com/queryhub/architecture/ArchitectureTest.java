package com.queryhub.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.adapter.metadata.setting.JdbcAppSettingRepository;
import com.queryhub.adapter.web.common.error.GlobalExceptionHandler;
import com.queryhub.app.QueryHubApplication;
import com.queryhub.application.runtime.RuntimeInfoService;
import com.queryhub.application.setting.AppSettingRepository;
import com.queryhub.domain.error.ErrorCode;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * AC-07: 운영 클래스(com.queryhub, 테스트 제외)에 R1~R9 를 적용한다.
 *
 * <p>app 의 테스트 클래스패스에는 모든 모듈이 있으므로, 나중에 추가되는 모듈의 클래스도 별도 설정 없이 검사된다.
 */
class ArchitectureTest {

  private static final String ROOT = "com.queryhub";

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  private static final ArchitectureRules RULES = new ArchitectureRules(ROOT);

  @Test
  void importContainsEveryModuleButNoTestClasses() {
    // 규칙이 빈 집합에 적용되어 통과하는 일을 막는다.
    assertThat(PRODUCTION.contain(ErrorCode.class)).as("domain").isTrue();
    assertThat(PRODUCTION.contain(RuntimeInfoService.class)).as("application").isTrue();
    assertThat(PRODUCTION.contain(AppSettingRepository.class)).as("application 포트").isTrue();
    assertThat(PRODUCTION.contain(GlobalExceptionHandler.class)).as("adapter-web").isTrue();
    assertThat(PRODUCTION.contain(JdbcAppSettingRepository.class)).as("adapter-metadata").isTrue();
    assertThat(PRODUCTION.contain(QueryHubApplication.class)).as("app").isTrue();
    assertThat(PRODUCTION.stream().map(JavaClass::getName))
        .noneMatch(name -> name.startsWith("com.queryhub.archfixture."))
        .noneMatch(name -> name.startsWith("com.queryhub.architecture."))
        .noneMatch(name -> name.endsWith("Test"));
  }

  @Test
  void r1LayeredArchitecture() {
    RULES.r1LayeredArchitecture().check(PRODUCTION);
  }

  @Test
  void r2AdaptersDoNotDependOnEachOther() {
    RULES.r2AdaptersDoNotDependOnEachOther().check(PRODUCTION);
  }

  @Test
  void r3DomainDependsOnlyOnJavaAndItself() {
    RULES.r3DomainDependsOnlyOnJavaAndItself().check(PRODUCTION);
  }

  @Test
  void r4ApplicationIsFreeOfDeliveryAndInfrastructure() {
    RULES.r4ApplicationIsFreeOfDeliveryAndInfrastructure().check(PRODUCTION);
  }

  @Test
  void r5AdminAndDataApiAreIndependent() {
    RULES.r5AdminAndDataApiAreIndependent().check(PRODUCTION);
  }

  @Test
  void r6OnlyProvidersUseJdbcDrivers() {
    RULES.r6OnlyProvidersUseJdbcDrivers().check(PRODUCTION);
  }

  @Test
  void r7EveryClassResidesInAKnownLayer() {
    RULES.r7EveryClassResidesInAKnownLayer().check(PRODUCTION);
  }

  @Test
  void r8OnlyPersistenceAdaptersAndAppUseJdbc() {
    RULES.r8OnlyPersistenceAdaptersAndAppUseJdbc().check(PRODUCTION);
  }

  @Test
  void r9TokenVerifierComparesInConstantTime() {
    RULES.r9TokenVerifierComparesInConstantTime().check(PRODUCTION);
    // 규칙이 실제 비교기를 대상으로 삼는다(이름이 바뀌어 대상이 비면 ArchUnit 이 실패하지만, 대상을 직접 확인해 둔다).
    assertThat(PRODUCTION.contain("com.queryhub.adapter.web.admin.security.AdminTokenVerifier"))
        .isTrue();
  }
}
