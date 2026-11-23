# ADR-027 업무 DB Provider: 필드로 만든 URL, 옵션 허용 목록, 연결마다 읽기 전용 풀

- 상태: 제안
- 날짜: 2026-11-23
- 구현: 구현됨 (P1-07, bb0cd8c (2026-11-23)). MariaDB 와 PostgreSQL 만 구현했다. Oracle 은 P1-10, MSSQL 은 P1-11 이다(계획). 실행해 본 곳은 macOS arm64 의 로컬 컨테이너(mariadb:12.3, postgres:16-alpine)와 CI 의 `gates (ubuntu-24.04)` 작업(Linux x86_64, 실행 45194878756 의 2회차, 2026-11-23)이다

## 맥락

- Phase 1 의 범위는 DB 연결이다(명세 11절 Phase 1, [ARCHITECTURE.md](../ARCHITECTURE.md) 「Phase 계획」). 업무 DB 는 Metadata 저장소와 다르다. Metadata 저장소는 내장 SQLite 이고([ADR-026](ADR-026-sqlite-embedded-metadata-store.md)) DataSource 빈 하나로 열린다.
- 사용자가 연결을 여러 개 등록하고 연결마다 시험하고 풀을 연다. 연결 정보는 다음 태스크(P1-08)부터 Metadata 저장소의 `database_connection` 에 저장한다. P1-07 은 저장 없이 설정 값, 연결 시험, 풀만 다룬다.
- JDBC 드라이버는 URL 과 연결 속성으로 많은 것을 바꾼다. 소켓 팩토리 클래스(`socketFactory`), 로거 파일(`loggerFile`), 인증 플러그인 클래스, 클라이언트 쪽 파일 읽기(MariaDB `allowLocalInfile`), 서버의 다른 호스트로의 이동(MariaDB `permitRedirect`), 서비스·비밀번호 파일(pgjdbc `service`, `.pgpass`)이 그 예다. 사용자가 준 원시 JDBC URL 을 거부 목록으로 거르면 이런 옵션을 놓친다(P1-07 계획의 「검토한 대안」).
- 드라이버의 오류 메시지는 비밀번호, 내부 호스트 이름, SQL 을 담을 수 있다.

## 결정

1. **포트.** `com.queryhub.application.connection` 에 포트 `DatabaseProvider`(종류 하나의 연결 시험)와 `ConnectionPools`(연결 id 마다 풀 하나)를 둔다. 둘 다 JDBC 형식을 쓰지 않는다(ArchUnit R3, R4). `TestConnectionUseCase` 는 종류로 Provider 를 고르고, Provider 가 없는 종류(지금은 ORACLE, MSSQL)는 `UNSUPPORTED_DATABASE_TYPE` 이다. 값 형식은 `com.queryhub.domain.connection` 에 있다: `DatabaseType`(4종), `ConnectionId`(`[A-Za-z0-9][A-Za-z0-9_-]{0,63}`), `ConnectionSettings`, `ConnectionPassword`, 결과 형식.
2. **URL 은 필드로 만든다.** 사용자는 JDBC URL 을 주지 않는다. `ConnectionSettings` 는 생성자에서 모든 필드를 허용 목록으로 검사한다: 호스트(호스트 이름 레이블, 점 네 개의 IPv4, 대괄호가 있거나 없는 IPv6 리터럴. 대괄호 없이 저장하고 zone id 는 거부), 포트 1–65535, 데이터베이스 이름(글자, 숫자, `_ $ - .`), 사용자 이름. 공통 코드 `JdbcUrls` 가 `<접두사>//<호스트>:<포트>/<DB>` 를 만들고 IPv6 는 대괄호로 감싼다. URL 에 질의 문자열은 없다. 사용자가 원시 URL 을 주는 정책은 정하지 않았다([BACKLOG](../BACKLOG.md) P107-1).
3. **옵션은 Provider 마다 허용 목록이다.** MariaDB 는 `{sslMode}`, PostgreSQL 은 `{sslmode}` 하나다. 목록 밖의 이름은 연결하기 전에 이름만으로 거부한다(`OPTION_REJECTED`, 메시지는 이름만 말하고 값은 말하지 않는다). 고정 속성, `user`, `password` 와 대소문자만 다른 이름은 Provider 가 실수로 허용 목록에 넣어도 거부한다(`ConnectionProperties`).
4. **고정 속성이 이긴다.** 고정 속성은 옵션 뒤에 쓴다.
   - MariaDB(Connector/J 3.5.11): `connectTimeout`·`socketTimeout`(밀리초), `allowLocalInfile=false`(LOCAL INFILE 로 클라이언트 파일을 읽지 못하게 한다, [ADR-018](ADR-018-mariadb-connector-j-override.md)), `readOnlyPropagatesToServer=true`(Connector/J 의 `setReadOnly(true)` 는 이 값이 true 일 때만 `SET SESSION TRANSACTION READ ONLY` 를 보낸다. 기본값 false 에서는 풀 연결의 INSERT 가 성공했다), `permitRedirect=false`(서버가 다른 호스트로 보내지 못하게 하는 심층 방어. 통합 시험은 없다, P107-7).
   - PostgreSQL(pgjdbc 42.7.14): `connectTimeout`·`socketTimeout`(초, 올림), `loginTimeout` = connectTimeout + 3 초, `readOnlyMode=always`(기본값 `transaction` 에서는 읽기 전용 풀 연결의 INSERT 가 성공했다. `always` 와 풀의 readOnly 로 SQLState 25006 으로 거부된다).
   - `loginTimeout` 이 아래 공통 마감보다 늦은 까닭: pgjdbc 의 로그인 제한이 먼저 끝나면 원인 없는 08001 을 던져 닿을 수 없는 서버와 구별되지 않는다. 그래서 공통 마감이 먼저 답한다.
