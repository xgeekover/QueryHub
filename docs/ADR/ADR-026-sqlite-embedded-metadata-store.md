# ADR-026 Metadata 저장소는 두 모드 모두 내장 SQLite 다 (ADR-009 를 대체)

- 상태: 승인됨
- 날짜: 2026-11-23
- 구현: 구현됨 (P1-06, 39c90e1 (2026-11-23)). P1-07 의 보강(아래 결정 4, 5, 8, 9, 10 의 P1-07 부분, P1-06 리뷰 N1–N7)은 커밋 전이다. 실행해 본 플랫폼은 macOS arm64(로컬, sqlite-jdbc 의 Mac/aarch64 네이티브)와 Linux x86_64(CI 의 `gates (ubuntu-24.04)` 작업, 2026-11-23, P1-06 코드)다. 다른 네이티브 18개와 Windows 는 미검증(PLAT-1)

## 맥락

- 명세 3절은 「Metadata Database: MariaDB, Flyway」를 적는다. 2026-11-15 의 사용자 결정 D2(「2. MariaDB로 단일로 진행해줘.」, [ADR-009](ADR-009-mariadb-metadata-db.md))는 두 모드 모두 MariaDB 를 쓰고 내장 DB(H2) 제안을 반려했다. 그래서 P0-B 부터 P1-05 까지 런타임은 `QUERYHUB_METADATA_DB_*` 환경변수로 외부 MariaDB 에 접속했고, desktop 셸은 그 변수가 없으면 `metadata_settings_missing` 으로 런타임을 띄우지 않았다(Finder 에서 연 앱이 그랬다, [ADR-017](ADR-017-desktop-metadata-settings-from-env.md)).
- 2026-11-23 에 사용자가 Metadata DB 가 꼭 필요한지 묻고 내부 DB 나 설정 파일로 동작해야 한다고 적었다(아래 근거). 사용자는 QueryHub 를 닫힌 사내망에서 쓴다.
- Queen 은 선택지를 냈다: H2 내장(Queen 의 권장), SQLite 내장, 설정 파일만, MariaDB 유지. 따로 MariaDB 를 server 전용 metadata 선택지로 남길지도 물었고, 남기지 않고 여러 서버가 저장소 하나를 함께 써야 할 때 다시 더하자고 권했다. 사용자는 SQLite 내장을 골랐고 MariaDB 에 대해서는 권장한 방식을 골랐다.
- Queen 이 H2 를 권할 때 든 SQLite 의 약점은 셋이다. (1) xerial sqlite-jdbc 는 실행할 때 네이티브 라이브러리를 기본으로 `java.io.tmpdir` 에 풀어 불러온다. `noexec` 로 마운트한 Linux 서버나 Windows 사내 백신에서 막힐 수 있고, 흔한 OS·아키텍처의 네이티브만 들어 있다. (2) SQLite 는 한 번에 쓰기 하나만 한다. server 모드의 쓰기는 줄을 선다. (3) 파일은 로컬 디스크에 있어야 한다. 네트워크 파일 시스템은 안전하지 않다.
- 다음 태스크들(DB 연결 CRUD, API Key)은 이 저장소에 연결 정보, 암호화한 비밀번호, API Key 해시를 둔다. 트랜잭션과 제약이 필요하므로 설정 파일만으로는 부족하다.

## 결정

