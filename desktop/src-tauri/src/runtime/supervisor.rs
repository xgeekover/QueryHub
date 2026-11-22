//! 런타임 감독: 상태 기계, 시작·정지·재시작, 준비·건강 확인 스레드, 앱 종료와 Drop 의 정지.
//!
//! 잠금은 두 개다.
//! - lifecycle: start/stop/restart/shutdown 을 한 번에 하나씩 실행한다(오래 걸릴 수 있다).
//! - shared: 상태와 자식 핸들. 짧게만 잡는다. 그래서 status 는 수명 주기 작업을 기다리지 않고 바로 답한다.
//!
//! 자식을 거두는 일(try_wait, wait)과 신호를 보내는 일은 모두 shared 잠금 안에서 한다. 거두기 전에만 SIGTERM 을 보내므로
//! 이미 끝난 PID 가 다른 프로세스에 재사용되어도 그 프로세스에 신호가 가지 않는다.

use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

use super::launch::{self, Env};
use super::output::{self, OutputBuffer};
use super::token::AdminToken;
use super::{health, java, Failure, FailureKind, Health, RuntimeState, RuntimeStatus};

/// desktop 프로파일의 고정 포트(runtime/app/src/main/resources/application-desktop.yml). 운영 CSP 의 connect-src 와 같다.
pub const DESKTOP_PORT: u16 = 18580;

/// 정지 중 자식의 종료를 확인하는 간격.
const STOP_POLL: Duration = Duration::from_millis(50);

/// 감독 설정. 간격과 제한 시간은 모두 여기서 정한다(테스트는 짧게 준다).
#[derive(Clone, Debug)]
pub struct SupervisorConfig {
    pub port: u16,
    /// 자식의 작업 디렉터리(앱 로컬 데이터 디렉터리). 시작할 때 만든다.
    pub work_dir: PathBuf,
    /// QUERYHUB_DESKTOP_RUNTIME_JAR 가 없을 때 쓰는 JAR(디버그 빌드: 저장소의 bootJar 출력, 릴리스: 없음).
    pub default_jar: Option<PathBuf>,
    pub readiness_interval: Duration,
    pub health_interval: Duration,
    pub startup_timeout: Duration,
    pub stop_grace: Duration,
    /// 자식 종료를 확인하는 감시 스레드의 주기.
    pub monitor_interval: Duration,
    pub java_version_timeout: Duration,
    pub connect_timeout: Duration,
    pub io_timeout: Duration,
    pub output_lines: usize,
    pub output_line_bytes: usize,
    pub status_tail_lines: usize,
    /// 포트가 비어 있는지 확인한다(테스트에서 바꿀 수 있다).
    pub port_probe: fn(u16) -> bool,
    /// 상태 전이와 가린 자식 출력을 셸의 stderr 에 쓴다(디버그 빌드).
    pub echo_to_stderr: bool,
}

impl SupervisorConfig {
    /// 앱 설정: 포트 18580, readiness 500 ms, health 5 s, 기동 제한 90 s, 정지 유예 10 s.
    pub fn desktop(work_dir: PathBuf) -> Self {
        SupervisorConfig {
            port: DESKTOP_PORT,
            work_dir,
            default_jar: default_jar(),
            readiness_interval: Duration::from_millis(500),
            health_interval: Duration::from_secs(5),
            startup_timeout: Duration::from_secs(90),
            stop_grace: Duration::from_secs(10),
            monitor_interval: Duration::from_millis(100),
            java_version_timeout: Duration::from_secs(10),
            connect_timeout: Duration::from_secs(1),
            io_timeout: Duration::from_secs(2),
            output_lines: 200,
            output_line_bytes: 2048,
            status_tail_lines: 40,
            port_probe: launch::port_is_free,
            echo_to_stderr: cfg!(debug_assertions),
        }
    }

    pub fn base_url(&self) -> String {
        format!("http://127.0.0.1:{}", self.port)
    }
}

/// 디버그 빌드: <CARGO_MANIFEST_DIR>/../../runtime/app/build/libs/queryhub-runtime.jar.
#[cfg(debug_assertions)]
fn default_jar() -> Option<PathBuf> {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .ancestors()
        .nth(2)
        .map(|root| root.join("runtime/app/build/libs/queryhub-runtime.jar"))
}

