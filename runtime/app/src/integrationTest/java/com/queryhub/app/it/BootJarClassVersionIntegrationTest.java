package com.queryhub.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * P1-05 (ADR-025): 실행 JAR(runtime/app/build/libs/queryhub-runtime.jar) 안의 모든 클래스가 Java 17 에서 읽힌다.
 *
 * <ul>
 *   <li>JAR 바로 아래(Boot 로더), BOOT-INF/classes, BOOT-INF/lib/*.jar 안의 모든 .class 를 읽는다. 주 버전이 61(Java
 *       17)을 넘으면 실패하고, 어느 JAR 의 어느 항목인지 모두 적는다.
 *   <li>예외는 멀티 릴리스 JAR 의 META-INF/versions/&lt;n&gt;/ (n &ge; 18) 항목뿐이다. Java 17 은 그 항목을 읽지 않는다.
 *   <li>com/queryhub 클래스(우리 모듈)는 정확히 61 이어야 한다. 나중에 의존성을 올려 Java 17 보다 높은 버전이 필요한 클래스가 들어오면 클래스가
 *       로드되기 전에 여기서 드러난다.
 * </ul>
 *
 * <p>Docker 가 필요 없다. 통합 테스트 게이트에서 돈다(실행 JAR 은 integrationTest 태스크가 bootJar 에 기대어 만든다).
 */
class BootJarClassVersionIntegrationTest {

  private static final String BOOT_JAR_PROPERTY = "queryhub.it.boot-jar";

  /** Java 17 클래스 파일의 주 버전 (JVMS 4.1). */
  private static final int CLASS_MAJOR_JAVA_17 = 61;

  private static final int CLASS_MAGIC = 0xCAFEBABE;

  private static final Pattern VERSIONED_ENTRY = Pattern.compile("^META-INF/versions/(\\d+)/");

  private static final String OUR_PACKAGE = "com/queryhub/";

  /** 실행 JAR 에 com/queryhub 클래스가 있어야 하는 모듈 JAR (BOOT-INF/lib/&lt;모듈&gt;-&lt;버전&gt;.jar). */
  private static final List<String> MODULE_JARS =
      List.of("domain", "application", "adapter-web", "adapter-metadata", "adapter-crypto");

  /** 검사 결과. 위반은 "JAR!항목 (major n)" 꼴로 모은다. */
  private static final class Scan {
    final List<String> tooNew = new ArrayList<>();
    final List<String> oursNot61 = new ArrayList<>();
    final Set<String> jarsWithOurClasses = new TreeSet<>();
    final List<String> skippedVersioned = new ArrayList<>();
    int classes;
    int ourClasses;
    int nestedJars;
  }

  @Test
  void everyClassInTheBootJarRunsOnJava17() throws IOException {
    String path = System.getProperty(BOOT_JAR_PROPERTY);
    assertThat(path).as("Gradle 이 %s 를 넘겨야 한다", BOOT_JAR_PROPERTY).isNotBlank();
    Path bootJar = Path.of(path);
    assertThat(bootJar).as("bootJar 산출물").isRegularFile();

    Scan scan = new Scan();
    try (InputStream in = Files.newInputStream(bootJar)) {
      scanJar(bootJar.getFileName().toString(), in, true, scan);
    }

    assertThat(scan.tooNew)
        .as("주 버전이 %d 을 넘는 클래스 (META-INF/versions/18+ 밖)", CLASS_MAJOR_JAVA_17)
        .isEmpty();
    assertThat(scan.oursNot61).as("com/queryhub 클래스는 정확히 %d 이어야 한다", CLASS_MAJOR_JAVA_17).isEmpty();
    // 검사가 실제로 무언가를 읽었는지 (빈 JAR 이나 경로 실수로 통과하지 않는다).
    assertThat(scan.nestedJars).as("BOOT-INF/lib 안의 JAR 수").isPositive();
    assertThat(scan.classes).as("읽은 클래스 수").isGreaterThan(scan.ourClasses);
    assertThat(scan.jarsWithOurClasses).as("com/queryhub 클래스가 있는 곳").contains("BOOT-INF/classes");
    for (String module : MODULE_JARS) {
      assertThat(scan.jarsWithOurClasses)
          .as("%s 모듈 JAR 에 com/queryhub 클래스가 있어야 한다", module)
          .anyMatch(jar -> jar.startsWith("BOOT-INF/lib/" + module + "-"));
    }
  }

