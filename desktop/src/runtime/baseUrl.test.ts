import { describe, expect, it } from "vitest";

import { runtimeBaseUrl } from "./baseUrl";

describe("runtimeBaseUrl", () => {
  it("운영(앱 번들)에서는 셸이 알려 준 baseUrl 이다", () => {
    expect(runtimeBaseUrl("http://127.0.0.1:18580", false)).toBe("http://127.0.0.1:18580");
  });

  it("개발 서버에서는 빈 문자열(같은 출처, Vite 프록시)이다", () => {
    expect(runtimeBaseUrl("http://127.0.0.1:18580", true)).toBe("");
  });
});