/// 릴리스 빌드: 묶은 JAR 은 Phase 8 계획이므로 없다.
#[cfg(not(debug_assertions))]
fn default_jar() -> Option<PathBuf> {
    None
}

/// 앱이 소유하는 감독. Drop 하면 실행 중인 자식을 정지한다.
pub struct Supervisor {
    handle: SupervisorHandle,
}

/// 감독에 대한 복제 가능한 핸들(커맨드가 blocking 스레드로 넘길 때 쓴다). Drop 해도 자식을 멈추지 않는다.
#[derive(Clone)]
pub struct SupervisorHandle {
    inner: Arc<Inner>,
}

struct Inner {
    config: SupervisorConfig,
    env: Env,
    /// 셸 프로세스의 관리 토큰. 재시작해도 같은 값이다(셸 프로세스마다 한 번 만든다).
    token: AdminToken,
    shared: Mutex<Shared>,
    lifecycle: Mutex<()>,
    output: Arc<OutputBuffer>,
    shut_down: AtomicBool,
}

struct Shared {
    state: RuntimeState,
    health: Health,
    pid: Option<u32>,
    failure: Option<Failure>,
    child: Option<Child>,
    /// 자식을 띄우거나 정지할 때마다 바뀐다. 이전 자식의 감시 스레드는 세대가 다르면 끝난다.
    generation: u64,
    stop_requested: bool,
}

struct LaunchPlan {
    java: PathBuf,
    args: Vec<String>,
    env: Env,
    /// 자식 출력에서 가릴 값: Metadata DB 비밀번호와 관리 토큰.
    secrets: Arc<[Vec<u8>]>,
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

impl Supervisor {
    /// env 는 셸의 환경이다(운영: launch::process_env()). token 은 셸 프로세스의 관리 토큰이다(lib.rs 의 setup 이 만든다).
    pub fn new(config: SupervisorConfig, env: Env, token: AdminToken) -> Self {
        let output = Arc::new(OutputBuffer::new(config.output_lines));
        Supervisor {
            handle: SupervisorHandle {
                inner: Arc::new(Inner {
                    config,
                    env,
                    token,
                    shared: Mutex::new(Shared {
                        state: RuntimeState::Stopped,
                        health: Health::Unknown,
                        pid: None,
                        failure: None,
                        child: None,
                        generation: 0,
                        stop_requested: false,
                    }),
                    lifecycle: Mutex::new(()),
                    output,
                    shut_down: AtomicBool::new(false),
                }),
            },
        }
    }

    pub fn handle(&self) -> SupervisorHandle {
        self.handle.clone()
    }
}

impl std::ops::Deref for Supervisor {
    type Target = SupervisorHandle;

    fn deref(&self) -> &SupervisorHandle {
        &self.handle
    }
}

impl Drop for Supervisor {
    fn drop(&mut self) {
        self.handle.shutdown();
    }
}

impl SupervisorHandle {
    /// 관리 토큰(admin_token 커맨드와 시험만 쓴다).
    pub fn admin_token(&self) -> &AdminToken {
        &self.inner.token
    }

    /// 현재 상태. 수명 주기 작업을 기다리지 않는다.
    pub fn status(&self) -> RuntimeStatus {
        let config = &self.inner.config;
        let shared = lock(&self.inner.shared);
        let failure = shared.failure.clone().map(|mut failure| {
            if failure.kind.has_output() {
                failure.output_tail = self.inner.output.tail(config.status_tail_lines);
            }
            failure
        });
        RuntimeStatus {
            state: shared.state,
            health: shared.health,
            pid: shared.pid,
            port: config.port,
            base_url: config.base_url(),
            failure,
        }
    }

    /// 시작한다. starting 이나 running 이면 아무것도 하지 않는다.
    pub fn start(&self) -> RuntimeStatus {
        {
            let _serial = lock(&self.inner.lifecycle);
            self.start_locked();
        }
        self.status()
    }

