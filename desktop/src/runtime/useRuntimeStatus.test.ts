import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// IPC 는 흉내 낸다. 커맨드마다 테스트가 직접 끝내는 약속을 돌려주어 응답이 도착하는 순서를 정한다.
vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(),
  isTauri: vi.fn(() => true),
}));

import { invoke } from "@tauri-apps/api/core";

import type { FailureKind, RuntimeState, RuntimeStatus } from "./types";
import { useAdminToken, useRuntimeInfo, useRuntimeStatus } from "./useRuntimeStatus";

const invokeMock = vi.mocked(invoke);

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function status(state: RuntimeState, kind: FailureKind | null = null): RuntimeStatus {
  return {
    state,
    health: state === "running" ? "up" : "unknown",
    pid: state === "running" || state === "starting" ? 4242 : null,
    port: 18580,
    baseUrl: "http://127.0.0.1:18580",
    failure: kind === null ? null : { kind, message: `failure ${kind}`, exitCode: null, outputTail: [] },
  };
}

// runtime_status 호출은 polls 에 차례로 쌓이고, 그 밖의 커맨드는 actions 에 쌓인다.
let polls: Deferred<RuntimeStatus>[];
let actions: { command: string; reply: Deferred<RuntimeStatus> }[];

beforeEach(() => {
  polls = [];
  actions = [];
  const impl = (command: string) => {
    const reply = deferred<RuntimeStatus>();
    if (command === "runtime_status") {
      polls.push(reply);
    } else {
      actions.push({ command, reply });
    }
    return reply.promise;
  };
  invokeMock.mockImplementation(impl as unknown as typeof invoke);
});

// 약속의 후속 처리와 React 갱신이 모두 끝날 때까지 기다린다.
async function settle(run: () => void = () => undefined) {
  await act(async () => {
    run();
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

// 첫 조회가 failed(port_in_use)를 돌려준 뒤, 상태가 바뀌어 다시 보낸 조회(polls[1])가 아직 오지 않은 상태.
async function renderFailedWithPollInFlight() {
  const hook = renderHook(() => useRuntimeStatus(true));
  expect(polls).toHaveLength(1);
  await settle(() => polls[0].resolve(status("failed", "port_in_use")));
  expect(hook.result.current.status?.failure?.kind).toBe("port_in_use");
  expect(polls).toHaveLength(2);
  return hook;
}

describe("useRuntimeStatus response order", () => {
  it("a status poll that resolves after an action's result does not replace it", async () => {
    const { result } = await renderFailedWithPollInFlight();

    await settle(() => result.current.start());
    expect(actions.map((a) => a.command)).toEqual(["runtime_start"]);
    expect(result.current.busy).toBe(true);
    // 다시 실패했지만 이유가 다르다. 상태(failed)가 그대로이므로 조회 효과는 다시 실행되지 않고 polls[1] 이 살아 있다.
    await settle(() => actions[0].reply.resolve(status("failed", "java_not_found")));
    expect(result.current.status?.failure?.kind).toBe("java_not_found");
    expect(result.current.busy).toBe(false);

    // 동작 전에 보낸 조회가 이제야 예전 상태로 도착한다.
    await settle(() => polls[1].resolve(status("failed", "port_in_use")));
    expect(result.current.status?.failure?.kind).toBe("java_not_found");
    expect(result.current.error).toBeNull();
  });

  it("a stale status poll that fails after an action's result shows no error", async () => {
    const { result } = await renderFailedWithPollInFlight();

    await settle(() => result.current.restart());
    await settle(() => actions[0].reply.resolve(status("failed", "jar_not_found")));
    await settle(() => polls[1].reject(new Error("ipc closed")));

    expect(result.current.status?.failure?.kind).toBe("jar_not_found");
    expect(result.current.error).toBeNull();
  });

  it("a poll sent after the action's result is applied again", async () => {
    const { result } = await renderFailedWithPollInFlight();

    await settle(() => result.current.start());
    await settle(() => actions[0].reply.resolve(status("starting")));
    expect(result.current.status?.state).toBe("starting");

    // 상태가 바뀌어 새 조회를 보냈다. 그 결과는 동작의 결과보다 새로우므로 반영한다. 예전 조회는 버린다.
    expect(polls).toHaveLength(3);
    await settle(() => polls[2].resolve(status("running")));
    expect(result.current.status?.state).toBe("running");
    await settle(() => polls[1].resolve(status("failed", "port_in_use")));
    expect(result.current.status?.state).toBe("running");
  });

  it("an action that fails keeps its error even if an older poll answers later", async () => {
    const { result } = await renderFailedWithPollInFlight();

    await settle(() => result.current.stop());
    await settle(() => actions[0].reply.reject(new Error("ipc closed")));
    expect(result.current.error).toBe("셸 호출이 실패했다: ipc closed");

    await settle(() => polls[1].resolve(status("failed", "port_in_use")));
    expect(result.current.error).toBe("셸 호출이 실패했다: ipc closed");
  });
});

// 시험용 고정 가짜 토큰(셸이 만드는 형식과 같은 64 자 16진수). 실제 토큰이 아니다.
const TOKEN = "a".repeat(64);

describe("useAdminToken", () => {
  it("Tauri 안에서 admin_token 을 한 번만 부르고, 다시 그려도 다시 부르지 않는다", async () => {
    invokeMock.mockImplementation((async (command: string) =>
      command === "admin_token" ? TOKEN : status("running")) as unknown as typeof invoke);
    const setItem = vi.spyOn(Storage.prototype, "setItem");

    const { result, rerender } = renderHook(() => useAdminToken(true));
    await waitFor(() => expect(result.current.token).toBe(TOKEN));
    rerender();
    rerender();
    await settle();

    expect(invokeMock.mock.calls).toEqual([["admin_token"]]);
    expect(result.current.error).toBeNull();
    expect(setItem).not.toHaveBeenCalled();
  });

  it("Tauri 밖(enabled=false)에서는 부르지 않는다", async () => {
    const { result } = renderHook(() => useAdminToken(false));
    await settle();

    expect(invokeMock).not.toHaveBeenCalled();
    expect(result.current).toEqual({ token: null, error: null });
  });

  it("셸 호출이 실패하면 토큰은 null 이고 오류 문장을 돌려준다", async () => {
    invokeMock.mockImplementation((async () => {
      throw new Error("not allowed");
    }) as unknown as typeof invoke);

    const { result } = renderHook(() => useAdminToken(true));
    await waitFor(() => expect(result.current.error).toBe("관리 토큰을 셸에서 받지 못했다: not allowed"));
    expect(result.current.token).toBeNull();
  });
});

describe("useRuntimeInfo", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn(async () => new Response(JSON.stringify({ success: false, error: { code: "X" } })));
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("토큰이 아직 없으면 running 이어도 요청하지 않고, 토큰이 오면 Bearer 헤더로 요청한다", async () => {
    const { result, rerender } = renderHook(
      ({ token }: { token: string | null }) => useRuntimeInfo("http://127.0.0.1:18580", true, token),
      { initialProps: { token: null as string | null } },
    );
    await settle();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.current).toBeNull();

    rerender({ token: TOKEN });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://127.0.0.1:18580/admin/api/v1/runtime");
    expect(init.headers).toEqual({ Authorization: `Bearer ${TOKEN}` });
  });

  it("running 이 아니면 토큰이 있어도 요청하지 않는다", async () => {
    renderHook(() => useRuntimeInfo("http://127.0.0.1:18580", false, TOKEN));
    await settle();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
