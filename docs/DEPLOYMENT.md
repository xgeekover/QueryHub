# QueryHub 빌드·실행·배포

런타임 JAR 을 빌드하고 desktop·server 프로파일로 실행하는 방법, 데이터 디렉터리와 내장 Metadata 저장소, 백업·복구, Desktop 셸의 실행 방법을 적는다. 지금 확인된 플랫폼은 macOS(arm64) 뿐이다. Windows·Linux 는 **미검증**이다([BACKLOG.md](BACKLOG.md) PLAT-1). 설치본과 Docker 이미지는 계획(Phase 8)이다.

## 준비물 (구현됨, P0-A·P0-B. Java 17 은 P1-05, 내장 저장소는 P1-06)

- **빌드**: 로컬에 설치된 **JDK 17** 이 있어야 한다. 컴파일·단위 테스트·통합 테스트는 Gradle Java toolchain 으로 하고, 그 언어 버전은 정확히 17 이다(`runtime/build.gradle`, [ADR-025](ADR/ADR-025-java-17-baseline.md)). Gradle 은 설치된 JDK 를 자동으로 찾는다(Gradle 을 실행한 JVM, `JAVA_HOME`, macOS 의 java_home 이 아는 JDK, `~/.gradle/jdks` 등). **JDK 는 내려받지 않는다**: foojay resolver 가 없고 `runtime/gradle.properties` 에 `org.gradle.java.installations.auto-download=false` 가 있다. 언어 버전은 정확히 맞아야 하므로 다른 버전의 JDK(예: 예전에 Gradle 이 `~/.gradle/jdks` 에 받아 둔 더 높은 버전)만 있으면 17 대신 쓰이지 않고 빌드가 실패한다. JDK 17 이 자동 감지되지 않는 곳에 있으면 `~/.gradle/gradle.properties` 에 `org.gradle.java.installations.paths=<JDK 17 홈 디렉터리>` 를 적어 Gradle 에 알려 준다. **Windows 에서는 경로의 `\` 를 `/` 로 쓰거나 `\\` 로 적는다**(예: `C:/Program Files/Java/jdk-17`). `gradle.properties` 는 `\` 를 이스케이프로 읽으므로 `C:\Program Files\Java\jdk-17` 을 그대로 적으면 `C:Program FilesJavajdk-17` 이 되어 찾지 못한다. 미검증(PLAT-1, P1-05 리뷰 R3). 어떤 JDK 가 보이는지는 `sh runtime/gradlew -p runtime javaToolchains` 가 보여 준다(`Auto-download: Disabled` 로 나와야 한다). Gradle 9.7.1 은 wrapper 가 내려받는다. 의존성과 wrapper 가 캐시에 있으면 `--offline` 으로 빌드된다(P1-05 에서 확인).
  - JDK 17 이 없을 때의 실패 (P1-05 에서 Gradle 9.7.1 이 macOS 에서 실제로 낸 메시지. `~/.gradle/jdks` 의 더 높은 버전 JDK 위에서 Gradle 을 돌리고 자동 감지를 꺼서 JDK 17 이 없는 장비를 흉내 냈다. 태스크 이름은 처음 실행되는 `JavaCompile` 태스크에 따라 다르다):

    ```text
    * What went wrong:
    Could not determine the dependencies of task ':domain:compileJava'.
    > Could not resolve all dependencies for configuration ':domain:compileClasspath'.
       > Failed to calculate the value of task ':domain:compileJava' property 'javaCompiler'.
          > Cannot find a Java installation on your machine (Mac OS X 27.2 aarch64) matching: {languageVersion=17, vendor=any vendor, implementation=vendor-specific, nativeImageCapable=false}. Toolchain auto-provisioning is not enabled.
    ```

    고치는 법: JDK 17 을 설치하거나, 설치된 JDK 17 의 홈 디렉터리를 `org.gradle.java.installations.paths` 로 알려 준다. 아무것도 내려받지 않는다. 명령행에서 toolchain 설정을 줄 때는 `-D` 형식(예: `-Dorg.gradle.java.installations.paths=...`)을 쓴다. P1-05 에서 `-Porg.gradle.java.installations.auto-detect=false` 를 주었을 때 Gradle 9.7.1 은 project property 형식이 deprecated 이고 Gradle 10 에서 없어진다고 경고했다.
- **실행**: 실행 JAR 은 Java 17 용으로 컴파일된다(클래스 파일 주 버전 61). `java -jar` 에는 **Java 17 이상 런타임**이 필요하다. 게이트의 단위·통합 테스트와 통합 테스트가 띄우는 실행 JAR 은 Java 17 에서 돈다(`BootJarIntegrationTest` 가 기동 줄의 `using Java 17 with` 또는 `using Java 17.0.x with` 를 확인한다). 더 높은 Java 버전에서는 P1-05 뒤에 다시 시험하지 않았다.
- **Metadata 저장소**: 외부 DB 가 필요 없다. 런타임은 데이터 디렉터리 안의 내장 SQLite 파일 `queryhub.db` 를 쓴다(아래 「데이터 디렉터리」, [ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md)). 데이터 디렉터리는 두 프로파일 모두 필수다. desktop 앱은 셸이 정해 준다.
- **Docker**: 통합 테스트 게이트(`integration.sh`)의 사전 점검에만 필요하다. P1-06 의 통합 테스트는 Docker 를 쓰지 않지만, 다음 DB Provider 태스크가 다시 쓰므로 사전 점검을 남겼다(Docker 가 없으면 게이트는 건너뛰지 않고 실패한다). `build.sh`, `test.sh`, `lint.sh` 와 런타임 실행에는 Docker 가 필요 없다.
- **네트워크**: 첫 빌드에는 Maven Central 접근이 필요하다(또는 사내 미러). sqlite-jdbc 도 그때 한 번 받는 의존성이고, SQLite 네이티브 라이브러리는 그 JAR 안에 있어 따로 받을 것이 없다. 실행 JAR 하나를 닫힌 사내망의 장비로 옮기면 네이티브까지 함께 간다. JDK 는 내려받지 않는다(위 「빌드」). 닫힌 사내망에서 빌드하거나 실행해 보지는 않았다.
- **셸**: 게이트 스크립트는 bash 3.2(macOS 기본) 이상과 POSIX 도구(find, awk, grep, mktemp)만 쓴다. 게이트 명령과 판정은 [TEST_PLAN.md](TEST_PLAN.md)에 있다.

## 데이터 디렉터리

구현됨 (P1-06, [ADR-026](ADR/ADR-026-sqlite-embedded-metadata-store.md)). 런타임은 Metadata 저장소를 이 디렉터리 안의 내장 SQLite 파일로 둔다. desktop 과 server 가 같은 규칙을 쓴다. 설정 코드는 `runtime/app/src/main/java/com/queryhub/app/config/metadata/MetadataStoreProperties.java`, 검사 코드는 `runtime/adapter-metadata/src/main/java/com/queryhub/adapter/metadata/store/` 에 있다.

| 환경변수 | 설정 키 | 필수 | 설명 |
|---|---|---|---|
| `QUERYHUB_DATA_DIR` | `queryhub.data-dir` | 예 (두 프로파일, 기본값 없음) | 데이터 디렉터리. `?` 가 없는 절대 경로. 없으면 런타임이 만든다 |
| `QUERYHUB_SQLITE_NATIVE_DIR` | `queryhub.sqlite.native-dir` | 아니요 (기본 `<데이터 디렉터리>/native`) | SQLite 네이티브 라이브러리를 푸는 디렉터리. `?` 가 없는 절대 경로. 아래 「SQLite 네이티브 라이브러리 디렉터리」 |

- desktop 앱: 셸이 `<앱 로컬 데이터 디렉터리>/data` 를 준비해 `QUERYHUB_DATA_DIR` 로 넘긴다. 따로 설정할 것이 없고, 셸 자신의 환경에 있는 `QUERYHUB_DATA_DIR`·`QUERYHUB_SQLITE_NATIVE_DIR` 은 런타임에 넘어가지 않는다. 경로는 아래 「Desktop 셸」의 표에 있다.
- server 와 손으로 실행한 `java -jar`: 값이 없거나, 비었거나, 상대 경로이거나, `?` 를 담으면 포트를 열기 전에 기동을 거부한다. 메시지는 `QUERYHUB_DATA_DIR` 과 `queryhub.data-dir` 를 말한다.
- 값을 주는 곳: 환경변수, 외부 설정 파일(아래), 명령행 인자(`--queryhub.data-dir=<경로>`) 어느 것이든 된다. 비밀 값이 아니므로 명령행에 보여도 된다.

### 저장소 파일

```
<데이터 디렉터리>/
├─ queryhub.db        Metadata 저장소 (SQLite). 0600
├─ queryhub.db-wal    WAL 파일 (SQLite 가 만든다). 0600
├─ queryhub.db-shm    WAL 공유 메모리 파일. 0600
├─ queryhub.lock      런타임 하나만 쓰게 하는 잠금 파일. 0600. 지우지 않는다
└─ native/            sqlite-jdbc 의 네이티브 라이브러리. 0700
```

새 데이터 디렉터리에서는 런타임이 디렉터리(0700), `native/`(0700), 빈 `queryhub.db`(0600)를 만들고 마이그레이션을 적용한다. 다음 시작은 같은 파일을 다시 쓴다. 테이블은 [ERD.md](ERD.md)에 있다.

### 권한과 거부 (unix)

런타임은 desktop 셸의 마스터 키 규칙과 같은 규칙을 적용하고, 맞지 않으면 포트를 열기 전에 기동을 거부한다(종료 코드 1). 느슨한 권한이나 다른 파일을 **저절로 고치거나 지우지 않는다**. 메시지는 경로와 고치는 법을 말하고 비밀 값을 담지 않는다.

| 상황 | 거부 메시지의 요지 | 고치는 법 |
|---|---|---|
| 데이터 디렉터리나 네이티브 디렉터리가 심볼릭 링크이거나 디렉터리가 아니다 | 경로와 종류 | 실제 디렉터리를 지정한다 |
| 디렉터리에 그룹·기타 권한 비트가 있다(예: 0755) | 경로와 `chmod 700 '<경로>'` | 안내된 `chmod 700` 을 실행한다 |
| 디렉터리를 쓸 수 없다(예: 0500) | 경로 | 런타임 사용자가 쓸 수 있게 한다 |
| 디렉터리나 파일의 소유자가 런타임 사용자가 아니다 | 경로와 소유자 | 런타임을 그 사용자로 실행하거나 소유자를 바꾼다 |
| `queryhub.db`, `-wal`, `-shm`, `queryhub.lock` 이 심볼릭 링크이거나 일반 파일이 아니다 | 경로 | 그 자리의 것을 옮긴다 |
| 그 파일에 그룹·기타 권한 비트가 있다(예: 0644) | 경로와 `chmod 600 '<경로>'` | 안내된 `chmod 600` 을 실행한다 |
| 비어 있지 않은 `queryhub.db` 가 SQLite 파일이 아니다 | 「SQLite DB 파일이 아니다」 | 다른 파일이면 옮기고, 아니면 아래 「백업·복구」 |
| `queryhub.db` 가 손상되었다(`PRAGMA quick_check` 가 ok 가 아님) | 「손상」 | 런타임을 멈춘 채 아래 「백업·복구」로 DB 와 키를 함께 되살린다 |
| `queryhub.db` 가 없거나 비었는데 `queryhub.db-wal` 이나 `queryhub.db-journal` 에 데이터가 있다 | 두 경로와 「데이터가 남아 있어」 | 옮기거나 지운 DB 파일을 그 짝과 함께 되돌려 놓거나 「백업·복구」. 그 데이터가 필요 없으면 남은 파일을 옮긴다 |
| 되돌리지 않은 롤백 저널(`queryhub.db-journal`)이 있다 | 「롤백 저널」 | 런타임은 WAL 만 쓰므로 다른 도구가 남긴 것이다. 「백업·복구」, 또는 그 도구로 열어 되돌린다 |
| 다른 런타임이 같은 데이터 디렉터리를 쓰고 있다 | 데이터 디렉터리와 `queryhub.lock` | 아래 「런타임 하나」 |
| 데이터 디렉터리가 네트워크 파일 시스템에 있다, 또는 데이터·네이티브 디렉터리가 Windows UNC 경로(`\\server\share\...`)다 | 파일 시스템 형식, 또는 「UNC 경로」 | 로컬 디스크의 디렉터리를 쓴다 |
| 마이그레이션 기록이 맞지 않는다(V1 체크섬이 다르다 등) | Flyway 의 validate 오류 | 그 저장소의 백업을 확인한다. 기록 테이블을 손으로 고치지 않는다 |

- **거부한 파일을 바꾸지 않는다.** 머리글 검사는 파일을 읽기만 하고, 내용 검사(`quick_check`)는 **읽기 전용** SQLite 연결로 한다. 그래서 거부할 때 `queryhub.db`, `queryhub.db-wal`, `queryhub.db-journal` 은 바이트 하나 바뀌지 않는다. 읽기·쓰기 연결이었다면 닫을 때 WAL 을 DB 파일로 옮겨 담고 `-wal`·`-shm` 을 지웠을 것이다(P1-06 리뷰 R1). 런타임이 쓰는 읽기·쓰기 연결은 내용 검사를 통과한 파일만 연다.
- 예외는 `queryhub.db-shm` 이다. 데이터가 없는 WAL 색인이라 SQLite 가 읽기 전용 연결에서도 만들거나 고쳐 쓴다. 새로 만들면 DB 파일과 같은 0600 이다. WAL 모드 DB 에 `-wal` 이 없으면 SQLite 가 빈 `-wal`(0600)도 만든다. 거부한 뒤에는 이런 새 파일과 `queryhub.lock`, 빈 `native/` 가 남을 수 있다.
- `-wal` 은 있고 `-shm` 이 없는 저장소(예: `-shm` 없이 복사한 사본)는 거부하지 않는다. 데이터 디렉터리는 쓸 수 있어야 하므로 SQLite 가 `-shm` 을 0600 으로 다시 만들고 `-wal` 의 내용을 읽는다.
- Windows 는 권한과 소유자를 확인하지 않는다. 사용자 프로필의 기본 ACL 에만 기댄다(마스터 키와 같다, 미검증 PLAT-1). 데이터 디렉터리를 다른 사용자가 읽을 수 있는 곳에 두지 않는다.
- 소유자 판정: Linux 에서는 `/proc/self` 의 소유자, 그 밖에서는 `user.name` 의 사용자와 비교한다. 둘 다 안 되면 기동을 거부한다. 다른 소유자의 경우는 값을 넣은 단위 시험으로만 확인했다(root 없이 만들 수 없다).
- SQLite 설정(`journal_mode=WAL`, `synchronous=FULL`, `foreign_keys=ON`, `busy_timeout=5000`, `transaction_mode=IMMEDIATE`)은 런타임이 정한다. `spring.datasource.hikari.data-source-properties` 로 그 이름을 주거나 `connection-init-sql` 로 값을 바꾸면 기동을 거부한다. 풀 크기(`spring.datasource.hikari.maximum-pool-size`, 기본 4)는 바꿔도 된다([DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)).

### 로컬 디스크만

데이터 디렉터리는 **로컬 디스크**에 둔다. SQLite 의 잠금과 WAL 은 네트워크 파일 시스템에서 안전하지 않다. 런타임은 데이터 디렉터리의 파일 시스템 형식이 알려진 네트워크 형식(nfs, nfs4, cifs, smbfs, smb2, smb3, afpfs, webdav, davfs, fuse.sshfs, 9p, ceph, glusterfs, fuse.glusterfs)이면 거부한다. 이 검사는 최선 노력이다.

- 모르는 형식은 받는다. 예를 들어 위 목록 밖의 FUSE 마운트는 통과한다.
- Windows 의 **UNC 경로**(`\\server\share\...`)는 데이터 디렉터리와 네이티브 디렉터리 모두 경로의 뿌리만 보고 거부한다. 이 검사는 아무것도 만들기 전에 한다. 분류기만 단위 시험이 있다(Windows 에서는 실행하지 않았다, PLAT-1).
- **Windows 에서 네트워크 드라이브에 매핑한 드라이브 문자(예: `Z:\`)는 잡지 못한다.** 보통 원격 형식(예: NTFS)을 보고한다(미검증).
- 실제 NFS·SMB·AFP·WebDAV 마운트에서는 시험하지 않았다. 분류기만 단위 시험이 있다([BACKLOG.md](BACKLOG.md) P106-4).

### 런타임 하나

한 데이터 디렉터리는 런타임 하나만 쓴다. 런타임은 살아 있는 동안 `queryhub.lock` 의 배타 잠금을 잡고, 끝날 때 푼다(프로세스가 죽어도 OS 가 푼다). 두 번째 런타임은 데이터 디렉터리와 잠금 파일을 말하고 기동을 거부한다.

같은 JVM 안의 두 번째 시도(예: 시험에서 컨텍스트를 둘 띄울 때)는 잠금 파일을 열기 전에 거부한다. macOS 와 Linux 에서는 한 프로세스가 그 파일의 파일 기술자를 하나라도 닫으면 그 프로세스의 잠금이 모두 풀리기 때문이다. 시험이 거부 뒤에도 다른 프로세스가 잠금을 잡을 수 없는지 확인한다(P1-06 리뷰 R2). 같은 디렉터리를 상위 디렉터리의 심볼릭 링크 같은 다른 경로로 가리켜도 같은 파일(장치와 inode)로 본다.

- desktop 앱과 손으로 실행한 `java -jar` 를 함께 돌리려면 서로 다른 데이터 디렉터리를 준다. worktree 마다, 시험마다 다른 디렉터리를 쓴다.
- desktop 셸이 강제 종료(SIGKILL, 크래시)되면 남은 런타임이 부모 감시에 걸려 끝날 때까지(약 5 초) 잠금을 잡고 있다. 그 사이에 앱을 다시 시작하면 잠금 메시지로 실패하므로 잠시 뒤 다시 시작한다([BACKLOG.md](BACKLOG.md) P106-7).
- 여러 서버가 저장소 하나를 함께 쓰는 구성은 지원하지 않는다(P106-1).

### 외부 설정 파일 예

사용자가 말한 「설정파일 기반」 시작 설정이다. 환경변수 대신 Spring Boot 의 외부 설정 파일에 데이터 디렉터리를 적을 수 있다. 관리자 토큰과 마스터 키는 이 파일에 적지 않는다(환경변수나 시크릿 파일로만 받는다).

```yaml
# /etc/queryhub/application.yml (경로는 예시다)
queryhub:
  data-dir: /var/lib/queryhub/data
  # sqlite:
  #   native-dir: /opt/queryhub/native   # noexec 일 때만 (아래)
