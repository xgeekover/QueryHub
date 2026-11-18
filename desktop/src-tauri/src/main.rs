// 릴리스 빌드에서는 Windows 의 콘솔 창을 띄우지 않는다.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    queryhub_desktop_lib::run();
}