    /// 정지한다. 프로세스가 없으면 아무것도 하지 않는다.
    pub fn stop(&self) -> RuntimeStatus {
        {
            let _serial = lock(&self.inner.lifecycle);
            self.stop_locked();
        }
        self.status()
    }

    /// 정지한 뒤 시작한다.
    pub fn restart(&self) -> RuntimeStatus {
        {
            let _serial = lock(&self.inner.lifecycle);
            self.stop_locked();
            self.start_locked();
        }
        self.status()
    }

    /// 앱 종료(RunEvent::Exit)와 Drop: 자식을 정지하고 이후의 시작을 막는다.
    pub fn shutdown(&self) {
        self.inner.shut_down.store(true, Ordering::SeqCst);
        let _serial = lock(&self.inner.lifecycle);
        self.stop_locked();
    }

    fn start_locked(&self) {
        let inner = &self.inner;
        if inner.shut_down.load(Ordering::SeqCst) {
            return;
        }
        if matches!(
            lock(&inner.shared).state,
            RuntimeState::Starting | RuntimeState::Running
        ) {
            return;
        }
        let epoch = inner.output.reset();
        let plan = match self.preflight() {
            Ok(plan) => plan,
            Err(failure) => return self.fail(failure),
        };
        let config = &inner.config;
        if let Err(e) = std::fs::create_dir_all(&config.work_dir) {
            return self.fail(Failure::new(
                FailureKind::SpawnFailed,
                format!(
                    "런타임 작업 디렉터리를 만들지 못했다 ({}): {e}",
                    config.work_dir.display()
                ),
            ));
        }
        // 사전 확인(java -version 은 몇 초 걸릴 수 있다) 중에 앱 종료나 shutdown() 이 왔으면 띄우지 않는다. shutdown() 은
        // lifecycle 잠금을 잡기 전에 표시하므로, 지금 기다리고 있는 shutdown 도 여기서 보인다.
        if inner.shut_down.load(Ordering::SeqCst) {
            return;
        }
        let spawned = Command::new(&plan.java)
            .args(&plan.args)
            .current_dir(&config.work_dir)
            .env_clear()
            .envs(&plan.env)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn();
        let mut child = match spawned {
            Ok(child) => child,
            Err(e) => {
                return self.fail(Failure::new(
                    FailureKind::SpawnFailed,
                    format!(
                        "런타임 프로세스를 시작하지 못했다 ({}): {e}",
                        plan.java.display()
                    ),
                ))
            }
        };
        let pid = child.id();
        if let Some(stdout) = child.stdout.take() {
            self.spawn_reader("stdout", stdout, epoch, Arc::clone(&plan.secrets));
        }
        if let Some(stderr) = child.stderr.take() {
            self.spawn_reader("stderr", stderr, epoch, Arc::clone(&plan.secrets));
        }
        let generation = {
            let mut shared = lock(&inner.shared);
            shared.generation += 1;
            shared.child = Some(child);
            shared.pid = Some(pid);
            shared.health = Health::Unknown;
            shared.stop_requested = false;
            inner.transition(&mut shared, RuntimeState::Starting, None);
            shared.generation
        };
        let monitor_inner = Arc::clone(inner);
        let monitor = thread::Builder::new()
            .name("queryhub-runtime-monitor".into())
            .spawn(move || monitor(monitor_inner, generation));
        if let Err(e) = monitor {
            let mut shared = lock(&inner.shared);
            if let Some(mut child) = shared.child.take() {
                let _ = child.kill();
                let _ = child.wait();
            }
            shared.pid = None;
            inner.transition(
                &mut shared,
                RuntimeState::Failed,
                Some(Failure::new(
                    FailureKind::SpawnFailed,
                    format!("런타임 감시 스레드를 만들지 못했다: {e}"),
                )),
            );
        }
    }