5. **socketTimeout = connectTimeout.** 두 드라이버 모두 핸드셰이크의 읽기를 묶어, 응답하지 않는 서버에서 시험 스레드가 남지 않게 한다. 이 값은 연결 시험에는 맞지만 긴 업무 쿼리를 끊는다. 업무 쿼리를 실행하기 전(P1-12)에 쿼리 기준의 제한으로 다시 설계해야 한다(P107-5).
6. **`Driver.connect` 만 쓴다.** 연결은 `DriverConnectDataSource` 하나로만 만든다(연결 시험과 풀 모두). Provider 가 준 드라이버 객체의 `Driver.connect(url, properties)` 를 부르고 `DriverManager` 는 쓰지 않는다(운영 코드에 `DriverManager` 가 없다). 그래서 클래스패스의 다른 드라이버가 URL 을 가로채지 못한다. 비밀번호는 연결할 때만 `Properties` 에 넣고 바로 지운다. `ConnectionPassword` 는 값을 `char[]` 에만 두고 `toString` 이 고정 문자열이며 `destroy()` 가 배열을 0 으로 채운다. JDBC `Properties` 는 문자열 값만 받으므로 연결마다 짧게 사는 `String` 사본이 생기고 드라이버가 자기 사본을 가질 수 있다.
7. **엄격한 마감 connectTimeout + 2 초.** 연결과 확인 쿼리(`SELECT 1`)는 데몬 단일 스레드 실행기(`queryhub-conn-test-N`)에서 돈다. 호출자는 최대 connectTimeout + 2 초 기다리고, 넘으면 `TIMEOUT` 을 돌려주며 인터럽트로 취소하고 실행기를 닫는다. 늦게 도착한 연결은 확인 쿼리 없이 닫는다. 인터럽트를 무시하고 소켓 읽기에 묶인 드라이버는 드라이버가 돌아올 때까지(실제로는 위 socketTimeout) 데몬 스레드를 쥔다.
8. **풀은 연결마다 하나, 읽기 전용, Spring 빈이 아니다.** `HikariConnectionPools` 가 연결 id 마다 풀 `queryhub-conn-<id>` 를 연다: `maximumPoolSize` 는 설정 값, `readOnly=true`, MBean 없음, `minimumIdle=0` 과 `initializationFailTimeout=-1`(풀을 열어도 연결하지 않는다). 다시 열 때는 새 옵션을 먼저 검사하므로 거부된 다시 열기는 옛 풀을 그대로 둔다. `close`·`closeAll` 은 비밀번호 사본을 0 으로 채운다. 풀은 Spring 빈이 아니고 `DataSource` 도 아니다. 앱의 `DataSource` 빈은 Metadata SQLite 풀 하나뿐이다(`DatabaseProviderConfigurationTest`). 그래서 Boot 의 DataSource·Flyway 자동 구성과 [ADR-012](ADR-012-flyway-connection-pin.md) 의 고정에 닿지 않는다. 앱 컨텍스트가 닫히면 `closeAll` 이 모든 업무 풀을 닫는다. 기동 때는 아무 업무 DB 에도 연결하지 않는다.
9. **고정 한국어 메시지.** 결과(`ConnectionTestResult`)와 예외(`ConnectionFailedException`)는 실패 분류(`ConnectionFailureKind`)의 고정 메시지만 담는다. 드라이버 메시지, 원인, 다음 예외, 억눌린 예외는 옮기지 않는다. SQLState 는 5 글자 모양일 때만 남긴다. 로그 줄은 분류, SQLState, 벤더 코드, 예외 클래스 이름만 적는다. 분류는 SQLState, 오류 코드, 예외 종류로 정하고 메시지 글자를 읽지 않는다. 제품 이름과 버전은 제어 문자를 빼고 128 자로 자른다.
10. **MariaDB 1044.** MariaDB 는 모르는 데이터베이스를 권한 있는 사용자(root)에게는 1049(ER_BAD_DB_ERROR)로, 일반 사용자에게는 1044(ER_DBACCESS_DENIED_ERROR, SQLState 42000)로 알린다. 1044 는 있는 데이터베이스에 권한이 없을 때도 오고 서버는 둘을 구별해 주지 않는다. 그래서 MariaDB Provider 는 1049 와 1044 를 모두 `DATABASE_NOT_FOUND` 로 두고(원인 사슬 안에 있어도), 그 고정 메시지는 두 원인을 모두 말한다: 「데이터베이스를 찾을 수 없거나 이 사용자에게 접근 권한이 없습니다. 데이터베이스 이름과 사용자 권한을 확인하세요.」(Queen 결정, 2026-11-23). 구별하지 못하는 것 자체는 남는다(P107-10).
11. **기본 한도 (사람이 바꿀 수 있다).** run B 개발자가 정한 값이다: connectTimeout 1–120 초, maxPoolSize 1–20, 비밀번호 1–1,024 자, 옵션 32 개 이하, 옵션 값 512 자 이하. 호스트 레이블의 밑줄은 받고, 끝에 점이 있는 호스트, IPv6 zone id, 빈 데이터베이스 이름은 거부한다.
12. **모듈.** 공통 JDBC 코드는 `runtime/adapter-dbprovider`(`com.queryhub.adapter.dbprovider`)에, 드라이버는 `runtime/provider-mariadb`·`runtime/provider-postgresql`(`com.queryhub.adapter.dbprovider.provider.<db>`)에 둔다. Provider 는 `JdbcProviderSpi`(종류, 드라이버, URL, 고정 속성, 허용 목록, 확인 쿼리, `classify`)만 구현한다. 드라이버 클래스는 Provider 패키지만 쓴다(ArchUnit R6). 새 규칙 R11: Provider 끼리 서로 의존하지 않고, 공통 코드는 Provider 에 의존하지 않는다. 앱의 `DatabaseProviderConfiguration` 이 두 Provider 빈, `TestConnectionUseCase`, `HikariConnectionPools`(`destroyMethod="closeAll"`)를 만든다.
13. **통합 시험.** app 의 `integrationTest` 가 Testcontainers 2.0.5 로 로컬 arm64 이미지를 띄운다. 컨테이너 포트는 127.0.0.1 에만 묶는다(`docker inspect` 의 HostIp 가 `["127.0.0.1"]`, P0B-R6). 시험의 비밀번호는 실행마다 새 UUID 이고, 결과, 예외 사슬, TRACE 로그 어디에도 없는 것을 확인한다. 거부와 부정 시험은 닫힌 로컬 포트를 쓴다([TEST_PLAN.md](../TEST_PLAN.md)).

