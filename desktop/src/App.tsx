import { RuntimePanel } from "@/components/RuntimePanel";

// 한 화면: 런타임 패널. 메뉴와 나머지 화면은 Phase 6 계획이다.
export default function App() {
  return (
    <main className="mx-auto flex min-h-screen max-w-3xl flex-col gap-4 p-6">
      <h1 className="text-2xl font-semibold">QueryHub</h1>
      <RuntimePanel />
    </main>
  );
}