    /// 순서대로: java 와 버전, JAR, Metadata DB 설정, 포트. 실패하면 자식을 띄우지 않는다.
    fn preflight(&self) -> Result<LaunchPlan, Failure> {
        let inner = &self.inner;
        let config = &inner.config;
        let env = &inner.env;
        let java = launch::resolve_java(env)?;
        java::check(
            &java,
            &launch::inherited_env(env),
            config.java_version_timeout,
        )?;
        let jar = launch::resolve_jar(env, config.default_jar.as_deref())?;
        let settings =
            launch::metadata_settings_from_env(env).map_err(launch::settings_missing_failure)?;
        if !(config.port_probe)(config.port) {
            return Err(launch::port_in_use_failure(config.port));
        }
        Ok(LaunchPlan {
            args: launch::child_args(&jar, config.port),
            env: launch::child_env(env, &settings, &inner.token, std::process::id()),
            secrets: Arc::from(vec![
                settings.password().as_bytes().to_vec(),
                inner.token.expose().as_bytes().to_vec(),
            ]),
            java,
        })
    }

    fn fail(&self, failure: Failure) {
        let mut shared = lock(&self.inner.shared);
        shared.pid = None;
        shared.health = Health::Unknown;
        self.inner
            .transition(&mut shared, RuntimeState::Failed, Some(failure));
    }

    fn spawn_reader<R>(&self, stream: &'static str, reader: R, epoch: u64, secrets: Arc<[Vec<u8>]>)
    where
        R: std::io::Read + Send + 'static,
    {
        let buffer = Arc::clone(&self.inner.output);
        let echo = self.inner.config.echo_to_stderr;
        let max_bytes = self.inner.config.output_line_bytes;
        // 스레드를 만들지 못하면 reader 가 닫히고 자식의 쓰기는 실패한다(출력이 막혀 멈추지는 않는다).
        let _ = thread::Builder::new()
            .name(format!("queryhub-runtime-{stream}"))
            .spawn(move || {
                output::drain(reader, &secrets, max_bytes, |line| {
                    if echo {
                        eprintln!("[runtime {stream}] {line}");
                    }
                    buffer.push(epoch, line);
                });
            });
    }

    fn stop_locked(&self) {
        let inner = &self.inner;
        {
            let mut shared = lock(&inner.shared);
            if shared.child.is_none() {
                return;
            }
            shared.stop_requested = true;
            if let Some(child) = shared.child.as_mut() {
                request_terminate(child);
            }
        }
        let deadline = Instant::now() + inner.config.stop_grace;
        loop {
            {
                let mut shared = lock(&inner.shared);
                let gone = match shared.child.as_mut() {
                    None => true,
                    Some(child) => !matches!(child.try_wait(), Ok(None)),
                };
                if gone {
                    shared.child = None;
                    break;
                }
                if Instant::now() >= deadline {
                    if let Some(mut child) = shared.child.take() {
                        let _ = child.kill();
                        let _ = child.wait();
                    }
                    break;
                }
            }
            thread::sleep(STOP_POLL);
        }
        let mut shared = lock(&inner.shared);
        shared.generation += 1;
        shared.pid = None;
        shared.health = Health::Unknown;
        shared.stop_requested = false;
        inner.transition(&mut shared, RuntimeState::Stopped, None);
    }
}

impl Inner {
    fn transition(&self, shared: &mut Shared, to: RuntimeState, failure: Option<Failure>) {
        if self.config.echo_to_stderr {
            let from = shared.state.as_str();
            match &failure {
                Some(f) => eprintln!(
                    "[queryhub-desktop] runtime: {from} -> {}({}) {}",
                    to.as_str(),
                    f.kind.as_str(),
                    f.message
                ),
                None => eprintln!("[queryhub-desktop] runtime: {from} -> {}", to.as_str()),
            }
        }
        shared.state = to;
        shared.failure = failure;
    }

