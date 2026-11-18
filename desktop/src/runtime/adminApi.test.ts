import { afterEach, describe, expect, it, vi } from "vitest";

import { fetchRuntimeInfo, parseRuntimeInfoEnvelope, RUNTIME_INFO_PATH } from "./adminApi";

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
  it("GET <base>/admin/api/v1/runtime 의 성공 응답에서 data 를 읽는다 (사용자 정의 헤더 없음)", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ success: true, data: DATA, meta: {} })));
    vi.stubGlobal("fetch", fetchMock);

    const result = await fetchRuntimeInfo("http://127.0.0.1:18580");

    expect(result).toEqual({ ok: true, info: DATA });
    expect(RUNTIME_INFO_PATH).toBe("/admin/api/v1/runtime");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://127.0.0.1:18580/admin/api/v1/runtime");
    expect(init.method).toBe("GET");
    expect(init.headers).toBeUndefined();
    expect(init.signal).toBeDefined();
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

    const result = await fetchRuntimeInfo("");

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

    const result = await fetchRuntimeInfo("", 20);

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
