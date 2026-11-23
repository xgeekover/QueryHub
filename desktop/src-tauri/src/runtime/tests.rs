//! 감독 단위 테스트(AC-10).
//!
//! - 가짜 자식은 sh 스크립트다. 스크립트와 JAR 경로에 표식 queryhub-fake-runtime 이 들어 있어 남은 프로세스를
//!   `pgrep -fl queryhub-fake-runtime` 으로 찾을 수 있다.
//! - HTTP 는 테스트 안의 작은 서버가 임시 포트에서 답한다. 포트 18580 과 std::env::set_var 는 쓰지 않는다.
//! - 실제 포트 확인(launch::port_is_free)을 거치는 테스트의 포트는 free_port() 가 OS 의 임시 포트 범위 밖에서 고른다
//!   (아래 설명).
//! - 관리 토큰은 테스트마다 AdminToken::generate() 로 만든다(고정 값을 쓰지 않는다).
//! - 마스터 키 파일은 샌드박스의 work/secrets/master.key 다(실제 앱 데이터 디렉터리는 쓰지 않는다). 감독이 첫 시작에서 만든다.
//! - 데이터 디렉터리는 샌드박스의 work/data 다(운영의 <앱 로컬 데이터 디렉터리>/data 자리). 감독이 시작할 때 0700 으로 만든다.
//! - 셸 환경에 Metadata 저장소 설정은 없다(P1-06: 런타임이 데이터 디렉터리의 내장 SQLite 를 쓴다).
//! - 모든 테스트는 끝날 때 자식을 정지하거나(Drop) 자식이 끝났음을 확인한다.

use std::collections::BTreeMap;
use std::fs;
use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU16, AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use super::launch::{self, Env};
use super::output::MASK;
use super::supervisor;
use super::{
    AdminToken, FailureKind, Health, MasterKey, RuntimeState, RuntimeStatus, Supervisor,
    SupervisorConfig,
};

const MARKER: &str = "queryhub-fake-runtime";
/// P1-06 에서 없앤 Metadata DB 변수의 이름 앞부분. 이름을 둘로 나누어 적어 desktop 에 옛 이름이 그대로 남지 않게 한다
/// (AC21 의 git grep). 셸 환경에 있어도 자식에게 가면 안 된다.
const RETIRED_METADATA_PREFIX: &str = concat!("QUERYHUB_", "METADATA_DB_");

/// 멈출 때까지 기다린다. 작업 디렉터리에 exit-now 가 생기면 7 로 끝난다.
const SLEEP_LOOP: &str = "while :; do [ -f exit-now ] && exit 7; sleep 0.1; done";
/// TERM 을 무시한다(정지 유예 뒤 KILL 되어야 한다).
const TERM_IGNORING: &str = "trap '' TERM\ntouch trapped\nwhile :; do sleep 0.1; done";
/// 관리 토큰, 마스터 키, 데이터 디렉터리를 찍고 3 으로 끝난다(토큰과 키 줄은 stdout 과 stderr 둘 다).
const EXIT_WITH_SECRETS: &str =
    "echo \"admin token=$QUERYHUB_ADMIN_TOKEN\"\necho \"master key=$QUERYHUB_MASTER_KEY\"\necho \"store failed at data dir=$QUERYHUB_DATA_DIR\" >&2\necho \"admin token=$QUERYHUB_ADMIN_TOKEN\" >&2\necho \"master key=$QUERYHUB_MASTER_KEY\" >&2\nexit 3";
/// 환경과 인자를 파일로 남기고 기다린다.
const DUMP_ENV_AND_ARGS: &str = "env > child-env.tmp && mv child-env.tmp child-env.txt\nfor a in \"$@\"; do printf '%s\\n' \"$a\"; done > child-argv.tmp && mv child-argv.tmp child-argv.txt\nwhile :; do sleep 0.1; done";

static COUNTER: AtomicUsize = AtomicUsize::new(0);

/// 테스트마다 쓰는 임시 디렉터리: bin/java(가짜), queryhub-runtime.jar(빈 파일), work/(자식의 작업 디렉터리).
struct Sandbox {
    root: PathBuf,
}

impl Sandbox {
    fn new() -> Self {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        let root = std::env::temp_dir().join(format!(
            "{MARKER}-{}-{}-{nanos}",
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::SeqCst)
        ));
        fs::create_dir_all(root.join("bin")).expect("bin");
        fs::create_dir_all(root.join("work")).expect("work");
        fs::write(root.join("queryhub-runtime.jar"), b"fake jar").expect("jar");
        Sandbox { root }
    }

    fn java(&self) -> PathBuf {
        self.root.join("bin").join("java")
    }

    fn jar(&self) -> PathBuf {
        self.root.join("queryhub-runtime.jar")
    }

    fn work(&self) -> PathBuf {
        self.root.join("work")
    }

    /// 마스터 키 파일(운영의 앱 로컬 데이터 디렉터리 secrets/master.key 자리).
    fn key_path(&self) -> PathBuf {
        self.work().join("secrets").join("master.key")
    }

    /// 데이터 디렉터리(운영의 앱 로컬 데이터 디렉터리 data 자리).
    fn data_dir(&self) -> PathBuf {
        self.work().join("data")
    }

    /// 감독이 만든 키 파일의 값(끝의 LF 를 뺀다).
    fn key_value(&self) -> String {
        fs::read_to_string(self.key_path())
            .expect("key file")
            .trim_end()
            .to_string()
    }

    fn spawned(&self) -> bool {
        self.work().join("spawned").exists()
    }

    /// -version 에는 version 을 찍고, 그 밖의 호출(-jar ...)에서는 spawned 를 만든 뒤 body 를 실행하는 가짜 java.
    fn fake_java_at(&self, path: &Path, version: &str, body: &str) {
        fs::create_dir_all(path.parent().expect("parent")).expect("dir");
        let script = format!(
            "#!/bin/sh\n# {MARKER}\nif [ \"$1\" = \"-version\" ]; then\n  echo 'openjdk version \"{version}\" 2026-04-21 LTS' >&2\n  exit 0\nfi\ntouch spawned\n{body}\n"
        );
        fs::write(path, script).expect("script");
        fs::set_permissions(path, fs::Permissions::from_mode(0o755)).expect("chmod");
    }

    fn fake_java(&self, version: &str, body: &str) {
        self.fake_java_at(&self.java(), version, body);
    }

    /// fake_java 와 같지만 -version 이 started 를 만든 뒤 delay_secs 초 기다렸다가 21.0.11 을 찍는다.
    fn fake_java_with_slow_version(&self, started: &Path, delay_secs: u32, body: &str) {
        let path = self.java();
        let script = format!(
            "#!/bin/sh\n# {MARKER}\nif [ \"$1\" = \"-version\" ]; then\n  touch '{}'\n  sleep {delay_secs}\n  echo 'openjdk version \"21.0.11\" 2026-04-21 LTS' >&2\n  exit 0\nfi\ntouch spawned\n{body}\n",
            started.display()
        );
        fs::write(&path, script).expect("script");
        fs::set_permissions(&path, fs::Permissions::from_mode(0o755)).expect("chmod");
    }

    /// 셸 환경: 넘겨야 하는 값과 걸러야 하는 값(QUERYHUB_OTHER, SPRING_PROFILES_ACTIVE, JVM 옵션)을 함께 넣는다.
    fn env(&self) -> Env {
        let java = self.java().display().to_string();
        let jar = self.jar().display().to_string();
        [
            ("PATH", "/usr/bin:/bin"),
            (launch::JAVA_VAR, java.as_str()),
            (launch::JAR_VAR, jar.as_str()),
            ("QUERYHUB_OTHER", "other"),
            ("SPRING_PROFILES_ACTIVE", "server"),
            ("JAVA_TOOL_OPTIONS", "-Dinjected=1"),
            ("JDK_JAVA_OPTIONS", "-Dinjected=2"),
            ("_JAVA_OPTIONS", "-Dinjected=3"),
        ]
        .iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect()
    }

    fn config(&self, port: u16, probe: fn(u16) -> bool) -> SupervisorConfig {
        let mut config = SupervisorConfig::desktop(self.work(), self.key_path(), self.data_dir());
        config.port = port;
        config.default_jar = None;
        config.readiness_interval = Duration::from_millis(50);
        config.health_interval = Duration::from_millis(100);
        config.startup_timeout = Duration::from_secs(10);
        config.stop_grace = Duration::from_secs(3);
        config.monitor_interval = Duration::from_millis(20);
        config.java_version_timeout = Duration::from_secs(5);
        config.connect_timeout = Duration::from_millis(200);
        config.io_timeout = Duration::from_millis(500);
        config.port_probe = probe;
        config.echo_to_stderr = false;
        config
    }
}

