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

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** V2 wire-level RED/GREEN contract tests. [Req-ID]: REQ-TGV2-002, REQ-TGV2-003, REQ-TGV2-005 */
class StructuredSkillV2ContractTest {
    @RegisterExtension static WireMockExtension kee = WireMockExtension.newInstance().build();

    @Test
    void factExtractionUsesContractTwoSingleDocumentScopeAndPreservesLargeParsedUnitContent() {
        String content = "需".repeat(20_000);
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-v2/isolated-skill"))
                .willReturn(okJson(factSuccess())));

        var response = adapter().extractRequirementFactsV2(new RequirementFactExtractionV2Invocation(
                "session-v2", "agent-v2", singleDocumentScope(), new RequirementFactExtractionV2Input(
                        "function-1", "订单提交", "订单/提交", "提交订单",
                        "material-1", MaterialContentTypeKey.WORK_ORDER_PLAN, "window-1",
                        List.of(new RequirementFactExtractionV2Input.MaterialUnit("unit-1", 1, content)),
                        List.of())));

        assertThat(response.data().schemaVersion()).isEqualTo("2.0");
        assertThat(response.data().result().requirementFacts()).hasSize(1);
        kee.verify(postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-v2/isolated-skill"))
                .withRequestBody(matchingJsonPath("$.contract_version", equalTo("2.0")))
                .withRequestBody(matchingJsonPath("$.skill_name", equalTo("requirement-fact-extraction")))
                .withRequestBody(matchingJsonPath("$.knowledge_ids.length()", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.knowledge_ids[0]", equalTo("document-1")))
                .withRequestBody(matchingJsonPath("$.input.units[0].content", equalTo(content)))
                .withRequestBody(notMatching(".*max_completion_tokens.*"))
                .withRequestBody(notMatching(".*max_tokens.*")));
    }

    @Test
    void factExtractionRejectsTaskWideScopeBeforeNetworkCall() {
        RequirementScope twoDocuments = new RequirementScope("kb-1", "system-1", "version-1",
                "requirements", "project-1", List.of(
                        new RequirementDocumentCoordinate("document-1", "work_order_plan"),
                        new RequirementDocumentCoordinate("document-2", "requirements_spec")));

        assertThatThrownBy(() -> new RequirementFactExtractionV2Invocation(
                "session-v2", "agent-v2", twoDocuments, factInput("one")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one document");
        kee.verify(0, postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-v2/isolated-skill")));
    }

    @Test
    void testcaseDesignUsesVersionTwoOutcomeContractWithoutLegacyFields() {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-v2/isolated-skill"))
                .willReturn(okJson(testcaseSuccess())));

        var response = adapter().designFunctionalTestcasesV2(new FunctionalTestcaseDesignV2Invocation(
                "session-v2", "agent-v2", singleDocumentScope(), testcaseInput()));

        assertThat(response.data().result().generationOutcome())
                .isEqualTo(FunctionalTestcaseDesignV2Result.GenerationOutcome.PENDING_ONLY);
        assertThat(response.data().result().testcases()).hasSize(1);
        kee.verify(postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-v2/isolated-skill"))
                .withRequestBody(matchingJsonPath("$.contract_version", equalTo("2.0")))
                .withRequestBody(matchingJsonPath("$.skill_name", equalTo("functional-testcase-design")))
                .withRequestBody(matchingJsonPath("$.input.requirement_facts[0].fact_key", equalTo("fact-1")))
                .withRequestBody(notMatching(".*formal_supports.*"))
                .withRequestBody(notMatching(".*authoring_information.*")));
    }

    @Test
    void adapterClassifiesV2FailuresByDetailsTypeAndIgnoresVariableMessage() {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-v2/isolated-skill"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"success":false,"error":{"message":"可变且不可依赖","details":{"type":"response_too_large","repair_attempted":true}}}
                                """)));

        assertThatThrownBy(() -> adapter().extractRequirementFactsV2(new RequirementFactExtractionV2Invocation(
                "session-v2", "agent-v2", singleDocumentScope(), factInput("one"))))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class, failure -> {
                    assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.RESPONSE_TOO_LARGE);
                    assertThat(failure.repairAttempted()).isTrue();
                });
    }

    @ParameterizedTest
    @MethodSource("v2FailurePairs")
    void adapterAcceptsOnlyFrozenHttpStatusAndDetailsTypePairs(int status, String wireType) {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-v2/isolated-skill"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":{\"details\":{\"type\":\""
                                + wireType + "\"}}}")));

        assertThatThrownBy(() -> adapter().extractRequirementFactsV2(new RequirementFactExtractionV2Invocation(
                "session-v2", "agent-v2", singleDocumentScope(), factInput("one"))))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class, failure ->
                        assertThat(failure.type().wireValue()).isEqualTo(wireType));
    }

    @Test
    void adapterRejectsAValidDetailsTypePairedWithTheWrongHttpStatus() {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-v2/isolated-skill"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":{\"details\":{\"type\":\"response_too_large\"}}}")));

        assertThatThrownBy(() -> adapter().extractRequirementFactsV2(new RequirementFactExtractionV2Invocation(
                "session-v2", "agent-v2", singleDocumentScope(), factInput("one"))))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class, failure ->
                        assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.STRUCTURED_OUTPUT_INVALID));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {201, 202, 204})
    void v2RequiresExactHttp200EvenWhenAnotherSuccessStatusHasAValidEnvelope(int status) {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-v2/isolated-skill"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json").withBody(factSuccess())));

        assertThatThrownBy(() -> adapter().extractRequirementFactsV2(new RequirementFactExtractionV2Invocation(
                "session-v2", "agent-v2", singleDocumentScope(), factInput("one"))))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class, failure ->
                        assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.STRUCTURED_OUTPUT_INVALID));
    }

    @Test
    void v2RejectsResponseBeyondFourMiBBeforeItCanReachTheResultMapper() {
        String oversized = "x".repeat(KnowledgeAgentProperties.DEFAULT_STRUCTURED_CONTRACT_V2_RESPONSE_MAX_BYTES + 1);
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-v2/isolated-skill"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(oversized)));

        assertThatThrownBy(() -> adapter().extractRequirementFactsV2(new RequirementFactExtractionV2Invocation(
                "session-v2", "agent-v2", singleDocumentScope(), factInput("one"))))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class, failure ->
                        assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.RESPONSE_TOO_LARGE));
    }

    @Test
    void interruptedV2WaitPreservesTheWorkerInterruptionSignalInsteadOfOnlyReportingAModelFailure()
            throws InterruptedException {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-v2/isolated-skill"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withFixedDelay(5_000).withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(factSuccess())));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                new WebClientKnowledgeAgentAdapter(kee.getRuntimeInfo().getHttpBaseUrl() + "/api/v1", "secret",
                        Duration.ofSeconds(10), 1, 1024, 16 * 1024 * 1024, 4 * 1024 * 1024)
                        .extractRequirementFactsV2(new RequirementFactExtractionV2Invocation(
                                "session-v2", "agent-v2", singleDocumentScope(), factInput("one")));
            } catch (Throwable failure) {
                thrown.set(failure);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "v2-interrupted-test-worker");
        worker.start();
        for (int attempt = 0; attempt < 100 && kee.getAllServeEvents().isEmpty(); attempt++) {
            Thread.sleep(20);
        }
        worker.interrupt();
        worker.join(2_000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(StructuredSkillExecutionException.class);
        assertThat(interrupted.get()).isTrue();
    }

    private static java.util.stream.Stream<Arguments> v2FailurePairs() {
        return java.util.stream.Stream.of(
                Arguments.of(400, "invalid_request"),
                Arguments.of(400, "unsupported_contract_version"),
                Arguments.of(400, "unsupported_skill"),
                Arguments.of(400, "structured_output_invalid"),
                Arguments.of(400, "response_too_large"),
                Arguments.of(400, "request_too_large"),
                Arguments.of(413, "request_too_large"),
                Arguments.of(401, "forbidden"),
                Arguments.of(403, "forbidden"),
                Arguments.of(409, "forbidden"),
                Arguments.of(404, "session_not_found"),
                Arguments.of(503, "model_unavailable"),
                Arguments.of(503, "skill_unavailable"),
                Arguments.of(500, "model_execution_failed"));
    }

    private static RequirementFactExtractionV2Input factInput(String content) {
        return new RequirementFactExtractionV2Input("function-1", "订单提交", "订单/提交", "提交订单",
                "material-1", MaterialContentTypeKey.WORK_ORDER_PLAN, "window-1",
                List.of(new RequirementFactExtractionV2Input.MaterialUnit("unit-1", 1, content)), List.of());
    }

    private static FunctionalTestcaseDesignV2Input testcaseInput() {
        var quote = new StructuredSourceQuoteV2("unit-1", "订单提交");
        return new FunctionalTestcaseDesignV2Input("function-1", "订单提交", "订单/提交", "提交订单",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交订单", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1",
                        RequirementFactExtractionV2Result.FactType.BUSINESS_RULE,
                        "订单提交", List.of(quote))));
    }

    private static RequirementScope singleDocumentScope() {
        return new RequirementScope("kb-1", "system-1", "version-1", "requirements", "project-1",
                List.of(new RequirementDocumentCoordinate("document-1", "work_order_plan")));
    }

    private static WebClientKnowledgeAgentAdapter adapter() {
        return new WebClientKnowledgeAgentAdapter(kee.getRuntimeInfo().getHttpBaseUrl() + "/api/v1", "secret",
                Duration.ofSeconds(2), 1, 1024, 16 * 1024 * 1024, 4 * 1024 * 1024);
    }

    private static String factSuccess() {
        return """
                {"success":true,"data":{"schema_version":"2.0","skill_name":"requirement-fact-extraction","repair_attempted":false,"result":{
                  "function_key":"function-1","window_key":"window-1",
                  "requirement_facts":[{"fact_type":"business_rule","statement":"订单提交","source_quotes":[{"evidence_key":"unit-1","quote":"订单提交"}]}],
                  "testability_observations":[]
                }}}
                """;
    }

    private static String testcaseSuccess() {
        return """
                {"success":true,"data":{"schema_version":"2.0","skill_name":"functional-testcase-design","repair_attempted":false,"result":{
                  "function_key":"function-1","test_point_key":"point-1","generation_outcome":"pending_only",
                  "missing_information":["缺少账号权限说明"],
                  "testcases":[{
                    "name":"提交订单待确认","title":"提交订单待确认","priority":"medium",
                    "preconditions":[],"initialization":{"hardware_configuration":[],"software_configuration":[],"test_configuration":[],"parameter_configuration":[]},
                    "inputs":[],"steps":[{"step_no":1,"action":"提交订单","expected":"订单提交","evaluation_criteria":"实际结果符合预期","termination_or_error":"无法继续则停止","result_collection":"记录结果"}],
                    "expected_results":["订单提交"],"evaluation_criteria":"全部步骤符合预期","result_evaluation_criteria":"任一步失败则不通过",
                    "termination_conditions":[],"result_collection":"记录结果","requirement_fact_keys":["fact-1"],"evidence_keys":["unit-1"],
                    "case_status":"pending_confirmation","missing_information":["缺少账号权限说明"]
                  }]
                }}}
                """;
    }
}
