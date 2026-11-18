package com.queryhub.application.setting;

import java.util.Optional;

/**
 * 런타임 설정(키-값)의 저장소 포트. 메타데이터 DB 의 app_setting 테이블이 구현의 기준이다.
 *
 * <p>계약 (모든 구현이 지킨다):
 *
 * <ul>
 *   <li>키는 null 이 아니고, 공백만으로 이루어지지 않으며, 최대 {@value #MAX_KEY_LENGTH} 자(코드 포인트)다.
 *   <li>키는 대소문자와 뒤쪽 공백을 구분한다. {@code "k"}, {@code "k "}, {@code "K"} 는 서로 다른 키다.
 *   <li>값은 null 이 아니다(빈 문자열은 허용한다).
 *   <li>계약을 어긴 인자는 저장소에 접근하기 전에 {@link IllegalArgumentException} 으로 거부한다.
 * </ul>
 *
 * <p>설정 키의 의미(예: API 서비스 스위치)는 그 기능을 구현하는 Phase 가 정한다. P0-B 는 저장 경로만 제공한다.
 */
public interface AppSettingRepository {

  /** 키의 최대 길이(코드 포인트 수). app_setting.setting_key 의 VARCHAR(100) 과 같다. */
  int MAX_KEY_LENGTH = 100;

  /** 키의 값을 돌려준다. 키가 없으면 빈 Optional 이다. */
  Optional<String> findValue(String key);

  /** 키의 값을 저장한다. 키가 이미 있으면 값을 바꾸고, 처음 저장한 시각은 그대로 둔다. */
  void putValue(String key, String value);
}