impl Drop for Sandbox {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}

/// 가짜 서버가 이미 포트를 쓰므로 포트 확인을 건너뛴다.
fn always_free(_: u16) -> bool {
    true
}

/// free_port 가 고르는 범위의 시작과 크기: 20000-31999. macOS(49152-65535)와 Linux(32768-60999)의 임시 포트 범위 밖이다.
const PORT_RANGE_FIRST: u16 = 20000;
const PORT_RANGE_SIZE: u16 = 12000;

/// 이 테스트 바이너리 안에서 겹치지 않는, 아무도 듣지 않는 포트. 실제 포트 확인(launch::port_is_free)으로 고른다.
///
/// 예전에는 127.0.0.1:0 에 바인딩했다가 놓은 임시 포트를 썼다. 그 포트는 놓은 순간부터 다른 테스트 스레드의 나가는 연결
/// (FakeServer 로 가는 readiness·health 확인 등)이 로컬 포트로 받을 수 있고, 그러면 감독의 port_is_free 가 사용 중으로 보아
/// PortInUse 로 실패했다(P1-02 리뷰 R4, P1-03 실행 A 의 한 번 실패: 포트 60435). 나가는 연결과 포트 0 바인딩은 임시 포트
/// 범위에서만 포트를 받으므로 그 범위 밖의 포트는 그렇게 빼앗기지 않는다. 테스트끼리는 원자 카운터로 서로 다른 포트를
/// 받는다. 시작점은 PID 로 흩는다(동시에 도는 다른 테스트 프로세스와 겹칠 가능성을 줄인다).
fn free_port() -> u16 {
    static NEXT: AtomicU16 = AtomicU16::new(0);
    let offset = (std::process::id() % u32::from(PORT_RANGE_SIZE)) as u16;
    for _ in 0..PORT_RANGE_SIZE {
        let n = NEXT.fetch_add(1, Ordering::SeqCst) % PORT_RANGE_SIZE;
        let port = PORT_RANGE_FIRST + (offset + n) % PORT_RANGE_SIZE;
        if launch::port_is_free(port) {
            return port;
        }
    }
    panic!("{PORT_RANGE_FIRST} 부터 {PORT_RANGE_SIZE} 개 중 빈 포트가 없다");
}

/// 테스트마다 새 관리 토큰.
fn token() -> AdminToken {
    AdminToken::generate().expect("token")
}

fn alive(pid: u32) -> bool {
    // SAFETY: 신호 0 은 존재 여부만 확인한다.
    unsafe { libc::kill(pid as libc::pid_t, 0) == 0 }
}

fn wait_gone(pid: u32, timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    while alive(pid) {
        if Instant::now() >= deadline {
            return false;
        }
        thread::sleep(Duration::from_millis(20));
    }
    true
}

fn wait_for(
    supervisor: &Supervisor,
    timeout: Duration,
    what: &str,
    condition: impl Fn(&RuntimeStatus) -> bool,
) -> RuntimeStatus {
    let deadline = Instant::now() + timeout;
    loop {
        let status = supervisor.status();
        if condition(&status) {
            return status;
        }
        assert!(
            Instant::now() < deadline,
            "{what}: 시간 안에 되지 않았다. 마지막 상태: {status:?}"
        );
        thread::sleep(Duration::from_millis(20));
    }
}

fn wait_file(path: &Path, timeout: Duration) {
    let deadline = Instant::now() + timeout;
    while !path.exists() {
        assert!(
            Instant::now() < deadline,
            "{} 이 생기지 않았다",
            path.display()
        );
        thread::sleep(Duration::from_millis(20));
    }
}

fn failure_kind(status: &RuntimeStatus) -> Option<FailureKind> {
    status.failure.as_ref().map(|f| f.kind)
}

/// readiness 와 health 에 정해 둔 상태 코드로 답하는 루프백 HTTP 서버.
struct FakeServer {
    port: u16,
    health: Arc<AtomicU16>,
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl FakeServer {
    fn start(readiness: u16, health: u16) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        listener.set_nonblocking(true).expect("nonblocking");
        let port = listener.local_addr().expect("addr").port();
        let readiness = Arc::new(AtomicU16::new(readiness));
        let health = Arc::new(AtomicU16::new(health));
        let stop = Arc::new(AtomicBool::new(false));
        let (thread_health, thread_stop) = (Arc::clone(&health), Arc::clone(&stop));
        let thread = thread::spawn(move || {
            while !thread_stop.load(Ordering::SeqCst) {
                let Ok((stream, _)) = listener.accept() else {
                    thread::sleep(Duration::from_millis(5));
                    continue;
                };
                let _ = stream.set_nonblocking(false);
                let _ = stream.set_read_timeout(Some(Duration::from_secs(1)));
                let mut reader = BufReader::new(&stream);
                let mut request_line = String::new();
                let _ = reader.read_line(&mut request_line);
                let mut header = String::new();
                while reader
                    .read_line(&mut header)
                    .map(|n| n > 2)
                    .unwrap_or(false)
                {
                    header.clear();
                }
                let code = if request_line.starts_with("GET /actuator/health/readiness ") {
                    readiness.load(Ordering::SeqCst)
                } else if request_line.starts_with("GET /actuator/health ") {
                    thread_health.load(Ordering::SeqCst)
                } else {
                    404
                };
                let response = format!(
                    "HTTP/1.1 {code} Fake\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                );
                let _ = (&stream).write_all(response.as_bytes());
            }
        });
        FakeServer {
            port,
            health,
            stop,
            thread: Some(thread),
        }
    }
}

impl Drop for FakeServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

const WAIT: Duration = Duration::from_secs(10);

// (1)
#[test]
fn starting_becomes_running_once_readiness_answers_200() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", SLEEP_LOOP);
    let server = FakeServer::start(200, 200);
    let supervisor = Supervisor::new(
        sandbox.config(server.port, always_free),
        sandbox.env(),
        token(),
    );

