//! 런타임(Spring Boot 실행 JAR) 감독 모듈. Tauri 타입을 쓰지 않는다.
//!
//! - launch: 셸 환경(주입한 맵)에서 java, JAR, Metadata DB 설정, 자식 환경과 인자를 정한다.
//! - java: `java -version` 으로 주 버전을 읽는다.
//! - health: std::net 으로 루프백 GET 의 상태 줄만 읽는다(HTTP 클라이언트 크레이트를 쓰지 않는다).
//! - output: 자식 출력의 링 버퍼와 비밀 값(비밀번호, 관리 토큰) 가리기.
//! - token: 셸 프로세스마다 한 번 만드는 관리 토큰(ADR-021). Debug 와 Display 는 값을 보이지 않는다.
//! - supervisor: 상태 기계(stopped/starting/running/failed + health), 시작·정지·재시작, 감시 스레드, Drop.
//!
//! UI 에 돌려주는 계약은 이 파일의 RuntimeStatus 다(camelCase 로 직렬화). desktop/src/runtime/types.ts 가 같은 모양이다.

pub mod health;
pub mod java;
pub mod launch;
pub mod output;
pub mod supervisor;
pub mod token;

#[cfg(all(test, unix))]
mod tests;

use serde::Serialize;

pub use supervisor::{Supervisor, SupervisorConfig, SupervisorHandle};
pub use token::AdminToken;

/// 런타임 프로세스의 수명 주기 상태.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum RuntimeState {
    Stopped,
    Starting,
    Running,
    Failed,
}

impl RuntimeState {
    /// 직렬화 이름과 같은 문자열(로그용).
    pub fn as_str(self) -> &'static str {
        match self {
            RuntimeState::Stopped => "stopped",
            RuntimeState::Starting => "starting",
            RuntimeState::Running => "running",
            RuntimeState::Failed => "failed",
        }
    }
}

/// running 동안 GET /actuator/health 로 따로 확인하는 건강 상태. 상태(state)를 바꾸지 않는다.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Health {
    Unknown,
    Up,
    Down,
}

/// 실패 종류. UI 와 수동 확인(AC-17)이 이 이름을 쓴다.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum FailureKind {
    JavaNotFound,
    JavaTooOld,
    JarNotFound,
    MetadataSettingsMissing,
    PortInUse,
    SpawnFailed,
    Exited,
    StartupTimeout,
}

impl FailureKind {
    /// 직렬화 이름과 같은 문자열(로그용).
    pub fn as_str(self) -> &'static str {
        match self {
            FailureKind::JavaNotFound => "java_not_found",
            FailureKind::JavaTooOld => "java_too_old",
            FailureKind::JarNotFound => "jar_not_found",
            FailureKind::MetadataSettingsMissing => "metadata_settings_missing",
            FailureKind::PortInUse => "port_in_use",
            FailureKind::SpawnFailed => "spawn_failed",
            FailureKind::Exited => "exited",
            FailureKind::StartupTimeout => "startup_timeout",
        }
    }

    /// 프로세스가 실제로 실행된 실패인가. 그때만 outputTail 에 자식 출력이 들어간다.
    pub fn has_output(self) -> bool {
        matches!(self, FailureKind::Exited | FailureKind::StartupTimeout)
    }
}

/// 실패 정보. message 는 한국어이고 변수 이름과 경로만 말한다. 비밀 값(비밀번호, 관리 토큰)은 넣지 않는다.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Failure {
    pub kind: FailureKind,
    pub message: String,
    pub exit_code: Option<i32>,
    /// 자식 출력의 마지막 줄들(비밀번호와 관리 토큰은 가려져 있다). 상태를 조회할 때 채운다.
    pub output_tail: Vec<String>,
}

impl Failure {
    pub fn new(kind: FailureKind, message: impl Into<String>) -> Self {
        Failure {
            kind,
            message: message.into(),
            exit_code: None,
            output_tail: Vec::new(),
        }
    }
}

/// 수명 주기 커맨드 네 개가 돌려주는 런타임 상태. 관리 토큰은 담지 않는다(admin_token 커맨드만 돌려준다).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeStatus {
    pub state: RuntimeState,
    pub health: Health,
    pub pid: Option<u32>,
    pub port: u16,
    pub base_url: String,
    pub failure: Option<Failure>,
}
