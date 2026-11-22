package com.queryhub.adapter.web.admin.runtime;

import com.queryhub.adapter.web.common.requestid.RequestIds;
import com.queryhub.adapter.web.common.response.SuccessEnvelope;
import com.queryhub.application.runtime.GetRuntimeInfoUseCase;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리 API: 런타임 상태 조회. 유스케이스에 위임하고 응답 형식으로 바꾸기만 한다.
 *
 * <p>P1-03 부터 관리 SecurityFilterChain(adapter.web.admin.security)이 이 경로를 지킨다. 루프백·Host 가드를 통과하고
 * {@code Authorization: Bearer <관리자 토큰>} 이 맞는 요청만 여기에 닿는다. 토큰이 없거나 틀리면 401, 가드가 거부하면 403 이다.
 */
@RestController
@RequestMapping("/admin/api/v1/runtime")
public class RuntimeInfoController {

  private final GetRuntimeInfoUseCase getRuntimeInfo;

  public RuntimeInfoController(GetRuntimeInfoUseCase getRuntimeInfo) {
    this.getRuntimeInfo = getRuntimeInfo;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public SuccessEnvelope<RuntimeInfoResponse> getRuntimeInfo(HttpServletRequest request) {
    return SuccessEnvelope.of(
        RuntimeInfoResponse.from(getRuntimeInfo.getRuntimeInfo()),
        RequestIds.currentOrGenerate(request));
  }
}