    let first = supervisor.start();
    assert!(
        matches!(first.state, RuntimeState::Starting | RuntimeState::Running),
        "{first:?}"
    );
    let pid = first.pid.expect("pid while starting");
    let running = wait_for(&supervisor, WAIT, "running + up", |s| {
        s.state == RuntimeState::Running && s.health == Health::Up
    });
    assert_eq!(running.pid, Some(pid));
    assert_eq!(running.port, server.port);
    assert_eq!(
        running.base_url,
        format!("http://127.0.0.1:{}", server.port)
    );
    assert!(running.failure.is_none());

    assert_eq!(supervisor.stop().state, RuntimeState::Stopped);
    assert!(!alive(pid));
}

// (2)
#[test]
fn readiness_that_never_answers_200_ends_in_startup_timeout_and_kills_the_child() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", SLEEP_LOOP);
    let server = FakeServer::start(503, 503);
    let mut config = sandbox.config(server.port, always_free);
    config.startup_timeout = Duration::from_secs(1);
    let supervisor = Supervisor::new(config, sandbox.env(), token());

    let pid = supervisor.start().pid.expect("pid");
    let failed = wait_for(&supervisor, WAIT, "startup_timeout", |s| {
        failure_kind(s) == Some(FailureKind::StartupTimeout)
    });
    assert_eq!(failed.state, RuntimeState::Failed);
    assert_eq!(failed.pid, None);
    assert!(
        wait_gone(pid, Duration::from_secs(2)),
        "자식 {pid} 이 남아 있다"
    );
}

// (3)
#[test]
fn an_exiting_child_gives_failed_exited_with_a_masked_tail_and_no_token_or_key_in_json() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", EXIT_WITH_SECRETS);
    let supervisor = Supervisor::new(
        sandbox.config(free_port(), launch::port_is_free),
        sandbox.env(),
        token(),
    );

    supervisor.start();
    let failed = wait_for(&supervisor, WAIT, "failed(exited) with tail", |s| {
        failure_kind(s) == Some(FailureKind::Exited)
            && s.failure
                .as_ref()
                .is_some_and(|f| f.output_tail.iter().any(|l| l.contains("store failed")))
    });
    let failure = failed.failure.as_ref().expect("failure");
    assert_eq!(failed.state, RuntimeState::Failed);
    assert_eq!(failure.exit_code, Some(3));
    // 데이터 디렉터리 경로는 비밀이 아니므로 가리지 않는다(가리는 값은 토큰과 키뿐이다, AC21).
    let line = failure
        .output_tail
        .iter()
        .find(|l| l.contains("store failed"))
        .expect("line");
    assert_eq!(
        line,
        &format!("store failed at data dir={}", sandbox.data_dir().display())
    );
    // 관리 토큰도 가려진다(stdout 과 stderr 의 줄 모두).
    let token = supervisor.admin_token().expose().to_string();
    let token_lines: Vec<&String> = failure
        .output_tail
        .iter()
        .filter(|l| l.starts_with("admin token="))
        .collect();
    assert!(!token_lines.is_empty(), "{:?}", failure.output_tail);
    for line in token_lines {
        assert_eq!(line, &format!("admin token={MASK}"));
    }
    // AC21: 마스터 키도 가려진다(stdout 과 stderr 의 줄 모두).
    let key = sandbox.key_value();
    let key_lines: Vec<&String> = failure
        .output_tail
        .iter()
        .filter(|l| l.starts_with("master key="))
        .collect();
    assert_eq!(key_lines.len(), 2, "{:?}", failure.output_tail);
    for line in key_lines {
        assert_eq!(line, &format!("master key={MASK}"));
    }

    let json = serde_json::to_string(&failed).expect("json");
    assert!(!json.contains(&token), "토큰이 상태 JSON 에 있다");
    let failure_json = serde_json::to_string(failure).expect("failure json");
    assert!(
        !failure_json.contains(&token),
        "토큰이 Failure JSON 에 있다"
    );
    assert!(
        !format!("{failed:?}").contains(&token),
        "토큰이 Debug 에 있다"
    );
    assert!(!json.contains(&key), "마스터 키가 상태 JSON 에 있다");
    assert!(
        !failure_json.contains(&key),
        "마스터 키가 Failure JSON 에 있다"
    );
    assert!(
        !format!("{failed:?}").contains(&key),
        "마스터 키가 Debug 에 있다"
    );
    for expected in [
        "\"state\":\"failed\"",
        "\"health\":\"unknown\"",
        "\"pid\":null",
        "\"baseUrl\":\"http://127.0.0.1:",
        "\"kind\":\"exited\"",
        "\"exitCode\":3",
        "\"outputTail\":[",
    ] {
        assert!(json.contains(expected), "{expected} 가 없다: {json}");
    }
}

// (3) AC21: 자식 출력에서 가리는 값은 관리 토큰과 마스터 키, 정확히 이 둘이다(Metadata DB 비밀번호는 없다).
#[test]
fn the_output_mask_list_is_exactly_the_admin_token_and_the_master_key() {
    let token = token();
    // 시험용 고정 키(NOT A SECRET).
    let key = MasterKey::from_test_value(&"0123456789abcdef".repeat(4));
    let secrets = supervisor::output_secrets(&token, &key);
    assert_eq!(
        secrets.to_vec(),
        vec![
            token.expose().as_bytes().to_vec(),
            key.expose().as_bytes().to_vec()
        ]
    );
}

// (4)
#[test]
fn stop_sends_sigterm_and_gives_stopped() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", SLEEP_LOOP);
    let supervisor = Supervisor::new(
        sandbox.config(free_port(), launch::port_is_free),
        sandbox.env(),
        token(),
    );
    let pid = supervisor.start().pid.expect("pid");
    wait_file(&sandbox.work().join("spawned"), WAIT);

    let started = Instant::now();
    let stopped = supervisor.stop();
    assert!(
        started.elapsed() < Duration::from_secs(2),
        "SIGTERM 으로 끝나지 않았다 (유예 3 s 를 기다렸다): {:?}",
        started.elapsed()
    );
    assert_eq!(stopped.state, RuntimeState::Stopped);
    assert_eq!(stopped.pid, None);
    assert!(stopped.failure.is_none());
    assert!(!alive(pid));
}