1. **저장소.** Metadata 저장소는 Desktop Mode 와 Server Mode 모두 내장 SQLite 하나다. 드라이버는 `org.xerial:sqlite-jdbc` 3.53.4.0 이고 Spring Boot 4.1.2 BOM 이 버전을 관리한다(`runtime/gradle/libs.versions.toml` 의 `sqlite-jdbc` 항목에 버전이 없고 덮어쓰지 않는다). `runtime/adapter-metadata` 의 `runtimeOnly` 이고, 운영 코드는 `org.sqlite` 클래스를 참조하지 않는다(ArchUnit R6). 스키마는 Flyway 로 관리한다. `flyway-core` 12.4.0 이 SQLite 지원을 담고 있으므로 Flyway 모듈은 더하지 않았다.
2. **MariaDB 는 metadata 저장소 선택지로 남기지 않는다** (사용자 항목 3). `flyway-mysql`, `mariadb-java-client`, Testcontainers 를 모든 main·integrationTest 클래스패스에서 뺐다. MariaDB 는 업무 DB 로서 계속 지원 계획이다(DB Provider 태스크, [DATABASE_SUPPORT.md](../DATABASE_SUPPORT.md)). `runtime/build.gradle` 의 `ext['mariadb.version']` 과 카탈로그의 MariaDB·Testcontainers 별칭은 Provider 태스크가 쓰도록 주석을 달아 남겼다. 어떤 클래스패스에도 아무것도 넣지 않는다([ADR-018](ADR-018-mariadb-connector-j-override.md)). 여러 서버가 저장소 하나를 함께 쓰는 것과 MariaDB metadata 선택지는 [BACKLOG](../BACKLOG.md) P106-1 이다.
3. **데이터 디렉터리.** 설정 `queryhub.data-dir`(환경변수 `QUERYHUB_DATA_DIR`)은 두 프로파일 모두 필수이고 기본값이 없다. 환경변수, 외부 설정 파일(`spring.config.additional-location` 이나 작업 디렉터리의 `./config/application.yml`), 명령행 인자 어느 것으로 줘도 된다(비밀 값이 아니다). 사용자가 말한 「설정파일 기반」은 이 시작 설정이다. 값은 `?` 가 없는 절대 경로여야 한다(sqlite-jdbc 는 파일 이름의 `?` 뒤를 URL 옵션으로 읽는다). 배치:
   - `<data-dir>/queryhub.db` (그리고 SQLite 가 만드는 `queryhub.db-wal`, `queryhub.db-shm`)
   - `<data-dir>/queryhub.lock`
   - `<data-dir>/native/` — sqlite-jdbc 가 네이티브 라이브러리를 푸는 곳. `queryhub.sqlite.native-dir`(환경변수 `QUERYHUB_SQLITE_NATIVE_DIR`)로 옮길 수 있다.
