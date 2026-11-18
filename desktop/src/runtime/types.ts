// 셸 커맨드가 돌려주는 런타임 상태. Rust 의 RuntimeStatus(desktop/src-tauri/src/runtime/mod.rs)와 같은 모양이다(camelCase).

export type RuntimeState = "stopped" | "starting" | "running" | "failed";

export type RuntimeHealth = "unknown" | "up" | "down";

export type FailureKind =
  | "java_not_found"
  | "java_too_old"
  | "jar_not_found"
  | "metadata_settings_missing"
  | "port_in_use"
  | "spawn_failed"
  | "exited"
  | "startup_timeout";

export interface RuntimeFailure {
  kind: FailureKind;
  message: string;
  exitCode: number | null;
  outputTail: string[];
}

export interface RuntimeStatus {
  state: RuntimeState;
  health: RuntimeHealth;
  pid: number | null;
  port: number;
  baseUrl: string;
  failure: RuntimeFailure | null;
}

// GET /admin/api/v1/runtime 의 성공 응답 data.
export interface RuntimeInfo {
  name: string;
  version: string;
  mode: string;
  startedAt: string;
  uptimeSeconds: number;
}

// 관리 API 호출 결과. 실패는 화면에 보일 한국어 문장이다(예외를 던지지 않는다).
export type RuntimeInfoResult = { ok: true; info: RuntimeInfo } | { ok: false; error: string };
