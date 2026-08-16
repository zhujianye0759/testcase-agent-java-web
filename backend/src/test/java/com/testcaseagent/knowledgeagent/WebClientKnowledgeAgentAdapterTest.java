package com.testcaseagent.knowledgeagent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.matching.UrlPattern;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.testcaseagent.fewshot.ExampleQualityKind;
import com.testcaseagent.fewshot.ExampleScope;
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
 * REQ-SCP-003, REQ-FEW-002, REQ-FEW-003, REQ-ANA-004, REQ-KSI-001, REQ-KSI-002, REQ-KSI-003, REQ-KSI-004
 */
class WebClientKnowledgeAgentAdapterTest {

    private static final String AGENT_ID = "agent-1";
    private static final String GENERATION_SKILL = "functional-testcase-design";
    private static final String RECONCILIATION_SKILL = "feature-scope-reconciliation";
    private static final String ORDINARY_CHAT_PATH = "/api/v1/agent-chat/session-1";
    private static final String ISOLATED_SKILL_CHAT_PATH = ORDINARY_CHAT_PATH + "/isolated-skill";
    /**
     * Local HTTP fixtures exercise several sequential WebClient calls. This is a test budget, not
     * the production knowledge-agent deadline (which remains externally configured at five minutes).
     */
    private static final Duration FIXTURE_TIMEOUT = Duration.ofSeconds(5);

    @RegisterExtension
    static WireMockExtension knowledgeEngine = WireMockExtension.newInstance().build();

