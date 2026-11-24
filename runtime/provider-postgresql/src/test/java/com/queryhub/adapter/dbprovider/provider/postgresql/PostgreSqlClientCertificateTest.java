package com.queryhub.adapter.dbprovider.provider.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.DatabaseType;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.X509KeyManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * P1-08a A-AC2 (R1): pgjdbc 42.7.14 의 {@code org.postgresql.ssl.LibPQFactory} 는 고정 속성 {@code
 * sslcert=""}, {@code sslkey=""} 가 있으면 실행 계정 홈의 클라이언트 인증서를 키 관리자에 올리지 않는다.
 *
 * <p>{@code user.home} 을 @TempDir 로 바꾸고 JDK keytool 로 만든 인증서를 {@code .postgresql/postgresql.crt} 에
 * 둔다. 그 다음 Provider 의 고정 속성 + {@code sslmode=require} 로 LibPQFactory 를 만들어 키 관리자(패키지 비공개 필드 km)를
 * 본다. 대조 시험은 두 속성을 뺀 같은 속성으로 홈 인증서가 실제로 읽힌다는 것을 보인다 (이 대조가 없으면 "별칭 없음" 은 아무것도 증명하지 못한다).
 *
 * <p>user.home 은 JVM 전역이다. 바꾼 값은 LibPQFactory 생성자 동안만 두고 finally 에서 되돌린다. Gradle 은 한 모듈의 시험 클래스를 한
 * JVM 에서 차례로 돌리고(maxParallelForks 1, JUnit 병렬 실행 없음) {@link Isolated} 가 병렬 실행이 켜져도 이 클래스를 혼자 돌린다.
 * Windows 의 pgjdbc 는 user.home 이 아닌 %APPDATA%\postgresql 을 보므로 건너뛴다. 실제 사용자의 ~/.postgresql 은 읽지도
 * 쓰지도 않는다.
 */
@Isolated
@DisabledOnOs(OS.WINDOWS)
class PostgreSqlClientCertificateTest {

  private static final String LIBPQ_FACTORY = "org.postgresql.ssl.LibPQFactory";

  /** LazyKeyManager 가 홈 인증서에 붙이는 별칭. */
  private static final String PGJDBC_ALIAS = "user";

  private static final String[] RSA = {"RSA"};

  private final PostgreSqlJdbcProvider provider = new PostgreSqlJdbcProvider();

  @Test
  void withTheFixedPropertiesTheHomeClientCertificateIsNeverOffered(@TempDir Path work)
      throws Exception {
    Path home = Files.createDirectories(work.resolve("home"));
    homeCertificate(work, home);

    Properties properties = driverProperties(true);
    X509KeyManager keyManager = keyManager(home, properties);

    assertThat(keyManager.chooseClientAlias(RSA, null, null)).as("클라이언트 인증서 별칭").isNull();
    assertThat(keyManager.getCertificateChain(PGJDBC_ALIAS)).as("클라이언트 인증서").isNull();
    // 드라이버가 URL 과 합친 뒤에도 빈 값이 남는다 (빈 값이 null 로 바뀌면 pgjdbc 는 홈 파일로 돌아간다).
    assertThat(properties.getProperty("sslcert")).isEmpty();
    assertThat(properties.getProperty("sslkey")).isEmpty();
  }

  @Test
  void withoutTheTwoPropertiesTheHomeClientCertificateIsRead(@TempDir Path work) throws Exception {
    Path home = Files.createDirectories(work.resolve("home"));
    X509Certificate generated = homeCertificate(work, home);

    Properties properties = driverProperties(false);
    assertThat(properties.getProperty("sslcert")).isNull();
    assertThat(properties.getProperty("sslkey")).isNull();

    X509KeyManager keyManager = keyManager(home, properties);

    assertThat(keyManager.chooseClientAlias(RSA, null, null)).isEqualTo(PGJDBC_ALIAS);
    assertThat(keyManager.getCertificateChain(PGJDBC_ALIAS)).containsExactly(generated);
  }