```

```sh
QUERYHUB_ADMIN_TOKEN_FILE=/secure/path/queryhub-admin-token \
QUERYHUB_MASTER_KEY_FILE=/secure/path/queryhub-master-key \
java -jar queryhub-runtime.jar --spring.profiles.active=server --spring.config.additional-location=file:/etc/queryhub/
```

- `--spring.config.additional-location=file:<디렉터리>/` 는 그 디렉터리의 `application.yml` 을 더 읽는다(끝의 `/` 가 디렉터리라는 뜻이다). 환경변수 `SPRING_CONFIG_ADDITIONAL_LOCATION` 으로 줘도 된다. P1-06 의 통합 시험은 server 프로파일의 실행 JAR 을 이 인자와 `queryhub.data-dir` 만 적은 파일로 띄워(`QUERYHUB_DATA_DIR` 없이) health 가 UP 이 되고 그 디렉터리에 `queryhub.db` 가 생기는 것을 확인했다.
- Spring Boot 는 작업 디렉터리의 `./config/application.yml` 도 읽는다(Boot 의 기본 위치. P1-06 이 따로 시험하지는 않았다).
- 같은 키를 여러 곳에서 주면 Spring Boot 의 우선순위를 따른다(명령행 인자 > 환경변수 > 외부 설정 파일).

### SQLite 네이티브 라이브러리 디렉터리

sqlite-jdbc 는 실행 JAR 안의 네이티브 라이브러리를 첫 연결 때 파일로 풀어 불러온다. 런타임은 그곳을 `java.io.tmpdir` 대신 `<데이터 디렉터리>/native`(0700)로 정한다(JVM 시스템 속성 `org.sqlite.tmpdir`). 들어 있는 네이티브와 검증 상태는 [DATABASE_SUPPORT.md](DATABASE_SUPPORT.md)에 있다(실행해 본 것은 Mac/aarch64 뿐이다).

불러오기에 실패하면 기동을 거부하고 메시지가 `os.name`/`os.arch`, 네이티브 디렉터리, `QUERYHUB_SQLITE_NATIVE_DIR` 을 말한다. 그때는 실행이 허용된 로컬 디렉터리를 `QUERYHUB_SQLITE_NATIVE_DIR`(또는 `queryhub.sqlite.native-dir`)로 준다. 그 디렉터리에도 데이터 디렉터리와 같은 규칙(0700, 런타임 사용자 소유, 심볼릭 링크가 아님)이 적용되고, 없으면 만든다. 지정하면 `<데이터 디렉터리>/native` 는 만들지 않는다.

- **`noexec` 마운트 (Linux 서버):** 데이터 디렉터리가 `noexec` 로 마운트된 파일 시스템(예: 경화한 `/var`)에 있으면 풀린 `.so` 를 불러올 수 없다. 실행이 허용된 로컬 디렉터리(예: `/opt/queryhub/native`)를 준다. **재현하지 않았다**(미검증, PLAT-1).
- **Windows 백신:** 사내 백신이 사용자 데이터 디렉터리에 풀린 DLL 을 막을 수 있다. 백신 정책이 허용하는 디렉터리를 주거나 그 디렉터리를 예외로 등록한다. **재현하지 않았다**(미검증, PLAT-1).
- 지원하지 않는 OS·아키텍처에서는 디렉터리를 바꿔도 해결되지 않는다. 런타임은 기동하지 않는다.

## 백업·복구

구현된 백업 명령은 없다([BACKLOG.md](BACKLOG.md) P106-2). 아래 절차는 SQLite 와 운영체제 도구로 한다. **P1-06 은 이 절차를 실행 중인 런타임의 저장소에서 시험하지 않았다.** 아래 `sqlite3` 명령의 문법만 macOS 의 `sqlite3` 3.54.0 과 시험용 WAL 파일로 확인했다(사본이 umask 077 에서 0600 으로 생기고, `VACUUM INTO` 는 대상 파일이 있으면 실패한다). 운영에 쓰기 전에 시험 데이터로 한 번 해 본다.

### 무엇을 함께 백업하는가

- **`queryhub.db` 와 마스터 키는 같은 시점에 함께 백업하고 함께 되살린다.** 저장소는 앞으로 업무 DB 비밀번호를 그 키로 암호화해 담는다(연결 저장 태스크부터). 키 없이 되살린 저장소의 암호화한 비밀번호는 되살릴 수 없다(`UNKNOWN_KEY`). 다른 키와 짝지은 저장소도 같다.
  - server: `QUERYHUB_MASTER_KEY` 의 값이나 `QUERYHUB_MASTER_KEY_FILE` 의 파일.
  - desktop: `<앱 로컬 데이터 디렉터리>/secrets/master.key`(데이터 디렉터리 `data/` 옆, 아래 「마스터 키」).
- 보관 장소는 나눌 수 있다(예: DB 사본은 백업 저장소, 키 사본은 사내 비밀 보관소). 둘을 한곳에 같이 두면 그 백업을 읽는 사람은 비밀번호를 풀 수 있으므로 그 백업 전체를 키와 같은 비밀 값으로 다룬다([SECURITY.md](SECURITY.md) 「Metadata 저장소 파일」).
- `queryhub.lock` 과 `native/` 는 백업하지 않아도 된다(런타임이 다시 만든다).

### 쓰고 있는 저장소를 파일 하나로 복사하지 않는다

런타임이 도는 동안 `queryhub.db` 하나만 복사하면 안 된다. WAL 모드에서는 커밋한 데이터가 한동안 `queryhub.db-wal` 에만 있으므로, 파일 하나의 사본은 그 데이터를 잃거나 앞뒤가 맞지 않는 사본이 된다. 도는 동안 OS 백업 도구(예: Time Machine)나 `cp`·`rsync` 로 데이터 디렉터리를 복사하는 것도 같다. 그런 사본은 백업으로 치지 않는다. 다음 둘 중 하나로 한다.

### 방법 1: 도는 중의 온라인 백업 (sqlite3)

SQLite 명령행 도구 `sqlite3` 가 있으면 런타임을 멈추지 않고 일관된 사본을 만든다. WAL 모드에서 읽기는 런타임의 쓰기를 막지 않는다. `sqlite3` 는 QueryHub 에 들어 있지 않은 별도 도구다(macOS 에는 기본으로 있다. 닫힌 사내망 장비에 없으면 방법 2 를 쓴다).

```sh
umask 077                                    # 사본을 0600 으로 만든다 (sqlite3 는 umask 를 따른다)
db="<데이터 디렉터리>/queryhub.db"
sqlite3 "$db" "VACUUM INTO '/backup/queryhub-$(date +%Y%m%d-%H%M%S).db'"   # 대상 파일은 아직 없어야 한다
# 또는 백업 API:
sqlite3 "$db" ".timeout 5000" ".backup '/backup/queryhub-$(date +%Y%m%d-%H%M%S).db'"
```

- `VACUUM INTO` 와 `.backup` 은 WAL 의 커밋된 내용까지 담은 파일 하나를 만든다. 사본에는 `-wal`·`-shm` 이 필요 없다.
- 같은 때에 마스터 키도 백업한다(위).
- 사본을 확인하려면 `sqlite3 <사본> "PRAGMA integrity_check"` 가 `ok` 인지 본다.

### 방법 2: 멈추고 복사

1. 런타임을 멈춘다. desktop 은 앱을 끝낸다(Cmd+Q). server 는 프로세스를 정상 종료한다. `queryhub.lock` 이 풀린다.
2. `queryhub.db` 와, 있으면 `queryhub.db-wal`, `queryhub.db-shm` 을 **한 벌로** 복사한다(권한을 지키는 `cp -p` 등). 정상 종료하면 보통 WAL 이 DB 에 합쳐져 `-wal` 이 없거나 비어 있지만, 있으면 반드시 함께 복사한다.
3. 마스터 키를 함께 백업한다.
4. 런타임을 다시 시작한다.

### 되살리기

1. 런타임을 멈춘다(방법 2 의 1).
2. 지금의 `queryhub.db`, `queryhub.db-wal`, `queryhub.db-shm` 을 다른 곳으로 **옮긴다**(되살리기가 끝날 때까지 지우지 않는다). 이전 DB 의 `-wal`·`-shm` 이 새 DB 옆에 남으면 SQLite 가 그것을 새 DB 와 함께 읽어 데이터를 잃거나 파일이 손상될 수 있으므로 반드시 함께 치운다.
3. 백업 사본을 `<데이터 디렉터리>/queryhub.db` 로 놓는다. 방법 2 의 사본이면 그 `-wal`·`-shm` 도 같은 이름 규칙으로 함께 놓는다. unix 에서는 파일 0600, 디렉터리 0700, 런타임 사용자 소유로 둔다(`install -m 600 <사본> "<데이터 디렉터리>/queryhub.db"`).
4. **그 사본과 짝인 마스터 키**를 되살린다(server 는 변수나 키 파일, desktop 은 `secrets/master.key` 를 같은 권한으로, 아래 「마스터 키」의 되살리는 방법).
5. 런타임을 시작한다. 런타임이 머리글, `quick_check`, 마이그레이션 기록을 확인하고, 맞지 않으면 포트를 열기 전에 멈춘다. 머리글과 `quick_check` 에서 거부한 파일은 바꾸지 않는다. 마이그레이션 기록은 그 검사를 통과한 파일을 읽기·쓰기로 연 뒤에 보므로, 기록이 맞지 않아 멈출 때 Flyway 는 마이그레이션하지 않지만 SQLite 가 연결을 닫으며 `-wal` 의 내용을 DB 파일로 옮겨 담을 수 있다(데이터는 그대로다).

- 손상으로 기동이 거부되었을 때도 이 순서다. 손상된 파일은 옮겨 두고 고치려 하지 않는다. 런타임은 거부할 때 `queryhub.db` 와 `-wal` 을 바꾸지 않으므로 2단계에서 옮기는 파일은 거부되던 때 그대로다(`-shm` 은 데이터가 없는 색인이라 SQLite 가 고쳐 쓸 수 있다).
- `queryhub.db` 만 옮기고 `-wal` 을 남기면 다음 시작은 「데이터가 남아 있어」로 거부된다. SQLite 는 빈 DB 옆의 WAL 을 지우므로 런타임이 새 DB 를 만들기 전에 멈춘다.
- 더 새 런타임이 마이그레이션한 저장소를 오래된 런타임으로 여는 경우는 감지하지 않는다(P1-06 범위 밖). 되살릴 때는 백업을 만든 런타임과 같거나 더 새 런타임을 쓴다.

## 개발용 DB (P1-06 에서 없어졌다)

P0-B 부터 P1-05 까지 쓰던 개발용 MariaDB compose(`deploy/compose.dev.yml`)는 P1-06 에서 지웠다. 런타임과 desktop 앱에 외부 DB 가 필요 없다. 예전에 메인 체크아웃에서 띄운 컨테이너와 볼륨(compose 프로젝트 `queryhub-dev`, 볼륨 `queryhub-dev-metadata-db`)이 남아 있으면 사람이 지운다(`docker ps -a --filter label=com.docker.compose.project=queryhub-dev` 로 찾는다). 그 데이터는 옮기지 않는다(`app_setting` 에 쓰는 기능이 아직 없었다).

예시 파일 `deploy/env.example` 은 일부러 이름 앞에 점이 없다. 도구와 검사가 `.env*` 를 비밀 파일로 다루므로, 커밋하고 읽을 수 있어야 하는 예시는 그 패턴을 피한다([ADR-016](ADR/ADR-016-env-example-naming.md)). `cp deploy/env.example deploy/.env` 로 복사한 `deploy/.env`(git 이 무시한다)에 `QUERYHUB_DATA_DIR`, 관리자 토큰, 마스터 키를 적어 아래 「런타임 실행」에 쓴다. 값에는 영문자, 숫자, `-`, `_`, `/`, `.` 만 쓴다(셸의 `. deploy/.env` 가 `$`, 따옴표, `\`, 공백을 다르게 읽는다).

## 런타임 실행 (구현됨, P0-A. 데이터 디렉터리는 P1-06)

실행에는 데이터 디렉터리, 관리자 토큰, 마스터 키가 필요하다. 아래 예는 `deploy/.env` 의 변수를 환경변수로 넘긴다(비밀 값을 명령행 인자로 넘기지 않는다). `deploy/.env` 에서 `QUERYHUB_DATA_DIR`, `QUERYHUB_ADMIN_TOKEN`, `QUERYHUB_MASTER_KEY` 줄의 주석을 풀고 값을 정한다. JAR 은 `runtime/app/build/libs/queryhub-runtime.jar` 에 생긴다.

차례로 빌드, desktop(기본 프로파일, `127.0.0.1:18580`), desktop 의 다른 포트, server(`0.0.0.0:8080`) 다. 세 실행을 함께 돌리려면 데이터 디렉터리를 서로 다르게 준다(위 「런타임 하나」).

```sh
bash scripts/gate/build.sh
(set -a; . deploy/.env; set +a; java -jar runtime/app/build/libs/queryhub-runtime.jar)
(set -a; . deploy/.env; set +a; java -jar runtime/app/build/libs/queryhub-runtime.jar --server.port=18581)
(set -a; . deploy/.env; set +a; SPRING_PROFILES_ACTIVE=server java -jar runtime/app/build/libs/queryhub-runtime.jar)
```

- 프로파일을 지정하지 않으면 `desktop` 이다. 실수로 프로파일 없이 실행해도 루프백에만 열린다. 바인딩 주소의 공통 기본값(`application.yml`)도 `127.0.0.1` 이고, 모든 인터페이스로 넓히는 것은 `server` 프로파일뿐이다.
- `desktop` 과 `server` 를 함께 켜거나, 다른 프로파일만 켜서 둘 다 없으면 기동 단계에서 포트를 열기 전에 실패한다. `spring.main.lazy-initialization=true` 여도 마찬가지다(모드 검사 빈은 `@Lazy(false)`).
- 런타임은 데이터 디렉터리 없이는 포트를 열기 전에 기동을 거부한다(위 「데이터 디렉터리」).
- 런타임은 관리자 토큰 없이는 포트를 열기 전에 기동을 거부한다. 아래 「관리자 토큰」을 본다. `server` 프로파일은 모든 인터페이스에 바인딩하고 다른 호스트의 관리 요청도 받으므로(토큰은 필요하다) 신뢰할 수 있는 네트워크에서만 실행하고 토큰을 비밀 값으로 다룬다. API Key 는 Phase 4 다([SECURITY.md](SECURITY.md)).
- P1-04 부터 런타임은 마스터 키(`QUERYHUB_MASTER_KEY` 나 `QUERYHUB_MASTER_KEY_FILE`) 없이도 포트를 열기 전에 기동을 거부한다. 관리자 토큰과 다른 값이어야 한다. 아래 「마스터 키」를 본다.
- 손으로 실행한 `java -jar` 에는 `QUERYHUB_DESKTOP_PARENT_PID` 가 없으므로 부모 감시가 동작하지 않는다([ADR-014](ADR/ADR-014-runtime-parent-watch.md)).

## 관리자 토큰 (구현됨, P1-03, 2698115)

관리 API(`/admin/**`)와 health 밖의 actuator 는 `Authorization: Bearer <토큰>` 을 요구한다([ADR-021](ADR/ADR-021-admin-token-bearer.md), [API_SPECIFICATION.md](API_SPECIFICATION.md)). 런타임을 직접 실행할 때(server, 손으로 실행한 desktop)는 다음 중 **하나만** 준다.

| 변수 | 설명 |
|---|---|
| `QUERYHUB_ADMIN_TOKEN` | 토큰 값. 32-512 자, RFC 6750 b64token 문자(영문자, 숫자, `-`, `.`, `_`, `~`, `+`, `/`, 끝에 `=` 여럿). 예: `openssl rand -hex 32` 의 출력(64 자) |
| `QUERYHUB_ADMIN_TOKEN_FILE` | 토큰 한 줄이 든 시크릿 파일의 경로. 4 KiB 이하. 끝의 LF 나 CRLF 하나는 지운다 |

- 둘 다 없거나 둘 다 있거나, 파일이 없거나 읽을 수 없거나 비었거나 4 KiB 를 넘거나, 형식이 틀리면 포트를 열기 전에 기동을 거부한다. 메시지는 변수 이름만 말하고 값을 출력하지 않는다.
- 명령행 인자(`--queryhub.admin.token=...`), `-D`, 그 두 곳으로 넘긴 `spring.application.json` 은 프로세스 목록에 보이므로 거부한다.
- 개발용: `deploy/.env` 에 `QUERYHUB_ADMIN_TOKEN=<openssl rand -hex 32 의 출력>` 한 줄을 더하면(`deploy/env.example` 의 주석 줄) 위 「런타임 실행」의 명령이 그대로 넘긴다. `deploy/.env` 는 git 이 무시한다.
- 서버: 시크릿 파일을 마운트하고 `QUERYHUB_ADMIN_TOKEN_FILE` 에 그 경로를 준다. Docker 이미지는 Phase 8 계획이다.
- server 프로파일의 Host 검사: `queryhub.admin.allowed-hosts` 에 이름(호스트 이름, IPv4, `[IPv6]`. 와일드카드와 포트는 받지 않는다)을 주면 그 Host 만 받는다. 비워 두면 검사하지 않는다. 기본 설정에서는 리버스 프록시의 `X-Forwarded-*` 를 읽지 않는다(`server.forward-headers-strategy: none`, `server.tomcat.remoteip.*` 없음). server 프로파일(`allow-remote=true`)은 이 설정을 막지 않는다. `allow-remote=false` 로 두면 두 설정과 Tomcat 파이프라인의 RemoteIpValve 를 기동에서 거부한다.
- 두 프로파일 모두 기동을 거부하는 설정은 아래 「기동 거부 설정」에 있다. 관리 체인이 보호하지 않는 경로·포트로 관리 API 나 actuator 를 옮기거나, 토큰이 로그에 남을 수 있는 설정이다.
- desktop 셸: 셸이 실행마다 토큰을 만들어 넘기므로 설정하지 않는다. 셸 자신의 환경에 있는 `QUERYHUB_ADMIN_TOKEN`, `QUERYHUB_ADMIN_TOKEN_FILE` 은 런타임에 넘어가지 않는다. desktop 프로파일의 JAR 을 손으로 실행하면 위 변수가 필요하고, 관리 요청은 루프백에서 `Host: localhost`·`127.0.0.1`·`[::1]` 로만 받는다.

### 기동 거부 설정 (P1-03, 두 프로파일)

아래 설정이 있으면 런타임은 포트를 열기 전에 기동을 거부하고 0 이 아닌 코드로 끝난다. 메시지는 속성 이름만 말하고 값은 옮기지 않는다.

| 설정 | 거부하는 경우 |
|---|---|
| `management.endpoints.web.base-path` | `/actuator` 가 아닌 값 |
| `management.server.port` | `server.port` 와 다른 포트 |
| `spring.mvc.servlet.path` | `/` 가 아닌 값 |
| `spring.security.filter.dispatcher-types` | Boot 기본 다섯 가지(request, error, async, forward, include) 중 하나라도 빠진 값 |
| `management.endpoints.web.path-mapping.*` | 값이 하나라도 있을 때 |
| `management.endpoint.health.probes.add-additional-paths` | `true` |
| `management.endpoint.health.group.*.additional-path` | 값이 있을 때 |
| `server.tomcat.accesslog.pattern` | `Authorization` 이 들어갈 때(대소문자 무관) |
| `spring.mvc.log-request-details` | `true` |
| `server.ssl.*` (SSL 번들 포함) | 내장 TLS 를 켤 때(속성이 하나라도 있고 `server.ssl.enabled=false` 가 아닐 때) |
| `server.forward-headers-strategy`, `server.tomcat.remoteip.*` | `queryhub.admin.allow-remote=false` 일 때 none 이 아니거나 값이 있을 때(server 프로파일의 기본 `allow-remote=true` 에서는 막지 않는다) |

- **TLS 는 앞단 리버스 프록시에서 끝낸다. 내장 `server.ssl` 은 기동 거부다.** 사람 결정 「받아들임 (권장)」(2026-11-22). 프록시와 런타임 사이는 평문 HTTP 이므로 같은 호스트나 신뢰할 수 있는 네트워크에 둔다. 런타임 자체의 HTTPS 는 [BACKLOG.md](BACKLOG.md) P103-7(Phase 8)이다.
- 속성 이름과 상관없이, 포트를 열기 전에 Tomcat 의 필터 매핑(`springSecurityFilterChain` 이 `/*` 와 dispatcher 다섯 가지로 매핑된 DelegatingFilterProxy 인지, 그 앞에 허용 목록 밖의 필터가 없는지)과, `allow-remote=false` 일 때 파이프라인의 valve 를 검사한다.
- **자기 점검:** 포트를 연 직후, readiness 가 200 이 되기 전에 런타임이 자기 포트로 토큰 없는 요청 여섯 개(관리 API, `/actuator/env`, `/actuator/beans`, health 세 경로)를 보낸다. 관리 체인의 401/403 이나 health 응답이 아니거나 health 경로가 5 초 안에 답하지 않으면(Metadata 저장소의 health 점검이 끝나지 않는 경우 등) 이유를 ERROR 로그에 남기고 기동을 멈춘다. 정리가 멈춰도 프로세스는 그 뒤 15 초쯤 안에 종료 코드 1 로 끝난다(`Runtime.halt`). 손으로 실행할 때 기동이 멈추면 로그의 `관리 체인 자기 점검` 줄을 먼저 본다.

## 마스터 키 (구현됨, P1-04, 037385b (2026-11-23))

런타임은 업무 DB 비밀번호를 암호화하는 256 비트 마스터 키가 있어야 기동한다(두 프로파일). 결정은 [ADR-023](ADR/ADR-023-master-key-delivery.md)에, 형식과 교체는 [ADR-024](ADR/ADR-024-secret-cipher-format-and-rotation.md)에 있다. 키는 16진수 64 자다. 키를 잃으면 그 키로 암호화한 비밀번호는 되살릴 수 없다.

### server 의 키

다음 중 **하나만** 준다.

| 변수 | 설명 |
|---|---|
| `QUERYHUB_MASTER_KEY` | 키 값. 16진수 64 자(대소문자 무관). `openssl rand -hex 32` 로 만든다 |
| `QUERYHUB_MASTER_KEY_FILE` | 키 한 줄이 든 시크릿 파일의 경로. 4096 바이트 이하. 끝의 LF 나 CRLF 하나는 지운다 |

```sh
umask 077
openssl rand -hex 32 > /secure/path/queryhub-master-key   # 경로는 예시다
QUERYHUB_MASTER_KEY_FILE=/secure/path/queryhub-master-key java -jar queryhub-runtime.jar --spring.profiles.active=server
```

다음 경우에는 포트를 열기 전에 기동을 거부한다. 메시지는 변수와 속성 이름만 말하고 값과 파일 경로는 말하지 않는다.

- 둘 다 없거나 둘 다 있다.
- 파일이 없거나, 일반 파일이 아니거나, 읽을 수 없거나, 비었거나, 4096 바이트를 넘는다.
- 값이 정확히 16진수 64 자가 아니거나, 32 바이트가 모두 같거나, 관리자 토큰과 같다(대소문자 무관).
- 키가 명령행 인자(`--queryhub.master-key=...`), `-D`, 또는 그 두 곳으로 넘긴 `spring.application.json` 에서 온다. 프로세스 목록에 보이기 때문이다.

개발용으로는 `deploy/.env` 에 `QUERYHUB_MASTER_KEY=<openssl rand -hex 32 의 출력>` 한 줄을 더한다(`deploy/env.example` 의 주석 줄). 관리자 토큰과 다른 값이어야 한다. 키 값은 그 키로 암호화한 저장소(`queryhub.db`)의 백업과 짝을 지어 함께 백업한다(위 「백업·복구」).

### desktop 의 키 파일

셸이 첫 실행에 키 파일을 만들고, 그다음부터는 시작할 때마다 읽기만 한다. 따로 설정할 것은 없다. 셸 자신의 환경에 있는 `QUERYHUB_MASTER_KEY` 와 `QUERYHUB_MASTER_KEY_FILE` 은 런타임에 넘어가지 않는다.

| OS | 키 파일 | 확인 |
|---|---|---|
| macOS | `~/Library/Application Support/com.queryhub.desktop/secrets/master.key` | 수동 확인 (P1-04 AC28) |
| Linux | `${XDG_DATA_HOME:-~/.local/share}/com.queryhub.desktop/secrets/master.key` | 미검증 (PLAT-1) |
| Windows | `%LOCALAPPDATA%\com.queryhub.desktop\secrets\master.key` (Roaming 이 아니다) | 미검증 (PLAT-1) |

- 형식은 소문자 16진수 64 자와 LF 다(`openssl rand -hex 32` 의 출력과 같다). unix 에서는 디렉터리가 0700, 파일이 0600 이고, 앱을 실행하는 사용자가 소유한다.
- 셸은 있는 파일을 덮어쓰거나, 지우거나, 권한을 바꾸지 않는다. 파일에 문제가 있으면 런타임을 띄우지 않고 `master_key_unavailable` 을 보인다. 메시지에는 경로와 고치는 명령(`chmod 600 '<경로>'`, `chmod 700 '<경로>'`, `chown <uid> '<경로>'`)이 있다. 파일 내용은 보이지 않는다.
- 형식이 틀린 파일(예: 첫 실행의 쓰기 도중 멈췄다)을 지워도 되는 것은, 런타임이 그 키로 한 번도 시작하지 않았을 때뿐이다. 그 키로 비밀번호를 저장했을 수 있으면 지우지 말고 백업에서 되살린다.

### 키의 백업과 복구

- 키 파일은 앱 로컬 데이터 디렉터리의 `secrets/` 에, 저장소는 그 옆의 `data/` 에 있다(P1-06). 그래서 앱 로컬 데이터 디렉터리를 백업하거나 복사하면(예: Time Machine) 키와 저장소가 함께 들어간다. 저장소와 키는 같은 시점에 함께 백업하고 함께 되살린다(위 「백업·복구」). 둘이 같은 백업에 있으면 그 백업을 읽는 사람은 암호화한 비밀번호를 풀 수 있으므로, 그 백업을 키와 같은 비밀 값으로 다룬다.
- 조직이 통제하는 곳(예: 사내 비밀 보관소)에 키 사본을 둔다. 키 파일을 잃으면 그 키로 암호화한 비밀번호는 되살릴 수 없다. 셸은 다음 시작에서 새 키를 만들고, 저장한 연결 비밀번호는 다시 입력해야 한다(연결 저장 태스크 이후).
- 되살리는 방법: 앱을 끄고, 백업한 키 파일을 같은 경로에 같은 권한(unix 는 파일 0600, 디렉터리 0700, 같은 소유자)으로 놓은 뒤 앱을 시작한다.

### 키 미리 배치 (사내 폐쇄망)

관리자는 첫 실행 전에 조직의 키 파일을 기본 경로에 둘 수 있다. 셸은 있는 파일을 그대로 쓴다. P1-06 부터 데스크톱마다 자기 저장소(`data/queryhub.db`)를 가진다. 한 데스크톱의 저장소를 다른 데스크톱으로 옮기면 키 파일도 함께 옮겨야 한다. 키가 다르면 그 저장소의 비밀번호를 풀지 못한다(`UNKNOWN_KEY`).

macOS 의 예다. 앱을 쓸 사용자로 실행한다. Linux 는 디렉터리를 `"${XDG_DATA_HOME:-$HOME/.local/share}/com.queryhub.desktop/secrets"` 로 바꾼다(미검증).

```sh
dir="$HOME/Library/Application Support/com.queryhub.desktop/secrets"
mkdir -p "$dir" && chmod 700 "$dir"
install -m 600 /path/to/organisation-master.key "$dir/master.key"
```

Windows(미검증)에서는 `%LOCALAPPDATA%\com.queryhub.desktop\secrets\master.key` 에 같은 형식으로 둔다. 셸은 권한을 확인하지 않으므로 사용자 프로필의 기본 ACL 을 넓히지 않는다. 다른 경로를 쓰게 하는 설정은 없다([BACKLOG.md](BACKLOG.md) P104-1).

### 키 교체

- server: 지금은 복호화 전용 이전 키를 넣는 방법이 없다(BACKLOG P104-4). 그래서 지금 `QUERYHUB_MASTER_KEY` 를 바꾸면 기존 암호문은 `UNKNOWN_KEY` 가 된다. 지금은 저장된 암호문이 없다.
- P104-4 가 생긴 뒤의 절차([ADR-024](ADR/ADR-024-secret-cipher-format-and-rotation.md) 결정 8):
  1. 새 키를 만든다.
  2. 새 키를 `QUERYHUB_MASTER_KEY` 로, 옛 키를 이전 키로 둔다.
  3. 재시작한다.
  4. 모든 행을 다시 암호화한다.
  5. 옛 kid 를 쓰는 행이 남지 않았는지 확인한다.
  6. 옛 키를 빼고 재시작한다.
- desktop: 키 파일 교체는 후속이다(P104-5). 그때까지 키 파일을 바꾸거나 지우지 않는다.

## Desktop 셸 (구현됨, P0-C 스파이크)

`desktop/` 은 Tauri 2.11 앱이다. 셸이 런타임을 자식 프로세스로 띄우는 방식, 상태와 버튼, 멈추는 방식은 [ARCHITECTURE.md](ARCHITECTURE.md)에 있다.

### 준비물 (desktop)

- Node 24, pnpm 11.9.0(`desktop/package.json` 의 `packageManager`), Rust 1.97 과 rustfmt·clippy, Xcode Command Line Tools(macOS).
- **JDK 17 이상**(런타임 실행용, [ADR-025](ADR/ADR-025-java-17-baseline.md)). 셸은 `<java> -version` 의 주 버전이 17 미만이면 `java_too_old` 로 거부한다(`desktop/src-tauri/src/runtime/java.rs` 의 `MIN_MAJOR`). 고르는 순서는 `QUERYHUB_DESKTOP_JAVA`, `JAVA_HOME/bin/java`, `PATH` 의 `java` 다. 정해진 JDK 를 쓰려면 `QUERYHUB_DESKTOP_JAVA` 에 그 `bin/java` 를 지정한다(예: macOS 의 Temurin 17 은 `/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java`). macOS 의 `/usr/bin/java` 스텁이 아니라 실제 JDK 의 `bin/java` 를 가리켜야 한다([BACKLOG.md](BACKLOG.md) P0C-C2-3).
- 런타임 JAR. 외부 DB 와 Docker 는 필요 없다(P1-06).

### 실행 (개발 모드)

```sh
sh runtime/gradlew -p runtime :app:bootJar              # 런타임 JAR (bash scripts/gate/build.sh 도 만든다)
export QUERYHUB_DESKTOP_JAVA="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java"   # JDK 17 이상의 bin/java
pnpm --dir desktop install
pnpm --dir desktop tauri dev
```

P1-06 부터 Metadata DB 변수와 `deploy/.env` 가 필요 없다. 셸이 데이터 디렉터리, 관리자 토큰, 마스터 키를 스스로 정해 런타임에 넘긴다.

`tauri dev` 가 Vite 개발 서버(`http://localhost:1420`, strictPort)를 띄우고 앱을 연다. 앱은 시작하자마자 런타임을 자동으로 시작한다(버튼을 누르지 않아도 된다). 디버그 빌드는 상태 전이와 관리자 토큰·마스터 키를 가린 런타임 출력을 셸의 stderr 에 찍는다.

### desktop 의 데이터 디렉터리

셸은 시작할 때마다 자식을 띄우기 전에 `<앱 로컬 데이터 디렉터리>/data` 를 준비해 `QUERYHUB_DATA_DIR` 로 넘긴다(`desktop/src-tauri/src/runtime/data_dir.rs`, 경로는 `lib.rs` 만 정한다). 마스터 키 디렉터리 `secrets/` 옆이다.

| OS | 데이터 디렉터리 | 확인 |
|---|---|---|
| macOS | `~/Library/Application Support/com.queryhub.desktop/data` | 수동 확인 (P1-06 AC33, macOS arm64, Queen): 환경변수 없이 두 번 시작해 `data/` 0700, `queryhub.db` 0600, 같은 inode 를 다시 쓴다 |
| Linux | `${XDG_DATA_HOME:-~/.local/share}/com.queryhub.desktop/data` | 미검증 (PLAT-1) |
| Windows | `%LOCALAPPDATA%\com.queryhub.desktop\data` (Roaming 이 아니다) | 미검증 (PLAT-1) |

- 규칙은 키 디렉터리와 같다: 절대 경로이고 UTF-8 이어야 한다. 없으면 만들고 unix 에서 0700 으로 둔다. 심볼릭 링크, 디렉터리가 아닌 것, 다른 소유자, 그룹·기타 권한 비트는 거부한다. 고치지 않고 `chmod 700 '<경로>'` 를 안내한다. Windows 는 권한을 확인하지 않는다(미검증).
- 문제가 있으면 런타임을 띄우지 않고 `data_dir_unavailable` 을 보인다. 메시지에는 경로와 고치는 명령이 있고 비밀 값은 없다. 사전 점검 순서는 java, JAR, 포트, 데이터 디렉터리, 마스터 키다. 데이터 디렉터리가 틀리면 첫 실행에도 키 파일을 만들지 않는다.
- 셸은 디렉터리만 준비한다. 그 안의 `queryhub.db`, `-wal`, `-shm`, `queryhub.lock`, `native/` 는 런타임이 만들고 확인한다(위 「데이터 디렉터리」의 규칙). 셸은 그 파일들을 만들거나, 읽거나, 지우지 않는다.
- 백업은 위 「백업·복구」를 따른다. 앱이 도는 동안 이 디렉터리를 복사한 사본(예: Time Machine)은 일관된 백업이 아닐 수 있다. 앱을 끝낸 뒤 `data/` 와 `secrets/` 를 함께 복사하거나 `sqlite3 … "VACUUM INTO …"` 를 쓴다.

### 환경변수 (desktop)

코드: `desktop/src-tauri/src/runtime/launch.rs` 의 `JAVA_VAR`, `JAR_VAR`, `PARENT_PID_VAR`, `DATA_DIR_VAR`, `SQLITE_NATIVE_DIR_VAR`, `ADMIN_TOKEN_VAR`, `MASTER_KEY_VAR`. 토큰은 `token.rs`, 키 파일은 `master_key.rs`, 데이터 디렉터리는 `data_dir.rs`.

| 변수 | 읽는 쪽 | 설명 |
|---|---|---|
| `QUERYHUB_DESKTOP_JAVA` | 셸 | java 실행 파일 경로. 없으면 `JAVA_HOME/bin/java`, 그다음 `PATH` 의 `java`. 지정했는데 실행할 수 없으면 다른 곳으로 넘어가지 않고 `java_not_found` |
| `QUERYHUB_DESKTOP_RUNTIME_JAR` | 셸 | 런타임 JAR 경로. 없으면 디버그 빌드는 저장소의 `runtime/app/build/libs/queryhub-runtime.jar`, 릴리스 빌드는 `jar_not_found`(JAR 묶기는 Phase 8 계획) |
| `QUERYHUB_DATA_DIR` | 셸 → 런타임 | 셸이 준비한 데이터 디렉터리(위 표)의 절대 경로. 비밀 값이 아니다. 셸 자신의 환경에 있는 `QUERYHUB_DATA_DIR`, `QUERYHUB_SQLITE_NATIVE_DIR` 은 넘기지 않는다(P1-06). 손으로 설정하지 않는다 |
| `QUERYHUB_DESKTOP_PARENT_PID` | 셸 → 런타임 | 셸의 PID. 런타임의 부모 감시가 읽는다([ADR-014](ADR/ADR-014-runtime-parent-watch.md)). 손으로 설정하지 않는다 |
| `QUERYHUB_ADMIN_TOKEN` | 셸 → 런타임 | 셸이 실행마다 OS 난수 생성기로 만든 64 자 16진수 관리자 토큰. 환경변수로만 넘긴다(명령행 인자에 넣지 않는다). UI 는 Tauri 커맨드 `admin_token` 으로 받는다. 셸 자신의 환경에 있는 `QUERYHUB_ADMIN_TOKEN`·`QUERYHUB_ADMIN_TOKEN_FILE` 은 넘기지 않는다. 손으로 설정하지 않는다. Windows·Linux 는 미검증(PLAT-1) |
| `QUERYHUB_MASTER_KEY` | 셸 → 런타임 | 셸이 키 파일(위 「마스터 키」)에서 시작마다 읽은 값. 환경변수로만 넘긴다(명령행 인자에 넣지 않는다). 셸 자신의 환경에 있는 `QUERYHUB_MASTER_KEY`, `QUERYHUB_MASTER_KEY_FILE`, `QUERYHUB_MASTER_KEY_PREVIOUS`, `QUERYHUB_MASTER_KEY_PREVIOUS_FILE` 은 넘기지 않는다 |

- 셸은 허용 목록의 변수만 자식 런타임에 물려준다. 목록과 이유는 [SECURITY.md](SECURITY.md)의 「Desktop 셸의 자식 환경」에 있다([ADR-013](ADR/ADR-013-desktop-child-env-allowlist.md)).
- P1-05 까지 셸이 읽어 넘기던 `QUERYHUB_METADATA_DB_*` 와 실패 `metadata_settings_missing` 은 P1-06 에서 없어졌다. 셸의 환경에 그 변수가 있어도 런타임에 넘어가지 않는다. 그래서 Finder 에서 연 앱도 환경변수 없이 시작한다.
- 개발 도구(예: Claude Code)의 셸에서 앱을 시작하면 그 셸의 환경(세션 변수 포함)을 물려받는다. 셸이 허용 목록 밖의 변수를 런타임에 넘기지 않으므로 런타임에는 영향이 없지만, 깨끗한 환경에서 시작하려면 `env -i` 를 쓴다.

```sh
env -i HOME="$HOME" USER="$USER" PATH="$PATH" QUERYHUB_DESKTOP_JAVA="$QUERYHUB_DESKTOP_JAVA" \
  pnpm --dir desktop tauri dev
```

### 서명하지 않은 번들 확인 (macOS)

```sh
pnpm --dir desktop tauri build --debug --bundles app --no-sign
```

결과는 `desktop/src-tauri/target/debug/bundle/macos/QueryHub.app` 이다(git 이 무시한다).

번들 안의 실행 파일(`QueryHub.app/Contents/MacOS/`)을 위 「실행 (개발 모드)」와 같은 환경에서 실행한다. 번들은 JAR 을 담지 않는다. 디버그 번들은 저장소의 JAR 경로를 기본값으로 쓰고, 다른 JAR 은 `QUERYHUB_DESKTOP_RUNTIME_JAR` 로 지정한다. `desktop/src-tauri/target` 은 작업 트리마다 수 GB 다.

## 계획 (Phase 8)

- **Desktop 설치본:** JRE 묶기(jlink), JAR 묶기(`bundle.resources`), dmg·설치 프로그램, 코드 서명과 공증. `bundle.externalBin` 사이드카는 쓰지 않는다([ADR-001](ADR/ADR-001-jvm-child-process.md)).
- **Server:** Docker 이미지와 `Dockerfile`(설계 §3 의 `deploy/`), 운영 프로파일(시크릿 파일 입력은 구현됐다: 관리자 토큰의 `QUERYHUB_ADMIN_TOKEN_FILE` 은 P1-03, 마스터 키의 `QUERYHUB_MASTER_KEY_FILE` 은 P1-04).
- **플랫폼:** Windows·Linux 의 빌드와 실행은 **미검증**이다. CI 워크플로(`.github/workflows/ci.yml`, 정의됨·미실행)의 첫 실행과 Phase 8 에서 확인한다([BACKLOG.md](BACKLOG.md) CI-1, PLAT-1). Windows 의 `Path` 대소문자 처리(P0C-C2-4)는 P0-D 에서 고쳤지만 Windows 에서 실행해 보지 않았다.
