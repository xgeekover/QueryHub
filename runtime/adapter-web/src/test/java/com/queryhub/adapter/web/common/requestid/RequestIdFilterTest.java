package com.queryhub.adapter.web.common.requestid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

  private final RequestIdFilter filter = new RequestIdFilter();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  /** 필터를 실행하고, 체인 안에서 본 MDC 값과 응답 헤더를 기록한다. */
  private static final class Observation {
    final AtomicReference<String> mdcInChain = new AtomicReference<>();
    final AtomicReference<String> headerBeforeChain = new AtomicReference<>();
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
    final MockHttpServletResponse response = new MockHttpServletResponse();

    FilterChain chain() {
      return (req, res) -> {
        mdcInChain.set(MDC.get(RequestIds.MDC_KEY));
        headerBeforeChain.set(response.getHeader(RequestIds.HEADER));
      };
    }
  }

  private Observation run(String incomingHeader) throws Exception {
    Observation observation = new Observation();
    if (incomingHeader != null) {
      observation.request.addHeader(RequestIds.HEADER, incomingHeader);
    }
    filter.doFilter(observation.request, observation.response, observation.chain());
    return observation;
  }

  @Test
  void echoesAValidIncomingId() throws Exception {
    Observation observation = run("abc-123.X_9");

    assertThat(observation.response.getHeader(RequestIds.HEADER)).isEqualTo("abc-123.X_9");
    assertThat(observation.request.getAttribute(RequestIds.ATTRIBUTE)).isEqualTo("abc-123.X_9");
    assertThat(observation.mdcInChain.get()).isEqualTo("abc-123.X_9");
  }

  @Test
  void acceptsAnIdOfExactly64Characters() throws Exception {
    String id = "a".repeat(64);

    assertThat(run(id).response.getHeader(RequestIds.HEADER)).isEqualTo(id);
  }

  @Test
  void generatesAUuidWhenTheHeaderIsAbsent() throws Exception {
    Observation observation = run(null);

    String id = observation.response.getHeader(RequestIds.HEADER);
    assertThat(UUID.fromString(id).toString()).isEqualTo(id);
    assertThat(observation.request.getAttribute(RequestIds.ATTRIBUTE)).isEqualTo(id);
    assertThat(observation.mdcInChain.get()).isEqualTo(id);
  }

  static Stream<String> invalidIds() {
    return Stream.of(
        "",
        "has space",
        "line\r\nX-Injected: 1",
        "semi;colon",
        "slash/value",
        "한글아이디",
        "a".repeat(65));
  }

  @ParameterizedTest
  @MethodSource("invalidIds")
  void replacesAnInvalidIdWithAGeneratedUuid(String invalid) throws Exception {
    Observation observation = run(invalid);

    String id = observation.response.getHeader(RequestIds.HEADER);
    assertThat(id).isNotEqualTo(invalid);
    assertThat(UUID.fromString(id).toString()).isEqualTo(id);
    assertThat(observation.request.getAttribute(RequestIds.ATTRIBUTE)).isEqualTo(id);
  }

  @Test
  void lengthLimitIs64Characters() {
    assertThat(RequestIds.isValid("a".repeat(65))).isFalse();
    assertThat(RequestIds.isValid("a".repeat(64))).isTrue();
  }

  @Test
  void setsTheResponseHeaderBeforeInvokingTheChain() throws Exception {
    Observation observation = run("before-chain");

    assertThat(observation.headerBeforeChain.get()).isEqualTo("before-chain");
  }

  @Test
  void setsMdcDuringTheChainAndRemovesItAfterwards() throws Exception {
    Observation observation = run("mdc-check");

    assertThat(observation.mdcInChain.get()).isEqualTo("mdc-check");
    assertThat(MDC.get(RequestIds.MDC_KEY)).isNull();
  }

  @Test
  void removesMdcEvenWhenTheChainThrows() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/boom");
    request.addHeader(RequestIds.HEADER, "throwing-chain");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> mdcInChain = new AtomicReference<>();
    FilterChain throwingChain =
        (req, res) -> {
          mdcInChain.set(MDC.get(RequestIds.MDC_KEY));
          throw new IllegalStateException("chain failed");
        };

    assertThatThrownBy(() -> filter.doFilter(request, response, throwingChain))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("chain failed");
    assertThat(mdcInChain.get()).isEqualTo("throwing-chain");
    assertThat(MDC.get(RequestIds.MDC_KEY)).isNull();
    assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo("throwing-chain");
  }

  @Test
  void runsFirst() {
    assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
  }

  @Test
  void currentOrGenerateStoresAGeneratedIdForRequestsThatBypassedTheFilter() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");

    assertThat(RequestIds.current(request)).isEmpty();
    String generated = RequestIds.currentOrGenerate(request);

    assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
    assertThat(RequestIds.current(request)).contains(generated);
    assertThat(RequestIds.currentOrGenerate(request)).isEqualTo(generated);
  }
}
