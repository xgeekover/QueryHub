import { afterEach, describe, expect, it, vi } from "vitest";

import {
  FORBIDDEN_MESSAGE,
  fetchRuntimeInfo,
  NO_TOKEN_MESSAGE,
  parseRuntimeInfoEnvelope,
  RUNTIME_INFO_PATH,
  UNAUTHORIZED_MESSAGE,
} from "./adminApi";

// 시험용 고정 가짜 토큰(셸이 만드는 형식과 같은 64 자 16진수). 실제 토큰이 아니다.
const TOKEN = "f".repeat(32) + "0".repeat(32);

const DATA = {
  name: "queryhub-runtime",
  version: "0.1.0-SNAPSHOT",
  mode: "desktop",
  startedAt: "2026-11-18T01:02:03.000Z",
  uptimeSeconds: 7,
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("fetchRuntimeInfo", () => {
  it("GET <base>/admin/api/v1/runtime 에 Authorization: Bearer <token> 헤더 하나만 더해 보내고 data 를 읽는다", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ success: true, data: DATA, meta: {} })));
    vi.stubGlobal("fetch", fetchMock);

    const result = await fetchRuntimeInfo("http://127.0.0.1:18580", TOKEN);

    expect(result).toEqual({ ok: true, info: DATA });
    expect(RUNTIME_INFO_PATH).toBe("/admin/api/v1/runtime");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://127.0.0.1:18580/admin/api/v1/runtime");
    expect(init.method).toBe("GET");
    expect(init.headers).toEqual({ Authorization: `Bearer ${TOKEN}` });
    expect(init.credentials).toBe("omit");
    expect(Object.keys(init).sort()).toEqual(["credentials", "headers", "method", "signal"]);
    expect(init.signal).toBeDefined();
  });

  it.each([null, ""])("토큰이 %j 이면 fetch 를 부르지 않는다", async (token) => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const result = await fetchRuntimeInfo("http://127.0.0.1:18580", token);

    expect(result).toEqual({ ok: false, error: NO_TOKEN_MESSAGE });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("401 과 403 은 서로 다른 고정 한국어 문장이고 토큰을 담지 않는다", async () => {
    const envelope = (code: string) =>
      JSON.stringify({ success: false, error: { code, message: TOKEN, requestId: "r" }, meta: {} });
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(envelope("UNAUTHORIZED"), { status: 401 })),
    );
    const unauthorized = await fetchRuntimeInfo("", TOKEN);
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(envelope("FORBIDDEN"), { status: 403 })),
    );
    const forbidden = await fetchRuntimeInfo("", TOKEN);

    expect(unauthorized).toEqual({ ok: false, error: UNAUTHORIZED_MESSAGE });
    expect(forbidden).toEqual({ ok: false, error: FORBIDDEN_MESSAGE });
    expect(UNAUTHORIZED_MESSAGE).not.toBe(FORBIDDEN_MESSAGE);
    expect(UNAUTHORIZED_MESSAGE).toContain("401");
    expect(FORBIDDEN_MESSAGE).toContain("403");
    for (const message of [UNAUTHORIZED_MESSAGE, FORBIDDEN_MESSAGE, NO_TOKEN_MESSAGE]) {
      expect(message).not.toContain(TOKEN);
      expect(message).toMatch(/[가-힣]/);
    }
  });

  it.each([
    ["네트워크 오류", () => Promise.reject(new TypeError("Failed to fetch")), "가져오지 못했다: Failed to fetch"],
    ["HTTP 503", () => Promise.resolve(new Response("{}", { status: 503 })), "HTTP 503"],
    [
      "success=false",
      () =>
        Promise.resolve(new Response(JSON.stringify({ success: false, error: { code: "NOT_FOUND", message: "m" } }))),
      "(NOT_FOUND)",
    ],
    ["잘못된 JSON", () => Promise.resolve(new Response("<html>")), "올바른 JSON 이 아니다"],
    [
      "data 형식 오류",
      () => Promise.resolve(new Response(JSON.stringify({ success: true, data: { ...DATA, uptimeSeconds: "7" } }))),
      "data 형식",
    ],
  ] as const)("%s 는 예외 없이 { ok: false } 다", async (_label, respond, text) => {
    vi.stubGlobal("fetch", vi.fn(respond));

    const result = await fetchRuntimeInfo("", TOKEN);

    expect(result.ok).toBe(false);
    expect(result.ok ? "" : result.error).toContain(text);
  });

  it("제한 시간이 지나면 요청을 취소한다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_url: string, init: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
          }),
      ),
    );

    const result = await fetchRuntimeInfo("", TOKEN, 20);

    expect(result).toEqual({ ok: false, error: "런타임 정보 요청이 0.02초 안에 끝나지 않았다" });
  });
});

describe("parseRuntimeInfoEnvelope", () => {
  it("객체가 아니거나 코드가 없는 실패 응답도 문장으로 바꾼다", () => {
    expect(parseRuntimeInfoEnvelope(null)).toEqual({ ok: false, error: "런타임 정보 응답의 형식이 올바르지 않다" });
    expect(parseRuntimeInfoEnvelope([1])).toEqual({ ok: false, error: "런타임 정보 응답의 형식이 올바르지 않다" });
    expect(parseRuntimeInfoEnvelope({ success: false })).toEqual({
      ok: false,
      error: "런타임이 오류를 돌려주었다 (코드 없음)",
    });
  });
});
