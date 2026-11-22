/// 앱 커맨드 다섯 개(수명 주기 네 개와 admin_token)를 앱 매니페스트로 등록한다.
///
/// 매니페스트가 없으면 Tauri 는 앱 커맨드에 ACL 을 적용하지 않는다(capability 가 아무것도 막지 못한다).
/// 등록하면 tauri-build 가 allow-runtime-* 와 allow-admin-token 권한을 만들고, capabilities/default.json 이 이 다섯 개만
/// main 창에 준다. src/commands.rs 의 시험이 이 목록, lib.rs 의 generate_handler!, capability 파일이 같은지 확인한다.
const COMMANDS: &[&str] = &[
    "runtime_status",
    "runtime_start",
    "runtime_stop",
    "runtime_restart",
    "admin_token",
];

fn main() {
    tauri_build::try_build(
        tauri_build::Attributes::new()
            .app_manifest(tauri_build::AppManifest::new().commands(COMMANDS)),
    )
    .expect("tauri-build 실패");
}
