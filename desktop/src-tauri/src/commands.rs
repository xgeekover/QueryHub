//! UI 가 invoke 로 부르는 커맨드 네 개. 모두 IPC 인자가 없다(카멜 케이스 인자 함정을 피한다).
//!
//! async 이므로 메인 스레드에서 실행되지 않는다. 시작·정지·재시작은 java 확인과 정지 유예(최대 10 초)를 기다릴 수 있으므로
//! blocking 스레드에서 실행한다. 커맨드는 얇은 포장일 뿐이고 동작은 runtime::Supervisor 에 있다.
//! 권한: build.rs 의 앱 매니페스트와 capabilities/default.json 이 이 네 개만 main 창에 허용한다.

use tauri::State;

use crate::runtime::{RuntimeStatus, Supervisor, SupervisorHandle};

async fn blocking(
    handle: SupervisorHandle,
    action: fn(&SupervisorHandle) -> RuntimeStatus,
) -> Result<RuntimeStatus, String> {
    tauri::async_runtime::spawn_blocking(move || action(&handle))
        .await
        .map_err(|e| format!("런타임 작업이 중단되었다: {e}"))
}

/// 현재 상태. 수명 주기 작업을 기다리지 않는다.
#[tauri::command]
pub async fn runtime_status(supervisor: State<'_, Supervisor>) -> Result<RuntimeStatus, String> {
    Ok(supervisor.status())
}

/// 시작(starting 이나 running 이면 아무것도 하지 않는다).
#[tauri::command]
pub async fn runtime_start(supervisor: State<'_, Supervisor>) -> Result<RuntimeStatus, String> {
    blocking(supervisor.handle(), SupervisorHandle::start).await
}

/// 정지(프로세스가 없으면 아무것도 하지 않는다).
#[tauri::command]
pub async fn runtime_stop(supervisor: State<'_, Supervisor>) -> Result<RuntimeStatus, String> {
    blocking(supervisor.handle(), SupervisorHandle::stop).await
}

/// 정지한 뒤 시작.
#[tauri::command]
pub async fn runtime_restart(supervisor: State<'_, Supervisor>) -> Result<RuntimeStatus, String> {
    blocking(supervisor.handle(), SupervisorHandle::restart).await
}
