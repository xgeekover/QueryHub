// 런타임 상태 폴링(셸 커맨드)과 런타임 정보 조회(관리 API) 훅.
import { useCallback, useEffect, useRef, useState } from "react";

import { fetchRuntimeInfo } from "./adminApi";
import { runtimeRestart, runtimeStart, runtimeStatus, runtimeStop } from "./shell";
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

// running 에 들어갈 때 한 번, 그 뒤 10 초마다 런타임 정보를 읽는다. running 이 아니면 null.
export function useRuntimeInfo(baseUrl: string | null, running: boolean): RuntimeInfoResult | null {
  const [result, setResult] = useState<RuntimeInfoResult | null>(null);

  useEffect(() => {
    if (!running || baseUrl === null) {
      return;
    }
    let cancelled = false;
    const load = async () => {
      const next = await fetchRuntimeInfo(baseUrl);
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
  }, [baseUrl, running]);

  return running ? result : null;
}
