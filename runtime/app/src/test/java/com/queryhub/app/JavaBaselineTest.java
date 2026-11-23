package com.queryhub.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.adapter.crypto.AesGcmSecretCipher;
import com.queryhub.adapter.metadata.setting.JdbcAppSettingRepository;
import com.queryhub.adapter.web.admin.security.AdminTokenFilter;
import com.queryhub.application.runtime.GetRuntimeInfoUseCase;
import com.queryhub.domain.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P1-05 (ADR-025): Java 기준선은 17 이다. 단위 테스트 JVM 이 Java 17 이고, 모든 모듈의 main 클래스가 Java 17 클래스 파일(주 버전
 * 61)이다.
 *
 * <p>기대값을 일부러 17/61 로 고정한다. 결정(ADR) 없이 toolchain 을 바꾸면 이 테스트가 실패해 바뀐 것이 드러난다. 기준선을 바꿀 때는 ADR 을 새로
 * 쓰고 이 값을 함께 고친다.
 */
class JavaBaselineTest {

  private static final int JAVA_FEATURE = 17;

  /** Java 17 클래스 파일의 주 버전 (JVMS 4.1). */
  private static final int CLASS_MAJOR_JAVA_17 = 61;

  private static final int CLASS_MAGIC = 0xCAFEBABE;

  @Test
  void unitTestJvmIsJava17() {
    assertThat(Runtime.version().feature())
        .as("단위 테스트 JVM 의 Java 주 버전 (java.home=%s)", System.getProperty("java.home"))
        .isEqualTo(JAVA_FEATURE);
  }

  @Test
  void everyModuleCompilesToJava17ClassFiles() throws IOException {
    Map<String, Class<?>> mainClassPerModule = new LinkedHashMap<>();
    mainClassPerModule.put("domain", ErrorCode.class);
    mainClassPerModule.put("application", GetRuntimeInfoUseCase.class);
    mainClassPerModule.put("adapter-web", AdminTokenFilter.class);
    mainClassPerModule.put("adapter-metadata", JdbcAppSettingRepository.class);
    mainClassPerModule.put("adapter-crypto", AesGcmSecretCipher.class);
    mainClassPerModule.put("app", QueryHubApplication.class);

    for (Map.Entry<String, Class<?>> entry : mainClassPerModule.entrySet()) {
      Class<?> type = entry.getValue();
      String resource = type.getSimpleName() + ".class";
      URL location = type.getResource(resource);
      assertThat(location).as("%s 의 클래스 파일 %s", entry.getKey(), type.getName()).isNotNull();
      assertThat(classFileMajorVersion(type, resource))
          .as("%s 모듈의 %s 클래스 파일 주 버전 (%s)", entry.getKey(), type.getName(), location)
          .isEqualTo(CLASS_MAJOR_JAVA_17);
    }
  }

  @Test
  void classFileHeaderReaderRejectsWhatIsNotAClassFile() {
    // 음성 검사: 읽는 쪽이 아무 바이트나 받아들이면 위 검사가 의미가 없다.
    byte[] notAClass = {0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 61};
    assertThat(majorVersionOf(notAClass)).isEqualTo(-1);
    byte[] newerClass = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 65};
    assertThat(majorVersionOf(newerClass)).isEqualTo(65).isNotEqualTo(CLASS_MAJOR_JAVA_17);
  }

  private static int classFileMajorVersion(Class<?> type, String resource) throws IOException {
    try (InputStream in = type.getResourceAsStream(resource)) {
      assertThat(in).as("%s 를 읽을 수 있어야 한다", resource).isNotNull();
      return majorVersionOf(in.readNBytes(8));
    }
  }

  /** 클래스 파일 머리(magic 4 바이트, minor 2 바이트, major 2 바이트)의 주 버전. 클래스 파일이 아니면 -1. */
  static int majorVersionOf(byte[] header) {
    if (header.length < 8) {
      return -1;
    }
    try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(header))) {
      if (data.readInt() != CLASS_MAGIC) {
        return -1;
      }
      data.readUnsignedShort();
      return data.readUnsignedShort();
    } catch (IOException impossible) {
      return -1;
    }
  }
}