    fn probe(&self, path: &str) -> Option<u16> {
        health::http_status(
            self.config.port,
            path,
            self.config.connect_timeout,
            self.config.io_timeout,
        )
    }
}

/// 아직 거두지 않은 자식에게만 SIGTERM 을 보낸다. 호출하는 쪽이 shared 잠금을 잡고 있다.
#[cfg(unix)]
fn request_terminate(child: &mut Child) {
    if let Ok(None) = child.try_wait() {
        if let Ok(pid) = libc::pid_t::try_from(child.id()) {
            // SAFETY: kill(2) 은 메모리를 건드리지 않는다. pid 는 거두지 않은 우리 자식이므로 다른 프로세스를 가리키지 않는다.
            unsafe {
                libc::kill(pid, libc::SIGTERM);
            }
        }
    }
}

/// unix 가 아니면 우아한 종료 신호가 없다: 바로 종료한다(미검증, 첫 CI 실행에서 확인한다. BACKLOG CI-1, PLAT-1).
#[cfg(not(unix))]
fn request_terminate(child: &mut Child) {
    let _ = child.kill();
}

/// 자식 하나의 감시: 종료 감지(stop 이 요청하지 않은 종료는 failed(exited)), starting 의 readiness 확인과 기동 제한,
/// running 의 health 확인. 세대가 바뀌거나 자식이 없어지면 끝난다.
fn monitor(inner: Arc<Inner>, generation: u64) {
    let config = &inner.config;
    let started = Instant::now();
    let mut next_readiness = started;
    let mut next_health = started;
    loop {
        thread::sleep(config.monitor_interval);
        let state = {
            let mut shared = lock(&inner.shared);
            if shared.generation != generation {
                return;
            }
            let exited = match shared.child.as_mut() {
                None => return,
                Some(child) => match child.try_wait() {
                    Ok(None) => None,
                    Ok(Some(status)) => Some(status.code()),
                    Err(_) => Some(None),
                },
            };
            if let Some(code) = exited {
                shared.child = None;
                shared.pid = None;
                shared.health = Health::Unknown;
                if !shared.stop_requested {
                    inner.transition(
                        &mut shared,
                        RuntimeState::Failed,
                        Some(exited_failure(code)),
                    );
                }
                return;
            }
            shared.state
        };
        let now = Instant::now();
        match state {
            RuntimeState::Starting => {
                if now.duration_since(started) >= config.startup_timeout {
                    let mut shared = lock(&inner.shared);
                    if shared.generation == generation
                        && shared.state == RuntimeState::Starting
                        && !shared.stop_requested
                    {
                        if let Some(mut child) = shared.child.take() {
                            let _ = child.kill();
                            let _ = child.wait();
                        }
                        shared.pid = None;
                        shared.health = Health::Unknown;
                        let failure = Failure::new(
                            FailureKind::StartupTimeout,
                            format!(
                                "런타임이 {} 초 안에 준비되지 않아 종료했다 (GET {} 가 200 이 아니다). 아래 출력을 확인한다.",
                                config.startup_timeout.as_secs(),
                                health::READINESS_PATH
                            ),
                        );
                        inner.transition(&mut shared, RuntimeState::Failed, Some(failure));
                    }
                    return;
                }
                if now >= next_readiness {
                    next_readiness = now + config.readiness_interval;
                    if inner.probe(health::READINESS_PATH) == Some(200) {
                        let mut shared = lock(&inner.shared);
                        if shared.generation == generation && shared.state == RuntimeState::Starting
                        {
                            inner.transition(&mut shared, RuntimeState::Running, None);
                        }
                        next_health = Instant::now();
                    }
                }
            }
            RuntimeState::Running => {
                if now >= next_health {
                    next_health = now + config.health_interval;
                    let observed = if inner.probe(health::HEALTH_PATH) == Some(200) {
                        Health::Up
                    } else {
                        Health::Down
                    };
                    let mut shared = lock(&inner.shared);
                    if shared.generation == generation && shared.state == RuntimeState::Running {
                        if shared.health != observed && config.echo_to_stderr {
                            eprintln!("[queryhub-desktop] runtime: health {observed:?}");
                        }
                        shared.health = observed;
                    }
                }
            }
            RuntimeState::Stopped | RuntimeState::Failed => return,
        }
    }
}

fn exited_failure(code: Option<i32>) -> Failure {
    let message = match code {
        Some(code) => {
            format!("런타임이 종료 코드 {code} 로 끝났다. 아래 출력의 마지막 줄을 확인한다.")
        }
        None => "런타임이 신호로 종료되었다. 아래 출력의 마지막 줄을 확인한다.".to_string(),
    };
    let mut failure = Failure::new(FailureKind::Exited, message);
    failure.exit_code = code;
    failure
}
