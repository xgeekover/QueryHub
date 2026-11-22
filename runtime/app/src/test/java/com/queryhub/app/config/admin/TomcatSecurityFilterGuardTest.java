package com.queryhub.app.config.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryhub.adapter.web.common.requestid.RequestIdFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import org.apache.catalina.core.StandardContext;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.Test;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.filter.ForwardedHeaderFilter;

/** P1-03 3회차 C2-R1 2층: Tomcat 필터 매핑 검사. 실제 기동 경로는 AdminSecurityStartupTest 가 본다. */
class TomcatSecurityFilterGuardTest {

  private static final String[] ALL = {"REQUEST", "FORWARD", "INCLUDE", "ERROR", "ASYNC"};

  @Test
  void securityFilterOnEveryRequestAfterKnownFiltersPasses() {
    StandardContext context = new StandardContext();
    add(context, "requestIdFilter", new RequestIdFilter(), "/*", ALL);
    add(context, TomcatSecurityFilterGuard.SECURITY_FILTER, new DelegatingFilterProxy(), "/*", ALL);
    add(context, "laterFilter", new AnswersEverything(), "/*", ALL);

    assertThat(TomcatSecurityFilterGuard.problems(context, false)).isEmpty();
  }

  @Test
  void missingSecurityFilterMappingIsAProblem() {
    StandardContext context = new StandardContext();
    add(context, "requestIdFilter", new RequestIdFilter(), "/*", ALL);

    assertThat(TomcatSecurityFilterGuard.problems(context, false))
        .singleElement()
        .asString()
        .contains("springSecurityFilterChain", "/*");
  }

  /** C3-R6: 같은 이름이라도 DelegatingFilterProxy 가 아닌 필터는 관리 체인이 아니다. */
  @Test
  void securityFilterThatIsNotADelegatingFilterProxyIsAProblem() {
    StandardContext context = new StandardContext();
    add(context, "requestIdFilter", new RequestIdFilter(), "/*", ALL);
    add(context, TomcatSecurityFilterGuard.SECURITY_FILTER, new AnswersEverything(), "/*", ALL);

    assertThat(TomcatSecurityFilterGuard.problems(context, false))
        .singleElement()
        .asString()
        .contains("DelegatingFilterProxy", AnswersEverything.class.getName());
  }

  @Test
  void securityFilterOnlyOnSomePathsIsAProblem() {
    StandardContext context = new StandardContext();
    add(
        context,
        TomcatSecurityFilterGuard.SECURITY_FILTER,
        new DelegatingFilterProxy(),
        "/api/*",
        ALL);

    assertThat(TomcatSecurityFilterGuard.problems(context, false)).hasSize(1);
  }

  @Test
  void securityFilterWithoutTheRequestDispatcherIsAProblem() {
    StandardContext context = new StandardContext();
    add(
        context,
        TomcatSecurityFilterGuard.SECURITY_FILTER,
        new DelegatingFilterProxy(),
        "/*",
        "ERROR");

    assertThat(TomcatSecurityFilterGuard.problems(context, false))
        .singleElement()
        .asString()
        .contains("dispatcher", "ERROR");
  }

  @Test
  void securityFilterWithoutForwardIsAProblem() {
    StandardContext context = new StandardContext();
    add(
        context,
        TomcatSecurityFilterGuard.SECURITY_FILTER,
        new DelegatingFilterProxy(),
        "/*",
        "REQUEST",
        "ERROR",
        "ASYNC",
        "INCLUDE");

    assertThat(TomcatSecurityFilterGuard.problems(context, false)).hasSize(1);
  }

  @Test
  void unknownFilterBeforeTheSecurityFilterIsAProblem() {
    StandardContext context = new StandardContext();
    add(context, "early", new AnswersEverything(), "/admin/*", ALL);
    add(context, TomcatSecurityFilterGuard.SECURITY_FILTER, new DelegatingFilterProxy(), "/*", ALL);

    assertThat(TomcatSecurityFilterGuard.problems(context, false))
        .singleElement()
        .asString()
        .contains("early", AnswersEverything.class.getName());
  }

  /** forward 헤더 필터는 allow-remote=true 일 때만 보안 필터 앞에 둘 수 있다 (루프백 검사의 이중 안전장치). */
  @Test
  void forwardedHeaderFilterBeforeTheSecurityFilterNeedsAllowRemote() {
    StandardContext context = new StandardContext();
    add(context, "forwardedHeaderFilter", new ForwardedHeaderFilter(), "/*", ALL);
    add(context, TomcatSecurityFilterGuard.SECURITY_FILTER, new DelegatingFilterProxy(), "/*", ALL);

    assertThat(TomcatSecurityFilterGuard.problems(context, true)).isEmpty();
    assertThat(TomcatSecurityFilterGuard.problems(context, false))
        .singleElement()
        .asString()
        .contains(TomcatSecurityFilterGuard.FORWARDED_HEADER_FILTER);
  }

  @Test
  void servletNameMappingBeforeTheSecurityFilterIsNotAProblem() {
    StandardContext context = new StandardContext();
    FilterDef def = new FilterDef();
    def.setFilterName("byServlet");
    def.setFilter(new AnswersEverything());
    context.addFilterDef(def);
    FilterMap map = new FilterMap();
    map.setFilterName("byServlet");
    map.addServletName("dispatcherServlet");
    context.addFilterMap(map);
    add(context, TomcatSecurityFilterGuard.SECURITY_FILTER, new DelegatingFilterProxy(), "/*", ALL);

    assertThat(TomcatSecurityFilterGuard.problems(context, false)).isEmpty();
  }

  private static void add(
      StandardContext context, String name, Filter filter, String pattern, String... dispatchers) {
    FilterDef def = new FilterDef();
    def.setFilterName(name);
    def.setFilter(filter);
    context.addFilterDef(def);
    FilterMap map = new FilterMap();
    map.setFilterName(name);
    map.addURLPattern(pattern);
    for (String dispatcher : dispatchers) {
      map.setDispatcher(dispatcher);
    }
    context.addFilterMap(map);
  }

  /** 체인을 넘기지 않고 직접 답하는 필터 (보안 필터 앞에 있으면 안 된다). */
  static final class AnswersEverything implements Filter {
    @Override
    public void doFilter(
        jakarta.servlet.ServletRequest request,
        jakarta.servlet.ServletResponse response,
        FilterChain chain) {
      // 응답을 쓰고 체인을 넘기지 않는 필터를 흉내 낸다.
    }
  }
}