  /**
   * Provider 의 고정 속성 + sslmode=require 를 pgjdbc 가 URL 과 합치는 방식({@link
   * org.postgresql.Driver#parseURL}) 그대로 합친다. withClientFiles=false 이면 sslcert 와 sslkey 를 뺀다
   * (P1-08a 전의 고정 속성).
   */
  private Properties driverProperties(boolean withClientFiles) {
    ConnectionSettings settings =
        new ConnectionSettings(
            DatabaseType.POSTGRESQL,
            "db.example.org",
            5432,
            "app_db",
            "reader",
            Duration.ofSeconds(5),
            1,
            Map.of("sslmode", "require"));
    Properties given = new Properties();
    settings.options().forEach(given::setProperty);
    provider
        .fixedProperties(settings)
        .forEach(
            (name, value) -> {
              if (withClientFiles || !(name.equals("sslcert") || name.equals("sslkey"))) {
                given.setProperty(name, value);
              }
            });
    Properties merged = org.postgresql.Driver.parseURL(provider.jdbcUrl(settings), given);
    assertThat(merged).as("pgjdbc 가 URL 을 받아들인다").isNotNull();
    assertThat(merged.getProperty("sslmode")).isEqualTo("require");
    return merged;
  }

  /**
   * user.home 을 home 으로 바꾼 채 LibPQFactory 를 만들고 되돌린다. 키 관리자는 만들 때 정한 경로를 나중에 읽는다.
   *
   * <p>LibPQFactory 는 이름으로 불러 만든다: 그 클래스 파일의 checker-qual 주석이 시험 컴파일 클래스패스에 없어서 직접 참조하면
   * 경고(-Werror)가 난다.
   */
  private static X509KeyManager keyManager(Path home, Properties properties) throws Exception {
    Class<?> factoryClass = Class.forName(LIBPQ_FACTORY);
    String saved = System.getProperty("user.home");
    Object factory;
    try {
      System.setProperty("user.home", home.toString());
      factory = factoryClass.getConstructor(Properties.class).newInstance(properties);
    } finally {
      if (saved == null) {
        System.clearProperty("user.home");
      } else {
        System.setProperty("user.home", saved);
      }
    }
    assertThat(System.getProperty("user.home")).isEqualTo(saved);
    Field km = factoryClass.getDeclaredField("km");
    km.setAccessible(true);
    return (X509KeyManager) km.get(factory);
  }

  /**
   * JDK keytool 로 RSA 키 쌍을 만들고 인증서만 home/.postgresql/postgresql.crt 에 둔다 (키 파일은 두지 않는다). 키 저장소
   * 비밀번호는 실행마다 새로 만드는 버리는 값이고 명령줄이 아닌 환경변수로 넘긴다.
   */
  private static X509Certificate homeCertificate(Path work, Path home)
      throws IOException, InterruptedException, java.security.GeneralSecurityException {
    Path keystore = work.resolve("client.p12");
    Path certificate =
        Files.createDirectories(home.resolve(".postgresql")).resolve("postgresql.crt");
    String storePassword = "kt-" + UUID.randomUUID();
    keytool(
        storePassword,
        "-genkeypair",
        "-alias",
        "qh-client",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-dname",
        "CN=queryhub-p108a-test",
        "-validity",
        "2",
        "-storetype",
        "PKCS12",
        "-keystore",
        keystore.toString(),
        "-storepass:env",
        "QH_TEST_STOREPASS");
    keytool(
        storePassword,
        "-exportcert",
        "-rfc",
        "-alias",
        "qh-client",
        "-keystore",
        keystore.toString(),
        "-storepass:env",
        "QH_TEST_STOREPASS",
        "-file",
        certificate.toString());
    try (InputStream in = Files.newInputStream(certificate)) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
    }
  }

  private static void keytool(String storePassword, String... arguments)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
    command.addAll(List.of(arguments));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().put("QH_TEST_STOREPASS", storePassword);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("keytool 이 끝났다").isTrue();
    assertThat(process.exitValue()).as("keytool %s: %s", arguments[0], output).isZero();
  }
}
