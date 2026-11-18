package com.queryhub.adapter.metadata.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * AC-10: Flyway 마이그레이션 스크립트(classpath:db/migration)의 규칙.
 *
 * <ul>
 *   <li>파일 이름은 {@code V<n>__<lower_snake>.sql} 이고, 버전은 1 부터 빈틈과 중복 없이 이어진다. 하위 디렉터리는 두지 않는다.
 *   <li>모든 CREATE TABLE 은 테이블 옵션에 utf8mb4 문자 집합과 utf8mb4_* 정렬 규칙을 직접 적는다. 서버 기본값(예: latin1)에 기대지 않기
 *       위해서다.
 *   <li>V1 은 app_setting 을 만들고 시드 행을 넣지 않는다.
 * </ul>
 *
 * <p>검사 함수는 고의로 틀린 입력에서도 실패하는지 같은 클래스에서 확인한다(규칙이 비어 있지 않다는 증거).
 */
class MigrationScriptConventionTest {

  private static final String LOCATION = "db/migration";

  private static final Pattern FILE_NAME =
      Pattern.compile("V([1-9][0-9]*)__([a-z0-9]+(?:_[a-z0-9]+)*)\\.sql");

  private static final Pattern CREATE_TABLE = Pattern.compile("(?is)^\\s*CREATE\\s+TABLE\\b.*");

  private static final Pattern TABLE_CHARSET =
      Pattern.compile("(?i)\\b(?:CHARSET|CHARACTER\\s+SET)\\s*=?\\s*utf8mb4\\b");

  private static final Pattern TABLE_COLLATION =
      Pattern.compile("(?i)\\bCOLLATE\\s*=?\\s*utf8mb4_\\w+");

  private static Path migrationDirectory;
  private static List<Path> scripts;

  @BeforeAll
  static void findScripts() throws IOException, URISyntaxException {
    URL url = MigrationScriptConventionTest.class.getClassLoader().getResource(LOCATION);
    assertThat(url).as("classpath:%s 가 있어야 한다", LOCATION).isNotNull();
    assertThat(url.getProtocol()).as("단위 테스트는 디렉터리에서 읽는다").isEqualTo("file");
    migrationDirectory = Path.of(url.toURI());
    try (Stream<Path> entries = Files.walk(migrationDirectory)) {
      scripts = entries.filter(path -> !path.equals(migrationDirectory)).sorted().toList();
    }
  }

  @Test
  void scriptsFollowTheNamingAndVersionConvention() {
    assertThat(scripts).as("마이그레이션이 하나 이상 있어야 한다").isNotEmpty();
    assertThat(scripts)
        .as("하위 디렉터리 없이 일반 파일만 둔다")
        .allSatisfy(
            path -> {
              assertThat(path.getParent()).isEqualTo(migrationDirectory);
              assertThat(Files.isRegularFile(path)).isTrue();
            });
    List<String> names = scripts.stream().map(path -> path.getFileName().toString()).toList();

    assertThat(namingViolations(names)).isEmpty();
    assertThat(names).contains("V1__create_app_setting.sql");
  }

  @Test
  void everyCreateTableDeclaresUtf8mb4Explicitly() throws IOException {
    int createTables = 0;
    for (Path script : scripts) {
      String sql = Files.readString(script, StandardCharsets.UTF_8);
      assertThat(charsetViolations(sql)).as(script.getFileName().toString()).isEmpty();
      createTables += createTableStatements(sql).size();
    }
    assertThat(createTables).as("검사한 CREATE TABLE 수").isGreaterThanOrEqualTo(1);
  }

  @Test
  void firstMigrationCreatesAppSettingWithoutSeedRows() throws IOException {
    String sql =
        Files.readString(
            migrationDirectory.resolve("V1__create_app_setting.sql"), StandardCharsets.UTF_8);
    List<String> statements = statements(sql);

    assertThat(statements).hasSize(1);
    assertThat(statements.get(0))
        .containsPattern("(?is)^\\s*CREATE\\s+TABLE\\s+app_setting\\b")
        .contains("utf8mb4_nopad_bin")
        .contains("utf8mb4_uca1400_ai_ci")
        .contains("DATETIME(6)")
        .contains("CONSTRAINT pk_app_setting PRIMARY KEY (setting_key)");
    assertThat(statements)
        .noneMatch(statement -> statement.strip().toUpperCase(Locale.ROOT).startsWith("INSERT"));
  }