  @Test
  void scannerReportsANewerClassAndSkipsOnlyVersionedEntriesFrom18() throws IOException {
    // 음성 검사: 검사기가 높은 버전을 놓치거나 너무 많이 건너뛰면 위 테스트가 의미가 없다.
    byte[] nested =
        zip(
            new Object[] {"META-INF/versions/21/org/example/A.class", classHeader(65)},
            new Object[] {"META-INF/versions/11/org/example/B.class", classHeader(62)},
            new Object[] {"org/example/C.class", classHeader(65)},
            new Object[] {"org/example/D.class", classHeader(61)});
    byte[] outer =
        zip(
            new Object[] {"BOOT-INF/classes/com/queryhub/app/E.class", classHeader(65)},
            new Object[] {"BOOT-INF/lib/example-1.0.jar", nested});

    Scan scan = new Scan();
    scanJar("fixture.jar", new ByteArrayInputStream(outer), true, scan);

    assertThat(scan.skippedVersioned)
        .containsExactly("BOOT-INF/lib/example-1.0.jar!META-INF/versions/21/org/example/A.class");
    assertThat(scan.tooNew)
        .containsExactly(
            "fixture.jar!BOOT-INF/classes/com/queryhub/app/E.class (major 65)",
            "BOOT-INF/lib/example-1.0.jar!META-INF/versions/11/org/example/B.class (major 62)",
            "BOOT-INF/lib/example-1.0.jar!org/example/C.class (major 65)");
    assertThat(scan.oursNot61)
        .containsExactly("fixture.jar!BOOT-INF/classes/com/queryhub/app/E.class (major 65)");
    assertThat(scan.nestedJars).isEqualTo(1);
  }

  /**
   * JAR 하나를 읽는다. outer 이면 실행 JAR 자체이고 BOOT-INF/lib/*.jar 를 안으로 들어가 읽는다. 중첩 JAR 이름은 실행 JAR 안의 경로로
   * 적는다.
   */
  private static void scanJar(String jarName, InputStream in, boolean outer, Scan scan)
      throws IOException {
    ZipInputStream zip = new ZipInputStream(in);
    ZipEntry entry;
    while ((entry = zip.getNextEntry()) != null) {
      String name = entry.getName();
      if (entry.isDirectory()) {
        continue;
      }
      if (outer && name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")) {
        scan.nestedJars++;
        // 중첩 JAR 은 메모리로 읽어 같은 방법으로 검사한다 (가장 큰 라이브러리 JAR 도 수 MB 다).
        scanJar(name, new ByteArrayInputStream(zip.readAllBytes()), false, scan);
        continue;
      }
      if (!name.endsWith(".class")) {
        continue;
      }
      String where = jarName + "!" + name;
      Matcher versioned = VERSIONED_ENTRY.matcher(name);
      if (versioned.find() && Integer.parseInt(versioned.group(1)) >= 18) {
        scan.skippedVersioned.add(where);
        continue;
      }
      int major = majorVersionOf(zip.readNBytes(8));
      scan.classes++;
      String classPath = name.startsWith("BOOT-INF/classes/") ? name.substring(17) : name;
      boolean ours = classPath.startsWith(OUR_PACKAGE);
      if (major < 0 || major > CLASS_MAJOR_JAVA_17) {
        scan.tooNew.add(where + " (major " + major + ")");
      }
      if (ours) {
        scan.ourClasses++;
        scan.jarsWithOurClasses.add(outer ? "BOOT-INF/classes" : jarName);
        if (major != CLASS_MAJOR_JAVA_17) {
          scan.oursNot61.add(where + " (major " + major + ")");
        }
      }
    }
  }

  /** 클래스 파일 머리(magic, minor, major)의 주 버전. 클래스 파일이 아니면 -1. */
  private static int majorVersionOf(byte[] header) {
    if (header.length < 8) {
      return -1;
    }
    int magic =
        ((header[0] & 0xFF) << 24)
            | ((header[1] & 0xFF) << 16)
            | ((header[2] & 0xFF) << 8)
            | (header[3] & 0xFF);
    if (magic != CLASS_MAGIC) {
      return -1;
    }
    return ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
  }

  private static byte[] classHeader(int major) {
    return new byte[] {
      (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, (byte) (major >> 8), (byte) major
    };
  }

  private static byte[] zip(Object[]... entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(bytes)) {
      for (Object[] entry : entries) {
        out.putNextEntry(new ZipEntry((String) entry[0]));
        out.write((byte[]) entry[1]);
        out.closeEntry();
      }
    }
    return bytes.toByteArray();
  }
}
