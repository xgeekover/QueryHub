package com.queryhub.adapter.metadata.store;

/**
 * Metadata 저장소(SQLite)를 열 수 없어 런타임을 시작하지 않는다.
 *
 * <p>메시지는 한국어이고 설정 이름(환경변수와 속성), 경로, 고치는 방법을 담는다. 경로와 설정 이름은 비밀이 아니다. 관리자 토큰, 마스터 키 같은 비밀 값은 담지
 * 않는다(이 패키지는 그 값을 받지도 않는다).
 */
public final class MetadataStoreException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public MetadataStoreException(String message) {
    super(message);
  }

  public MetadataStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
