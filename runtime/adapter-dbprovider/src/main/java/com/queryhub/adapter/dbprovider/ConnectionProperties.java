package com.queryhub.adapter.dbprovider;

import com.queryhub.domain.connection.ConnectionSettings;
import com.queryhub.domain.connection.InvalidConnectionSettingsException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** 드라이버에 줄 속성 (user, password 제외): 이름과 값의 허용 목록을 통과한 옵션, 그 위에 고정 속성. */
final class ConnectionProperties {

  static final String USER = "user";
  static final String PASSWORD = "password";

  private ConnectionProperties() {}

  /**
   * @throws InvalidConnectionSettingsException 허용 목록에 없는 옵션, 값이 그 옵션의 허용 값이 아닌(대소문자 구분) 옵션, 고정 속성,
   *     user, password 와 이름이 겹치는(대소문자 무시) 옵션이 있으면. 이름 순으로 첫 옵션의 이름만 밝히고 값은 밝히지 않는다
   * @throws IllegalStateException Provider 가 잘못 만들어졌으면: 고정 속성에 user 나 password 가 있거나, 허용 옵션에 값 집합이
   *     없거나 비어 있으면 (옵션을 주지 않은 설정에서도)
   */
  static Properties build(JdbcProviderSpi spi, ConnectionSettings settings) {
    Map<String, String> fixed = Objects.requireNonNull(spi.fixedProperties(settings), "fixed");
    Map<String, Set<String>> allowed =
        Objects.requireNonNull(spi.allowedOptions(), "allowedOptions");
    allowed.forEach(
        (name, values) -> {
          if (values == null || values.isEmpty()) {
            throw new IllegalStateException("허용 옵션 " + name + " 에 허용 값이 없다 (" + spi.type() + ").");
          }
        });
    Set<String> reserved = new HashSet<>();
    reserved.add(USER);
    reserved.add(PASSWORD);
    for (String name : fixed.keySet()) {
      String lower = name.toLowerCase(Locale.ROOT);
      if (lower.equals(USER) || lower.equals(PASSWORD)) {
        throw new IllegalStateException("고정 속성에 user, password 를 넣을 수 없다 (" + spi.type() + ").");
      }
      reserved.add(lower);
    }
    // settings.options() 는 이름 순이다. 값은 정확히 같아야 한다 (대소문자 구분).
    for (Map.Entry<String, String> option : settings.options().entrySet()) {
      String name = option.getKey();
      Set<String> values = allowed.get(name);
      if (values == null
          || reserved.contains(name.toLowerCase(Locale.ROOT))
          || !values.contains(option.getValue())) {
        throw InvalidConnectionSettingsException.optionNotAllowed(name);
      }
    }
    Properties properties = new Properties();
    settings.options().forEach(properties::setProperty);
    // 고정 속성이 이긴다 (겹치는 옵션은 위에서 이미 거부했다).
    fixed.forEach(properties::setProperty);
    return properties;
  }
}
