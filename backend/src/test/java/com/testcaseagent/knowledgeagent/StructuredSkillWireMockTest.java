package com.testcaseagent.knowledgeagent;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Synchronous HTTP consumer tests for the fixed isolated Skill endpoint. [Req-ID]: REQ-SKI-002, REQ-SKI-005 */
class StructuredSkillWireMockTest {
    @RegisterExtension static WireMockExtension kee = WireMockExtension.newInstance().build();

    @Test
    void opensOnlyAnEmptySessionCoordinateWithoutCreatingAChatMessage() {
        kee.stubFor(post(urlEqualTo("/api/v1/sessions"))
                .willReturn(okJson("{\"success\":true,\"data\":{\"id\":\"structured-session-1\"}}")));

        String sessionId = ((StructuredSkillSessionPort) adapter()).openStructuredSession();

        assertThat(sessionId).isEqualTo("structured-session-1");
        kee.verify(1, postRequestedFor(urlEqualTo("/api/v1/sessions")));
        kee.verify(0, postRequestedFor(urlEqualTo("/api/v1/agent-chat/structured-session-1")));
    }

    /** [Req-ID]: REQ-SKI-002, REQ-SKI-004 */
    @Test
    void sendsOnlyTheSixStructuredFieldsAndDoesNotCreateSessionsOrUseSse() {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill")).willReturn(okJson(success())));

        StructuredSkillSuccessEnvelope<RequirementMaterialQualityReviewResult> response = adapter()
                .reviewRequirementMaterial(invocation());

        assertThat(response.success()).isTrue();
        assertThat(response.data().result().reviewFindings()).hasSize(1);
        kee.verify(postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .withRequestBody(matchingJsonPath("$.agent_id", equalTo("agent-1")))
                .withRequestBody(matchingJsonPath("$.skill_name", equalTo("requirement-material-quality-review")))
                .withRequestBody(matchingJsonPath("$.knowledge_base_ids[0]", equalTo("kb-1")))
                .withRequestBody(matchingJsonPath("$.system_scopes[0].project_id", equalTo("project-1")))
                .withRequestBody(matchingJsonPath("$.input.units[0].ordinal", equalTo("33")))
                .withRequestBody(notMatching(".*\\\"query\\\".*")));
        kee.verify(0, postRequestedFor(urlEqualTo("/api/v1/sessions")));
    }

