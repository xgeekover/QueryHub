import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { runtimeBaseUrl } from "@/runtime/baseUrl";
import { insideTauri } from "@/runtime/shell";
import type { RuntimeHealth, RuntimeInfoResult, RuntimeState, RuntimeStatus } from "@/runtime/types";
import { useAdminToken, useRuntimeInfo, useRuntimeStatus } from "@/runtime/useRuntimeStatus";

const STATE_LABELS: Record<RuntimeState, string> = {
  stopped: "중지됨",
  starting: "시작 중",
  running: "실행 중",
  failed: "실패",
};

const HEALTH_LABELS: Record<RuntimeHealth, string> = {
  unknown: "health 알 수 없음",
  up: "health 정상",
  down: "health 비정상",
};

// FR-13 버튼 규칙: 시작은 stopped·failed, 중지는 starting·running, 재시작은 running·failed.
const ENABLED: Record<"start" | "stop" | "restart", RuntimeState[]> = {
  start: ["stopped", "failed"],
  stop: ["starting", "running"],
  restart: ["running", "failed"],
};

interface RuntimePanelProps {
  // 기본값: Tauri 창 안인지(isTauri), 개발 서버인지(import.meta.env.DEV). 테스트가 바꿀 수 있다.
  inTauri?: boolean;
  dev?: boolean;
}

export function RuntimePanel({ inTauri = insideTauri(), dev = import.meta.env.DEV }: RuntimePanelProps) {
  const runtime = useRuntimeStatus(inTauri);
  const status = runtime.status;
  const running = status?.state === "running";
  // 관리 토큰은 Tauri 안에서 한 번 받아 메모리에만 두고 런타임 정보 요청에만 넘긴다(화면에 그리지 않는다).
  const admin = useAdminToken(inTauri);
  const fetched = useRuntimeInfo(status ? runtimeBaseUrl(status.baseUrl, dev) : null, running, admin.token);
  const info: RuntimeInfoResult | null = running && admin.error !== null ? { ok: false, error: admin.error } : fetched;

  if (!inTauri) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>런타임</CardTitle>
        </CardHeader>
        <CardContent>
          <Alert>
            <AlertTitle>Tauri 앱 밖에서 열렸다</AlertTitle>
            <AlertDescription>
              런타임 시작·중지는 데스크톱 앱에서만 할 수 있다. pnpm --dir desktop tauri dev 로 앱을 실행한다.
            </AlertDescription>
          </Alert>
        </CardContent>
      </Card>
    );
  }

  const state = status?.state;
  const can = (action: keyof typeof ENABLED) => !runtime.busy && state !== undefined && ENABLED[action].includes(state);

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle>런타임</CardTitle>
          <CardDescription>QueryHub 런타임(Spring Boot)을 이 앱의 자식 프로세스로 실행한다.</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center gap-2">
            <Badge data-testid="runtime-state" variant={state === "failed" ? "destructive" : "default"}>
              {state ? STATE_LABELS[state] : "확인 중"}
            </Badge>
            {status && (
              <Badge data-testid="runtime-health" variant="outline">
                {HEALTH_LABELS[status.health]}
              </Badge>
            )}
          </div>
          {status && <StatusDetails status={status} />}
          <div className="flex gap-2">
            <Button onClick={runtime.start} disabled={!can("start")}>
              시작
            </Button>
            <Button variant="outline" onClick={runtime.stop} disabled={!can("stop")}>
              중지
            </Button>
            <Button variant="outline" onClick={runtime.restart} disabled={!can("restart")}>
              재시작
            </Button>
          </div>
          {runtime.error && (
            <Alert variant="destructive">
              <AlertTitle>셸 오류</AlertTitle>
              <AlertDescription>{runtime.error}</AlertDescription>
            </Alert>
          )}
        </CardContent>
      </Card>
      {status?.state === "failed" && status.failure && (
        <Card data-testid="runtime-failure">
          <CardHeader>
            <CardTitle>실패: {status.failure.kind}</CardTitle>
            <CardDescription>
              {status.failure.exitCode === null ? "종료 코드 없음" : `종료 코드 ${status.failure.exitCode}`}
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-2">
            <p className="text-sm">{status.failure.message}</p>
            {status.failure.outputTail.length > 0 && (
              <pre className="max-h-80 overflow-auto rounded-md bg-muted p-3 text-xs whitespace-pre-wrap">
                {status.failure.outputTail.join("\n")}
              </pre>
            )}
          </CardContent>
        </Card>
      )}
      {running && <RuntimeInfoCard info={info} />}
    </div>
  );
}

function StatusDetails({ status }: { status: RuntimeStatus }) {
  return (
    <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm">
      <dt className="text-muted-foreground">PID</dt>
      <dd data-testid="runtime-pid">{status.pid ?? "-"}</dd>
      <dt className="text-muted-foreground">주소</dt>
      <dd data-testid="runtime-base-url">{status.baseUrl}</dd>
    </dl>
  );
}

function RuntimeInfoCard({ info }: { info: RuntimeInfoResult | null }) {
  return (
    <Card data-testid="runtime-info">
      <CardHeader>
        <CardTitle>런타임 정보</CardTitle>
        <CardDescription>GET /admin/api/v1/runtime</CardDescription>
      </CardHeader>
      <CardContent>
        {info === null && <p className="text-sm text-muted-foreground">불러오는 중</p>}
        {info !== null && !info.ok && (
          <p role="status" className="text-sm text-destructive">
            {info.error}
          </p>
        )}
        {info !== null && info.ok && (
          <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm">
            <dt className="text-muted-foreground">이름</dt>
            <dd>{info.info.name}</dd>
            <dt className="text-muted-foreground">버전</dt>
            <dd>{info.info.version}</dd>
            <dt className="text-muted-foreground">모드</dt>
            <dd>{info.info.mode}</dd>
            <dt className="text-muted-foreground">시작 시각</dt>
            <dd>{info.info.startedAt}</dd>
            <dt className="text-muted-foreground">가동 시간</dt>
            <dd>{info.info.uptimeSeconds}초</dd>
          </dl>
        )}
      </CardContent>
    </Card>
  );
}