// (4)
#[test]
fn stop_kills_a_child_that_ignores_sigterm_after_the_grace_period() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", TERM_IGNORING);
    let mut config = sandbox.config(free_port(), launch::port_is_free);
    config.stop_grace = Duration::from_secs(1);
    let supervisor = Supervisor::new(config, sandbox.env(), token());
    let pid = supervisor.start().pid.expect("pid");
    wait_file(&sandbox.work().join("trapped"), WAIT);

    let started = Instant::now();
    let stopped = supervisor.stop();
    let elapsed = started.elapsed();
    assert!(
        elapsed >= Duration::from_secs(1),
        "유예 전에 끝났다: {elapsed:?}"
    );
    assert!(
        elapsed < Duration::from_secs(5),
        "너무 오래 걸렸다: {elapsed:?}"
    );
    assert_eq!(stopped.state, RuntimeState::Stopped);
    assert!(!alive(pid));
}

// (5)
#[test]
fn restart_gives_running_with_a_different_pid() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", SLEEP_LOOP);
    let server = FakeServer::start(200, 200);
    let supervisor = Supervisor::new(
        sandbox.config(server.port, always_free),
        sandbox.env(),
        token(),
    );
    supervisor.start();
    let first = wait_for(&supervisor, WAIT, "running", |s| {
        s.state == RuntimeState::Running
    })
    .pid
    .expect("pid");

    let restarted = supervisor.restart();
    let second = restarted.pid.expect("new pid");
    assert_ne!(first, second);
    assert!(!alive(first));
    let running = wait_for(&supervisor, WAIT, "running again", |s| {
        s.state == RuntimeState::Running
    });
    assert_eq!(running.pid, Some(second));
}

// (5) 첫 시작이 키 파일을 한 번 만들고, 재시작은 같은 파일을 다시 읽기만 한다(바이트와 수정 시각이 그대로다).
#[test]
fn the_first_start_creates_the_key_file_once_and_a_restart_reuses_it() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", SLEEP_LOOP);
    let server = FakeServer::start(200, 200);
    let supervisor = Supervisor::new(
        sandbox.config(server.port, always_free),
        sandbox.env(),
        token(),
    );
    assert!(!sandbox.key_path().exists());
    supervisor.start();
    wait_for(&supervisor, WAIT, "running", |s| {
        s.state == RuntimeState::Running
    });
    let bytes = fs::read(sandbox.key_path()).expect("key file");
    let modified = fs::metadata(sandbox.key_path())
        .and_then(|m| m.modified())
        .expect("mtime");
    let mode = |path: &Path| fs::metadata(path).expect("meta").permissions().mode() & 0o777;
    assert_eq!(mode(&sandbox.key_path()), 0o600);
    assert_eq!(mode(sandbox.key_path().parent().expect("dir")), 0o700);

    supervisor.restart();
    wait_for(&supervisor, WAIT, "running again", |s| {
        s.state == RuntimeState::Running
    });
    assert_eq!(fs::read(sandbox.key_path()).expect("key file"), bytes);
    assert_eq!(
        fs::metadata(sandbox.key_path())
            .and_then(|m| m.modified())
            .expect("mtime"),
        modified
    );
}

// (5) AC21: 형식이 틀린 키 파일이면 failed(master_key_unavailable)이고, 자식을 띄우지 않으며, 파일을 바꾸지 않는다.
#[test]
fn a_malformed_key_file_gives_master_key_unavailable_spawns_nothing_and_keeps_the_file() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", SLEEP_LOOP);
    let dir = sandbox.key_path().parent().expect("dir").to_path_buf();
    fs::create_dir_all(&dir).expect("dir");
    fs::set_permissions(&dir, fs::Permissions::from_mode(0o700)).expect("chmod");
    let contents = "not-a-key-marker-0123456789\n";
    fs::write(sandbox.key_path(), contents).expect("key file");
    fs::set_permissions(sandbox.key_path(), fs::Permissions::from_mode(0o600)).expect("chmod");
    let supervisor = Supervisor::new(
        sandbox.config(free_port(), launch::port_is_free),
        sandbox.env(),
        token(),
    );

    let status = supervisor.start();
    assert_eq!(status.state, RuntimeState::Failed);
    assert_eq!(
        failure_kind(&status),
        Some(FailureKind::MasterKeyUnavailable)
    );
    let failure = status.failure.as_ref().expect("failure");
    assert!(failure.output_tail.is_empty());
    assert_eq!(failure.exit_code, None);
    assert!(
        failure
            .message
            .contains(&sandbox.key_path().display().to_string()),
        "{}",
        failure.message
    );
    assert!(
        !failure.message.contains("not-a-key-marker"),
        "{}",
        failure.message
    );
    assert_eq!(status.pid, None);
    assert!(!sandbox.spawned(), "자식을 띄웠다");
    assert_eq!(
        fs::read_to_string(sandbox.key_path()).expect("key file"),
        contents
    );
    let json = serde_json::to_string(&status).expect("json");
    assert!(
        json.contains("\"kind\":\"master_key_unavailable\""),
        "{json}"
    );
}