  @Test
  void namingCheckRejectsBadNamesGapsAndDuplicates() {
    assertThat(namingViolations(List.of("V1__a.sql", "V2__b_c.sql"))).isEmpty();

    assertThat(namingViolations(List.of("V1__Create_Table.sql"))).hasSize(1);
    assertThat(namingViolations(List.of("V1_create.sql"))).hasSize(1);
    assertThat(namingViolations(List.of("V0__zero.sql"))).isNotEmpty();
    assertThat(namingViolations(List.of("V01__padded.sql"))).isNotEmpty();
    assertThat(namingViolations(List.of("R__repeatable.sql"))).isNotEmpty();
    assertThat(namingViolations(List.of("V1__a.sql", "notes.txt"))).hasSize(1);
    assertThat(namingViolations(List.of("V1__a.sql", "V3__c.sql"))).hasSize(1);
    assertThat(namingViolations(List.of("V2__b.sql"))).hasSize(1);
    assertThat(namingViolations(List.of("V1__a.sql", "V1__b.sql"))).isNotEmpty();
  }

  @Test
  void charsetCheckRejectsTablesWithoutExplicitUtf8mb4Options() {
    String good =
        "CREATE TABLE t (id INT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            + " COLLATE=utf8mb4_uca1400_ai_ci;";
    assertThat(charsetViolations(good)).isEmpty();
    assertThat(
            charsetViolations(
                "CREATE TABLE t (id INT) CHARACTER SET utf8mb4 COLLATE utf8mb4_nopad_bin;"))
        .isEmpty();

    assertThat(charsetViolations("CREATE TABLE t (id INT NOT NULL) ENGINE=InnoDB;")).hasSize(1);
    // 컬럼에만 적고 테이블 옵션에는 없다.
    assertThat(
            charsetViolations(
                "CREATE TABLE t (k VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_nopad_bin)"
                    + " ENGINE=InnoDB;"))
        .hasSize(1);
    assertThat(charsetViolations("CREATE TABLE t (id INT) DEFAULT CHARSET=latin1;")).hasSize(1);
    assertThat(charsetViolations("CREATE TABLE t (id INT) DEFAULT CHARSET=utf8mb4;"))
        .as("정렬 규칙도 적어야 한다")
        .hasSize(1);
    // 주석 안의 utf8mb4 는 인정하지 않는다.
    assertThat(
            charsetViolations(
                "CREATE TABLE t (id INT) ENGINE=InnoDB -- DEFAULT CHARSET=utf8mb4"
                    + " COLLATE=utf8mb4_bin\n;"))
        .hasSize(1);
  }

  /** 파일 이름 목록의 규칙 위반을 돌려준다: 형식, 1 부터 이어지는 고유 버전. */
  static List<String> namingViolations(List<String> names) {
    List<String> violations = new ArrayList<>();
    Set<Long> versions = new TreeSet<>();
    for (String name : names) {
      Matcher matcher = FILE_NAME.matcher(name);
      if (!matcher.matches()) {
        violations.add(name + ": V<n>__<lower_snake>.sql 형식이 아니다");
        continue;
      }
      long version = Long.parseLong(matcher.group(1));
      if (!versions.add(version)) {
        violations.add(name + ": 버전 " + version + " 이 중복된다");
      }
    }
    long expected = 1;
    for (long version : versions) {
      if (version != expected) {
        violations.add("버전이 1 부터 이어지지 않는다: V" + expected + " 가 없다 (다음 버전 V" + version + ")");
        break;
      }
      expected++;
    }
    return violations;
  }

  /** CREATE TABLE 문마다 테이블 옵션(마지막 닫는 괄호 뒤)에 utf8mb4 문자 집합과 정렬 규칙이 있는지 본다. */
  static List<String> charsetViolations(String sql) {
    List<String> violations = new ArrayList<>();
    for (String statement : createTableStatements(sql)) {
      String options = statement.substring(statement.lastIndexOf(')') + 1);
      if (!TABLE_CHARSET.matcher(options).find() || !TABLE_COLLATION.matcher(options).find()) {
        violations.add("utf8mb4 문자 집합과 정렬 규칙을 테이블 옵션에 직접 적어야 한다: " + statement.strip());
      }
    }
    return violations;
  }

  private static List<String> createTableStatements(String sql) {
    return statements(sql).stream().filter(s -> CREATE_TABLE.matcher(s).matches()).toList();
  }

  /** 주석(-- 줄 주석, 블록 주석)을 지우고 세미콜론으로 나눈 비어 있지 않은 문장들. */
  private static List<String> statements(String sql) {
    String withoutComments = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
    List<String> statements = new ArrayList<>();
    for (String statement : withoutComments.split(";")) {
      if (!statement.isBlank()) {
        statements.add(statement);
      }
    }
    return statements;
  }
}
