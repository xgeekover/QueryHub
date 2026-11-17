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
 * <p>Phase 0 에서는 민감하지 않은 상태만 돌려주므로 인증이 없다. 관리자 토큰은 Phase 1 의 첫 태스크에서 도입한다.
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
