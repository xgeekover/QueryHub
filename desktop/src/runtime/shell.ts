// 셸(Tauri IPC) 호출은 이 파일에만 있다. 수명 주기만 IPC 로 다루고, 런타임 정보는 adminApi.ts 가 HTTP 로 읽는다.
// 커맨드 이름은 Rust 의 generate_handler!(desktop/src-tauri/src/lib.rs)와 같다. 커맨드는 인자를 받지 않는다.
import { invoke, isTauri } from "@tauri-apps/api/core";

import type { RuntimeStatus } from "./types";

export type RuntimeCommand = "runtime_status" | "runtime_start" | "runtime_stop" | "runtime_restart";

// Tauri 창 안에서 실행 중인가. 브라우저(pnpm dev)에서는 false 이고, 그때는 invoke 를 부르지 않는다.
export function insideTauri(): boolean {
  return isTauri();
}

// 인자 없이 부른다(두 번째 인자를 넘기지 않는다). 결과 타입은 반환 타입에서 정해진다.
function call(command: RuntimeCommand): Promise<RuntimeStatus> {
  return invoke(command);
}

export function runtimeStatus(): Promise<RuntimeStatus> {
  return call("runtime_status");
}

export function runtimeStart(): Promise<RuntimeStatus> {
  return call("runtime_start");
}

export function runtimeStop(): Promise<RuntimeStatus> {
  return call("runtime_stop");
}

export function runtimeRestart(): Promise<RuntimeStatus> {
  return call("runtime_restart");
}