4. **디렉터리와 파일 규칙** (desktop 셸의 마스터 키 규칙과 같다, [ADR-023](ADR-023-master-key-delivery.md)). 없는 데이터 디렉터리와 네이티브 디렉터리는 만들고 unix 에서 0700 으로 둔다. 있는 디렉터리는 심볼릭 링크가 아니고, 디렉터리이고, 런타임을 실행한 사용자가 소유하고, 그룹·기타 권한 비트가 없고, 쓸 수 있어야 한다. `queryhub.db` 가 없으면 런타임이 어떤 JDBC 연결보다 먼저 `CREATE_NEW` 와 0600 으로 빈 파일을 만든다(SQLite 가 umask 기본값으로 만들지 않게 한다). 있는 `queryhub.db`, `-wal`, `-shm`, `queryhub.lock` 은 심볼릭 링크가 아닌 일반 파일이고, 같은 사용자 소유이고, 그룹·기타 비트가 없어야 한다. P1-07 부터 `queryhub.db-journal` 도 같은 파일 규칙을 연결 전에 거치고(리뷰 N2), unix 에서는 파일에 소유자 읽기·쓰기 비트가 있어야 한다(0400 인 `-shm`·`-wal`·DB 는 SQLite 가 읽기 전용으로 열어 쓰기마다 실패하므로 `chmod 600` 을 안내하고 거부한다, 리뷰 N4). 없거나 빈 DB 옆에 데이터가 든 `-wal`·`-journal` 이 있으면 만들지 않고 거부한다. 느슨한 권한은 저절로 고치지 않고 메시지가 `chmod 700`·`chmod 600` 명령을 안내한다. Windows 는 권한과 소유자를 확인하지 않고(사용자 프로필의 ACL 에만 기댄다) 쓸 수 없는 파일만 거부한다(미검증 PLAT-1).
5. **로컬 디스크만.** 데이터 디렉터리의 `FileStore.type()` 이 알려진 네트워크 형식(nfs, nfs4, cifs, smbfs, smb2, smb3, afpfs, webdav, davfs, fuse.sshfs, 9p, ceph, glusterfs, fuse.glusterfs. 대소문자 무시)이면 거부한다. 모르는 형식은 받는다. 그래서 이 검사는 최선 노력이고, 문서는 로컬 디스크만 쓰라고 적는다. Windows UNC 경로(`\\server\share\...`)는 경로의 뿌리로 거부한다(P1-06 리뷰 R4). P1-07 부터는 아무것도 만들기 전에 데이터·네이티브 디렉터리의 가장 가까운 있는 조상의 실제 경로(`toRealPath`)의 뿌리도 보므로, UNC 공유를 가리키는 심볼릭 링크나 junction 조상도 거부한다. 실제 경로를 읽지 못하면(예: 깨진 링크) 거부한다(실패 닫힘, 리뷰 N6). 매핑한 네트워크 드라이브 문자(예: `Z:`)는 잡지 못한다. Windows 에서는 실행하지 않았다(주입한 뿌리로만 시험, PLAT-1).
6. **데이터 디렉터리 하나에 런타임 하나.** 런타임은 살아 있는 동안 `<data-dir>/queryhub.lock`(0600)에 `FileChannel.tryLock` 배타 잠금을 잡고, 컨텍스트가 닫힐 때 푼다. 다른 프로세스나 같은 JVM 이 잡고 있으면 데이터 디렉터리와 잠금 파일을 말하고 기동을 거부한다. 잠금 파일은 지우지 않는다. 프로세스가 죽으면 OS 가 잠금을 푼다.
7. **네이티브 라이브러리.** 첫 SQLite 연결 전에 네이티브 디렉터리를 4 의 규칙으로 준비하고 JVM 시스템 속성 `org.sqlite.tmpdir` 을 그 디렉터리로 정한다. sqlite-jdbc 는 `java.io.tmpdir`(여러 사용자가 함께 쓰고, 서버에서는 자주 `noexec` 이고, 임시 파일 정리가 지운다) 대신 여기에 푼다. 불러오기에 실패하면(원인 사슬의 `UnsatisfiedLinkError` 나 sqlite-jdbc 의 `NativeLibraryNotFoundException`) 메시지가 `QUERYHUB_SQLITE_NATIVE_DIR`(`queryhub.sqlite.native-dir`), `os.name`/`os.arch`, `noexec` 와 Windows 백신이라는 원인 후보를 말한다. 네이티브는 실행 JAR 안의 `BOOT-INF/lib/sqlite-jdbc-3.53.4.0.jar` 에 들어 있으므로 닫힌 사내망의 장비도 따로 받을 것이 없다.
8. **파일 내용 검사.** 비어 있지 않은 `queryhub.db` 의 첫 16 바이트가 `SQLite format 3` 과 NUL 이 아니면 열지 않고 「SQLite DB 파일이 아니다」로 거부한다. 읽기 전용 연결(sqlite-jdbc 연결 속성 `open_mode=1`)로 연 뒤 `PRAGMA quick_check` 가 `ok` 가 아니면 「손상」으로 거부한다. 되돌려야 하는 롤백 저널(`queryhub.db-journal`)이 있으면 거부한다. 런타임도 새 DB 나 롤백 저널 모드로 되살린 DB(예: `VACUUM INTO` 사본)를 처음 WAL 로 바꿀 때 롤백 저널을 한 번 쓰므로, P1-07 부터 메시지는 그때 멈췄거나 다른 도구가 남긴 저널이라고 말한다(리뷰 N1). 저널 머리글(28 바이트, 읽기 전용, 링크를 따르지 않는다)의 원래 페이지 수가 0 이면 데이터가 없는 새 저장소이므로, 런타임을 멈추고 `queryhub.db`·`queryhub.db-journal`(과 있으면 `-wal`·`-shm`)을 함께 데이터 디렉터리 밖으로 옮긴 뒤 다시 시작하라고 안내한다. 아니면 두 파일을 복사해 둔 뒤 `sqlite3 "<db>" "PRAGMA quick_check"` 로 열어 SQLite 가 되돌리게 하거나 백업에서 되살리라고 안내한다. 거부한 파일은 고치거나, 자르거나, 이름을 바꾸거나, 지우지 않는다. 거부한 `queryhub.db`·`-wal`·`-journal` 은 바뀌지 않는다. 데이터가 없는 `-shm` 과 빈 `-wal` 은 생기거나 고쳐 쓰일 수 있다(P1-06 리뷰 R1).
9. **연결 설정.** 런타임이 URL 을 만든다: `jdbc:sqlite:<절대 경로>/queryhub.db?journal_mode=WAL&synchronous=FULL&foreign_keys=true&busy_timeout=5000&transaction_mode=IMMEDIATE&open_mode=2`. P1-07 부터 끝의 `open_mode=2`(읽기·쓰기, 만들기 없음)는 `URL_OPTIONS`(PRAGMA 목록) 밖에서 붙인다. 파일은 런타임이 늘 먼저 만들므로, 실행 중에 `queryhub.db` 가 사라지면 다음 새 연결이 빈 DB 를 조용히 만들지 않고 실패한다(리뷰 N5). Hikari 풀 `queryhub-metadata` 는 `maximum-pool-size: 4`(운영자가 바꿀 수 있다), `connection-timeout: 10000` 이다. 기동 검사는 부트스트랩 연결과, Flyway 전과 뒤에 최대 풀 크기만큼 동시에 빌린 풀 연결에서 연 파일이 검사한 `queryhub.db` 인지(`PRAGMA database_list`, 장치와 inode)와 `journal_mode=wal`, `foreign_keys=1`, `synchronous=2`(FULL), `busy_timeout=5000` 을 확인하고, 다르면 기동을 거부한다. `spring.datasource.hikari.data-source-properties` 에 이 다섯 설정 이름이나 `open_mode`(sqlite-jdbc 는 연결 속성을 URL 의 같은 이름보다 앞세운다, P1-07)가 있으면 이름만으로 거부한다(`connection-init-sql` 로 바꾼 값은 PRAGMA 확인이 잡는다). `synchronous=FULL` 은 metadata 쓰기가 드물고 저장소가 암호화한 자격 증명을 담게 되므로 쓰기 지연보다 전원 장애 때의 내구성을 골랐다.
10. **기동 순서.** 각 단계는 웹 서버가 포트를 열기 전에 실패 닫힘으로 멈추고(종료 코드 1), 메시지는 한국어로 설정 이름·경로·고치는 법을 말하며 비밀 값을 담지 않는다: 데이터 디렉터리 설정 → 데이터·네이티브 디렉터리의 Windows UNC 경로 거부(경로의 뿌리와, P1-07 부터 가장 가까운 있는 조상의 실제 경로의 뿌리. 아무것도 만들기 전) → 디렉터리 규칙 → 네트워크 파일 시스템 → 잠금(같은 JVM 의 두 번째 시도는 잠금 파일을 열기 전에 JVM 전체 표로 거부) → 네이티브 디렉터리와 `org.sqlite.tmpdir` → DB 파일 확인(`-journal` 포함, 없거나 빈 DB 옆에 데이터가 든 `-wal`·`-journal` 이 있으면 연결 전에 거부)·만들기와 머리글 → **읽기 전용** 내용 검사 연결(네이티브 불러오기, `quick_check`, 되돌려야 하는 롤백 저널이 있으면 거부) → 부트스트랩 연결(읽기·쓰기)의 PRAGMA → 풀 연결의 주 파일(`PRAGMA database_list`)과 PRAGMA → Flyway 마이그레이션(메인 풀에 고정, [ADR-012](ADR-012-flyway-connection-pin.md)) → 풀 연결의 주 파일과 PRAGMA 다시 확인. 앞의 단계들은 `JdbcConnectionDetails` 빈을 만들 때 돌므로 DataSource 나 Flyway 빈보다 먼저다. P1-03 의 관리 체인 기동 검사와 P1-04 의 마스터 키 규칙은 그대로다.
11. **마이그레이션.** V1 `app_setting` 을 SQLite DDL 로 그 자리에서 다시 썼다(SQLite 저장소는 아직 어디에도 없었으므로 Flyway 체크섬이 깨질 곳이 없다): STRICT 테이블, `setting_key TEXT COLLATE BINARY`(1-100 자), UTC 고정 폭 문자열 시각. 저장소 구현은 `INSERT … ON CONFLICT … DO UPDATE` 로 쓴다. 테이블 정의는 [ERD.md](../ERD.md)에 있다.
12. **지운 것.** `QUERYHUB_METADATA_DB_*` 변수와 검사(`MetadataDatabaseProperties`), [ADR-011](ADR-011-metadata-secrets-env-only.md)의 Metadata 비밀번호·Hikari 비밀 키 거부(프로세스에 보이는 출처 검사 `ProcessVisibleSources` 는 관리자 토큰과 마스터 키를 위해 남는다), MariaDB 통합 테스트 지원과 MariaDB 전용 스키마 시험, `deploy/compose.dev.yml`. desktop 셸의 `QUERYHUB_METADATA_DB_*` 전달과 `metadata_settings_missing`([ADR-017](ADR-017-desktop-metadata-settings-from-env.md)).
13. **desktop 셸.** 셸은 `<앱 로컬 데이터 디렉터리>/data` 를 키 디렉터리 `secrets/` 옆에 정하고, 자식을 띄우기 전에 키 디렉터리와 같은 규칙(만들면 0700, 심볼릭 링크·디렉터리가 아님·다른 소유자·그룹·기타 비트 거부, `chmod` 안내, Windows 는 확인하지 않음)으로 준비해 `QUERYHUB_DATA_DIR` 로 넘긴다([ADR-013](ADR-013-desktop-child-env-allowlist.md) 허용 목록). 문제가 있으면 `data_dir_unavailable` 로 실패하고 자식을 띄우지 않는다. 셸은 DB 파일을 만들거나 읽지 않는다. 셸 자신의 환경에 있는 `QUERYHUB_DATA_DIR`, `QUERYHUB_SQLITE_NATIVE_DIR`, `QUERYHUB_METADATA_DB_*` 는 넘기지 않는다. 그래서 앱은 환경변수 없이 시작한다.
14. **게이트.** `scripts/gate/integration.sh` 는 Docker 사전 점검을 그대로 둔다. P1-06 의 통합 테스트는 Docker 를 쓰지 않았고, P1-07 의 업무 DB Provider 통합 테스트가 Testcontainers 로 다시 쓴다([ADR-027](ADR-027-database-provider.md)). 그래서 통합 게이트에는 실행 중인 Docker 가 필요하다.

