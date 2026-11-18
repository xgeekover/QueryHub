// 관리 API(HTTP) 호출은 이 파일에만 있다. 앱에서는 http://127.0.0.1:18580 에 직접(런타임 CORS 가 tauri://localhost 를
// 허용한다), 개발 서버에서는 같은 출처의 /admin/api 로 요청하고 Vite 프록시가 Origin 을 지워 런타임으로 넘긴다.
// 사용자 정의 헤더를 보내지 않는다(사전 요청이 생기지 않는다. 런타임은 GET 과 기본 헤더만 허용한다).
import type { RuntimeInfo, RuntimeInfoResult } from "./types";

export const RUNTIME_INFO_PATH = "/admin/api/v1/runtime";
export const ADMIN_API_TIMEOUT_MS = 3000;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

// 공통 응답 형식을 해석한다: { success: true, data: {...} } 또는 { success: false, error: { code, ... } }.
export function parseRuntimeInfoEnvelope(body: unknown): RuntimeInfoResult {
  if (!isRecord(body)) {
    return { ok: false, error: "런타임 정보 응답의 형식이 올바르지 않다" };
  }
  if (body.success !== true) {
    const code = isRecord(body.error) && typeof body.error.code === "string" ? body.error.code : "코드 없음";
    return { ok: false, error: `런타임이 오류를 돌려주었다 (${code})` };
  }
  const data = body.data;
  if (
    !isRecord(data) ||
    typeof data.name !== "string" ||
    typeof data.version !== "string" ||
    typeof data.mode !== "string" ||
    typeof data.startedAt !== "string" ||
    typeof data.uptimeSeconds !== "number"
  ) {
    return { ok: false, error: "런타임 정보 응답의 data 형식이 올바르지 않다" };
  }
  const info: RuntimeInfo = {
    name: data.name,
    version: data.version,
    mode: data.mode,
    startedAt: data.startedAt,
    uptimeSeconds: data.uptimeSeconds,
  };
  return { ok: true, info };
}

// GET <base>/admin/api/v1/runtime. 네트워크 오류, 시간 초과, 2xx 가 아닌 응답, 잘못된 JSON 은 모두 { ok: false } 로 돌려준다.
export async function fetchRuntimeInfo(
  baseUrl: string,
  timeoutMs: number = ADMIN_API_TIMEOUT_MS,
): Promise<RuntimeInfoResult> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    let response: Response;
    try {
      response = await fetch(`${baseUrl}${RUNTIME_INFO_PATH}`, {
        method: "GET",
        signal: controller.signal,
      });
    } catch (error) {
      if (controller.signal.aborted) {
        return { ok: false, error: `런타임 정보 요청이 ${timeoutMs / 1000}초 안에 끝나지 않았다` };
      }
      return { ok: false, error: `런타임 정보를 가져오지 못했다: ${describe(error)}` };
    }
    if (!response.ok) {
      return { ok: false, error: `런타임 정보 요청이 실패했다 (HTTP ${response.status})` };
    }
    let body: unknown;
    try {
      body = await response.json();
    } catch {
      return { ok: false, error: "런타임 정보 응답이 올바른 JSON 이 아니다" };
    }
    return parseRuntimeInfoEnvelope(body);
  } finally {
    clearTimeout(timer);
  }
}
