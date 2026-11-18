import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// IPC 는 흉내 낸다. 실제 셸 없이 invoke 호출과 Tauri 밖 동작을 확인한다.
vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(),
  isTauri: vi.fn(() => true),
}));

import { invoke, isTauri } from "@tauri-apps/api/core";

import type { RuntimeState, RuntimeStatus } from "@/runtime/types";

import { RuntimePanel } from "./RuntimePanel";

const invokeMock = vi.mocked(invoke);
const isTauriMock = vi.mocked(isTauri);

const LABELS: Record<RuntimeState, string> = {
  stopped: "중지됨",
  starting: "시작 중",
  running: "실행 중",
  failed: "실패",
};

const INFO_ENVELOPE = {
  success: true,
  data: {
    name: "queryhub-runtime",
    version: "0.1.0-SNAPSHOT",
    mode: "desktop",
    startedAt: "2026-11-18T01:02:03.000Z",
    uptimeSeconds: 42,
  },
  meta: { requestId: "req-1" },
};

function status(state: RuntimeState, extra: Partial<RuntimeStatus> = {}): RuntimeStatus {
  return {
    state,
    health: state === "running" ? "up" : "unknown",
    pid: state === "running" || state === "starting" ? 4242 : null,
    port: 18580,
    baseUrl: "http://127.0.0.1:18580",
    failure:
      state === "failed"
        ? {
            kind: "exited",
            message: "런타임이 종료 코드 1 로 끝났다.",
            exitCode: 1,
            outputTail: ["Caused by: java.sql.SQLException", "password=********"],
          }
        : null,
    ...extra,
  };
}

// runtime_status 는 current 를, 그 밖의 커맨드는 after 에 정한 상태(없으면 current)를 돌려준다.
function mockShell(current: RuntimeStatus, after: Partial<Record<string, RuntimeStatus>> = {}) {
  const impl = async (command: string) => after[command] ?? current;
  invokeMock.mockImplementation(impl as unknown as typeof invoke);
}

function jsonResponse(body: unknown, init: ResponseInit = { status: 200 }): Response {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { "Content-Type": "application/json" },
  });
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  isTauriMock.mockReturnValue(true);
  fetchMock = vi.fn(async () => jsonResponse(INFO_ENVELOPE));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

async function expectState(state: RuntimeState) {
  await waitFor(() => expect(screen.getByTestId("runtime-state")).toHaveTextContent(LABELS[state]));
}

describe("RuntimePanel", () => {
  it.each([
    ["stopped", { start: true, stop: false, restart: false }],
    ["starting", { start: false, stop: true, restart: false }],
    ["running", { start: false, stop: true, restart: true }],
    ["failed", { start: true, stop: false, restart: true }],
  ] as const)("%s 상태의 한국어 라벨과 버튼 활성화 규칙", async (state, enabled) => {
    mockShell(status(state));
    render(<RuntimePanel dev={false} />);
    await expectState(state);

    const buttons = { start: "시작", stop: "중지", restart: "재시작" } as const;
    for (const [action, name] of Object.entries(buttons) as [keyof typeof buttons, string][]) {
      const button = screen.getByRole("button", { name });
      if (enabled[action]) {
        expect(button).toBeEnabled();
      } else {
        expect(button).toBeDisabled();
      }
    }
  });

  it("시작 버튼은 runtime_start 를 부른다", async () => {
    mockShell(status("stopped"), { runtime_start: status("starting") });
    render(<RuntimePanel dev={false} />);
    await expectState("stopped");

    fireEvent.click(screen.getByRole("button", { name: "시작" }));
    await waitFor(() => expect(invokeMock).toHaveBeenCalledWith("runtime_start"));
    expect(invokeMock).toHaveBeenCalledWith("runtime_status");
  });

  it("중지와 재시작 버튼은 runtime_stop 과 runtime_restart 를 부른다", async () => {
    mockShell(status("running"));
    render(<RuntimePanel dev={false} />);
    await expectState("running");

    fireEvent.click(screen.getByRole("button", { name: "중지" }));
    await waitFor(() => expect(invokeMock).toHaveBeenCalledWith("runtime_stop"));
    await waitFor(() => expect(screen.getByRole("button", { name: "재시작" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "재시작" }));
    await waitFor(() => expect(invokeMock).toHaveBeenCalledWith("runtime_restart"));

    const commands = invokeMock.mock.calls.map((call) => call[0]);
    expect(new Set(commands)).toEqual(new Set(["runtime_status", "runtime_stop", "runtime_restart"]));
  });

  it("running 이면 관리 API 에서 런타임 정보를 읽어 보여 준다", async () => {
    mockShell(status("running"));
    render(<RuntimePanel dev={false} />);

    const card = await screen.findByTestId("runtime-info");
    await waitFor(() => expect(card).toHaveTextContent("queryhub-runtime"));
    expect(card).toHaveTextContent("0.1.0-SNAPSHOT");
    expect(card).toHaveTextContent("desktop");
    expect(card).toHaveTextContent("42초");
    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:18580/admin/api/v1/runtime",
      expect.objectContaining({ method: "GET" }),
    );
  });

  it("개발 서버에서는 같은 출처(프록시)로 요청한다", async () => {
    mockShell(status("running"));
    render(<RuntimePanel dev={true} />);

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith("/admin/api/v1/runtime", expect.objectContaining({ method: "GET" })),
    );
  });

  it.each([
    ["네트워크 오류", () => Promise.reject(new TypeError("Failed to fetch")), "가져오지 못했다: Failed to fetch"],
    ["HTTP 500", () => Promise.resolve(new Response("oops", { status: 500 })), "HTTP 500"],
    [
      "success=false",
      () =>
        Promise.resolve(
          jsonResponse({ success: false, error: { code: "INTERNAL_ERROR", message: "x", requestId: "r" } }),
        ),
      "INTERNAL_ERROR",
    ],
    ["잘못된 JSON", () => Promise.resolve(new Response("{not json", { status: 200 })), "올바른 JSON 이 아니다"],
  ] as const)("%s 는 오류 문장으로 보이고 화면은 그대로다", async (_label, respond, text) => {
    fetchMock.mockImplementation(respond);
    mockShell(status("running"));
    render(<RuntimePanel dev={false} />);

    const card = await screen.findByTestId("runtime-info");
    await waitFor(() => expect(card).toHaveTextContent(text));
    expect(screen.getByTestId("runtime-state")).toHaveTextContent("실행 중");
  });

  it("failed 상태는 메시지와 outputTail 을 보여 준다", async () => {
    mockShell(status("failed"));
    render(<RuntimePanel dev={false} />);

    const card = await screen.findByTestId("runtime-failure");
    expect(card).toHaveTextContent("런타임이 종료 코드 1 로 끝났다.");
    expect(card).toHaveTextContent("종료 코드 1");
    const pre = card.querySelector("pre");
    expect(pre?.textContent).toBe("Caused by: java.sql.SQLException\npassword=********");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("Tauri 밖에서는 안내만 보이고 invoke 를 부르지 않는다", async () => {
    isTauriMock.mockReturnValue(false);
    render(<RuntimePanel />);

    expect(screen.getByText("Tauri 앱 밖에서 열렸다")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "시작" })).not.toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(invokeMock).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
