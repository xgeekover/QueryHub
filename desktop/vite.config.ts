import path from "node:path";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// 런타임의 desktop 포트(runtime/app/src/main/resources/application-desktop.yml). 셸이 --server.port 로 같은 값을 넘긴다.
const RUNTIME_ORIGIN = "http://127.0.0.1:18580";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(import.meta.dirname, "src"),
    },
  },
  // Tauri CLI 의 출력을 지우지 않는다.
  clearScreen: false,
  server: {
    // tauri.conf.json 의 devUrl(http://localhost:1420)과 같다. 포트가 사용 중이면 다른 포트로 옮기지 않고 실패한다.
    port: 1420,
    strictPort: true,
    watch: {
      // Rust 쪽 변경은 Tauri CLI 가 본다.
      ignored: ["**/src-tauri/**"],
    },
    // 개발 전용: 관리 API 를 런타임으로 넘긴다. Origin 헤더를 지워 런타임이 개발 출처(http://localhost:1420)를 CORS 로 허용할
    // 필요가 없게 한다. 운영 빌드의 UI 는 http://127.0.0.1:18580 으로 직접 요청한다(런타임 CORS 가 tauri://localhost 를 허용).
    proxy: {
      "/admin/api": {
        target: RUNTIME_ORIGIN,
        changeOrigin: false,
        configure: (proxy) => {
          proxy.on("proxyReq", (proxyReq) => {
            proxyReq.removeHeader("origin");
          });
        },
      },
    },
  },
  build: {
    outDir: "dist",
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
    setupFiles: ["src/test/setup.ts"],
    // it.only 가 남아 있으면 실패한다. 테스트가 하나도 없어도 실패한다 (게이트의 거짓 통과 방지).
    allowOnly: false,
    passWithNoTests: false,
    restoreMocks: true,
    reporters: ["default", "junit"],
    outputFile: {
      // desktop/ 기준. scripts/gate/junit-check.sh 가 이 디렉터리의 TEST-*.xml 을 판정한다.
      junit: "build/test-results/vitest/TEST-vitest.xml",
    },
  },
});
