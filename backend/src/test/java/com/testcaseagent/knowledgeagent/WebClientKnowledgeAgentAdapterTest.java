package com.testcaseagent.knowledgeagent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.testcaseagent.fewshot.ExampleQualityKind;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.markdown.MarkdownFeatureRow;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * [Req-ID]: REQ-KAG-001, REQ-KAG-002, REQ-KAG-003, REQ-KAG-004, REQ-KAG-005, REQ-SCP-001,
 * REQ-SCP-003, REQ-FEW-002, REQ-FEW-003, REQ-ANA-004
 */
class WebClientKnowledgeAgentAdapterTest {

    private static final String AGENT_ID = "agent-1";
    /**
     * Local HTTP fixtures exercise several sequential WebClient calls. This is a test budget, not
     * the production knowledge-agent deadline (which remains externally configured at five minutes).
     */
    private static final Duration FIXTURE_TIMEOUT = Duration.ofSeconds(5);

    @RegisterExtension
    static WireMockExtension knowledgeEngine = WireMockExtension.newInstance().build();

    @Test
    void acceptsMarkdownOnlyAfterExplicitCompleteAndSendsStrictRequirementScope() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(containing("需求与功能清单审查发现"))
                .withRequestBody(containing("测试用例"))
                .withRequestBody(containing("正式事实和审查发现只能来自这些需求材料"))
                .withRequestBody(notMatching(".*FEATURE_AUDIT.*|.*CASE_GENERATION.*|.*testcase-agent.request.*"))
                .willReturn(sse(markdownResult())));

        KnowledgeAgentInvocationResult result = adapter().invoke(invocation(FewShotPolicy.NONE));

        assertThat(result.terminalMarkdown()).contains("## 测试用例");
        knowledgeEngine.verify(postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(containing("\"knowledge_ids\":[\"requirement-doc\"]"))
                .withRequestBody(containing("\"admission_type_keys\":[\"requirements_spec\"]"))
                .withRequestBody(notMatching(".*\"project_id\".*")));
    }

    @Test
    void loadsOnlyReadyWhitelistedExamplesAndEmbedsTheirTextInTheMarkdownPrompt() {
        stubAgentAndSession();
        stubExample("good-doc", "GOOD", "# 标准用例\n清晰步骤");
        stubExample("bad-doc", "BAD", """
                ## bad_case
                缺少约束。
                ## why_bad
                缺少正式材料依据。
                ## corrected_pattern
                依据正式材料写清楚预期。
                """);
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1")).willReturn(sse(markdownResult())));

        adapter().invoke(invocation(FewShotPolicy.AUTO));

        knowledgeEngine.verify(postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(containing("清晰步骤"))
                .withRequestBody(containing("缺少正式材料依据"))
                .withRequestBody(notMatching(".*approval_status.*|.*APPROVED.*|.*retired.*|.*lifecycle.*")));
    }

    @Test
    void discoversFeaturesFromTheSmallMarkdownListInOneBackgroundSafeCall() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(containing("## 功能点清单"))
                .willReturn(sse("""
                        ## 功能点清单
                        | 序号 | 功能点 |
                        |---|---|
                        | 1 | 用户登录 |
                        | 2 | 用户退出 |
                        """)));

        List<MarkdownFeatureRow> discovered = adapter().discoverFeatures(discoveryInvocation());

        assertThat(discovered).extracting(MarkdownFeatureRow::featureName).containsExactly("用户登录", "用户退出");
    }

    @Test
    void rejectsTerminalErrorAndCleanEof() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody("event: message\ndata: {\"response_type\":\"error\",\"done\":true,\"message\":\"失败\"}\n\n")));
        assertThatThrownBy(() -> adapter().invoke(invocation(FewShotPolicy.NONE)))
                .isInstanceOf(KnowledgeAgentInvocationException.class)
                .hasMessageContaining("terminal error");

        knowledgeEngine.resetAll();
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody("event: message\ndata: {\"response_type\":\"answer\",\"done\":false,\"content\":\"内容\"}\n\n")));
        assertThatThrownBy(() -> adapter().invoke(invocation(FewShotPolicy.NONE)))
                .isInstanceOf(KnowledgeAgentInvocationException.class)
                .hasMessageContaining("without an explicit complete event");
    }

    @Test
    void rejectsManyIndividuallyValidAnswerFramesWhenTheirBoundedTotalIsExceeded() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody("event: message\ndata: {\"response_type\":\"answer\",\"done\":false,\"content\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}\n\n"
                                + "event: message\ndata: {\"response_type\":\"answer\",\"done\":false,\"content\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"}\n\n"
                                + "event: message\ndata: {\"response_type\":\"answer\",\"done\":false,\"content\":\"cccccccccccccccccccccccccccccccccccccccccccccccccc\"}\n\n"
                                + "event: message\ndata: {\"response_type\":\"complete\",\"done\":true,\"content\":\"\"}\n\n")));
        WebClientKnowledgeAgentAdapter adapter = new WebClientKnowledgeAgentAdapter(knowledgeEngine.baseUrl() + "/api/v1",
                "test-key", FIXTURE_TIMEOUT, 1, 140);
        assertThatThrownBy(() -> adapter.invoke(invocation(FewShotPolicy.NONE)))
                .isInstanceOf(KnowledgeAgentInvocationException.class).hasMessageContaining("answer exceeds maximum size");
    }

    @Test
    void retriesOnlyTheIdempotentAgentDiscoveryBeforeStartingTheSession() {
        knowledgeEngine.stubFor(get(urlEqualTo("/api/v1/agents/" + AGENT_ID))
                .inScenario("agent discovery retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("available")
                .willReturn(aResponse().withStatus(503)));
        knowledgeEngine.stubFor(get(urlEqualTo("/api/v1/agents/" + AGENT_ID))
                .inScenario("agent discovery retry")
                .whenScenarioStateIs("available")
                .willReturn(okJson("{\"success\":true,\"data\":{\"id\":\"" + AGENT_ID + "\"}}")));
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/sessions"))
                .willReturn(okJson("{\"success\":true,\"data\":{\"id\":\"session-1\"}}")));
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1")).willReturn(sse(markdownResult())));

        WebClientKnowledgeAgentAdapter retryingAdapter = new WebClientKnowledgeAgentAdapter(knowledgeEngine.baseUrl() + "/api/v1",
                "test-key", FIXTURE_TIMEOUT, 2, 20_000);
        assertThat(retryingAdapter.invoke(invocation(FewShotPolicy.NONE)).terminalMarkdown()).contains("## 测试用例");
        knowledgeEngine.verify(2, getRequestedFor(urlEqualTo("/api/v1/agents/" + AGENT_ID)));
        knowledgeEngine.verify(1, postRequestedFor(urlEqualTo("/api/v1/sessions")));
    }

    private WebClientKnowledgeAgentAdapter adapter() {
        return new WebClientKnowledgeAgentAdapter(knowledgeEngine.baseUrl() + "/api/v1", "test-key", FIXTURE_TIMEOUT, 1, 20_000);
    }

    private static KnowledgeAgentInvocation invocation(FewShotPolicy policy) {
        ExampleScope examples = policy == FewShotPolicy.NONE
                ? new ExampleScope("example-kb", List.of("good-doc"), Map.of("good-doc", ExampleQualityKind.GOOD_CASE))
                : new ExampleScope("example-kb", List.of("good-doc", "bad-doc"), Map.of(
                        "good-doc", ExampleQualityKind.GOOD_CASE, "bad-doc", ExampleQualityKind.BAD_CASE));
        return new KnowledgeAgentInvocation(AGENT_ID,
                RequirementScope.freeze("requirement-kb", "system-1", "version-1", "admission_material", null,
                        List.of(new RequirementDocumentCoordinate("requirement-doc"))),
                examples, List.of("requirements_spec"), "补充说明：覆盖正常与异常场景。", policy);
    }

    private static FeatureDiscoveryInvocation discoveryInvocation() {
        return new FeatureDiscoveryInvocation(AGENT_ID,
                RequirementScope.freeze("requirement-kb", "system-1", "version-1", "admission_material", null,
                        List.of(new RequirementDocumentCoordinate("requirement-doc"))), List.of("function_list"));
    }

    private static String markdownResult() {
        return """
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                |---|---|---|---|
                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                |---|---|---|---|---|---|
                | 登录成功 | 用户登录 | 已登录页 | 输入正确凭据 | 进入首页 | 功能清单登录 |
                """;
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder sse(String answer) {
        return aResponse().withHeader("Content-Type", "text/event-stream")
                .withBody("event: message\ndata: {\"response_type\":\"answer\",\"done\":false,\"content\":"
                        + quote(answer) + "}\n\n"
                        + "event: message\ndata: {\"response_type\":\"complete\",\"done\":true,\"content\":\"\"}\n\n");
    }

    private static void stubAgentAndSession() {
        knowledgeEngine.stubFor(get(urlEqualTo("/api/v1/agents/" + AGENT_ID))
                .withHeader("X-API-Key", equalTo("test-key"))
                .willReturn(okJson("{\"success\":true,\"data\":{\"id\":\"" + AGENT_ID + "\"}}")));
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/sessions"))
                .withHeader("X-API-Key", equalTo("test-key"))
                .willReturn(okJson("{\"success\":true,\"data\":{\"id\":\"session-1\"}}")));
    }

    private static void stubExample(String documentId, String kind, String body) {
        knowledgeEngine.stubFor(get(urlEqualTo("/api/v1/knowledge/" + documentId))
                .willReturn(okJson("{\"success\":true,\"data\":{\"knowledge_base_id\":\"example-kb\",\"parse_status\":\"completed\",\"enable_status\":\"enabled\"}}")));
        knowledgeEngine.stubFor(get(urlEqualTo("/api/v1/knowledge/" + documentId + "/preview"))
                .willReturn(aResponse().withHeader("Content-Type", "text/markdown")
                        .withBody("---\nquality_kind: " + kind + "\nversion: 1.0.0\n---\n" + body)));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
