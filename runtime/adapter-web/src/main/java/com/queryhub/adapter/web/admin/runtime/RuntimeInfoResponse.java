package com.queryhub.adapter.web.admin.runtime;

import com.queryhub.application.runtime.RuntimeInfo;

/**
 * GET /admin/api/v1/runtime 의 data.
 *
 * @param name 애플리케이션 이름
 * @param version 빌드 버전
 * @param mode "desktop" 또는 "server"
 * @param startedAt 기동 시각 (ISO-8601, UTC)
 * @param uptimeSeconds 기동 후 경과 초
 */
public record RuntimeInfoResponse(
    String name, String version, String mode, String startedAt, long uptimeSeconds) {

  public static RuntimeInfoResponse from(RuntimeInfo info) {
    return new RuntimeInfoResponse(
        info.name(),
        info.version(),
        info.mode().id(),
        info.startedAt().toString(),
        info.uptimeSeconds());
  }
}