// (6) AC19: 자식은 허용 목록의 셸 환경, 셸이 준비한 데이터 디렉터리, 토큰, 키, 셸 PID 만 받는다.
#[test]
fn the_child_gets_only_the_data_dir_the_token_the_key_the_parent_pid_and_the_fixed_argv() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", DUMP_ENV_AND_ARGS);
    let port = free_port();
    // 주입한 셸 환경: 허용 목록의 변수와, Spring 이 바인딩하는 변수(바인딩 주소, 엔드포인트 노출, 로깅)와 아무 변수.
    let tmpdir = sandbox.root.join("tmp").display().to_string();
    let allowed = [
        ("HOME", "/nonexistent-queryhub-home"),
        ("LANG", "C"),
        ("LC_ALL", "C"),
        ("TZ", "UTC"),
        ("TMPDIR", tmpdir.as_str()),
    ];
    let denied = [
        ("SERVER_ADDRESS", "0.0.0.0"),
        ("MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE", "*"),
        ("LOGGING_LEVEL_ROOT", "TRACE"),
        ("DEBUG", "true"),
        ("SPRING_APPLICATION_JSON", "{}"),
        ("FOO_BAR", "baz"),
        // 셸 자신의 환경에 있는 토큰 변수는 넘어가지 않는다(자식의 토큰은 셸이 만든 값이다).
        (launch::ADMIN_TOKEN_FILE_VAR, "/x"),
        // 키 변수도 넘어가지 않는다(자식의 키는 셸이 키 파일에서 읽은 값이다).
        (launch::MASTER_KEY_FILE_VAR, "/x"),
        (launch::MASTER_KEY_PREVIOUS_VAR, "inherited"),
        (launch::MASTER_KEY_PREVIOUS_FILE_VAR, "/x"),
        // 셸 자신의 네이티브 디렉터리도 넘어가지 않는다(데스크톱은 런타임의 기본값 <데이터 디렉터리>/native 를 쓴다).
        (launch::SQLITE_NATIVE_DIR_VAR, "/x"),
    ];
    // 없앤 Metadata DB 변수(P1-06): 셸 환경에 있어도 넘어가지 않는다.
    let retired: Vec<String> = ["HOST", "PORT", "NAME", "USERNAME", "PASSWORD"]
        .iter()
        .map(|suffix| format!("{RETIRED_METADATA_PREFIX}{suffix}"))
        .collect();
    let mut injected = sandbox.env();
    for (name, value) in allowed.iter().chain(denied.iter()) {
        injected.insert(name.to_string(), value.to_string());
    }
    for name in &retired {
        injected.insert(name.clone(), "inherited".to_string());
    }
    injected.insert(launch::ADMIN_TOKEN_VAR.to_string(), "inherited".to_string());
    injected.insert(launch::MASTER_KEY_VAR.to_string(), "inherited".to_string());
    // 셸 자신의 QUERYHUB_DATA_DIR 도 넘어가지 않는다(자식의 값은 셸이 준비한 경로다).
    injected.insert(launch::DATA_DIR_VAR.to_string(), "inherited".to_string());
    let supervisor = Supervisor::new(
        sandbox.config(port, launch::port_is_free),
        injected,
        token(),
    );
    supervisor.start();
    wait_file(&sandbox.work().join("child-env.txt"), WAIT);
    wait_file(&sandbox.work().join("child-argv.txt"), WAIT);

    let env_text = fs::read_to_string(sandbox.work().join("child-env.txt")).expect("env");
    let env: BTreeMap<&str, &str> = env_text
        .lines()
        .filter_map(|line| line.split_once('='))
        .collect();
    let data_dir = sandbox.data_dir().display().to_string();
    assert_eq!(env.get(launch::DATA_DIR_VAR), Some(&data_dir.as_str()));
    let parent_pid = std::process::id().to_string();
    assert_eq!(env.get(launch::PARENT_PID_VAR), Some(&parent_pid.as_str()));
    let token = supervisor.admin_token().expose().to_string();
    assert_eq!(env.get(launch::ADMIN_TOKEN_VAR), Some(&token.as_str()));
    // AC20: 자식의 키는 키 파일의 값이다.
    let key = sandbox.key_value();
    assert_eq!(env.get(launch::MASTER_KEY_VAR), Some(&key.as_str()));
    assert!(
        env.values().all(|v| *v != "inherited"),
        "셸의 토큰, 키, 데이터 디렉터리, Metadata DB 값이 넘어갔다"
    );
    assert_eq!(env.get("PATH"), Some(&"/usr/bin:/bin"));
    for (name, value) in allowed {
        assert_eq!(env.get(name), Some(&value), "{name}");
    }
    let not_inherited = [
        "QUERYHUB_OTHER",
        "SPRING_PROFILES_ACTIVE",
        "JAVA_TOOL_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "_JAVA_OPTIONS",
        launch::JAVA_VAR,
        launch::JAR_VAR,
    ];
    for stripped in not_inherited
        .into_iter()
        .chain(denied.iter().map(|(name, _)| *name))
        .chain(retired.iter().map(String::as_str))
    {
        assert!(
            !env.contains_key(stripped),
            "{stripped} 가 자식 환경에 있다"
        );
    }
    let queryhub: Vec<&str> = env
        .keys()
        .copied()
        .filter(|k| k.starts_with("QUERYHUB_"))
        .collect();
    assert_eq!(
        queryhub,
        vec![
            launch::ADMIN_TOKEN_VAR,
            launch::DATA_DIR_VAR,
            launch::PARENT_PID_VAR,
            launch::MASTER_KEY_VAR
        ]
    );

    let argv_text = fs::read_to_string(sandbox.work().join("child-argv.txt")).expect("argv");
    let argv: Vec<&str> = argv_text.lines().collect();
    let jar = sandbox.jar().display().to_string();
    let port_arg = format!("--server.port={port}");
    assert_eq!(
        argv,
        vec![
            "-jar",
            jar.as_str(),
            "--spring.profiles.active=desktop",
            port_arg.as_str()
        ]
    );
    assert!(
        argv.iter().all(|a| !a.contains(&data_dir)),
        "데이터 디렉터리가 argv 에 있다"
    );
    assert!(
        argv.iter().all(|a| !a.contains(&token)),
        "토큰이 argv 에 있다"
    );
    assert!(argv.iter().all(|a| !a.contains(&key)), "키가 argv 에 있다");
}

// (6) java -version 사전 확인의 환경에는 토큰도 키도 데이터 디렉터리도 없다(허용 목록의 셸 환경뿐이다).
#[test]
fn the_java_version_preflight_gets_neither_the_token_nor_the_data_dir() {
    let sandbox = Sandbox::new();
    let version_env = sandbox.root.join("version-env.txt");
    let script = format!(
        "#!/bin/sh\n# {MARKER}\nif [ \"$1\" = \"-version\" ]; then\n  env > '{}'\n  echo 'openjdk version \"21.0.11\" 2026-04-21 LTS' >&2\n  exit 0\nfi\ntouch spawned\n{SLEEP_LOOP}\n",
        version_env.display()
    );
    fs::write(sandbox.java(), script).expect("script");
    fs::set_permissions(sandbox.java(), fs::Permissions::from_mode(0o755)).expect("chmod");
    let mut injected = sandbox.env();
    injected.insert(launch::ADMIN_TOKEN_VAR.to_string(), "inherited".to_string());
    injected.insert(launch::MASTER_KEY_VAR.to_string(), "inherited".to_string());
    injected.insert(launch::DATA_DIR_VAR.to_string(), "inherited".to_string());
    let supervisor = Supervisor::new(
        sandbox.config(free_port(), launch::port_is_free),
        injected,
        token(),
    );
    let pid = supervisor.start().pid.expect("pid");
    let token = supervisor.admin_token().expose().to_string();
    let key = sandbox.key_value();
    let text_for_key = fs::read_to_string(&version_env).expect("version env");
    assert!(
        !text_for_key.contains(&key),
        "키가 java -version 환경에 있다"
    );

    let text = fs::read_to_string(&version_env).expect("version env");
    let names: Vec<&str> = text
        .lines()
        .filter_map(|line| line.split_once('=').map(|(k, _)| k))
        .collect();
    assert!(!text.contains(&token), "토큰이 java -version 환경에 있다");
    assert!(!text.contains("inherited"));
    assert!(!text.contains(&sandbox.data_dir().display().to_string()));
    for absent in [
        launch::ADMIN_TOKEN_VAR,
        launch::ADMIN_TOKEN_FILE_VAR,
        launch::MASTER_KEY_VAR,
        launch::DATA_DIR_VAR,
        launch::PARENT_PID_VAR,
    ] {
        assert!(!names.contains(&absent), "{absent} 가 있다: {names:?}");
    }
    assert_eq!(supervisor.stop().state, RuntimeState::Stopped);
    assert!(!alive(pid));
}