## SQLite 의 약점에 대한 대응

| 약점 (H2 를 권할 때의 반론) | 대응 | 확인한 것과 미검증 |
|---|---|---|
| 네이티브 라이브러리를 `java.io.tmpdir` 에 푼다. `noexec`, Windows 백신, 지원하지 않는 OS·아키텍처 | 네이티브는 실행 JAR 안에 있다(20개, [DATABASE_SUPPORT.md](../DATABASE_SUPPORT.md)). `org.sqlite.tmpdir` 을 0700 의 `<data-dir>/native` 로 정해 `/tmp` 에 풀지 않는다. 막히면 `QUERYHUB_SQLITE_NATIVE_DIR` 로 실행이 허용된 로컬 디렉터리를 준다. 불러오지 못하면 그 변수와 `os.name`/`os.arch` 를 말하고 기동을 거부한다 | 실행 JAR 자식 프로세스가 `-Djava.io.tmpdir=<빈 디렉터리>` 에서도 `<data-dir>/native` 에 풀고 tmpdir 에는 풀지 않는 것, `QUERYHUB_SQLITE_NATIVE_DIR` 로 옮겨지는 것을 macOS arm64 에서 확인했다. 실패 메시지는 분류기 단위 시험만 있다. `noexec` 마운트와 Windows 백신은 재현하지 않았다. Mac/aarch64 밖의 네이티브 19개는 미검증(PLAT-1) |
| 쓰기는 한 번에 하나 | WAL(읽기는 쓰기와 함께 돈다), `busy_timeout=5000`, `transaction_mode=IMMEDIATE`(트랜잭션을 시작할 때 쓰기 잠금을 잡아 읽기→쓰기 승격 교착을 피한다), 풀 4 | 8 스레드 × 50 쓰기가 SQLITE_BUSY 없이 성공, 1 초 동안 잡힌 쓰기 잠금 뒤의 쓰기는 기다렸다 성공, 5 초 넘게 잡히면 5 초 뒤 `DataAccessException` 으로 실패하고 저장소는 그 뒤에도 쓸 수 있다(단위 시험, macOS arm64). 쓰기가 몰리는 server 부하는 실측하지 않았다 |
| 로컬 디스크에 있어야 한다 | 알려진 네트워크 파일 시스템 형식은 거부한다. 문서가 로컬 디스크만 쓰라고 적는다. 한 데이터 디렉터리에 런타임 하나(`queryhub.lock`) | 분류기 단위 시험만 있다. 실제 NFS·SMB·AFP·WebDAV 마운트는 시험하지 않았다. Windows UNC 경로는 경로의 뿌리로 거부한다(R4). UNC 를 가리키는 심볼릭 링크·junction 조상은 P1-07 부터 실제 경로로 거부한다(주입한 뿌리로만 시험). 매핑한 네트워크 드라이브 문자와 모르는 형식은 잡지 못한다(P106-4) |

