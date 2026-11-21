//! 실행 계획: java, JAR, Metadata DB 설정, 포트, 자식 환경과 인자를 정한다.
//!
//! 셸의 환경은 주입한 맵(Env)으로 받는다. 운영에서는 process_env(), 테스트에서는 직접 만든 맵이다. 테스트는
//! std::env::set_var 를 부르지 않는다.

use std::collections::BTreeMap;
use std::fmt;
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::time::Duration;

use super::{Failure, FailureKind};

/// 셸 환경(이름 -> 값).
pub type Env = BTreeMap<String, String>;

/// java 실행 파일 경로(최우선).
pub const JAVA_VAR: &str = "QUERYHUB_DESKTOP_JAVA";
/// 런타임 JAR 경로.
pub const JAR_VAR: &str = "QUERYHUB_DESKTOP_RUNTIME_JAR";
/// 자식에게 넘기는 셸의 PID. 런타임의 부모 감시(ParentProcessWatch)가 읽는다.
pub const PARENT_PID_VAR: &str = "QUERYHUB_DESKTOP_PARENT_PID";

pub const DB_HOST_VAR: &str = "QUERYHUB_METADATA_DB_HOST";
pub const DB_PORT_VAR: &str = "QUERYHUB_METADATA_DB_PORT";
pub const DB_NAME_VAR: &str = "QUERYHUB_METADATA_DB_NAME";
pub const DB_USERNAME_VAR: &str = "QUERYHUB_METADATA_DB_USERNAME";
pub const DB_PASSWORD_VAR: &str = "QUERYHUB_METADATA_DB_PASSWORD";

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

/// Metadata DB 접속 정보. Debug 출력은 비밀번호를 가린다.
#[derive(Clone)]
pub struct MetadataSettings {
    pub host: String,
    pub port: Option<String>,
    pub name: String,
    pub username: String,
    password: String,
}

impl MetadataSettings {
    pub fn password(&self) -> &str {
        &self.password
    }
}

impl fmt::Debug for MetadataSettings {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("MetadataSettings")
            .field("host", &self.host)
            .field("port", &self.port)
            .field("name", &self.name)
            .field("username", &self.username)
            .field("password", &super::output::MASK)
            .finish()
    }
}

/// Metadata DB 설정의 출처(스파이크: 셸 자신의 환경변수).
///
/// Phase 6 에서는 설정 파일과 OS 키체인으로 바꾼다(계획). 이 함수만 바꾸면 되고 감독(supervisor)은 그대로다.
/// HOST, NAME, USERNAME, PASSWORD 는 비어 있지 않아야 하고 PORT 는 선택이다. 값의 형식 검사는 런타임이 한다(P0-B).
/// 실패하면 빠진 변수 이름들을 돌려준다(값은 담지 않는다).
pub fn metadata_settings_from_env(env: &Env) -> Result<MetadataSettings, Vec<&'static str>> {
    let required = [DB_HOST_VAR, DB_NAME_VAR, DB_USERNAME_VAR, DB_PASSWORD_VAR];
    let missing: Vec<&'static str> = required
        .iter()
        .copied()
        .filter(|name| non_empty(env, name).is_none())
        .collect();
    if !missing.is_empty() {
        return Err(missing);
    }
    let get = |name: &str| non_empty(env, name).unwrap_or_default().to_string();
    Ok(MetadataSettings {
        host: get(DB_HOST_VAR),
        port: non_empty(env, DB_PORT_VAR).map(str::to_string),
        name: get(DB_NAME_VAR),
        username: get(DB_USERNAME_VAR),
        password: get(DB_PASSWORD_VAR),
    })
}

