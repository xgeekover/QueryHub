//! 실행 계획: java, JAR, 포트, 자식 환경과 인자를 정한다.
//!
//! 셸의 환경은 주입한 맵(Env)으로 받는다. 운영에서는 process_env(), 테스트에서는 직접 만든 맵이다. 테스트는
//! std::env::set_var 를 부르지 않는다.

use std::collections::BTreeMap;
use std::io;
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::time::Duration;

// 메시지의 최소 버전은 java.rs 의 MIN_MAJOR 에서 온다 (숫자를 따로 적지 않는다, ADR-025).
use super::java::MIN_MAJOR;
use super::master_key::MasterKey;
use super::token::AdminToken;
use super::{Failure, FailureKind};

/// 셸 환경(이름 -> 값).
pub type Env = BTreeMap<String, String>;

/// java 실행 파일 경로(최우선).
pub const JAVA_VAR: &str = "QUERYHUB_DESKTOP_JAVA";
/// 런타임 JAR 경로.
pub const JAR_VAR: &str = "QUERYHUB_DESKTOP_RUNTIME_JAR";
/// 자식에게 넘기는 셸의 PID. 런타임의 부모 감시(ParentProcessWatch)가 읽는다.
pub const PARENT_PID_VAR: &str = "QUERYHUB_DESKTOP_PARENT_PID";

/// 런타임의 데이터 디렉터리(P1-06, ADR-026): 내장 SQLite Metadata 저장소가 있는 곳. 셸이 준비한 <앱 로컬 데이터
/// 디렉터리>/data 의 절대 경로만 넣는다(data_dir::prepare). 셸 자신의 환경에 있는 같은 이름은 넘기지 않는다(허용 목록 밖).
pub const DATA_DIR_VAR: &str = "QUERYHUB_DATA_DIR";
/// 런타임의 SQLite 네이티브 라이브러리 디렉터리(서버 배포용, 기본 <데이터 디렉터리>/native). 데스크톱에서는 셸 환경에 있어도
/// 넘기지 않는다(런타임의 기본값을 쓴다).
pub const SQLITE_NATIVE_DIR_VAR: &str = "QUERYHUB_SQLITE_NATIVE_DIR";

/// 런타임의 관리 토큰(ADR-021). 셸이 만든 값만 넣는다. 셸 자신의 환경에 있는 같은 이름은 넘기지 않는다(허용 목록 밖).
pub const ADMIN_TOKEN_VAR: &str = "QUERYHUB_ADMIN_TOKEN";
/// 서버 배포용 토큰 파일 경로. 데스크톱에서는 셸 환경에 있어도 자식에게 넘기지 않는다(둘 다 있으면 런타임이 기동을 거부한다).
pub const ADMIN_TOKEN_FILE_VAR: &str = "QUERYHUB_ADMIN_TOKEN_FILE";

/// 런타임의 마스터 키(ADR-023, ADR-024). 셸이 키 파일에서 읽은 값만 넣는다. 셸 자신의 환경에 있는 같은 이름은 넘기지 않는다.
pub const MASTER_KEY_VAR: &str = "QUERYHUB_MASTER_KEY";
/// 서버 배포용 키 파일 경로. 데스크톱에서는 셸 환경에 있어도 자식에게 넘기지 않는다(둘 다 있으면 런타임이 기동을 거부한다).
pub const MASTER_KEY_FILE_VAR: &str = "QUERYHUB_MASTER_KEY_FILE";
/// 교체용 이전 키(후속 작업, BACKLOG P104). 지금은 런타임이 읽지 않고, 셸 환경에 있어도 넘기지 않는다.
pub const MASTER_KEY_PREVIOUS_VAR: &str = "QUERYHUB_MASTER_KEY_PREVIOUS";
/// 교체용 이전 키 파일(후속 작업, BACKLOG P104). 셸 환경에 있어도 넘기지 않는다.
pub const MASTER_KEY_PREVIOUS_FILE_VAR: &str = "QUERYHUB_MASTER_KEY_PREVIOUS_FILE";

/// JAR 을 만드는 명령(jar_not_found 안내).
pub const BOOT_JAR_COMMAND: &str = "sh runtime/gradlew -p runtime :app:bootJar";

/// 셸 환경에서 자식(런타임과 java -version)에게 물려주는 변수의 허용 목록. 그 밖의 변수는 넘기지 않는다.
///
/// Spring Boot 는 OS 환경변수를 패키지의 application-desktop.yml 보다 앞세운다(SERVER_ADDRESS, MANAGEMENT_*, LOGGING_*,
/// DEBUG, SPRING_*). JVM 은 JAVA_TOOL_OPTIONS 같은 변수로 -D 값을 받는다. 허용 목록이므로 셸의 환경이 런타임의 바인딩 주소,
/// 엔드포인트 노출, 로깅, 프로파일을 바꾸지 못한다. 여기 있는 이름은 어느 것도 Spring 속성에 바인딩되지 않는다.
/// unix 는 이 이름들과 LC_* 다.
#[cfg(not(windows))]
const INHERITED_NAMES: &[&str] = &["PATH", "HOME", "USER", "LOGNAME", "LANG", "TZ", "TMPDIR"];
#[cfg(not(windows))]
const INHERITED_PREFIX: &str = "LC_";
/// windows(미검증, 첫 CI 실행에서 확인한다. BACKLOG CI-1, PLAT-1): 이름을 대소문자 없이 비교한다.
#[cfg(windows)]
const INHERITED_NAMES: &[&str] = &[
    "PATH",
    "SYSTEMROOT",
    "WINDIR",
    "TEMP",
    "TMP",
    "USERPROFILE",
    "APPDATA",
    "LOCALAPPDATA",
    "PATHEXT",
    "COMPUTERNAME",
];