    /** [Req-ID]: REQ-SKI-002, REQ-SKI-003, REQ-SKI-004 */
    @Test
    void callsTheExtractOperationWithGlobalOrdinalsAndRejectsAMismatchedOperationResult() {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .inScenario("feature-operation").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willSetStateTo("mismatched").willReturn(okJson(extractSuccess())));
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .inScenario("feature-operation").whenScenarioStateIs("mismatched")
                .willReturn(okJson(reconcileOperationWithExtractResult())));

        StructuredSkillSuccessEnvelope<FunctionListExtractionResult> response = adapter().extractFunctionList(extractionInvocation());

        assertThat(response.data().result().functionListItems()).hasSize(1);
        kee.verify(postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .withRequestBody(matchingJsonPath("$.skill_name", equalTo("feature-scope-reconciliation")))
                .withRequestBody(matchingJsonPath("$.input.operation", equalTo("extract_function_list")))
                .withRequestBody(matchingJsonPath("$.input.units[0].ordinal", equalTo("33")))
                .withRequestBody(notMatching(".*\\\"query\\\".*")));
        assertThatThrownBy(() -> adapter().extractFunctionList(extractionInvocation()))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class,
                        failure -> assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.STRUCTURED_OUTPUT_INVALID));
    }

    /** [Req-ID]: REQ-FTG-004 */
    @Test
    void sendsTheCompleteBoundFormalSupportsInTheTestcaseDesignRequest() throws Exception {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .willReturn(okJson(testcaseSuccess())));
        FunctionalTestcaseDesignInput input = new ObjectMapper().readValue(formalSupportInputJson(),
                FunctionalTestcaseDesignInput.class);
        var scope = new RequirementScope("kb-1", "system-1", "version-1", "requirements_spec", "project-1",
                List.of(new RequirementDocumentCoordinate("doc-1")));

        adapter().designFunctionalTestcases(new FunctionalTestcaseDesignInvocation(
                "session-1", "agent-1", scope, input));

        kee.verify(postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .withRequestBody(matchingJsonPath("$.skill_name", equalTo("functional-testcase-design")))
                .withRequestBody(matchingJsonPath("$.input.formal_supports[0].fact_key", equalTo("fact-1")))
                .withRequestBody(matchingJsonPath("$.input.formal_supports[0].inputs[0]", equalTo("账号")))
                .withRequestBody(matchingJsonPath("$.input.formal_supports[0].evidence_texts[1]",
                        equalTo("登录成功后进入首页"))));
    }

    /** [Req-ID]: REQ-SKI-005 */
    @Test
    void mapsOnlyTheStableErrorTypeWithoutRetainingErrorText() {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .willReturn(okJson("{\"success\":false,\"error\":{\"details\":{\"type\":\"forbidden\",\"repair_attempted\":true},\"message\":\"secret\"}}")));

        assertThatThrownBy(() -> adapter().reviewRequirementMaterial(invocation()))
                .isInstanceOf(StructuredSkillExecutionException.class)
                .hasMessageContaining("forbidden").hasMessageNotContaining("secret");
    }

    /** [Req-ID]: REQ-SKI-005 */
    @ParameterizedTest
    @ValueSource(strings = {"invalid_request", "request_too_large", "session_not_found", "forbidden",
            "unsupported_skill", "skill_unavailable", "model_unavailable", "model_execution_failed",
            "structured_output_invalid", "response_too_large"})
    void mapsEveryFrozenErrorTypeAndKeepsTheRepairFlag(String wireType) {
        boolean repairAttempted = wireType.equals("structured_output_invalid");
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .willReturn(okJson("{\"success\":false,\"error\":{\"details\":{\"type\":\"" + wireType
                        + "\",\"repair_attempted\":" + repairAttempted + "}}}")));

        assertThatThrownBy(() -> adapter().reviewRequirementMaterial(invocation()))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class, failure -> {
                    assertThat(failure.type().wireValue()).isEqualTo(wireType);
                    assertThat(failure.repairAttempted()).isEqualTo(repairAttempted);
                });
    }

    /** [Req-ID]: REQ-SKI-005 */
    @Test
    void rejectsNon2xxBodiesThatForgeSuccessButStillClassifiesValidErrorEnvelopes() {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .inScenario("http-status-gate")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willSetStateTo("error-envelope")
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(502)
                        .withHeader("Content-Type", "application/json").withBody(success())));
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .inScenario("http-status-gate").whenScenarioStateIs("error-envelope")
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":{\"details\":{\"type\":\"forbidden\"}}}")));

        assertThatThrownBy(() -> adapter().reviewRequirementMaterial(invocation()))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class,
                        failure -> assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.STRUCTURED_OUTPUT_INVALID));
        assertThatThrownBy(() -> adapter().reviewRequirementMaterial(invocation()))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class,
                        failure -> assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.FORBIDDEN));
    }

    /** [Req-ID]: REQ-SKI-002, REQ-SKI-005 */
    @Test
    void rejectsAnOversizedRequestBeforeAnyNetworkCall() {
        List<RequirementMaterialQualityReviewInput.MaterialUnit> units = new ArrayList<>();
        for (int ordinal = 1; ordinal <= 32; ordinal++) {
            units.add(new RequirementMaterialQualityReviewInput.MaterialUnit(
                    "unit-" + ordinal, ordinal, "x".repeat(65_536)));
        }
        RequirementMaterialQualityReviewInvocation oversized = invocation(
                new RequirementMaterialQualityReviewInput("material-1", MaterialContentTypeKey.REQUIREMENTS_SPEC,
                        "需求", units));

        assertThatThrownBy(() -> adapter().reviewRequirementMaterial(oversized))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class,
                        failure -> assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.REQUEST_TOO_LARGE));
        kee.verify(0, postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill")));
    }

    /** [Req-ID]: REQ-FTG-004 */
    @Test
    void rejectsOversizedFormalSupportsBeforeAnyNetworkCall() {
        List<String> factKeys = new ArrayList<>();
        List<FormalSupport> supports = new ArrayList<>();
        String maximumText = "x".repeat(16_384);
        for (int index = 1; index <= 100; index++) {
            String factKey = "fact-" + index;
            factKeys.add(factKey);
            supports.add(new FormalSupport(factKey, maximumText, List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(maximumText)));
        }
        var point = new FunctionalTestcaseDesignInput.TestPoint("point-1",
                FunctionalTestcaseDesignInput.TestPointType.NORMAL_BEHAVIOR, "账号登录", factKeys,
                List.of("unit-1"), FunctionalTestcaseDesignInput.Basis.FORMAL_REQUIREMENT, List.of());
        var scope = new RequirementScope("kb-1", "system-1", "version-1", "requirements_spec", "project-1",
                List.of(new RequirementDocumentCoordinate("doc-1")));
        var invocation = new FunctionalTestcaseDesignInvocation("session-1", "agent-1", scope,
                new FunctionalTestcaseDesignInput("function-1", "账号登录", point, supports));

        assertThatThrownBy(() -> adapter().designFunctionalTestcases(invocation))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class,
                        failure -> assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.REQUEST_TOO_LARGE));
        kee.verify(0, postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill")));
    }

    /** [Req-ID]: REQ-SKI-004, REQ-SKI-005 */
    @Test
    void rejectsUnknownFieldsTrailingMarkdownAndInvalidRepairFlags() {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .inScenario("strict-response")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willSetStateTo("markdown")
                .willReturn(okJson(success().replace("\"result\":{", "\"unknown\":true,\"result\":{"))));
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .inScenario("strict-response").whenScenarioStateIs("markdown").willSetStateTo("repair")
                .willReturn(okJson(success() + " explanatory markdown")));
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .inScenario("strict-response").whenScenarioStateIs("repair")
                .willReturn(okJson("{\"success\":false,\"error\":{\"details\":{\"type\":\"forbidden\",\"repair_attempted\":\"true\"}}}")));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> adapter().reviewRequirementMaterial(invocation()))
                    .isInstanceOfSatisfying(StructuredSkillExecutionException.class,
                            failure -> assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.STRUCTURED_OUTPUT_INVALID));
        }
    }

    /** [Req-ID]: REQ-SKI-004 */
    @Test
    void rejectsWrongSchemaVersionAndSkillEcho() {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .inScenario("wrong-envelope")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willSetStateTo("wrong-skill")
                .willReturn(okJson(success().replace("\"schema_version\":\"1.0\"", "\"schema_version\":\"2.0\""))));
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .inScenario("wrong-envelope").whenScenarioStateIs("wrong-skill")
                .willReturn(okJson(success().replace("requirement-material-quality-review", "functional-testcase-design"))));

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> adapter().reviewRequirementMaterial(invocation()))
                    .isInstanceOfSatisfying(StructuredSkillExecutionException.class,
                            failure -> assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.STRUCTURED_OUTPUT_INVALID));
        }
    }

    /** [Req-ID]: REQ-SKI-004 */
    @Test
    void classifiesResponsesBeyondFourMiBWithoutRetainingTheBody() {
        String oversized = success().replace("\"description\":\"描述\"",
                "\"description\":\"" + "x".repeat(4 * 1024 * 1024) + "\"");
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill")).willReturn(okJson(oversized)));

        assertThatThrownBy(() -> adapter().reviewRequirementMaterial(invocation()))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class,
                        failure -> assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.RESPONSE_TOO_LARGE));
    }

    private static WebClientKnowledgeAgentAdapter adapter() { return new WebClientKnowledgeAgentAdapter(kee.baseUrl() + "/api/v1", "test-key", Duration.ofSeconds(5), 1, 20_000); }
    private static RequirementMaterialQualityReviewInvocation invocation() {
        return invocation(new RequirementMaterialQualityReviewInput("material-1",
                MaterialContentTypeKey.REQUIREMENTS_SPEC, "需求",
                List.of(new RequirementMaterialQualityReviewInput.MaterialUnit("unit-33", 33, "内容"))));
    }
    private static RequirementMaterialQualityReviewInvocation invocation(RequirementMaterialQualityReviewInput input) {
        return new RequirementMaterialQualityReviewInvocation("session-1", "agent-1",
                new RequirementScope("kb-1", "system-1", "version-1", "requirements_spec", "project-1",
                        List.of(new RequirementDocumentCoordinate("doc-1"))), input);
    }
    private static FunctionListExtractionInvocation extractionInvocation() {
        return new FunctionListExtractionInvocation("session-1", "agent-1",
                new RequirementScope("kb-1", "system-1", "version-1", "requirements_spec", "project-1",
                        List.of(new RequirementDocumentCoordinate("doc-1"))),
                new FunctionListExtractionInput("material-1", "功能清单", List.of(
                        new FunctionListExtractionInput.Unit("unit-33", 33, "功能一"),
                        new FunctionListExtractionInput.Unit("unit-34", 34, "功能二"))));
    }
    private static String success() { return "{\"success\":true,\"data\":{\"schema_version\":\"1.0\",\"skill_name\":\"requirement-material-quality-review\",\"repair_attempted\":false,\"result\":{\"requirement_facts\":[],\"review_findings\":[{\"finding_key\":\"finding-1\",\"issue_type\":\"missing\",\"description\":\"描述\",\"evidence_keys\":[],\"test_design_impact\":\"影响\",\"current_project_recommendation\":\"建议\",\"design_center_guideline_recommendation\":\"规范建议\",\"handling_level\":\"improvement\"}]}}}"; }
    private static String extractSuccess() { return "{\"success\":true,\"data\":{\"schema_version\":\"1.0\",\"skill_name\":\"feature-scope-reconciliation\",\"repair_attempted\":false,\"result\":{\"operation\":\"extract_function_list\",\"function_list_items\":[{\"path\":\"订单/提交\",\"description\":\"提交订单\",\"evidence_keys\":[\"unit-33\"]}]}}}"; }
    private static String reconcileOperationWithExtractResult() { return "{\"success\":true,\"data\":{\"schema_version\":\"1.0\",\"skill_name\":\"feature-scope-reconciliation\",\"repair_attempted\":false,\"result\":{\"operation\":\"reconcile\",\"function_list_items\":[]}}}"; }
    private static String testcaseSuccess() { return "{\"success\":true,\"data\":{\"schema_version\":\"1.0\",\"skill_name\":\"functional-testcase-design\",\"repair_attempted\":false,\"result\":{\"function_key\":\"function-1\",\"test_point_key\":\"point-1\",\"testcases\":[{\"case_key\":\"case-1\",\"title\":\"账号登录\",\"preconditions\":[\"已注册用户\"],\"steps\":[{\"step_no\":1,\"action\":\"用户提交账号和正确密码\",\"expected\":\"进入首页\"}],\"requirement_fact_keys\":[\"fact-1\"],\"evidence_keys\":[\"unit-1\",\"unit-2\"],\"case_status\":\"formal\",\"missing_information\":[]}]}}}"; }
    private static String formalSupportInputJson() { return """
            {"function_key":"function-1","function_name":"账号登录","test_point":{"test_point_key":"point-1","type":"normal_behavior","description":"用户提交账号和正确密码后进入首页","requirement_fact_keys":["fact-1"],"evidence_keys":["unit-1","unit-2"],"basis":"formal_requirement","missing_information":[]},"formal_supports":[{"fact_key":"fact-1","function":"账号登录","roles":["已注册用户"],"trigger_conditions":["用户提交账号和正确密码"],"inputs":["账号","正确密码"],"business_rules":[],"outputs":["进入首页"],"permissions":[],"state_changes":["匿名变为已登录"],"exception_handling":[],"external_dependencies":[],"evidence_texts":["用户提交账号和正确密码","登录成功后进入首页"]}]}
            """; }
}
