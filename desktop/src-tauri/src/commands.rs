//! UI 가 invoke 로 부르는 커맨드 다섯 개: 수명 주기 네 개와 admin_token. 모두 IPC 인자가 없다(카멜 케이스 인자 함정을 피한다).
//!
//! async 이므로 메인 스레드에서 실행되지 않는다. 시작·정지·재시작은 java 확인과 정지 유예(최대 10 초)를 기다릴 수 있으므로
//! blocking 스레드에서 실행한다. 커맨드는 얇은 포장일 뿐이고 동작은 runtime::Supervisor 에 있다.
//! 권한: build.rs 의 앱 매니페스트와 capabilities/default.json 이 이 다섯 개만 main 창(로컬 출처, remote 없음)에 허용한다.

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

/// 관리 API 의 Bearer 토큰(ADR-021). 셸 프로세스마다 한 번 만든 값이고 재시작해도 같다. UI 는 메모리에만 두고
/// Authorization: Bearer 헤더로만 보낸다. 로그에 쓰지 않는다.
#[tauri::command]
pub async fn admin_token(supervisor: State<'_, Supervisor>) -> Result<String, String> {
    Ok(supervisor.admin_token().expose().to_string())
}

#[cfg(test)]
mod tests {
    //! AC12: 커맨드 다섯 개가 build.rs 의 매니페스트, lib.rs 의 generate_handler!, capabilities/default.json 에서 서로 같다.

    const BUILD_RS: &str = include_str!("../build.rs");
    const LIB_RS: &str = include_str!("lib.rs");
    const CAPABILITY: &str = include_str!("../capabilities/default.json");

    const COMMANDS: [&str; 5] = [
        "runtime_status",
        "runtime_start",
        "runtime_stop",
        "runtime_restart",
        "admin_token",
    ];

    /// text 에서 start 뒤 첫 end 까지의 부분.
    fn section<'a>(text: &'a str, start: &str, end: &str) -> &'a str {
        let from = text.find(start).expect("start") + start.len();
        let len = text[from..].find(end).expect("end");
        &text[from..from + len]
    }

    #[test]
    fn build_rs_registers_exactly_the_five_commands() {
        let list = section(BUILD_RS, "const COMMANDS: &[&str] = &[", "];");
        let names: Vec<&str> = list
            .split(',')
            .map(|item| item.trim().trim_matches('"'))
            .filter(|item| !item.is_empty())
            .collect();
        assert_eq!(names, COMMANDS);
    }

    #[test]
    fn lib_rs_generate_handler_lists_exactly_the_five_commands() {
        let list = section(LIB_RS, "tauri::generate_handler![", "]");
        let names: Vec<&str> = list
            .split(',')
            .map(|item| item.trim().trim_start_matches("commands::"))
            .filter(|item| !item.is_empty())
            .collect();
        assert_eq!(names, COMMANDS);
    }

    #[test]
    fn the_capability_grants_exactly_the_five_permissions_to_the_main_window_without_remote() {
        let value: serde_json::Value = serde_json::from_str(CAPABILITY).expect("json");
        let object = value.as_object().expect("object");
        assert!(!object.contains_key("remote"), "remote 키가 있다");
        assert_eq!(value["windows"], serde_json::json!(["main"]));
        let expected: Vec<String> = COMMANDS
            .iter()
            .map(|c| format!("allow-{}", c.replace('_', "-")))
            .collect();
        assert_eq!(value["permissions"], serde_json::json!(expected));
        let mut keys: Vec<&str> = object.keys().map(String::as_str).collect();
        keys.sort_unstable();
        assert_eq!(
            keys,
            [
                "$schema",
                "description",
                "identifier",
                "permissions",
                "windows"
            ]
        );
    }
}