/// 포트 확인에서 127.0.0.1 로 연결해 보는 제한 시간.
const PORT_CONNECT_TIMEOUT: Duration = Duration::from_millis(300);

#[cfg(windows)]
const JAVA_EXE: &str = "java.exe";
#[cfg(not(windows))]
const JAVA_EXE: &str = "java";

/// 이 프로세스의 환경. UTF-8 이 아닌 이름이나 값은 건너뛴다.
pub fn process_env() -> Env {
    std::env::vars_os()
        .filter_map(|(k, v)| Some((k.into_string().ok()?, v.into_string().ok()?)))
        .collect()
}

fn non_empty<'a>(env: &'a Env, name: &str) -> Option<&'a str> {
    env.get(name).map(String::as_str).filter(|v| !v.is_empty())
}

/// 이름이 name 인 변수의 값. 같은 이름을 먼저 찾고, ignore_case 이면 그다음 ASCII 대소문자를 무시하고 찾는다.
///
/// Windows 의 환경변수 이름은 대소문자를 가리지 않고, 그곳의 셸 환경에는 보통 `PATH` 가 아니라 `Path` 가 있다(P0C-C2-4).
/// 호출하는 쪽이 `cfg!(windows)` 로 ignore_case 를 고르므로 이 함수는 모든 플랫폼에서 컴파일되고 테스트된다.
fn env_value<'a>(env: &'a Env, name: &str, ignore_case: bool) -> Option<&'a str> {
    if let Some(value) = env.get(name) {
        return Some(value.as_str());
    }
    if !ignore_case {
        return None;
    }
    env.iter()
        .find(|(key, _)| key.eq_ignore_ascii_case(name))
        .map(|(_, value)| value.as_str())
}