## 결과

- 좋아지는 점: 외부 DB 와 Docker 없이 두 모드가 돈다. desktop 앱은 환경변수 없이 시작한다(Queen 의 수동 확인, macOS arm64, [TEST_PLAN.md](../TEST_PLAN.md)). 닫힌 사내망 장비는 실행 JAR 하나만 받으면 된다(네이티브가 그 안에 있다). 실행 JAR 은 sqlite-jdbc(12,002,370 바이트)가 들어오고 MariaDB 드라이버와 `flyway-mysql` 이 빠져 약 11.2 MB 커졌다.
- 치르는 값:
  - 저장소는 호스트 하나의 파일이다. 여러 서버가 metadata 를 함께 쓸 수 없다(P106-1).
  - 백업은 운영자의 일이다. DB 파일과 마스터 키를 함께 백업해야 하고, 쓰고 있는 WAL 저장소의 파일 하나만 복사하면 안 된다. 절차는 [DEPLOYMENT.md](../DEPLOYMENT.md)의 「백업·복구」에 있다. 내장 백업 명령은 없다(P106-2).
  - 네이티브 라이브러리에 기댄다. 네이티브가 없는 플랫폼에서는 기동하지 않는다.
  - `transaction_mode=IMMEDIATE` 에서는 읽기 전용 트랜잭션도 쓰기 잠금을 잡는다. 긴 트랜잭션은 다른 쓰기를 5 초 넘게 막으면 실패시킨다(P106-6, 연결 CRUD 태스크가 본다).
  - desktop 셸이 강제 종료되면 남은 런타임이 부모 감시에 걸려 끝날 때까지(약 5 초) 잠금을 잡고 있어, 그 사이의 재시작은 잠금 메시지로 실패한다(P106-7).
  - Windows 에서는 데이터 디렉터리와 파일의 권한을 확인하지 않는다(PLAT-1).
