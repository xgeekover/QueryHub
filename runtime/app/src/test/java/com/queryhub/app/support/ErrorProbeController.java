package com.queryhub.app.support;

import com.queryhub.adapter.web.common.requestid.RequestIds;
import com.queryhub.adapter.web.common.response.SuccessEnvelope;
import com.queryhub.domain.error.ErrorCode;
import com.queryhub.domain.error.QueryHubException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring MVC 안의 오류 경로를 만드는 테스트 컨트롤러.
 *
 * <p>{@link ErrorProbeTestConfiguration} 이 {@code @Bean} 으로 등록한다. {@code @TestComponent} 이므로
 * {@code @SpringBootTest} 의 컴포넌트 스캔(com.queryhub 전체)은 이 클래스를 건너뛴다. 그래서 {@link QueryHubWebTest} 의
 * 컨텍스트에만 있다.
 */
@TestComponent
@RestController
@RequestMapping(ErrorProbeTestConfiguration.BASE_PATH)
public class ErrorProbeController {

  /** POST /echo 의 본문. */
  public record EchoRequest(String text) {}

  @PostMapping(
      path = "/echo",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public SuccessEnvelope<EchoRequest> echo(
      @RequestBody EchoRequest body, HttpServletRequest request) {
    return SuccessEnvelope.of(body, RequestIds.currentOrGenerate(request));
  }

  @GetMapping("/domain-error")
  public void domainError() {
    throw new QueryHubException(
        ErrorCode.INVALID_PARAMETER, ErrorProbeTestConfiguration.DOMAIN_ERROR_MESSAGE);
  }

  @GetMapping("/unexpected")
  public void unexpected() {
    throw new IllegalStateException("db password=" + ErrorProbeTestConfiguration.SECRET_MARKER);
  }
}