#[cfg(unix)]
fn is_executable(path: &Path) -> bool {
    use std::os::unix::fs::PermissionsExt;
    path.metadata()
        .map(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
        .unwrap_or(false)
}

#[cfg(not(unix))]
fn is_executable(path: &Path) -> bool {
    path.is_file()
}

/// java 를 고른다: QUERYHUB_DESKTOP_JAVA, 그다음 JAVA_HOME/bin/java, 그다음 PATH 의 java.
///
/// QUERYHUB_DESKTOP_JAVA 를 지정했는데 실행할 수 없으면 다른 곳으로 넘어가지 않고 실패한다(명시한 설정의 오류다).
pub fn resolve_java(env: &Env) -> Result<PathBuf, Failure> {
    if let Some(explicit) = non_empty(env, JAVA_VAR) {
        let path = PathBuf::from(explicit);
        if is_executable(&path) {
            return Ok(path);
        }
        return Err(Failure::new(
            FailureKind::JavaNotFound,
            format!("{JAVA_VAR} 가 가리키는 java 를 실행할 수 없다: {explicit}. JDK {MIN_MAJOR} 이상의 bin/java 경로를 지정한다."),
        ));
    }
    if let Some(home) = non_empty(env, "JAVA_HOME") {
        let candidate = Path::new(home).join("bin").join(JAVA_EXE);
        if is_executable(&candidate) {
            return Ok(candidate);
        }
    }
    // Windows 에서는 Path 처럼 대소문자가 다른 이름도 찾는다(P0C-C2-4).
    if let Some(path) = env_value(env, "PATH", cfg!(windows)) {
        for dir in std::env::split_paths(path) {
            if dir.as_os_str().is_empty() {
                continue;
            }
            let candidate = dir.join(JAVA_EXE);
            if is_executable(&candidate) {
                return Ok(candidate);
            }
        }
    }
    Err(Failure::new(
        FailureKind::JavaNotFound,
        format!("Java 를 찾지 못했다({JAVA_VAR}, JAVA_HOME/bin/java, PATH 순서로 찾는다). {JAVA_VAR} 에 JDK {MIN_MAJOR} 이상의 java 경로를 지정한다."),
    ))
}

/// 런타임 JAR 을 고른다: QUERYHUB_DESKTOP_RUNTIME_JAR, 없으면 default_jar(디버그 빌드의 저장소 경로).
pub fn resolve_jar(env: &Env, default_jar: Option<&Path>) -> Result<PathBuf, Failure> {
    let path = match (non_empty(env, JAR_VAR), default_jar) {
        (Some(explicit), _) => PathBuf::from(explicit),
        (None, Some(default)) => default.to_path_buf(),
        (None, None) => {
            return Err(Failure::new(
                FailureKind::JarNotFound,
                format!("{JAR_VAR} 가 없다. 릴리스 빌드에 런타임 JAR 을 묶는 것은 Phase 8 계획이다. 지금은 {JAR_VAR} 에 queryhub-runtime.jar 경로를 지정한다."),
            ))
        }
    };
    if path.is_file() {
        Ok(path)
    } else {
        Err(Failure::new(
            FailureKind::JarNotFound,
            format!(
                "런타임 JAR 이 없다: {}. 저장소 루트에서 `{BOOT_JAR_COMMAND}` 로 만들거나 {JAR_VAR} 에 경로를 지정한다.",
                path.display()
            ),
        ))
    }
}

/// 자식에게 물려주는 변수인가(허용 목록).
#[cfg(not(windows))]
pub fn is_inherited(name: &str) -> bool {
    INHERITED_NAMES.contains(&name) || name.starts_with(INHERITED_PREFIX)
}

/// 자식에게 물려주는 변수인가(허용 목록, 대소문자 무시).
#[cfg(windows)]
pub fn is_inherited(name: &str) -> bool {
    INHERITED_NAMES.iter().any(|n| n.eq_ignore_ascii_case(name))
}

/// 셸 환경에서 허용 목록의 변수만 남긴 환경. java -version 은 이 환경으로 실행한다.
pub fn inherited_env(base: &Env) -> Env {
    base.iter()
        .filter(|(k, _)| is_inherited(k))
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect()
}

/// 자식 환경: 허용 목록의 셸 환경 + 데이터 디렉터리 + 관리 토큰 + 마스터 키 + 셸 PID. 토큰과 키는 환경변수로만 넘긴다
/// (argv 에 넣지 않는다). 셸 환경의 QUERYHUB_DATA_DIR, QUERYHUB_SQLITE_NATIVE_DIR, QUERYHUB_ADMIN_TOKEN,
/// QUERYHUB_ADMIN_TOKEN_FILE, QUERYHUB_MASTER_KEY, QUERYHUB_MASTER_KEY_FILE, QUERYHUB_MASTER_KEY_PREVIOUS,
/// QUERYHUB_MASTER_KEY_PREVIOUS_FILE 과 그 밖의 QUERYHUB_* 는 허용 목록 밖이므로 넘어가지 않는다. 데이터 디렉터리는 셸이
/// 준비한 경로(data_dir), 토큰은 셸이 만든 값, 키는 셸이 키 파일에서 읽은 값으로만 정한다. 데이터 디렉터리는 비밀이 아니다.
pub fn child_env(
    base: &Env,
    data_dir: &str,
    token: &AdminToken,
    master_key: &MasterKey,
    parent_pid: u32,
) -> Env {
    let mut env = inherited_env(base);
    env.insert(DATA_DIR_VAR.to_string(), data_dir.to_string());
    env.insert(ADMIN_TOKEN_VAR.to_string(), token.expose().to_string());
    env.insert(MASTER_KEY_VAR.to_string(), master_key.expose().to_string());
    env.insert(PARENT_PID_VAR.to_string(), parent_pid.to_string());
    env
}

/// `java` 뒤의 인자: -jar <jar> --spring.profiles.active=desktop --server.port=<port>.
pub fn child_args(jar: &Path, port: u16) -> Vec<String> {
    vec![
        "-jar".to_string(),
        jar.display().to_string(),
        "--spring.profiles.active=desktop".to_string(),
        format!("--server.port={port}"),
    ]
}

/// 포트가 비어 있는가. 세 단계로 본다(P101-T1, P0C-C2-1).
///
/// 1. 127.0.0.1:<port> 에 바인딩한다(바로 놓는다). 실패하면 사용 중이다.
/// 2. macOS 만: 0.0.0.0:<port> 와 [::]:<port> 에 SO_REUSEADDR 를 켠 소켓을 바인딩만 하고(listen 하지 않는다) 닫는다.
///    EADDRINUSE 면 사용 중이다. std 의 TcpListener 는 unix 에서 SO_REUSEADDR 를 켜므로 macOS 에서는 다른 프로세스가
///    0.0.0.0:<port> 나 [::]:<port> 에서 듣고 있어도 1 단계가 성공한다. 이 단계는 연결 시간에 기대지 않고 그런 리스너를
///    찾는다. IPv4 소켓을 준비하지 못하면 사용 중으로 보고, IPv6 의 EADDRINUSE 가 아닌 오류(IPv6 를 끈 기계 등)는 무시한다.
/// 3. 127.0.0.1:<port> 로 연결해 본다(제한 시간 300 ms). 연결되면 사용 중이다. 예외는 자기 연결(로컬 주소와 상대 주소가
///    같다)이다: 듣는 소켓이 없어도 TCP 동시 열기로 자기 자신에게 연결될 수 있으므로 비어 있다로 본다(P0C-C2-1). 주소를
///    읽지 못하면 사용 중이다. 연결이 실패하면(시간 초과 포함) 비어 있다.
///
/// Linux 는 1 단계의 바인딩이 0.0.0.0 리스너와 이중 스택 [::] 리스너에 부딪힌다고 보고 2 단계를 두지 않는다(확인하지
/// 않았다, CI-1). Windows 는 99040c0 과 같은 1, 3 단계에 자기 연결 예외만 더했다(확인하지 않았다, PLAT-1, CI-1). Windows 에서는
/// 거부되는 루프백 연결이 300 ms 보다 오래 걸릴 수 있으므로 시간 초과를 사용 중으로 보지 않는다.
pub fn port_is_free(port: u16) -> bool {
    port_is_free_with(port, |addr| {
        TcpStream::connect_timeout(addr, PORT_CONNECT_TIMEOUT)
    })
}

/// port_is_free 의 본체. 3 단계의 연결을 바꿔 끼울 수 있다(시험이 연결 결과를 기계 부하와 상관없이 정한다).
fn port_is_free_with(
    port: u16,
    connect: impl FnOnce(&SocketAddr) -> io::Result<TcpStream>,
) -> bool {
    let loopback = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    if TcpListener::bind(loopback).is_err() {
        return false;
    }
    if wildcard_in_use(port) {
        return false;
    }
    match connect(&loopback) {
        Ok(stream) => match (stream.local_addr(), stream.peer_addr()) {
            (Ok(local), Ok(peer)) => connected_port_is_free(local, peer),
            _ => false,
        },
        Err(_) => true,
    }
}

/// 연결 시험이 연결되었을 때의 판정: 자기 연결(로컬 주소 == 상대 주소)이면 듣는 소켓이 없으므로 비어 있다(P0C-C2-1).
fn connected_port_is_free(local: SocketAddr, peer: SocketAddr) -> bool {
    local == peer
}

/// 2 단계가 없는 플랫폼(Linux, Windows): 와일드카드 리스너를 따로 찾지 않는다.
#[cfg(not(target_os = "macos"))]
fn wildcard_in_use(_port: u16) -> bool {
    false
}

/// 2 단계(macOS): 다른 소켓이 0.0.0.0:<port> 나 [::]:<port> 를 쥐고 있는가.
#[cfg(target_os = "macos")]
fn wildcard_in_use(port: u16) -> bool {
    match wildcard::bind_only(libc::AF_INET, port) {
        wildcard::Probe::NoSocket | wildcard::Probe::InUse => return true,
        wildcard::Probe::NotInUse => {}
    }
    matches!(
        wildcard::bind_only(libc::AF_INET6, port),
        wildcard::Probe::InUse
    )
}

/// macOS 의 바인딩만 하는 와일드카드 시험. SO_REUSEADDR 를 켜고 SO_REUSEPORT 는 켜지 않는다: BSD 는 그러면 같은
/// 와일드카드 주소를 쥔 소켓만 EADDRINUSE 로 알리고, 127.0.0.1:<port> 의 TIME_WAIT 소켓은 막지 않는다. listen 하지 않으므로
/// 연결을 받지 않는다.
#[cfg(target_os = "macos")]
mod wildcard {
    use std::io;
    use std::mem;
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};

    pub(super) enum Probe {
        /// 소켓을 만들거나 SO_REUSEADDR 를 켜지 못했다.
        NoSocket,
        /// bind 가 EADDRINUSE 로 실패했다.
        InUse,
        /// bind 가 성공했거나 EADDRINUSE 가 아닌 이유로 실패했다.
        NotInUse,
    }

    /// family(AF_INET 이나 AF_INET6)의 와일드카드 주소 port 에 바인딩만 하고 닫는다.
    pub(super) fn bind_only(family: libc::c_int, port: u16) -> Probe {
        // SAFETY: socket(2) 은 정수 인자만 읽는다. 돌려준 fd 는 바로 아래에서 OwnedFd 가 맡아 닫는다.
        let raw = unsafe { libc::socket(family, libc::SOCK_STREAM, 0) };
        if raw < 0 {
            return Probe::NoSocket;
        }
        // SAFETY: raw 는 방금 연, 아무도 맡지 않은 열린 fd 다.
        let fd = unsafe { OwnedFd::from_raw_fd(raw) };
        let one: libc::c_int = 1;
        // SAFETY: optval 은 살아 있는 c_int 를 가리키고 optlen 은 그 크기다.
        let rc = unsafe {
            libc::setsockopt(
                fd.as_raw_fd(),
                libc::SOL_SOCKET,
                libc::SO_REUSEADDR,
                (&one as *const libc::c_int).cast(),
                mem::size_of::<libc::c_int>() as libc::socklen_t,
            )
        };
        if rc != 0 {
            return Probe::NoSocket;
        }
        let rc = if family == libc::AF_INET {
            // SAFETY: sockaddr_in 은 정수와 바이트 배열만 담은 C 구조체이므로 모두 0 인 값이 올바르다(주소 0.0.0.0).
            let mut addr: libc::sockaddr_in = unsafe { mem::zeroed() };
            addr.sin_len = mem::size_of::<libc::sockaddr_in>() as u8;
            addr.sin_family = libc::AF_INET as libc::sa_family_t;
            addr.sin_port = port.to_be();
            // SAFETY: addr 는 살아 있는 sockaddr_in 이고 addrlen 은 그 크기다.
            unsafe {
                libc::bind(
                    fd.as_raw_fd(),
                    (&addr as *const libc::sockaddr_in).cast(),
                    mem::size_of::<libc::sockaddr_in>() as libc::socklen_t,
                )
            }
        } else {
            // SAFETY: sockaddr_in6 은 정수와 바이트 배열만 담은 C 구조체이므로 모두 0 인 값이 올바르다(주소 [::]).
            let mut addr: libc::sockaddr_in6 = unsafe { mem::zeroed() };
            addr.sin6_len = mem::size_of::<libc::sockaddr_in6>() as u8;
            addr.sin6_family = libc::AF_INET6 as libc::sa_family_t;
            addr.sin6_port = port.to_be();
            // SAFETY: addr 는 살아 있는 sockaddr_in6 이고 addrlen 은 그 크기다.
            unsafe {
                libc::bind(
                    fd.as_raw_fd(),
                    (&addr as *const libc::sockaddr_in6).cast(),
                    mem::size_of::<libc::sockaddr_in6>() as libc::socklen_t,
                )
            }
        };
        if rc == 0 {
            return Probe::NotInUse;
        }
        if io::Error::last_os_error().raw_os_error() == Some(libc::EADDRINUSE) {
            Probe::InUse
        } else {
            Probe::NotInUse
        }
    }
}

