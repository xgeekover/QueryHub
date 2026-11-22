// 관리 API(HTTP) 호출은 이 파일에만 있다. 앱에서는 http://127.0.0.1:18580 에 직접(런타임 CORS 가 tauri://localhost 를
// 허용한다), 개발 서버에서는 같은 출처의 /admin/api 로 요청하고 Vite 프록시가 Origin 을 지워 런타임으로 넘긴다.
// 요청마다 Authorization: Bearer <토큰> 헤더 하나만 더한다(ADR-021). 앱에서는 이 헤더 때문에 CORS 사전 요청(OPTIONS)이 생기고,
// 런타임은 토큰 없이 그 사전 요청에 답한다(허용 헤더는 Authorization 하나, 메서드는 GET). 토큰이 없으면 요청하지 않는다.
// 오류 문장에는 토큰을 넣지 않는다.
import type { RuntimeInfo, RuntimeInfoResult } from "./types";

export const RUNTIME_INFO_PATH = "/admin/api/v1/runtime";
export const ADMIN_API_TIMEOUT_MS = 3000;

// 고정 문장. 토큰이나 응답 본문을 담지 않는다.
export const NO_TOKEN_MESSAGE = "관리 토큰이 없어 런타임 정보를 요청하지 않았다";
export const UNAUTHORIZED_MESSAGE =
  "런타임이 관리 토큰을 받아들이지 않았다 (HTTP 401). 셸과 런타임이 다른 실행에서 왔을 수 있다. 앱을 다시 시작한다.";
export const FORBIDDEN_MESSAGE =
  "런타임이 관리 요청을 거부했다 (HTTP 403). 루프백 주소나 Host 헤더 검사를 통과하지 못했다.";

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

// GET <base>/admin/api/v1/runtime (Authorization: Bearer <token>). 토큰이 null 이면 요청하지 않는다. 네트워크 오류, 시간 초과,
// 2xx 가 아닌 응답(401, 403 은 따로 고정 문장), 잘못된 JSON 은 모두 { ok: false } 로 돌려준다.
export async function fetchRuntimeInfo(
  baseUrl: string,
  token: string | null,
  timeoutMs: number = ADMIN_API_TIMEOUT_MS,
): Promise<RuntimeInfoResult> {
  if (token === null || token === "") {
    return { ok: false, error: NO_TOKEN_MESSAGE };
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    let response: Response;
    try {
      response = await fetch(`${baseUrl}${RUNTIME_INFO_PATH}`, {
        method: "GET",
        headers: { Authorization: `Bearer ${token}` },
        // 쿠키와 HTTP 인증 정보를 보내지 않는다. 관리 API 는 Bearer 토큰만 쓴다 (기본값 same-origin 에 기대지 않는다).
        credentials: "omit",
        signal: controller.signal,
      });
    } catch (error) {
      if (controller.signal.aborted) {
        return { ok: false, error: `런타임 정보 요청이 ${timeoutMs / 1000}초 안에 끝나지 않았다` };
      }
      return { ok: false, error: `런타임 정보를 가져오지 못했다: ${describe(error)}` };
    }
    if (response.status === 401) {
      return { ok: false, error: UNAUTHORIZED_MESSAGE };
    }
    if (response.status === 403) {
      return { ok: false, error: FORBIDDEN_MESSAGE };
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