// (7) AC20: 셸 환경에 허용 목록의 변수만 있어도(QUERYHUB_* 없음, java 는 PATH, JAR 은 기본 경로) 런타임을 띄운다.
// 없던 데이터 디렉터리를 0700 으로 만들고 자식에게 넘긴다. 셸은 그 안에 파일을 만들지 않는다. 재시작은 같은 디렉터리와
// 그 안의 파일을 그대로 쓴다.
#[test]
fn an_allow_listed_shell_env_spawns_the_runtime_and_the_missing_data_dir_is_created_0700() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", DUMP_ENV_AND_ARGS);
    let server = FakeServer::start(200, 200);
    let mut config = sandbox.config(server.port, always_free);
    config.default_jar = Some(sandbox.jar());
    let path = format!("{}:/usr/bin:/bin", sandbox.root.join("bin").display());
    let env: Env = [
        ("PATH", path.as_str()),
        ("HOME", "/nonexistent-queryhub-home"),
        ("LANG", "C"),
    ]
    .iter()
    .map(|(k, v)| (k.to_string(), v.to_string()))
    .collect();
    assert!(env.keys().all(|name| launch::is_inherited(name)), "{env:?}");
    assert!(!sandbox.data_dir().exists());
    let supervisor = Supervisor::new(config, env, token());

    let first = supervisor.start();
    assert!(first.failure.is_none(), "{first:?}");
    wait_for(&supervisor, WAIT, "running + up", |s| {
        s.state == RuntimeState::Running && s.health == Health::Up
    });
    assert!(sandbox.spawned());
    let mode = |path: &Path| {
        fs::symlink_metadata(path)
            .expect("meta")
            .permissions()
            .mode()
            & 0o777
    };
    assert_eq!(mode(&sandbox.data_dir()), 0o700);
    assert_eq!(
        fs::read_dir(sandbox.data_dir()).expect("list").count(),
        0,
        "셸이 데이터 디렉터리 안에 무엇인가를 만들었다"
    );
    let child_env = sandbox.work().join("child-env.txt");
    wait_file(&child_env, WAIT);
    let text = fs::read_to_string(&child_env).expect("env");
    let data_dir = sandbox.data_dir().display().to_string();
    assert!(
        text.lines()
            .any(|line| line == format!("{}={data_dir}", launch::DATA_DIR_VAR)),
        "{text}"
    );

    // 런타임이 만든 저장소 파일 자리. 재시작은 디렉터리와 파일을 바꾸지 않는다.
    let store = sandbox.data_dir().join("queryhub.db");
    fs::write(&store, b"store-marker").expect("store");
    fs::set_permissions(&store, fs::Permissions::from_mode(0o600)).expect("chmod");
    let inode = |path: &Path| {
        use std::os::unix::fs::MetadataExt;
        fs::symlink_metadata(path).expect("meta").ino()
    };
    let (dir_inode, store_inode) = (inode(&sandbox.data_dir()), inode(&store));
    fs::remove_file(&child_env).expect("rm child-env");
    supervisor.restart();
    wait_for(&supervisor, WAIT, "running again", |s| {
        s.state == RuntimeState::Running
    });
    wait_file(&child_env, WAIT);
    assert_eq!(inode(&sandbox.data_dir()), dir_inode);
    assert_eq!(inode(&store), store_inode);
    assert_eq!(fs::read(&store).expect("store"), b"store-marker");
    assert_eq!(mode(&sandbox.data_dir()), 0o700);
    assert_eq!(mode(&store), 0o600);
    assert_eq!(supervisor.stop().state, RuntimeState::Stopped);
}

// (7) AC20: 심볼릭 링크, 디렉터리가 아닌 것, 0755/0750 인 데이터 디렉터리는 failed(data_dir_unavailable)이고, 자식을 띄우지
// 않으며, 바꾸지 않는다. 메시지에는 경로와 고치는 방법이 있고 토큰과 키는 없다. 키는 마지막 확인이라 만들지 않는다.
#[test]
fn a_symlinked_non_directory_or_open_data_dir_gives_data_dir_unavailable_and_spawns_nothing() {
    use std::os::unix::fs::symlink;
    let mode = |path: &Path| {
        fs::symlink_metadata(path)
            .expect("meta")
            .permissions()
            .mode()
            & 0o777
    };
    for case in ["symlink", "file", "0755", "0750"] {
        let sandbox = Sandbox::new();
        sandbox.fake_java("21.0.11", SLEEP_LOOP);
        let data_dir = sandbox.data_dir();
        let real = sandbox.root.join("real-data");
        match case {
            "symlink" => {
                fs::create_dir_all(&real).expect("real");
                fs::set_permissions(&real, fs::Permissions::from_mode(0o700)).expect("chmod");
                symlink(&real, &data_dir).expect("symlink");
            }
            "file" => fs::write(&data_dir, b"not a directory").expect("file"),
            open => {
                fs::create_dir_all(&data_dir).expect("dir");
                let bits = u32::from_str_radix(open, 8).expect("mode");
                fs::set_permissions(&data_dir, fs::Permissions::from_mode(bits)).expect("chmod");
            }
        }
        let supervisor = Supervisor::new(
            sandbox.config(free_port(), launch::port_is_free),
            sandbox.env(),
            token(),
        );

        let status = supervisor.start();
        assert_eq!(status.state, RuntimeState::Failed, "{case}");
        assert_eq!(
            failure_kind(&status),
            Some(FailureKind::DataDirUnavailable),
            "{case}: {status:?}"
        );
        let failure = status.failure.as_ref().expect("failure");
        assert!(failure.output_tail.is_empty());
        assert_eq!(failure.exit_code, None);
        assert_eq!(status.pid, None);
        assert!(!sandbox.spawned(), "{case}: 자식을 띄웠다");
        assert!(!sandbox.key_path().exists(), "{case}: 키 파일을 만들었다");
        let message = &failure.message;
        assert!(
            message.contains(&data_dir.display().to_string()),
            "{case}: {message}"
        );
        let expected = match case {
            "symlink" => "심볼릭 링크".to_string(),
            "file" => "디렉터리가 아닌".to_string(),
            _ => format!("chmod 700 '{}'", data_dir.display()),
        };
        assert!(message.contains(&expected), "{case}: {message}");
        // 토큰도 키도 없다: 경로를 뺀 메시지에 16진수가 16 자 넘게 이어진 곳이 없다.
        let token = supervisor.admin_token().expose().to_string();
        assert!(!message.contains(&token), "{case}: 토큰이 메시지에 있다");
        let without_paths = message.replace(&sandbox.root.display().to_string(), "<root>");
        let mut run = 0;
        for c in without_paths.chars() {
            run = if c.is_ascii_hexdigit() { run + 1 } else { 0 };
            assert!(run < 16, "{case}: 16진수가 이어진다: {message}");
        }
        let json = serde_json::to_string(&status).expect("json");
        assert!(json.contains("\"kind\":\"data_dir_unavailable\""), "{json}");
        assert!(!json.contains(&token), "{case}: 토큰이 상태 JSON 에 있다");
        match case {
            "symlink" => {
                assert!(fs::symlink_metadata(&data_dir)
                    .expect("link")
                    .file_type()
                    .is_symlink());
                assert_eq!(fs::read_dir(&real).expect("list").count(), 0);
            }
            "file" => assert_eq!(fs::read(&data_dir).expect("file"), b"not a directory"),
            open => assert_eq!(
                mode(&data_dir),
                u32::from_str_radix(open, 8).expect("mode"),
                "{case}: 권한을 저절로 바꾸었다"
            ),
        }
    }
}

