package com.queryhub.domain.runtime;

import java.util.Optional;

/**
 * 런타임 실행 모드.
 *
 * <p>DESKTOP 은 Tauri 셸이 띄우는 로컬 모드(루프백 바인딩), SERVER 는 서버 배포 모드다. {@link #id()} 는 Spring 프로파일 이름이자
 * API 응답의 mode 값이다.
 */
public enum RuntimeMode {
  DESKTOP("desktop"),
  SERVER("server");

  private final String id;

  RuntimeMode(String id) {
    this.id = id;
  }

  /** 프로파일 이름과 응답 값으로 쓰는 소문자 식별자. */
  public String id() {
    return id;
  }

  /**
   * 식별자로 모드를 찾는다. 대소문자를 구분한다(Spring 프로파일 이름과 같은 규칙).
   *
   * @return 일치하는 모드. 없거나 null 이면 비어 있다.
   */
  public static Optional<RuntimeMode> findById(String id) {
    if (id == null) {
      return Optional.empty();
    }
    for (RuntimeMode mode : values()) {
      if (mode.id.equals(id)) {
        return Optional.of(mode);
      }
    }
    return Optional.empty();
  }
}