/// port_in_use 실패.
pub fn port_in_use_failure(port: u16) -> Failure {
    Failure::new(
        FailureKind::PortInUse,
        format!("포트 {port} 을(를) 다른 프로세스가 쓰고 있다. `lsof -nP -iTCP:{port} -sTCP:LISTEN` 으로 확인한다."),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 시험용 고정 키(NOT A SECRET).
    fn test_key() -> MasterKey {
        MasterKey::from_test_value(&"f0e1d2c3b4a5968778695a4b3c2d1e0f".repeat(2))
    }

    fn env(pairs: &[(&str, &str)]) -> Env {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    /// 시험용 데이터 디렉터리(셸이 준비한 <앱 로컬 데이터 디렉터리>/data 자리).
    const DATA_DIR: &str = "/home/qh/.local/share/com.queryhub.desktop/data";

    /// P1-06 에서 없앤 Metadata DB 변수의 이름 앞부분. 이름을 둘로 나누어 적어 desktop 에 옛 이름이 그대로 남지 않게 한다
    /// (AC21 의 git grep). 셸 환경에 있어도 자식에게 가면 안 된다.
    const RETIRED_METADATA_PREFIX: &str = concat!("QUERYHUB_", "METADATA_DB_");

    /// 허용 목록 밖의 변수. Spring 이 바인딩하는 것(SERVER_*, MANAGEMENT_*, LOGGING_*, DEBUG, SPRING_*), JVM 옵션,
    /// 다른 QUERYHUB_* 와 아무 변수.
    const NOT_INHERITED: &[(&str, &str)] = &[
        ("SERVER_ADDRESS", "0.0.0.0"),
        ("SERVER_PORT", "9999"),
        ("MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE", "*"),
        ("LOGGING_LEVEL_ROOT", "TRACE"),
        ("DEBUG", "true"),
        (
            "SPRING_APPLICATION_JSON",
            "{\"server\":{\"address\":\"0.0.0.0\"}}",
        ),
        ("SPRING_PROFILES_ACTIVE", "server"),
        ("JAVA_TOOL_OPTIONS", "-Dx=1"),
        ("JDK_JAVA_OPTIONS", "-Dy=1"),
        ("_JAVA_OPTIONS", "-Dz=1"),
        ("JAVA_HOME", "/opt/jdk"),
        ("QUERYHUB_OTHER", "x"),
        (concat!("QUERYHUB_", "METADATA_DB_PASSWORD"), "inherited"),
        ("QUERYHUB_DATA_DIR", "/shell/data"),
        ("QUERYHUB_SQLITE_NATIVE_DIR", "/shell/native"),
        ("QUERYHUB_ADMIN_TOKEN", "inherited"),
        ("QUERYHUB_ADMIN_TOKEN_FILE", "/x"),
        ("QUERYHUB_MASTER_KEY", "inherited"),
        ("QUERYHUB_MASTER_KEY_FILE", "/x"),
        ("QUERYHUB_MASTER_KEY_PREVIOUS", "inherited"),
        ("QUERYHUB_MASTER_KEY_PREVIOUS_FILE", "/x"),
        ("FOO_BAR", "baz"),
    ];

    #[cfg(not(windows))]
    #[test]
    fn child_env_passes_only_the_allow_list_and_the_data_dir() {
        let inherited = [
            ("PATH", "/usr/bin"),
            ("HOME", "/home/qh"),
            ("USER", "qh"),
            ("LOGNAME", "qh"),
            ("LANG", "C"),
            ("LC_ALL", "C"),
            ("LC_CTYPE", "UTF-8"),
            ("TZ", "UTC"),
            ("TMPDIR", "/tmp/qh"),
        ];
        let mut pairs = inherited.to_vec();
        pairs.extend_from_slice(NOT_INHERITED);
        let base = env(&pairs);
        let token = AdminToken::generate().expect("token");
        let key = test_key();
        let child = child_env(&base, DATA_DIR, &token, &key, 4242);
        for (name, value) in inherited {
            assert_eq!(child.get(name).map(String::as_str), Some(value), "{name}");
        }
        for (name, _) in NOT_INHERITED
            .iter()
            .filter(|(n, _)| *n != DATA_DIR_VAR && *n != ADMIN_TOKEN_VAR && *n != MASTER_KEY_VAR)
        {
            assert!(!child.contains_key(*name), "{name} 가 자식 환경에 있다");
        }
        let mut expected: Vec<&str> = inherited.iter().map(|(k, _)| *k).collect();
        expected.extend([
            ADMIN_TOKEN_VAR,
            DATA_DIR_VAR,
            MASTER_KEY_VAR,
            PARENT_PID_VAR,
        ]);
        expected.sort_unstable();
        assert_eq!(
            child.keys().map(String::as_str).collect::<Vec<_>>(),
            expected
        );
        assert_eq!(child[DATA_DIR_VAR], DATA_DIR);
        assert_eq!(child[PARENT_PID_VAR], "4242");
        assert_eq!(child[ADMIN_TOKEN_VAR], token.expose());
        assert_eq!(child[MASTER_KEY_VAR], key.expose());

        // java -version 의 환경도 같은 허용 목록이다(데이터 디렉터리, 토큰, 키, PID 도 없다).
        assert_eq!(inherited_env(&base), env(&inherited));
    }

    /// AC19: 자식의 QUERYHUB_DATA_DIR 은 셸이 준비한 경로다. 셸 환경의 QUERYHUB_DATA_DIR 와 QUERYHUB_SQLITE_NATIVE_DIR,
    /// 없앤 Metadata DB 변수(HOST, PORT, NAME, USERNAME, PASSWORD)는 넘어가지 않는다. java -version 의 환경과 argv 에는
    /// 데이터 디렉터리가 없다.
    #[test]
    fn child_env_sets_the_prepared_data_dir_and_drops_shell_store_and_retired_metadata_variables() {
        let retired: Vec<String> = ["HOST", "PORT", "NAME", "USERNAME", "PASSWORD"]
            .iter()
            .map(|suffix| format!("{RETIRED_METADATA_PREFIX}{suffix}"))
            .collect();
        let mut pairs = vec![
            ("PATH", "/usr/bin"),
            (DATA_DIR_VAR, "/shell/data-marker"),
            (SQLITE_NATIVE_DIR_VAR, "/shell/native-marker"),
        ];
        pairs.extend(retired.iter().map(|name| (name.as_str(), "retired-marker")));
        let base = env(&pairs);
        let token = AdminToken::generate().expect("token");
        let child = child_env(&base, DATA_DIR, &token, &test_key(), 1);
        assert_eq!(child.get(DATA_DIR_VAR).map(String::as_str), Some(DATA_DIR));
        assert!(!child.contains_key(SQLITE_NATIVE_DIR_VAR));
        for name in &retired {
            assert!(!child.contains_key(name), "{name} 가 자식 환경에 있다");
        }
        assert!(
            child
                .keys()
                .all(|k| !k.starts_with(RETIRED_METADATA_PREFIX)),
            "{child:?}"
        );
        assert!(child
            .values()
            .all(|v| !v.contains("marker") && !v.starts_with("/shell/")));
        let queryhub: Vec<&str> = child
            .keys()
            .map(String::as_str)
            .filter(|k| k.starts_with("QUERYHUB_"))
            .collect();
        assert_eq!(
            queryhub,
            vec![
                ADMIN_TOKEN_VAR,
                DATA_DIR_VAR,
                PARENT_PID_VAR,
                MASTER_KEY_VAR
            ]
        );

        let version_env = inherited_env(&base);
        assert_eq!(version_env, env(&[("PATH", "/usr/bin")]));

        let args = child_args(Path::new("/opt/queryhub-runtime.jar"), 18580);
        assert!(args.iter().all(|a| !a.contains(DATA_DIR)), "{args:?}");
        assert!(
            args.iter()
                .all(|a| !a.to_ascii_lowercase().contains("data")),
            "{args:?}"
        );
    }

    /// AC12: 셸 환경의 QUERYHUB_ADMIN_TOKEN=inherited 와 QUERYHUB_ADMIN_TOKEN_FILE=/x 는 자식에게 가지 않고, 자식의 토큰은
    /// 셸이 만든 값이다. 인자와 java -version 의 환경에는 토큰이 없다.
    #[test]
    fn child_env_uses_the_generated_token_and_drops_inherited_token_variables() {
        let base = env(&[
            ("PATH", "/usr/bin"),
            (ADMIN_TOKEN_VAR, "inherited"),
            (ADMIN_TOKEN_FILE_VAR, "/x"),
        ]);
        let token = AdminToken::generate().expect("token");
        let child = child_env(&base, DATA_DIR, &token, &test_key(), 1);
        assert_eq!(
            child.get(ADMIN_TOKEN_VAR).map(String::as_str),
            Some(token.expose())
        );
        assert!(!child.contains_key(ADMIN_TOKEN_FILE_VAR));
        assert!(child.values().all(|v| v != "inherited" && v != "/x"));

        let version_env = inherited_env(&base);
        assert!(!version_env.contains_key(ADMIN_TOKEN_VAR));
        assert!(!version_env.contains_key(ADMIN_TOKEN_FILE_VAR));
        assert!(version_env.values().all(|v| !v.contains(token.expose())));

        let args = child_args(Path::new("/opt/queryhub-runtime.jar"), 18580);
        assert!(args.iter().all(|a| !a.contains(token.expose())), "{args:?}");
        assert!(
            args.iter().all(|a| !a.contains(ADMIN_TOKEN_VAR)),
            "{args:?}"
        );
    }

    /// AC20: 자식의 QUERYHUB_MASTER_KEY 는 키 파일에서 읽은 값이다. 셸 환경의 QUERYHUB_MASTER_KEY, _FILE, _PREVIOUS,
    /// _PREVIOUS_FILE 은 넘어가지 않는다. java -version 의 환경과 argv 에는 키의 이름도 값도 없다.
    #[test]
    fn child_env_uses_the_key_file_value_and_drops_inherited_master_key_variables() {
        let inherited_names = [
            MASTER_KEY_VAR,
            MASTER_KEY_FILE_VAR,
            MASTER_KEY_PREVIOUS_VAR,
            MASTER_KEY_PREVIOUS_FILE_VAR,
        ];
        let mut pairs = vec![("PATH", "/usr/bin")];
        pairs.extend(
            inherited_names
                .iter()
                .map(|name| (*name, "inherited-key-marker")),
        );
        let base = env(&pairs);
        let token = AdminToken::generate().expect("token");
        let key = test_key();
        let child = child_env(&base, DATA_DIR, &token, &key, 1);
        assert_eq!(
            child.get(MASTER_KEY_VAR).map(String::as_str),
            Some(key.expose())
        );
        for name in &inherited_names[1..] {
            assert!(!child.contains_key(*name), "{name} 가 자식 환경에 있다");
        }
        assert!(child.values().all(|v| v != "inherited-key-marker"));
        assert_ne!(key.expose(), token.expose());

        let version_env = inherited_env(&base);
        for name in inherited_names {
            assert!(
                !version_env.contains_key(name),
                "{name} 가 java -version 환경에 있다"
            );
        }
        assert!(version_env.values().all(|v| !v.contains(key.expose())));

        let args = child_args(Path::new("/opt/queryhub-runtime.jar"), 18580);
        assert!(args.iter().all(|a| !a.contains(key.expose())), "{args:?}");
        assert!(
            args.iter()
                .all(|a| !a.to_ascii_uppercase().contains("MASTER")),
            "{args:?}"
        );
    }

    #[cfg(windows)]
    #[test]
    fn windows_allow_list_compares_names_case_insensitively() {
        let mut pairs = vec![("Path", "C:\\Windows"), ("SystemRoot", "C:\\Windows")];
        pairs.extend_from_slice(NOT_INHERITED);
        assert_eq!(
            inherited_env(&env(&pairs)),
            env(&[("Path", "C:\\Windows"), ("SystemRoot", "C:\\Windows")])
        );
    }

    /// C2-4: 대소문자 무시는 요청할 때만 하고, 같은 이름이 있으면 그것을 먼저 쓴다. 플랫폼과 관계없이 실행된다.
    #[test]
    fn env_value_finds_mixed_case_names_only_in_ignore_case_mode() {
        let windows_like = env(&[("Path", "C:\\Windows"), ("HOME", "/home/qh")]);
        assert_eq!(env_value(&windows_like, "PATH", true), Some("C:\\Windows"));
        assert_eq!(env_value(&windows_like, "PATH", false), None);
        assert_eq!(env_value(&windows_like, "HOME", false), Some("/home/qh"));
        assert_eq!(env_value(&windows_like, "MISSING", true), None);

        let both = env(&[("PATH", "exact"), ("Path", "other")]);
        assert_eq!(env_value(&both, "PATH", true), Some("exact"));
        assert_eq!(env_value(&both, "PATH", false), Some("exact"));
    }

    /// C2-4: Windows 의 셸 환경처럼 Path 만 있어도 그 안의 java.exe 를 찾는다.
    /// 미검증: Windows 에서만 컴파일·실행된다(CI desktop 작업의 windows-2025, 아직 실행되지 않았다).
    #[cfg(windows)]
    #[test]
    fn windows_resolve_java_reads_a_mixed_case_path_variable() {
        let dir = std::env::temp_dir().join(format!("queryhub-launch-path-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("dir");
        let java = dir.join(JAVA_EXE);
        std::fs::write(&java, b"").expect("java.exe");
        let dir_text = dir.display().to_string();
        let found = resolve_java(&env(&[("Path", dir_text.as_str())]));
        let _ = std::fs::remove_dir_all(&dir);
        assert_eq!(found.expect("java from Path"), java);
    }

    #[test]
    fn port_is_free_sees_loopback_and_wildcard_listeners() {
        let loopback = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind loopback");
        assert!(!port_is_free(loopback.local_addr().expect("addr").port()));

        // 0.0.0.0 에서 듣는 리스너: 127.0.0.1 바인딩은 성공하지만(SO_REUSEADDR) 연결 시험이 찾는다.
        let wildcard = TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0)).expect("bind wildcard");
        let port = wildcard.local_addr().expect("addr").port();
        assert!(!port_is_free(port), "0.0.0.0:{port} 리스너를 놓쳤다");
        drop(wildcard);
        assert!(port_is_free(port), "닫은 뒤에도 {port} 이 사용 중이다");
    }

    // P101-T1: 아래 시험은 3 단계의 연결을 바꿔 끼워 연결 결과를 정한다. 기계가 바빠 연결이 300 ms 안에 끝나지 않아도
    // (시간 초과) 리스너가 있는 포트는 1, 2 단계의 바인딩만으로 사용 중이어야 한다.
    fn timed_out(_: &SocketAddr) -> io::Result<TcpStream> {
        Err(io::Error::from(io::ErrorKind::TimedOut))
    }

    fn refused(_: &SocketAddr) -> io::Result<TcpStream> {
        Err(io::Error::from(io::ErrorKind::ConnectionRefused))
    }

    // (a) 0.0.0.0 리스너: macOS 는 2 단계가, Linux 는 1 단계가 찾는다(Linux 는 확인하지 않았다, CI-1).
    #[cfg(not(windows))]
    #[test]
    fn a_wildcard_ipv4_listener_is_in_use_even_when_the_connect_times_out() {
        let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0)).expect("bind 0.0.0.0");
        let port = listener.local_addr().expect("addr").port();
        assert!(
            !port_is_free_with(port, timed_out),
            "0.0.0.0:{port} 리스너를 놓쳤다"
        );
    }

    // (b) [::] 리스너(이중 스택): macOS 는 2 단계가, Linux 는 1 단계가 찾는다(Linux 는 확인하지 않았다, CI-1).
    #[cfg(not(windows))]
    #[test]
    fn a_wildcard_ipv6_listener_is_in_use_even_when_the_connect_times_out() {
        let listener = TcpListener::bind((std::net::Ipv6Addr::UNSPECIFIED, 0)).expect("bind [::]");
        let port = listener.local_addr().expect("addr").port();
        assert!(
            !port_is_free_with(port, timed_out),
            "[::]:{port} 리스너를 놓쳤다"
        );
    }

    // (c) 127.0.0.1 리스너: 1 단계가 찾는다.
    #[test]
    fn a_loopback_listener_is_in_use_even_when_the_connect_times_out() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind 127.0.0.1");
        let port = listener.local_addr().expect("addr").port();
        assert!(
            !port_is_free_with(port, timed_out),
            "127.0.0.1:{port} 리스너를 놓쳤다"
        );
    }

    // (d) 빈 포트: 연결이 시간 초과로 끝나도, 거부되어도 비어 있다(Windows 의 느린 거부를 비어 있다로 본다).
    #[test]
    fn a_free_port_is_free_when_the_connect_times_out_or_is_refused() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind 127.0.0.1");
        let port = listener.local_addr().expect("addr").port();
        drop(listener);
        assert!(
            port_is_free_with(port, timed_out),
            "시간 초과인데 {port} 이 사용 중이다"
        );
        assert!(
            port_is_free_with(port, refused),
            "거부되었는데 {port} 이 사용 중이다"
        );
    }

    // (e) 연결되었을 때: 자기 연결(로컬 주소 == 상대 주소)은 비어 있고, 다른 소켓과의 연결은 사용 중이다(P0C-C2-1).
    #[test]
    fn a_self_connect_is_free_and_a_connect_to_another_socket_is_in_use() {
        let own = SocketAddr::from((Ipv4Addr::LOCALHOST, 18580));
        let other = SocketAddr::from((Ipv4Addr::LOCALHOST, 54321));
        assert!(connected_port_is_free(own, own));
        assert!(!connected_port_is_free(other, own));
        assert!(!connected_port_is_free(own, other));
    }

    // (f) 서버 쪽이 먼저 닫은 연결(127.0.0.1:<port> 의 TIME_WAIT)만 남은 포트는 비어 있다: 1, 2 단계는 SO_REUSEADDR 를 켠다.
    #[cfg(not(windows))]
    #[test]
    fn a_port_with_only_a_server_side_time_wait_is_free() {
        use std::io::Read;
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind 127.0.0.1");
        let port = listener.local_addr().expect("addr").port();
        let mut client = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).expect("connect");
        let (served, _) = listener.accept().expect("accept");
        drop(served);
        let mut buf = [0u8; 1];
        assert_eq!(client.read(&mut buf).expect("read eof"), 0);
        drop(client);
        drop(listener);
        assert!(
            port_is_free_with(port, refused),
            "TIME_WAIT 만 남은 {port} 이 사용 중이다"
        );
    }
}