// (8)
#[test]
fn a_bound_port_gives_port_in_use_without_spawning() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", SLEEP_LOOP);
    let occupied = TcpListener::bind("127.0.0.1:0").expect("bind");
    let port = occupied.local_addr().expect("addr").port();
    let supervisor = Supervisor::new(
        sandbox.config(port, launch::port_is_free),
        sandbox.env(),
        token(),
    );

    let status = supervisor.start();
    assert_eq!(failure_kind(&status), Some(FailureKind::PortInUse));
    let message = &status.failure.as_ref().expect("failure").message;
    assert!(
        message.contains(&format!("lsof -nP -iTCP:{port} -sTCP:LISTEN")),
        "{message}"
    );
    assert_eq!(status.pid, None);
    assert!(!sandbox.spawned());
}

// (8) 0.0.0.0 에서 듣는 리스너도 port_in_use 다(127.0.0.1 바인딩은 SO_REUSEADDR 로 성공한다).
#[test]
fn a_wildcard_listener_gives_port_in_use_without_spawning() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", SLEEP_LOOP);
    let occupied = TcpListener::bind("0.0.0.0:0").expect("bind");
    let port = occupied.local_addr().expect("addr").port();
    let supervisor = Supervisor::new(
        sandbox.config(port, launch::port_is_free),
        sandbox.env(),
        token(),
    );

    let status = supervisor.start();
    assert_eq!(
        failure_kind(&status),
        Some(FailureKind::PortInUse),
        "{status:?}"
    );
    assert_eq!(status.pid, None);
    assert!(!sandbox.spawned());
}

// (9)
#[test]
fn java_is_resolved_from_queryhub_desktop_java_then_java_home_then_path() {
    let sandbox = Sandbox::new();
    let explicit = sandbox.root.join("explicit").join("java");
    let home = sandbox.root.join("home");
    let from_home = home.join("bin").join("java");
    let path_dir = sandbox.root.join("pathdir");
    let from_path = path_dir.join("java");
    for java in [&explicit, &from_home, &from_path] {
        sandbox.fake_java_at(java, "21.0.11", SLEEP_LOOP);
    }
    let empty_dir = sandbox.root.join("empty");
    fs::create_dir_all(&empty_dir).expect("empty");

    let mut env = Env::new();
    env.insert(launch::JAVA_VAR.into(), explicit.display().to_string());
    env.insert("JAVA_HOME".into(), home.display().to_string());
    env.insert(
        "PATH".into(),
        format!("{}:{}", empty_dir.display(), path_dir.display()),
    );
    assert_eq!(launch::resolve_java(&env).expect("explicit"), explicit);

    env.remove(launch::JAVA_VAR);
    assert_eq!(launch::resolve_java(&env).expect("home"), from_home);

    env.insert(
        "JAVA_HOME".into(),
        sandbox.root.join("no-such-home").display().to_string(),
    );
    assert_eq!(launch::resolve_java(&env).expect("path"), from_path);

    env.remove("JAVA_HOME");
    env.insert("PATH".into(), empty_dir.display().to_string());
    let missing = launch::resolve_java(&env).expect_err("missing");
    assert_eq!(missing.kind, FailureKind::JavaNotFound);
    // 안내하는 최소 버전은 java::MIN_MAJOR(17)에서 온다 (P1-05, ADR-025).
    assert!(
        missing.message.contains("JDK 17 이상"),
        "{}",
        missing.message
    );

    // 명시한 경로가 틀리면 JAVA_HOME 으로 넘어가지 않는다.
    let wrong = sandbox.root.join("missing").join("java");
    env.insert(launch::JAVA_VAR.into(), wrong.display().to_string());
    env.insert("JAVA_HOME".into(), home.display().to_string());
    let failure = launch::resolve_java(&env).expect_err("explicit missing");
    assert_eq!(failure.kind, FailureKind::JavaNotFound);
    assert!(failure.message.contains(&wrong.display().to_string()));
    assert!(
        failure.message.contains("JDK 17 이상"),
        "{}",
        failure.message
    );
}

// (9) 기준선 Java 17 (P1-05, ADR-025): 16 은 거부한다.
#[test]
fn java_16_gives_java_too_old_naming_the_version_and_path() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("16.0.2", SLEEP_LOOP);
    let supervisor = Supervisor::new(
        sandbox.config(free_port(), launch::port_is_free),
        sandbox.env(),
        token(),
    );

    let status = supervisor.start();
    let failure = status.failure.as_ref().expect("failure");
    assert_eq!(failure.kind, FailureKind::JavaTooOld);
    assert!(failure.message.contains("16.0.2"), "{}", failure.message);
    assert!(failure
        .message
        .contains(&sandbox.java().display().to_string()));
    assert!(failure.message.contains(launch::JAVA_VAR));
    assert!(
        failure.message.contains("Java 17 이상"),
        "{}",
        failure.message
    );
    assert_eq!(status.pid, None);
    assert!(!sandbox.spawned());
}

// (9) 기준선 Java 17 (P1-05, ADR-025): 17 은 버전 확인을 통과하고 런타임을 띄운다.
#[test]
fn java_17_passes_the_version_check_and_spawns_the_runtime() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("17.0.19", SLEEP_LOOP);
    let server = FakeServer::start(200, 200);
    let supervisor = Supervisor::new(
        sandbox.config(server.port, always_free),
        sandbox.env(),
        token(),
    );

    let first = supervisor.start();
    assert!(first.failure.is_none(), "{first:?}");
    let running = wait_for(&supervisor, WAIT, "running + up", |s| {
        s.state == RuntimeState::Running && s.health == Health::Up
    });
    let pid = running.pid.expect("pid");
    assert!(sandbox.spawned());
    assert!(running.failure.is_none());

    assert_eq!(supervisor.stop().state, RuntimeState::Stopped);
    assert!(!alive(pid));
}

// (9)
#[test]
fn a_missing_java_gives_java_not_found() {
    let sandbox = Sandbox::new();
    let mut env = sandbox.env();
    let missing = sandbox.root.join("nowhere").join("java");
    env.insert(launch::JAVA_VAR.into(), missing.display().to_string());
    let supervisor = Supervisor::new(
        sandbox.config(free_port(), launch::port_is_free),
        env,
        token(),
    );

    let status = supervisor.start();
    assert_eq!(failure_kind(&status), Some(FailureKind::JavaNotFound));
    assert!(status
        .failure
        .as_ref()
        .expect("failure")
        .message
        .contains(&missing.display().to_string()));
    assert_eq!(status.pid, None);
}