/// metadata_settings_missing 실패. 빠진 이름만 말한다.
pub fn settings_missing_failure(missing: Vec<&'static str>) -> Failure {
    Failure::new(
        FailureKind::MetadataSettingsMissing,
        format!(
            "Metadata DB 설정이 없다: {}. 개발 모드에서는 docs/DEPLOYMENT.md 의 절차대로 deploy/.env 를 하위 셸에서만 읽어 앱을 시작한다: (set -a; . deploy/.env; set +a; pnpm --dir desktop tauri dev). 첫 실행 설정 화면과 OS 키체인 저장은 Phase 6 계획이다.",
            missing.join(", ")
        ),
    )
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
            format!("{JAVA_VAR} 가 가리키는 java 를 실행할 수 없다: {explicit}. JDK 21 이상의 bin/java 경로를 지정한다."),
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
        format!("Java 를 찾지 못했다({JAVA_VAR}, JAVA_HOME/bin/java, PATH 순서로 찾는다). {JAVA_VAR} 에 JDK 21 이상의 java 경로를 지정한다."),
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

/// 자식 환경: 허용 목록의 셸 환경 + Metadata DB 설정 + 셸 PID. 비밀번호는 환경변수로만 넘긴다(argv 에 넣지 않는다).
pub fn child_env(base: &Env, settings: &MetadataSettings, parent_pid: u32) -> Env {
    let mut env = inherited_env(base);
    env.insert(DB_HOST_VAR.to_string(), settings.host.clone());
    if let Some(port) = &settings.port {
        env.insert(DB_PORT_VAR.to_string(), port.clone());
    }
    env.insert(DB_NAME_VAR.to_string(), settings.name.clone());
    env.insert(DB_USERNAME_VAR.to_string(), settings.username.clone());
    env.insert(DB_PASSWORD_VAR.to_string(), settings.password.clone());
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

/// 포트가 비어 있는가: 127.0.0.1:<port> 에 바인딩할 수 있고(바로 놓는다), 127.0.0.1:<port> 로 연결되지 않는다.
///
/// std 의 TcpListener 는 unix 에서 SO_REUSEADDR 를 켜므로, 다른 프로세스가 0.0.0.0:<port> 에서 듣고 있어도 바인딩은
/// 성공한다(macOS). 연결 시험이 그런 와일드카드 리스너를 찾는다.
pub fn port_is_free(port: u16) -> bool {
    let loopback = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    if TcpListener::bind(loopback).is_err() {
        return false;
    }
    TcpStream::connect_timeout(&loopback, PORT_CONNECT_TIMEOUT).is_err()
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

    fn env(pairs: &[(&str, &str)]) -> Env {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    #[test]
    fn settings_need_four_non_empty_variables_and_debug_hides_the_password() {
        let missing = metadata_settings_from_env(&env(&[(DB_HOST_VAR, ""), (DB_NAME_VAR, "qh")]))
            .expect_err("missing");
        assert_eq!(missing, vec![DB_HOST_VAR, DB_USERNAME_VAR, DB_PASSWORD_VAR]);

        let settings = metadata_settings_from_env(&env(&[
            (DB_HOST_VAR, "127.0.0.1"),
            (DB_NAME_VAR, "qh"),
            (DB_USERNAME_VAR, "qh-user"),
            (DB_PASSWORD_VAR, "pw-debug-marker"),
        ]))
        .expect("settings");
        assert_eq!(settings.port, None);
        assert_eq!(settings.password(), "pw-debug-marker");
        assert!(!format!("{settings:?}").contains("pw-debug-marker"));
    }

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
        ("QUERYHUB_METADATA_DB_PASSWORD", "inherited"),
        ("FOO_BAR", "baz"),
    ];

    #[cfg(not(windows))]
    #[test]
    fn child_env_passes_only_the_allow_list_and_the_metadata_settings() {
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
        let settings = MetadataSettings {
            host: "h".into(),
            port: Some("13306".into()),
            name: "n".into(),
            username: "u".into(),
            password: "p".into(),
        };
        let child = child_env(&base, &settings, 4242);
        for (name, value) in inherited {
            assert_eq!(child.get(name).map(String::as_str), Some(value), "{name}");
        }
        for (name, _) in NOT_INHERITED.iter().filter(|(n, _)| *n != DB_PASSWORD_VAR) {
            assert!(!child.contains_key(*name), "{name} 가 자식 환경에 있다");
        }
        let mut expected: Vec<&str> = inherited.iter().map(|(k, _)| *k).collect();
        expected.extend([
            PARENT_PID_VAR,
            DB_HOST_VAR,
            DB_NAME_VAR,
            DB_PASSWORD_VAR,
            DB_PORT_VAR,
            DB_USERNAME_VAR,
        ]);
        expected.sort_unstable();
        assert_eq!(
            child.keys().map(String::as_str).collect::<Vec<_>>(),
            expected
        );
        assert_eq!(child[DB_PASSWORD_VAR], "p");
        assert_eq!(child[PARENT_PID_VAR], "4242");

        // java -version 의 환경도 같은 허용 목록이다(Metadata DB 설정과 PID 도 없다).
        assert_eq!(inherited_env(&base), env(&inherited));
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

    /// C2-3: 안내는 DEPLOYMENT.md 와 deploy/.env 를 하위 셸에서만 읽는 명령을 가리키고, 빠진 이름만 말한다.
    #[test]
    fn settings_missing_failure_points_to_the_deployment_doc_and_the_scoped_command() {
        let failure = settings_missing_failure(vec![DB_HOST_VAR, DB_PASSWORD_VAR]);
        assert_eq!(failure.kind, FailureKind::MetadataSettingsMissing);
        assert!(
            failure
                .message
                .contains("(set -a; . deploy/.env; set +a; pnpm --dir desktop tauri dev)"),
            "{}",
            failure.message
        );
        assert!(
            failure.message.contains("docs/DEPLOYMENT.md"),
            "{}",
            failure.message
        );
        assert!(failure.message.contains(DB_HOST_VAR));
        assert!(failure.message.contains(DB_PASSWORD_VAR));
        for absent in [DB_NAME_VAR, DB_USERNAME_VAR, DB_PORT_VAR] {
            assert!(!failure.message.contains(absent), "{absent}");
        }
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
}