- 미검증: Mac/aarch64 와 Linux/x86_64(CI) 밖의 네이티브, Windows 실행(PLAT-1, CI 의 Windows 작업은 실패한다, CI-1), `noexec` 와 백신, 실제 네트워크 마운트, 닫힌 사내망에서의 실행.
- [ADR-009](ADR-009-mariadb-metadata-db.md)(Metadata DB 는 MariaDB), [ADR-011](ADR-011-metadata-secrets-env-only.md)(Metadata DB 비밀 값 규칙), [ADR-017](ADR-017-desktop-metadata-settings-from-env.md)(desktop 이 Metadata DB 설정을 셸 환경에서 읽는다)은 이 ADR 이 대체한다. [ADR-012](ADR-012-flyway-connection-pin.md)(Flyway 고정)와 [ADR-013](ADR-013-desktop-child-env-allowlist.md)(자식 환경 허용 목록)은 그대로이고 SQLite 에 맞춘 주를 달았다. [ADR-018](ADR-018-mariadb-connector-j-override.md)은 제안 그대로다. P1-06 에서는 MariaDB 드라이버가 어떤 클래스패스에도 없었고, P1-07 에서 Connector/J 3.5.11 이 업무 DB 드라이버로 다시 들어왔다([ADR-027](ADR-027-database-provider.md)).
- 남은 문제([BACKLOG](../BACKLOG.md)): P106-1–P106-7, PLAT-1, CI-1.

