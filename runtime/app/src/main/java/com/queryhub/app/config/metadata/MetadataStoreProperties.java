package com.queryhub.app.config.metadata;

import com.queryhub.adapter.metadata.store.StoreLayout;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Metadata 저장소(내장 SQLite) 설정 (P1-06, ADR-026). desktop 과 server 가 같은 규칙을 쓴다.
 *
 * <table>
 *   <caption>설정</caption>
 *   <tr><th>속성</th><th>환경변수</th><th>필수</th><th>규칙</th></tr>
 *   <tr><td>{@value StoreLayout#PROPERTY_DATA_DIR}</td><td>{@value StoreLayout#ENV_DATA_DIR}</td>
 *       <td>예 (기본값 없음)</td><td>로컬 디스크의 절대 경로, '?' 없음</td></tr>
 *   <tr><td>{@value StoreLayout#PROPERTY_NATIVE_DIR}</td><td>{@value StoreLayout#ENV_NATIVE_DIR}</td>
 *       <td>아니요 (기본 &lt;data-dir&gt;/native)</td><td>절대 경로, '?' 없음</td></tr>
 * </table>
 *
 * <p>환경변수는 Spring 의 느슨한 바인딩이 속성으로 연결한다(QUERYHUB_MASTER_KEY 가 queryhub.master-key 로 가는 것과 같다). 비밀이
 * 아니므로 외부 설정 파일(spring.config.additional-location, ./config/application.yml)이나 명령행 인자로도 줄 수 있다. 검사는
 * {@link StoreLayout#resolve(String, String)} 가 한다.
 *
 * @param dataDir 데이터 디렉터리 ({@code queryhub.data-dir})
 * @param sqlite SQLite 드라이버 설정 ({@code queryhub.sqlite.*})
 */
@ConfigurationProperties(prefix = "queryhub")
public record MetadataStoreProperties(@Nullable String dataDir, @Nullable Sqlite sqlite) {

  /**
   * @param nativeDir 네이티브 라이브러리 디렉터리 ({@code queryhub.sqlite.native-dir})
   */
  public record Sqlite(@Nullable String nativeDir) {}

  /** 검사한 저장소 배치. */
  StoreLayout toLayout() {
    return StoreLayout.resolve(dataDir, sqlite == null ? null : sqlite.nativeDir());
  }
}
