//! QueryHub 데스크톱 셸.
//!
//! - setup: 런타임 감독(runtime::Supervisor)을 앱 상태로 두고 런타임을 자동으로 시작한다(별도 스레드, 창을 막지 않는다).
//! - 커맨드 네 개(commands.rs): runtime_status, runtime_start, runtime_stop, runtime_restart.
//! - RunEvent::Exit: 런타임을 정지한다(SIGTERM, 10 초 뒤 SIGKILL). 셸이 SIGKILL 로 죽으면 런타임의 부모 감시
//!   (QUERYHUB_DESKTOP_PARENT_PID)가 약 5 초 안에 런타임을 끝낸다.
//!
//! 런타임 정보는 셸이 아니라 UI 가 관리 API 에서 HTTP 로 읽는다(셸은 actuator 의 health 경로만 부른다).

mod commands;
pub mod runtime;

use tauri::{Manager, RunEvent};

use runtime::{launch, Supervisor, SupervisorConfig};

/// Tauri 앱을 만들고 실행한다. 창이 모두 닫히면 돌아오지 않고 프로세스를 끝낸다.
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            commands::runtime_status,
            commands::runtime_start,
            commands::runtime_stop,
            commands::runtime_restart
        ])
        .setup(|app| {
            // 자식의 작업 디렉터리. 시작할 때 만든다.
            let work_dir = app.path().app_local_data_dir()?;
            let supervisor =
                Supervisor::new(SupervisorConfig::desktop(work_dir), launch::process_env());
            let handle = supervisor.handle();
            app.manage(supervisor);
            std::thread::Builder::new()
                .name("queryhub-runtime-autostart".into())
                .spawn(move || {
                    handle.start();
                })?;
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("Tauri 앱을 만들지 못했다")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                if let Some(supervisor) = app.try_state::<Supervisor>() {
                    supervisor.shutdown();
                }
            }
        });
}