    @Test
    void invokesOnlyTheDedicatedIsolatedSkillEndpointAndNeverFallsBackToOrdinaryChat() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .willReturn(sseWithReadSkill(markdownResult(), GENERATION_SKILL)));

        assertThat(adapter().invoke(invocation(FewShotPolicy.NONE)).terminalMarkdown()).contains("## 测试用例");

        knowledgeEngine.verify(1, postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .withRequestBody(matchingJsonPath("$.skill_names", equalToJson("[\"functional-testcase-design\"]"))));
        knowledgeEngine.verify(0, postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(ORDINARY_CHAT_PATH)));
    }

    @Test
    void failsClosedWhenTheDedicatedIsolatedSkillEndpointIsUnavailableWithoutTryingOrdinaryChat() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(ORDINARY_CHAT_PATH))
                .willReturn(sseWithDeclaredReadSkill(markdownResult(), GENERATION_SKILL)));

        assertThatThrownBy(() -> adapter().invoke(invocation(FewShotPolicy.NONE)))
                .isInstanceOf(KnowledgeAgentInvocationException.class)
                .hasMessageContaining("chat request failed");

        knowledgeEngine.verify(1, postRequestedFor(urlEqualTo(ISOLATED_SKILL_CHAT_PATH)));
        knowledgeEngine.verify(0, postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(ORDINARY_CHAT_PATH)));
    }

    @Test
    void rejectsAForbiddenRetrievalToolDuringAnIsolatedBusinessSkillCall() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo(ORDINARY_CHAT_PATH))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody(readSkillDeclaration("read-skill-1") + readSkillEvents(GENERATION_SKILL, true)
                                + "event: message\ndata: {\"response_type\":\"tool_call\",\"done\":false,\"data\":{\"tool_name\":\"grep_chunks\",\"tool_call_id\":\"grep-1\"}}\n\n"
                                + answerAndComplete(markdownResult()))));

        assertThatThrownBy(() -> adapter().invoke(invocation(FewShotPolicy.NONE)))
                .isInstanceOf(KnowledgeAgentInvocationException.class)
                .hasMessageContaining("forbidden tool");
    }

    @Test
    void permitsExecuteSkillScriptAlongsideTheRequiredReadSkillEvidence() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo(ORDINARY_CHAT_PATH))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody(readSkillDeclaration("read-skill-1") + readSkillEvents(GENERATION_SKILL, true)
                                + "event: message\ndata: {\"response_type\":\"tool_call\",\"done\":false,\"data\":{\"tool_name\":\"execute_skill_script\",\"tool_call_id\":\"script-1\"}}\n\n"
                                + "event: message\ndata: {\"response_type\":\"tool_result\",\"done\":false,\"data\":{\"tool_name\":\"execute_skill_script\",\"tool_call_id\":\"script-1\",\"success\":true}}\n\n"
                                + answerAndComplete(markdownResult()))));

        assertThat(adapter().invoke(invocation(FewShotPolicy.NONE)).terminalMarkdown()).contains("## 测试用例");
    }

    @Test
    void acceptsMarkdownOnlyAfterExplicitCompleteAndSendsStrictRequirementScope() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(containing("需求与功能清单审查发现"))
                .withRequestBody(containing("测试用例"))
                .withRequestBody(containing("正式事实和审查发现只能来自这些需求材料"))
                .withRequestBody(notMatching(".*FEATURE_AUDIT.*|.*CASE_GENERATION.*|.*testcase-agent.request.*"))
                .willReturn(sseWithReadSkill(markdownResult(), GENERATION_SKILL)));

        KnowledgeAgentInvocationResult result = adapter().invoke(invocation(FewShotPolicy.NONE));

        assertThat(result.terminalMarkdown()).contains("## 测试用例");
        knowledgeEngine.verify(postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(containing("\"knowledge_ids\":[\"requirement-doc\"]"))
                .withRequestBody(containing("\"admission_type_keys\":[\"requirements_spec\"]"))
                .withRequestBody(matchingJsonPath("$.skill_names", equalToJson("[\"functional-testcase-design\"]")))
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
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(sseWithReadSkill(markdownResult(), GENERATION_SKILL)));

        adapter().invoke(invocation(FewShotPolicy.AUTO));

        knowledgeEngine.verify(postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(containing("清晰步骤"))
                .withRequestBody(containing("缺少正式材料依据"))
                .withRequestBody(notMatching(".*approval_status.*|.*APPROVED.*|.*retired.*|.*lifecycle.*")));
    }

    @Test
    void reconcilesFeatureScopeWithVerbatimPromptAndOnlyRequirementEvidence() {
        stubAgentAndSession();
        String prompt = "逐字保留：核对功能清单与需求候选项。";
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(containing(prompt))
                .willReturn(sseWithReadSkill("## 双向核对结论\n", RECONCILIATION_SKILL)));

        KnowledgeAgentPort port = adapter();
        KnowledgeAgentInvocationResult result = port.reconcileFeatures(reconciliationInvocation(prompt));

        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(result.terminalMarkdown()).isEqualTo("## 双向核对结论\n");
        knowledgeEngine.verify(postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(containing("\"knowledge_base_ids\":[\"requirement-kb\"]"))
                .withRequestBody(containing("\"knowledge_ids\":[\"requirement-doc\"]"))
                .withRequestBody(containing("\"admission_type_keys\":[\"function_list\"]"))
                .withRequestBody(matchingJsonPath("$.skill_names", equalToJson("[\"feature-scope-reconciliation\"]")))
                .withRequestBody(notMatching(".*example-kb.*|.*good-doc.*")));
    }

    @Test
    void reusesOnePreparedReconciliationSessionAfterVerifiedSkillLoading() {
        stubAgentAndSession();
        String prompt = "逐字保留：核对功能清单与需求候选项。";
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .inScenario("prepared reconciliation session")
                .whenScenarioStateIs(Scenario.STARTED)
                .withRequestBody(matchingJsonPath("$.query", equalTo("你好")))
                .willSetStateTo("skill-loaded")
                .willReturn(sseWithDeclaredReadSkill("SKILL_READY", RECONCILIATION_SKILL)));
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .inScenario("prepared reconciliation session")
                .whenScenarioStateIs("skill-loaded")
                .withRequestBody(containing(prompt))
                .willReturn(sseWithDeclaredReadSkill("## 双向核对结论\n", RECONCILIATION_SKILL)));

        KnowledgeAgentPort port = adapter();
        FeatureReconciliationInvocation invocation = reconciliationInvocation(prompt);
        port.prepareReconciliationSession(invocation);
        KnowledgeAgentInvocationResult result = port.reconcileFeatures(invocation);
        port.closePreparedSession();

        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(result.terminalMarkdown()).isEqualTo("## 双向核对结论\n");
        knowledgeEngine.verify(2, postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1")));
        knowledgeEngine.verify(1, getRequestedFor(urlEqualTo("/api/v1/agents/" + AGENT_ID)));
    }

    @Test
    void refusesBusinessReconciliationWhenPreparedSessionCannotProveExactSkillLoading() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("你好")))
                .willReturn(sse("SKILL_READY")));

        KnowledgeAgentPort port = adapter();
        assertThatThrownBy(() -> port.prepareReconciliationSession(reconciliationInvocation("核对提示")))
                .isInstanceOf(KnowledgeAgentInvocationException.class)
                .hasMessageContaining("required read_skill evidence");
        knowledgeEngine.verify(0, postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(containing("核对提示")));
    }

    @Test
    void reusesOnePreparedGenerationSessionAfterVerifiedSkillLoading() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .inScenario("prepared generation session")
                .whenScenarioStateIs(Scenario.STARTED)
                .withRequestBody(matchingJsonPath("$.query", equalTo("你好")))
                .willSetStateTo("skill-loaded")
                .willReturn(sseWithDeclaredReadSkill("SKILL_READY", GENERATION_SKILL)));
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .inScenario("prepared generation session")
                .whenScenarioStateIs("skill-loaded")
                .withRequestBody(containing("严格按以下两张 Markdown 表返回"))
                .willReturn(sseWithDeclaredReadSkill(markdownResult(), GENERATION_SKILL)));

        KnowledgeAgentPort port = adapter();
        KnowledgeAgentInvocation invocation = invocation(FewShotPolicy.NONE);
        port.prepareGenerationSession(invocation);
        KnowledgeAgentInvocationResult result = port.invoke(invocation);
        port.closePreparedSession();

        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(result.terminalMarkdown()).contains("## 测试用例");
        knowledgeEngine.verify(2, postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1")));
        knowledgeEngine.verify(1, getRequestedFor(urlEqualTo("/api/v1/agents/" + AGENT_ID)));
    }

    @Test
    void acceptsKeeReadSkillDeclarationThenArgumentsUpdateOnTheSameCallId() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("你好")))
                .willReturn(sseWithDeclaredReadSkill("SKILL_READY", RECONCILIATION_SKILL)));

        KnowledgeAgentPort port = adapter();
        port.prepareReconciliationSession(reconciliationInvocation("核对提示"));
        port.closePreparedSession();

        knowledgeEngine.verify(postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("你好")))
                .withRequestBody(matchingJsonPath("$.skill_names", equalToJson("[\"feature-scope-reconciliation\"]"))));
    }

    @Test
    void rejectsReadSkillDeclarationWithoutArgumentsUpdateBeforeComplete() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("你好")))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody(readSkillDeclaration("read-skill-1") + completeEvent())));

        assertThatThrownBy(() -> adapter().prepareReconciliationSession(reconciliationInvocation("核对提示")))
                .isInstanceOf(KnowledgeAgentSkillPreparationException.class)
                .hasMessageContaining("required read_skill evidence");
    }

    @Test
    void rejectsReadSkillDeclarationWhenAnotherCallIdSuppliesTheExactSkill() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("你好")))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody(readSkillDeclaration("declared-call")
                                + readSkillEvents(RECONCILIATION_SKILL, true, "other-call") + completeEvent())));

        assertThatThrownBy(() -> adapter().prepareReconciliationSession(reconciliationInvocation("核对提示")))
                .isInstanceOf(KnowledgeAgentSkillPreparationException.class)
                .hasMessageContaining("prior declaration");
    }

    @Test
    void rejectsReadSkillDeclarationResultWithoutExactSkillArgumentsOnTheSameCallId() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("你好")))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody(readSkillDeclaration("read-skill-1")
                                + "event: message\ndata: {\"response_type\":\"tool_result\",\"done\":false,\"data\":{\"tool_call_id\":\"read-skill-1\",\"success\":true}}\n\n"
                                + completeEvent())));

        assertThatThrownBy(() -> adapter().prepareReconciliationSession(reconciliationInvocation("核对提示")))
                .isInstanceOf(KnowledgeAgentSkillPreparationException.class)
                .hasMessageContaining("required read_skill evidence");
    }

    @Test
    void doesNotRetryACompletedPreparationThatLacksReadSkillEvidence() {
        stubAgent();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/sessions"))
                .inScenario("preparation retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("second session")
                .willReturn(okJson("{\"success\":true,\"data\":{\"id\":\"session-1\"}}")));
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/sessions"))
                .inScenario("preparation retry")
                .whenScenarioStateIs("second session")
                .willReturn(okJson("{\"success\":true,\"data\":{\"id\":\"session-2\"}}")));
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("你好")))
                .willReturn(sse("SKILL_READY")));
        KnowledgeAgentPort port = new WebClientKnowledgeAgentAdapter(knowledgeEngine.baseUrl() + "/api/v1",
                "test-key", FIXTURE_TIMEOUT, 2, 20_000);
        FeatureReconciliationInvocation invocation = reconciliationInvocation("核对提示");
        assertThatThrownBy(() -> port.prepareReconciliationSession(invocation))
                .isInstanceOf(KnowledgeAgentSkillPreparationException.class)
                .hasMessageContaining("required read_skill evidence");

        knowledgeEngine.verify(1, postRequestedFor(urlEqualTo("/api/v1/sessions")));
        knowledgeEngine.verify(1, postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("你好"))));
        knowledgeEngine.verify(0, postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(containing("核对提示")));
    }

    @Test
    void retriesThreeFreshSessionsForPreparationTransportTimeoutsBeforeAnyBusinessPrompt() {
        stubAgent();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/sessions"))
                .willReturn(okJson("{\"success\":true,\"data\":{\"id\":\"session-timeout\"}}")
                        .withFixedDelay(200)));

        KnowledgeAgentPort port = new WebClientKnowledgeAgentAdapter(knowledgeEngine.baseUrl() + "/api/v1",
                "test-key", Duration.ofMillis(50), 1, 20_000);
        assertThatThrownBy(() -> port.prepareReconciliationSession(reconciliationInvocation("核对提示")))
                .isInstanceOf(KnowledgeAgentSkillPreparationException.class)
                .satisfies(exception -> assertThat(((KnowledgeAgentSkillPreparationException) exception)
                        .transportRetriesExhausted()).isTrue());

        knowledgeEngine.verify(3, postRequestedFor(urlEqualTo("/api/v1/sessions")));
        knowledgeEngine.verify(0, postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-timeout")));
    }

    @Test
    void retriesThreeFreshSessionsForPreparationConnectionFailuresBeforeAnyBusinessPrompt() {
        stubAgent();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/sessions"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> adapter().prepareReconciliationSession(reconciliationInvocation("核对提示")))
                .isInstanceOf(KnowledgeAgentSkillPreparationException.class)
                .satisfies(exception -> assertThat(((KnowledgeAgentSkillPreparationException) exception)
                        .transportRetriesExhausted()).isTrue());

        knowledgeEngine.verify(3, postRequestedFor(urlEqualTo("/api/v1/sessions")));
    }

    @Test
    void rejectsAnyRetrievalToolDuringPreparationWithoutSendingTheBusinessPrompt() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("你好")))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody("event: message\ndata: {\"response_type\":\"tool_call\",\"done\":false,\"data\":{\"tool_name\":\"knowledge_search\",\"tool_call_id\":\"search-1\"}}\n\n"
                                + "event: message\ndata: {\"response_type\":\"complete\",\"done\":true,\"content\":\"\"}\n\n")));

        KnowledgeAgentPort port = adapter();
        assertThatThrownBy(() -> port.prepareReconciliationSession(reconciliationInvocation("核对提示")))
                .isInstanceOf(KnowledgeAgentSkillPreparationException.class)
                .hasMessageContaining("may only call the exact read_skill");

        knowledgeEngine.verify(1, postRequestedFor(urlEqualTo("/api/v1/sessions")));
        knowledgeEngine.verify(0, postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(containing("核对提示")));
    }

    @Test
    void rejectsWrongReadSkillBeforeALaterCorrectSkillDuringPreparation() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("你好")))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody(readSkillEvents(RECONCILIATION_SKILL, true)
                                .replace(RECONCILIATION_SKILL, GENERATION_SKILL)
                                + readSkillEvents(RECONCILIATION_SKILL, true)
                                + completeEvent())));

        KnowledgeAgentPort port = adapter();
        assertThatThrownBy(() -> port.prepareReconciliationSession(reconciliationInvocation("核对提示")))
                .isInstanceOf(KnowledgeAgentSkillPreparationException.class)
                .hasMessageContaining("exact read_skill");

        knowledgeEngine.verify(1, postRequestedFor(urlEqualTo("/api/v1/sessions")));
        knowledgeEngine.verify(0, postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(containing("核对提示")));
    }

    @Test
    void rejectsAnyNonTerminalErrorAfterSuccessfulSkillReadDuringPreparation() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("你好")))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody(readSkillDeclaration("read-skill-1") + readSkillEvents(RECONCILIATION_SKILL, true)
                                + "event: message\ndata: {\"response_type\":\"error\",\"done\":false,\"message\":\"工具失败\"}\n\n"
                                + completeEvent())));

        KnowledgeAgentPort port = adapter();
        assertThatThrownBy(() -> port.prepareReconciliationSession(reconciliationInvocation("核对提示")))
                .isInstanceOf(KnowledgeAgentSkillPreparationException.class)
                .hasMessageContaining("preparation error");

        knowledgeEngine.verify(0, postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1"))
                .withRequestBody(containing("核对提示")));
    }

    @Test
    void wrapsConfiguredAgentDiscoveryTimeoutAsBoundedPreparationFailure() {
        knowledgeEngine.stubFor(get(urlEqualTo("/api/v1/agents/" + AGENT_ID))
                .willReturn(okJson("{\"success\":true,\"data\":{\"id\":\"" + AGENT_ID + "\"}}")
                        .withFixedDelay(200)));
        KnowledgeAgentPort port = new WebClientKnowledgeAgentAdapter(knowledgeEngine.baseUrl() + "/api/v1",
                "test-key", Duration.ofMillis(50), 2, 20_000);

        assertThatThrownBy(() -> port.prepareGenerationSession(invocation(FewShotPolicy.NONE)))
                .isInstanceOf(KnowledgeAgentSkillPreparationException.class)
                .satisfies(exception -> assertThat(((KnowledgeAgentSkillPreparationException) exception)
                        .transportRetriesExhausted()).isTrue());

        knowledgeEngine.verify(2, getRequestedFor(urlEqualTo("/api/v1/agents/" + AGENT_ID)));
        knowledgeEngine.verify(0, postRequestedFor(urlEqualTo("/api/v1/sessions")));
    }

    @Test
    void wrapsMissingConfiguredAgentAsNonRetryableGenerationPreparationFailure() {
        knowledgeEngine.stubFor(get(urlEqualTo("/api/v1/agents/" + AGENT_ID))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":{\"message\":\"Agent not found\"}}")));

        assertThatThrownBy(() -> adapter().prepareGenerationSession(invocation(FewShotPolicy.NONE)))
                .isInstanceOf(KnowledgeAgentSkillPreparationException.class)
                .satisfies(exception -> assertThat(((KnowledgeAgentSkillPreparationException) exception)
                        .transportRetriesExhausted()).isFalse());

        knowledgeEngine.verify(1, getRequestedFor(urlEqualTo("/api/v1/agents/" + AGENT_ID)));
        knowledgeEngine.verify(0, postRequestedFor(urlEqualTo("/api/v1/sessions")));
    }

    @Test
    void rejectsReconciliationMarkdownWithoutPairedExactReadSkillEvidence() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(sse("## 双向核对结论\n")));

        assertThatThrownBy(() -> adapter().reconcileFeatures(reconciliationInvocation("核对提示")))
                .isInstanceOf(KnowledgeAgentInvocationException.class)
                .hasMessageContaining("required read_skill evidence");
    }

    @Test
    void rejectsMarkdownCompletedWithoutPairedExactReadSkillEvidence() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1")).willReturn(sse(markdownResult())));

        assertThatThrownBy(() -> adapter().invoke(invocation(FewShotPolicy.NONE)))
                .isInstanceOf(KnowledgeAgentInvocationException.class);
    }

    @Test
    void rejectsWrongReadSkillName() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(sseWithReadSkill(markdownResult(), RECONCILIATION_SKILL)));

        assertThatThrownBy(() -> adapter().invoke(invocation(FewShotPolicy.NONE)))
                .isInstanceOf(KnowledgeAgentInvocationException.class);
    }

    @Test
    void rejectsFailedReadSkillResult() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(sseWithReadSkillResult(markdownResult(), GENERATION_SKILL, false)));

        assertThatThrownBy(() -> adapter().invoke(invocation(FewShotPolicy.NONE)))
                .isInstanceOf(KnowledgeAgentInvocationException.class);
    }

    @Test
    void acceptsReadSkillResultWithoutSkillNameWhenItMatchesTheVerifiedToolCallId() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(sseWithCallIdReadSkillResult(markdownResult(), "read-skill-1", "read-skill-1")));

        assertThat(adapter().invoke(invocation(FewShotPolicy.NONE)).terminalMarkdown()).contains("## 测试用例");
    }

    @Test
    void rejectsReadSkillResultWithWrongToolCallId() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(sseWithCallIdReadSkillResult(markdownResult(), "read-skill-1", "other-call")));

        assertThatThrownBy(() -> adapter().invoke(invocation(FewShotPolicy.NONE)))
                .isInstanceOf(KnowledgeAgentInvocationException.class);
    }

    @Test
    void rejectsReadSkillResultWithoutToolCallIdWhenTheVerifiedCallHasOne() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(sseWithCallIdReadSkillResult(markdownResult(), "read-skill-1", null)));

        assertThatThrownBy(() -> adapter().invoke(invocation(FewShotPolicy.NONE)))
                .isInstanceOf(KnowledgeAgentInvocationException.class);
    }

    @Test
    void rejectsTerminalErrorAndCleanEofEvenAfterReadSkillEvidence() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody(readSkillDeclaration("read-skill-1") + readSkillEvents(GENERATION_SKILL, true)
                                + "event: message\ndata: {\"response_type\":\"error\",\"done\":true,\"message\":\"失败\"}\n\n")));
        assertThatThrownBy(() -> adapter().invoke(invocation(FewShotPolicy.NONE)))
                .isInstanceOf(KnowledgeAgentInvocationException.class)
                .hasMessageContaining("terminal error");

        knowledgeEngine.resetAll();
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody(readSkillDeclaration("read-skill-1") + readSkillEvents(GENERATION_SKILL, true)
                                + "event: message\ndata: {\"response_type\":\"answer\",\"done\":false,\"content\":\"内容\"}\n\n")));
        assertThatThrownBy(() -> adapter().invoke(invocation(FewShotPolicy.NONE)))
                .isInstanceOf(KnowledgeAgentInvocationException.class)
                .hasMessageContaining("without an explicit complete event");
    }

    @Test
    void nonterminalErrorDoesNotTerminateAReadSkillVerifiedStream() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody(readSkillDeclaration("read-skill-1") + readSkillEvents(GENERATION_SKILL, true)
                                + "event: message\ndata: {\"response_type\":\"error\",\"done\":false,\"message\":\"继续\"}\n\n"
                                + answerAndComplete(markdownResult()))));

        assertThat(adapter().invoke(invocation(FewShotPolicy.NONE)).terminalMarkdown()).contains("## 测试用例");
    }

    @Test
    void rejectsManyIndividuallyValidAnswerFramesWhenTheirBoundedTotalIsExceeded() {
        stubAgentAndSession();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream")
                        .withBody(readSkillDeclaration("read-skill-1") + readSkillEvents(GENERATION_SKILL, true)
                                + "event: message\ndata: {\"response_type\":\"answer\",\"done\":false,\"content\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}\n\n"
                                + "event: message\ndata: {\"response_type\":\"answer\",\"done\":false,\"content\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"}\n\n"
                                + "event: message\ndata: {\"response_type\":\"answer\",\"done\":false,\"content\":\"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\"}\n\n"
                                + "event: message\ndata: {\"response_type\":\"complete\",\"done\":true,\"content\":\"\"}\n\n")));
        WebClientKnowledgeAgentAdapter adapter = new WebClientKnowledgeAgentAdapter(knowledgeEngine.baseUrl() + "/api/v1",
                "test-key", FIXTURE_TIMEOUT, 1, 220);
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
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1"))
                .willReturn(sseWithReadSkill(markdownResult(), GENERATION_SKILL)));

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

    private static FeatureReconciliationInvocation reconciliationInvocation(String prompt) {
        return new FeatureReconciliationInvocation(AGENT_ID,
                RequirementScope.freeze("requirement-kb", "system-1", "version-1", "admission_material", null,
                        List.of(new RequirementDocumentCoordinate("requirement-doc"))), List.of("function_list"), prompt);
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
                .withBody(answerAndComplete(answer));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder sseWithReadSkill(String answer, String skillName) {
        return sseWithReadSkillResult(answer, skillName, true);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder sseWithDeclaredReadSkill(String answer, String skillName) {
        return aResponse().withHeader("Content-Type", "text/event-stream")
                .withBody(readSkillDeclaration("read-skill-1") + readSkillEvents(skillName, true) + answerAndComplete(answer));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder sseWithReadSkillResult(
            String answer, String skillName, boolean success) {
        return aResponse().withHeader("Content-Type", "text/event-stream")
                .withBody(readSkillDeclaration("read-skill-1") + readSkillEvents(skillName, success) + answerAndComplete(answer));
    }

    private static String readSkillEvents(String skillName, boolean success) {
        return readSkillEvents(skillName, success, "read-skill-1");
    }

    private static String readSkillEvents(String skillName, boolean success, String toolCallId) {
        // KEE handler contract: tool-call arguments hold the Skill name and the tool result is
        // correlated only through tool_call_id. Keep this fixture wire-compatible with KEE.
        return "event: message\ndata: {\"response_type\":\"tool_call\",\"done\":false,\"data\":{\"tool_name\":\"read_skill\",\"tool_call_id\":" + quote(toolCallId) + ",\"arguments\":{\"skill_name\":"
                + quote(skillName) + "}}}\n\n"
                + "event: message\ndata: {\"response_type\":\"tool_result\",\"done\":false,\"data\":{\"tool_call_id\":" + quote(toolCallId) + ",\"success\":"
                + success + "}}\n\n";
    }

    private static String readSkillDeclaration(String toolCallId) {
        return "event: message\ndata: {\"response_type\":\"tool_call\",\"done\":false,\"data\":{\"tool_name\":\"read_skill\",\"tool_call_id\":"
                + quote(toolCallId) + ",\"arguments\":null}}\n\n";
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder sseWithCallIdReadSkillResult(
            String answer, String toolCallId, String resultToolCallId) {
        String resultCallId = resultToolCallId == null ? "" : "\"tool_call_id\":" + quote(resultToolCallId) + ",";
        return aResponse().withHeader("Content-Type", "text/event-stream")
                .withBody(readSkillDeclaration(toolCallId)
                        + "event: message\ndata: {\"response_type\":\"tool_call\",\"done\":false,\"data\":{\"tool_name\":\"read_skill\",\"tool_call_id\":"
                        + quote(toolCallId) + ",\"arguments\":{\"skill_name\":\"functional-testcase-design\"}}}\n\n"
                        + "event: message\ndata: {\"response_type\":\"tool_result\",\"done\":false,\"data\":{" + resultCallId
                        + "\"tool_name\":\"read_skill\",\"success\":true}}\n\n" + answerAndComplete(answer));
    }

    private static String answerAndComplete(String answer) {
        return "event: message\ndata: {\"response_type\":\"answer\",\"done\":false,\"content\":"
                + quote(answer) + "}\n\n"
                + "event: message\ndata: {\"response_type\":\"complete\",\"done\":true,\"content\":\"\"}\n\n";
    }

    private static String completeEvent() {
        return "event: message\ndata: {\"response_type\":\"complete\",\"done\":true,\"content\":\"\"}\n\n";
    }

    private static void stubAgentAndSession() {
        stubAgent();
        knowledgeEngine.stubFor(post(urlEqualTo("/api/v1/sessions"))
                .withHeader("X-API-Key", equalTo("test-key"))
                .willReturn(okJson("{\"success\":true,\"data\":{\"id\":\"session-1\"}}")));
    }

    private static void stubAgent() {
        knowledgeEngine.stubFor(get(urlEqualTo("/api/v1/agents/" + AGENT_ID))
                .withHeader("X-API-Key", equalTo("test-key"))
                .willReturn(okJson("{\"success\":true,\"data\":{\"id\":\"" + AGENT_ID + "\"}}")));
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

    /** Keeps legacy fixture declarations focused on their payload while targeting KEE's isolated Skill route. */
    private static UrlPattern urlEqualTo(String path) {
        return com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(
                ORDINARY_CHAT_PATH.equals(path) ? ISOLATED_SKILL_CHAT_PATH : path);
    }
}
