// Vitest 공통 설정: jest-dom 매처(toBeInTheDocument 등)를 expect 에 더하고, 테스트마다 렌더링한 DOM 을 지운다.
import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

afterEach(() => {
  cleanup();
});
