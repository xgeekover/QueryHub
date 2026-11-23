//! QueryHub 데스크톱 셸.
//!
//! - setup: 관리 토큰(runtime::AdminToken)을 한 번 만들고, 런타임 감독(runtime::Supervisor)을 앱 상태로 두고 런타임을
//!   자동으로 시작한다(별도 스레드, 창을 막지 않는다). 토큰을 만들지 못하면 setup 이 실패하고 런타임은 시작하지 않는다.
//! - 마스터 키 파일의 경로는 여기서만 정한다: <app_local_data_dir>/secrets/master.key(ADR-023). 감독이 시작할 때마다 읽고,
//!   없을 때만 한 번 만든다. 키는 런타임 자식의 환경변수로만 넘기고 커맨드로 돌려주지 않는다.
//! - 데이터 디렉터리의 경로도 여기서만 정한다: <app_local_data_dir>/data(P1-06, ADR-026). 감독이 시작할 때마다 0700 으로
//!   준비하고 QUERYHUB_DATA_DIR 로 넘긴다. 런타임은 그 안에 내장 SQLite 저장소(queryhub.db)를 만든다. 그래서 앱은 환경변수
//!   없이 시작한다.
//! - 커맨드 다섯 개(commands.rs): runtime_status, runtime_start, runtime_stop, runtime_restart, admin_token.
//! - RunEvent::Exit: 런타임을 정지한다(SIGTERM, 10 초 뒤 SIGKILL). 셸이 SIGKILL 로 죽으면 런타임의 부모 감시
//!   (QUERYHUB_DESKTOP_PARENT_PID)가 약 5 초 안에 런타임을 끝낸다.
//!
//! 런타임 정보는 셸이 아니라 UI 가 관리 API 에서 HTTP 로 읽는다(셸은 actuator 의 health 경로만 부른다). UI 는 admin_token
//! 커맨드로 받은 토큰을 Authorization: Bearer 헤더로 보낸다(ADR-021).

mod commands;
pub mod runtime;

use tauri::{Manager, RunEvent};

use runtime::{data_dir, launch, master_key, AdminToken, Supervisor, SupervisorConfig};

/// Tauri 앱을 만들고 실행한다. 창이 모두 닫히면 돌아오지 않고 프로세스를 끝낸다.
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            commands::runtime_status,
            commands::runtime_start,
            commands::runtime_stop,
            commands::runtime_restart,
            commands::admin_token
        ])
        .setup(|app| {
            // 관리 토큰: 셸 프로세스마다 한 번 OS 난수 생성기에서 만든다. 실패하면 setup 이 실패한다(토큰 없이 띄우지 않는다).
            let token = AdminToken::generate()?;
            // 자식의 작업 디렉터리. 시작할 때 만든다.
            let work_dir = app.path().app_local_data_dir()?;
            // 마스터 키 파일(ADR-023). macOS ~/Library/Application Support/com.queryhub.desktop/secrets/master.key,
            // Linux ~/.local/share/com.queryhub.desktop/..., Windows %LOCALAPPDATA%\com.queryhub.desktop\...(둘은 미검증).
            let master_key_path = work_dir
                .join(master_key::KEY_DIR_NAME)
                .join(master_key::KEY_FILE_NAME);
            // 데이터 디렉터리(P1-06). macOS ~/Library/Application Support/com.queryhub.desktop/data, Linux
            // ~/.local/share/com.queryhub.desktop/data, Windows %LOCALAPPDATA%\com.queryhub.desktop\data(둘은 미검증).
            let data_dir_path = work_dir.join(data_dir::DATA_DIR_NAME);
            let supervisor = Supervisor::new(
                SupervisorConfig::desktop(work_dir, master_key_path, data_dir_path),
                launch::process_env(),
                token,
            );
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
