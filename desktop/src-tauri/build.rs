/// 앱 커맨드 네 개를 앱 매니페스트로 등록한다.
///
/// 매니페스트가 없으면 Tauri 는 앱 커맨드에 ACL 을 적용하지 않는다(capability 가 아무것도 막지 못한다).
/// 등록하면 tauri-build 가 allow-runtime-* 권한을 만들고, capabilities/default.json 이 그중 네 개만 main 창에 준다.
const COMMANDS: &[&str] = &[
    "runtime_status",
    "runtime_start",
    "runtime_stop",
    "runtime_restart",
];

fn main() {
    tauri_build::try_build(
        tauri_build::Attributes::new()
            .app_manifest(tauri_build::AppManifest::new().commands(COMMANDS)),
    )
    .expect("tauri-build 실패");
}