## 결과

- 좋아지는 점: 사용자가 준 문자열이 URL 구조나 드라이버 옵션이 되지 못한다. 쓰기는 풀 연결에서 서버가 거부한다(두 DB 모두 SQLState 25006, 통합 시험). 비밀번호와 드라이버 메시지가 결과, 예외, 로그에 나오지 않는다. DataSource 빈이 하나뿐이라 Metadata 저장소의 기동 검사와 Flyway 고정이 그대로다.
- 치르는 값:
  - TLS 모드 말고는 드라이버 옵션을 쓸 수 없다. 필요한 옵션은 Provider 의 허용 목록을 넓혀야 한다.
  - pgjdbc 의 `sslmode=verify-ca`·`verify-full` 은 기본 SSL 팩토리가 `~/.postgresql/root.crt` 와 클라이언트 키 파일을 읽는다(시험하지 않았다, P107-6).
  - socketTimeout 이 짧다(결정 5, P107-5).
  - 1044 의 두 원인을 구별하지 못한다(결정 10).
- 미검증: MariaDB 12.3.3 과 PostgreSQL 16 밖의 서버 버전(P107-8), `permitRedirect=false`(P107-7), PostgreSQL 의 응답 없는 서버 시간(2.0 초 또는 4.0 초, 원인 미확인, P107-9), Ryuk 컨테이너의 포트 바인딩(P107-11), Windows·Linux 실행(PLAT-1).
- 남은 문제([BACKLOG](../BACKLOG.md)): P107-1–P107-11, CI-2.

## 근거

- 업무 DB 4종과 Provider 모듈 배치는 승인된 설계(§3, §6)와 [DATABASE_SUPPORT.md](../DATABASE_SUPPORT.md)다.
- 사람의 결정 (2026-11-23): PostgreSQL 드라이버와 Testcontainers PostgreSQL 모듈만 내려받기 승인(그것만 받았다). Oracle 과 MSSQL 은 「Oracle만, MSSQL은 테스트 생략」: P1-10 이 Oracle Provider(ojdbc 내려받기는 그때 사람이 다시 답한다), P1-11 이 MSSQL Provider(드라이버만, arm64 이미지가 없어 통합 시험은 생략). CI-2 는 「Phase 1 마무리로 미룸」(P1-12).
- URL·옵션·풀·마감·메시지의 설계는 P1-07 의 요구사항·계획 단계에서 Planner 와 Queen 이 정했다(T-20261123-164312-7774). 드라이버별 고정 속성, socketTimeout, loginTimeout, 한도(결정 11)는 개발 중에 정했고(run B, run C), 1044 의 메시지는 Queen 이 정했다. 사용자의 명시적 결정이 아니므로 상태는 제안이다.
