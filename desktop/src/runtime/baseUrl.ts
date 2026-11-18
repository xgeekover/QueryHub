// 관리 API 의 기준 URL. 운영(앱 번들)에서는 셸이 알려 준 status.baseUrl(http://127.0.0.1:18580)이고, 개발 서버에서는 ''
// (같은 출처, Vite 프록시가 /admin/api 를 런타임으로 넘긴다). 호출하는 쪽이 dev 에 import.meta.env.DEV 를 넘긴다.
export function runtimeBaseUrl(statusBaseUrl: string, dev: boolean): string {
  return dev ? "" : statusBaseUrl;
}
