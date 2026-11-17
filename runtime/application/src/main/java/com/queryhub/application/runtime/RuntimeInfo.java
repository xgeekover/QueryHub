package com.queryhub.application.runtime;

import com.queryhub.domain.runtime.RuntimeMode;
import java.time.Instant;

/**
 * 런타임 상태 조회 결과.
 *
 * @param name 애플리케이션 이름 (spring.application.name)
 * @param version 빌드 버전
 * @param mode 실행 모드
 * @param startedAt 기동 시각
 * @param uptimeSeconds 기동 후 경과 초. 0 이상이다.
 */
public record RuntimeInfo(
    String name, String version, RuntimeMode mode, Instant startedAt, long uptimeSeconds) {}
