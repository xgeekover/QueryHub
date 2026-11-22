// 런타임 상태 폴링(셸 커맨드), 관리 토큰(셸 커맨드, 한 번), 런타임 정보 조회(관리 API) 훅.
import { useCallback, useEffect, useRef, useState } from "react";

import { fetchRuntimeInfo } from "./adminApi";
import { adminToken, runtimeRestart, runtimeStart, runtimeStatus, runtimeStop } from "./shell";
import type { RuntimeInfoResult, RuntimeState, RuntimeStatus } from "./types";

// 상태 조회 간격: starting 동안 1 초, 그 밖에는 3 초. 런타임 정보는 running 동안 10 초마다.
export const STATUS_POLL_STARTING_MS = 1000;
export const STATUS_POLL_MS = 3000;
export const INFO_POLL_MS = 10000;

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export interface RuntimeController {
  status: RuntimeStatus | null;
  // 셸 호출 자체가 실패했을 때의 문장.
  error: string | null;
  busy: boolean;
  start: () => void;
  stop: () => void;
  restart: () => void;
}

// 응답 순서. 상태 조회는 보낼 때 번호를 받는다(issued 를 늘린다). 시작·정지·재시작의 결과가 오면 그때까지 보낸 조회를 모두 낡은
// 것으로 본다(applied = issued). 조회 결과는 번호가 applied 보다 클 때만 반영한다. 그래서 동작이 끝나기 전에 보낸 조회가 늦게
// 도착해도 동작의 결과를 덮어쓰지 않고, 조회끼리도 늦게 보낸 것이 이긴다.
interface ResponseOrder {
  issued: number;
  applied: number;
}

function issue(order: ResponseOrder): number {
  order.issued += 1;
  return order.issued;
}

function accept(order: ResponseOrder, ticket: number): boolean {
  if (ticket <= order.applied) {
    return false;
  }
  order.applied = ticket;
  return true;
}

// enabled 가 false 이면(Tauri 밖) 아무것도 부르지 않는다.
export function useRuntimeStatus(enabled: boolean): RuntimeController {
  const [status, setStatus] = useState<RuntimeStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const orderRef = useRef<ResponseOrder>({ issued: 0, applied: 0 });
  const state: RuntimeState | undefined = status?.state;

  useEffect(() => {
    if (!enabled) {
      return;
    }
    const order = orderRef.current;
    let cancelled = false;
    const poll = async () => {
      const ticket = issue(order);
      try {
        const next = await runtimeStatus();
        if (!cancelled && accept(order, ticket)) {
          setStatus(next);
          setError(null);
        }
      } catch (e) {
        if (!cancelled && accept(order, ticket)) {
          setError(`셸 호출이 실패했다: ${describe(e)}`);
        }
      }
    };
    void poll();
    const timer = setInterval(() => void poll(), state === "starting" ? STATUS_POLL_STARTING_MS : STATUS_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [enabled, state]);

  const run = useCallback(
    (action: () => Promise<RuntimeStatus>) => {
      if (!enabled) {
        return;
      }
      setBusy(true);
      // 동작의 결과가 지금까지 보낸 어떤 조회보다 새롭다.
      const supersedePolls = () => {
        const order = orderRef.current;
        order.applied = order.issued;
      };
      action()
        .then((next) => {
          supersedePolls();
          setStatus(next);
          setError(null);
        })
        .catch((e: unknown) => {
          supersedePolls();
          setError(`셸 호출이 실패했다: ${describe(e)}`);
        })
        .finally(() => setBusy(false));
    },
    [enabled],
  );

  return {
    status,
    error,
    busy,
    start: () => run(runtimeStart),
    stop: () => run(runtimeStop),
    restart: () => run(runtimeRestart),
  };
}

export interface AdminTokenState {
  // 셸이 준 토큰. 받기 전이거나 받지 못했으면 null.
  token: string | null;
  // 셸 호출이 실패했을 때의 문장(토큰을 담지 않는다).
  error: string | null;
}

// 관리 토큰을 셸에서 한 번(마운트당 한 번) 받는다. enabled 가 false 이면(Tauri 밖) 부르지 않는다. 토큰은 이 훅의 메모리(React
// 상태)에만 있고 localStorage, sessionStorage, 화면, 로그에 남기지 않는다. 셸 프로세스마다 값이 하나이므로 다시 받지 않는다.
export function useAdminToken(enabled: boolean): AdminTokenState {
  const [state, setState] = useState<AdminTokenState>({ token: null, error: null });
  const requested = useRef(false);

  useEffect(() => {
    if (!enabled || requested.current) {
      return;
    }
    requested.current = true;
    adminToken()
      .then((token) => setState({ token, error: null }))
      .catch((e: unknown) => setState({ token: null, error: `관리 토큰을 셸에서 받지 못했다: ${describe(e)}` }));
  }, [enabled]);

  return state;
}

// running 에 들어갈 때 한 번, 그 뒤 10 초마다 런타임 정보를 읽는다(Authorization: Bearer <token>). running 이 아니면 null.
// 토큰이 아직 없으면 요청하지 않는다.
export function useRuntimeInfo(
  baseUrl: string | null,
  running: boolean,
  token: string | null,
): RuntimeInfoResult | null {
  const [result, setResult] = useState<RuntimeInfoResult | null>(null);

  useEffect(() => {
    if (!running || baseUrl === null || token === null) {
      return;
    }
    let cancelled = false;
    const load = async () => {
      const next = await fetchRuntimeInfo(baseUrl, token);
      if (!cancelled) {
        setResult(next);
      }
    };
    void load();
    const timer = setInterval(() => void load(), INFO_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
      setResult(null);
    };
  }, [baseUrl, running, token]);

  return running ? result : null;
}