// (10)
#[test]
fn a_missing_jar_gives_jar_not_found_naming_the_path() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", SLEEP_LOOP);
    let mut env = sandbox.env();
    let missing = sandbox.root.join("missing").join("queryhub-runtime.jar");
    env.insert(launch::JAR_VAR.into(), missing.display().to_string());
    let supervisor = Supervisor::new(
        sandbox.config(free_port(), launch::port_is_free),
        env,
        token(),
    );

    let status = supervisor.start();
    let failure = status.failure.as_ref().expect("failure");
    assert_eq!(failure.kind, FailureKind::JarNotFound);
    assert!(failure.message.contains(&missing.display().to_string()));
    assert!(failure.message.contains(launch::BOOT_JAR_COMMAND));
    assert!(!sandbox.spawned());

    // 변수도 기본 경로도 없으면(릴리스 빌드) Phase 8 계획을 말한다.
    let mut env = sandbox.env();
    env.remove(launch::JAR_VAR);
    let supervisor = Supervisor::new(
        sandbox.config(free_port(), launch::port_is_free),
        env,
        token(),
    );
    let status = supervisor.start();
    let failure = status.failure.as_ref().expect("failure");
    assert_eq!(failure.kind, FailureKind::JarNotFound);
    assert!(failure.message.contains("Phase 8"), "{}", failure.message);
}

// (11)
#[test]
fn dropping_the_supervisor_kills_a_running_child() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", SLEEP_LOOP);
    let server = FakeServer::start(200, 200);
    let supervisor = Supervisor::new(
        sandbox.config(server.port, always_free),
        sandbox.env(),
        token(),
    );
    supervisor.start();
    let pid = wait_for(&supervisor, WAIT, "running", |s| {
        s.state == RuntimeState::Running
    })
    .pid
    .expect("pid");

    drop(supervisor);
    assert!(!alive(pid), "Drop 뒤에 자식 {pid} 이 남아 있다");
}

// (11)
#[test]
fn shutdown_kills_the_child_and_blocks_later_starts() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", SLEEP_LOOP);
    let supervisor = Supervisor::new(
        sandbox.config(free_port(), launch::port_is_free),
        sandbox.env(),
        token(),
    );
    let pid = supervisor.start().pid.expect("pid");

    supervisor.shutdown();
    assert!(!alive(pid));
    assert_eq!(supervisor.status().state, RuntimeState::Stopped);
    let after = supervisor.start();
    assert_eq!(after.state, RuntimeState::Stopped);
    assert_eq!(after.pid, None);
}

// (11) 사전 확인(느린 java -version) 중에 shutdown 이 오면 자식을 띄우지 않는다.
#[test]
fn shutdown_during_preflight_spawns_nothing_and_blocks_later_starts() {
    let sandbox = Sandbox::new();
    let version_started = sandbox.root.join("version-started");
    sandbox.fake_java_with_slow_version(&version_started, 2, SLEEP_LOOP);
    let supervisor = Supervisor::new(
        sandbox.config(free_port(), launch::port_is_free),
        sandbox.env(),
        token(),
    );

    // 띄우자마자 정지되면 spawned 표식이 생기기 전에 끝날 수 있으므로, 상태에 자식이 한 번이라도 보였는지도 기록한다.
    let watching = Arc::new(AtomicBool::new(true));
    let saw_child = Arc::new(AtomicBool::new(false));
    let watcher = {
        let handle = supervisor.handle();
        let (watching, saw_child) = (Arc::clone(&watching), Arc::clone(&saw_child));
        thread::spawn(move || {
            while watching.load(Ordering::SeqCst) {
                let status = handle.status();
                if status.pid.is_some() || status.state == RuntimeState::Starting {
                    saw_child.store(true, Ordering::SeqCst);
                }
                thread::sleep(Duration::from_millis(2));
            }
        })
    };
    let starter = {
        let handle = supervisor.handle();
        thread::spawn(move || handle.start())
    };
    wait_file(&version_started, WAIT);

    let began = Instant::now();
    supervisor.shutdown();
    let waited = began.elapsed();
    let started = starter.join().expect("start thread");
    thread::sleep(Duration::from_millis(200));
    watching.store(false, Ordering::SeqCst);
    watcher.join().expect("watcher");

    assert_eq!(started.state, RuntimeState::Stopped, "{started:?}");
    assert_eq!(started.pid, None);
    assert!(
        !saw_child.load(Ordering::SeqCst),
        "사전 확인 뒤에 자식을 띄웠다"
    );
    assert!(!sandbox.spawned(), "자식이 실행되었다");
    assert!(
        waited < Duration::from_secs(5),
        "shutdown 이 사전 확인보다 오래 기다렸다: {waited:?}"
    );

    let after = supervisor.start();
    assert_eq!(after.state, RuntimeState::Stopped);
    assert_eq!(after.pid, None);
    assert!(!sandbox.spawned());
}

// (12)
#[test]
fn health_503_keeps_running_with_health_down_and_a_later_exit_gives_failed_exited() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", SLEEP_LOOP);
    let server = FakeServer::start(200, 503);
    let supervisor = Supervisor::new(
        sandbox.config(server.port, always_free),
        sandbox.env(),
        token(),
    );
    supervisor.start();

    let down = wait_for(&supervisor, WAIT, "running + down", |s| {
        s.state == RuntimeState::Running && s.health == Health::Down
    });
    assert!(down.failure.is_none());
    server.health.store(200, Ordering::SeqCst);
    wait_for(&supervisor, WAIT, "running + up", |s| {
        s.state == RuntimeState::Running && s.health == Health::Up
    });

    fs::write(sandbox.work().join("exit-now"), b"").expect("exit-now");
    let failed = wait_for(&supervisor, WAIT, "failed(exited)", |s| {
        failure_kind(s) == Some(FailureKind::Exited)
    });
    assert_eq!(failed.state, RuntimeState::Failed);
    assert_eq!(failed.failure.as_ref().expect("failure").exit_code, Some(7));
    assert_eq!(failed.pid, None);
    assert_eq!(failed.health, Health::Unknown);
}

// (13)
#[test]
fn start_while_running_and_stop_while_stopped_are_no_ops() {
    let sandbox = Sandbox::new();
    sandbox.fake_java("21.0.11", SLEEP_LOOP);
    let server = FakeServer::start(200, 200);
    let supervisor = Supervisor::new(
        sandbox.config(server.port, always_free),
        sandbox.env(),
        token(),
    );

    let never_started = supervisor.stop();
    assert_eq!(never_started.state, RuntimeState::Stopped);
    assert_eq!(never_started.pid, None);

    supervisor.start();
    let running = wait_for(&supervisor, WAIT, "running", |s| {
        s.state == RuntimeState::Running
    });
    let again = supervisor.start();
    assert_eq!(again.state, RuntimeState::Running);
    assert_eq!(again.pid, running.pid);

    assert_eq!(supervisor.stop().state, RuntimeState::Stopped);
    let twice = supervisor.stop();
    assert_eq!(twice.state, RuntimeState::Stopped);
    assert_eq!(twice.pid, None);
    assert!(twice.failure.is_none());
}