## 근거

- 사용자 요청, 2026-11-23: 「Java OpenJDK 17 환경에서 사용할 수 있어야 해. Metadata DB는 꼭 필요한가요? SQLLite 등의 내부DB나 설정파일 기반으로 동작할 수 있어야 할 것 같아요.」 이 가운데 P1-06 에 해당하는 부분은 「Metadata DB는 꼭 필요한가요? SQLLite 등의 내부DB나 설정파일 기반으로 동작할 수 있어야 할 것 같아요.」 다(앞 문장은 [ADR-025](ADR-025-java-17-baseline.md)).
- 사용자 결정, 2026-11-23 (Queen 의 선택지에 대한 답, 줄바꿈과 글자를 원문 그대로 둔다):

> 「1. 진행
> 2. SQLite 내장
> 3. 권장하는 반식으로 진행
> 4. 병합 및 승인 (키ID 방식은 나중에 도입)」

| 항목 | 이 ADR 에서의 뜻 |
|---|---|
| 「2. SQLite 내장」 | Metadata 저장소는 desktop·server 모두 내장 SQLite 다(결정 1). Queen 이 권한 H2 대신이다 |
| 「3. 권장하는 반식으로 진행」 | MariaDB 를 metadata 저장소 선택지로 남기지 않는다(결정 2). 여러 서버가 저장소를 함께 써야 할 때 다시 더한다(P106-1) |

- 「SQLLite」와 「반식」은 사용자가 적은 글자 그대로다(「반식」은 「방식」의 오타로 읽는다). 1·4 항목의 대응은 [REQUIREMENTS.md](../REQUIREMENTS.md) AR-2 에 있다.
- 이 결정은 사용자 결정 D2(2026-11-15, 「2. MariaDB로 단일로 진행해줘.」)를 바꾼다. 명세 3절의 「Metadata Database: MariaDB」와도 다르다([ARCHITECTURE.md](../ARCHITECTURE.md) 「설계와 다른 구현」 13).
- 저장소 선택(SQLite)과 MariaDB 를 남기지 않는 것은 사용자 결정이다. 파일 배치, 권한 규칙, 잠금, 네이티브 디렉터리, PRAGMA 와 풀 크기, 기동 순서, desktop 셸의 전달 방식은 P1-06 의 요구사항·계획 단계에서 Planner 와 Queen 이 정했다(T-20261123-103009-16f8).
