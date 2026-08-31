package com.testcaseagent.knowledgeagent;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Public transport contract for auditable function candidates.
 *
 * [Req-ID]: REQ-AFCE-001, REQ-AFCE-002, REQ-AFCE-008
 */
class FunctionCandidateExtractionProtocolContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_ID = "session-contract-001";

    @RegisterExtension
    static WireMockExtension kee = WireMockExtension.newInstance().build();

    /** [Req-ID]: REQ-AFCE-001 */
    @Test
    void serializesTheFrozenCandidateWindowExactly() throws Exception {
        JsonNode expected = MAPPER.readTree(fixture("request.json"));
        FunctionCandidateExtractionInput input = MAPPER.treeToValue(expected.path("input"),
                FunctionCandidateExtractionInput.class);
        JsonNode actual = MAPPER.valueToTree(input);

        assertThat(actual).isEqualTo(expected.path("input"));
        assertThat(input.units()).extracting(FunctionCandidateExtractionInput.Unit::ordinal)
                .containsExactly(10, 11, 12, 13);
        assertThat(input.contextUnits()).extracting(FunctionCandidateExtractionInput.Unit::ordinal)
                .containsExactly(9);
    }

    /** [Req-ID]: REQ-AFCE-001, REQ-AFCE-004 */
    @Test
    void derivesTheFrozenWindowIdentityBeforeRegistrationOrTransport() throws Exception {
        JsonNode expected = MAPPER.readTree(fixture("request.json"));
        FunctionCandidateExtractionInput frozen = MAPPER.treeToValue(expected.path("input"),
                FunctionCandidateExtractionInput.class);

        FunctionCandidateExtractionInput derived = FunctionCandidateExtractionInput.forWindow(
                "task-meizhou-acceptance-001", frozen.materialKey(), frozen.sourceLabel(),
                frozen.units(), frozen.contextUnits());

        assertThat(derived).isEqualTo(frozen);
        assertThat(derived.windowKey())
                .isEqualTo("365b565a76db3fe91166af3dd1606113f064e63967f3baaeac8002d95245447d");
    }

    /** [Req-ID]: REQ-AFCE-001 */
    @Test
    void rejectsUntrustedTargetAndContextWindowsBeforeCallingKee() {
        List<FunctionCandidateExtractionInput.Unit> discontinuous = List.of(
                unit("unit-10", 10), unit("unit-12", 12));
        List<FunctionCandidateExtractionInput.Unit> targets = units(1, 32, "target-");

        assertThatThrownBy(() -> input(discontinuous, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("continuous");
        assertThatThrownBy(() -> input(List.of(unit("unit-10", 10)), List.of(unit("unit-10", 9))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
        assertThatThrownBy(() -> input(targets, List.of(unit("context-33", 33))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    /** [Req-ID]: REQ-AFCE-001, REQ-AFCE-002 */
    @Test
    void sendsTheFrozenRequestAndMapsTheCanonicalSuccessEnvelope() throws Exception {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/" + SESSION_ID + "/isolated-skill"))
                .withRequestBody(equalToJson(fixture("request.json"), false, false))
                .willReturn(okJson(fixture("canonical-success.json"))));

        StructuredSkillSuccessEnvelope<FunctionCandidateExtractionResult> response = adapter()
                .extractFunctionCandidates(invocationFromFixture());

        assertThat(response.success()).isTrue();
        assertThat(response.data().result().windowKey())
                .isEqualTo("365b565a76db3fe91166af3dd1606113f064e63967f3baaeac8002d95245447d");
        assertThat(response.data().result().sourceOutcomes()).hasSize(4);
        assertThat(response.data().result().candidates()).hasSize(2);
    }

    /** [Req-ID]: REQ-AFCE-002 */
    @Test
    void acceptsMoreThanTwoHundredCandidatesWhenTheResponseFitsTheSixteenMibBoundary() throws Exception {
        String responseBody = largeCanonicalResponse(300);
        assertThat(responseBody.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(4 * 1024 * 1024)
                .isLessThan(16 * 1024 * 1024);
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/" + SESSION_ID + "/isolated-skill"))
                .willReturn(okJson(responseBody)));

        StructuredSkillSuccessEnvelope<FunctionCandidateExtractionResult> response = adapter()
                .extractFunctionCandidates(invocationFromFixture());

        assertThat(response.data().result().candidates()).hasSize(300);
        assertThat(response.data().result().sourceOutcomes().get(0).candidateRefs()).hasSize(300);
    }

    /** [Req-ID]: REQ-AFCE-002 */
    @Test
    void rejectsAnUnknownResultFieldOrMismatchedWindowEcho() throws Exception {
        JsonNode unknown = MAPPER.readTree(fixture("canonical-success.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknown.path("data").path("result"))
                .put("unexpected", true);
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/" + SESSION_ID + "/isolated-skill"))
                .willReturn(okJson(MAPPER.writeValueAsString(unknown))));

        assertThatThrownBy(() -> adapter().extractFunctionCandidates(invocationFromFixture()))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class,
                        failure -> assertThat(failure.type())
                                .isEqualTo(StructuredSkillErrorType.STRUCTURED_OUTPUT_INVALID));

        kee.resetAll();
        JsonNode mismatched = MAPPER.readTree(fixture("canonical-success.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) mismatched.path("data").path("result"))
                .put("window_key", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/" + SESSION_ID + "/isolated-skill"))
                .willReturn(okJson(MAPPER.writeValueAsString(mismatched))));

        assertThatThrownBy(() -> adapter().extractFunctionCandidates(invocationFromFixture()))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class,
                        failure -> assertThat(failure.type())
                                .isEqualTo(StructuredSkillErrorType.STRUCTURED_OUTPUT_INVALID));
    }

    /** [Req-ID]: REQ-AFCE-008 */
    @Test
    void unsupportedCandidateProtocolFailsWithoutFallingBackToLegacyExtraction() throws Exception {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/" + SESSION_ID + "/isolated-skill"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":{\"details\":{\"type\":\"invalid_request\"}}}")));

        assertThatThrownBy(() -> adapter().extractFunctionCandidates(invocationFromFixture()))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class,
                        failure -> assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.INVALID_REQUEST));

        kee.verify(1, postRequestedFor(urlEqualTo("/api/v1/agent-chat/" + SESSION_ID + "/isolated-skill"))
                .withRequestBody(matchingJsonPath("$.input.operation",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("extract_function_candidates"))));
        kee.verify(0, postRequestedFor(urlEqualTo("/api/v1/agent-chat/" + SESSION_ID + "/isolated-skill"))
                .withRequestBody(matchingJsonPath("$.input.operation",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("extract_function_list"))));
    }

    /** [Req-ID]: REQ-AFCE-008 */
    @Test
    void keepsTheLegacyExtractionOperationAvailableAsASeparateCall() {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-legacy/isolated-skill"))
                .willReturn(okJson("{\"success\":true,\"data\":{\"schema_version\":\"1.0\","
                        + "\"skill_name\":\"feature-scope-reconciliation\",\"repair_attempted\":false,"
                        + "\"result\":{\"operation\":\"extract_function_list\",\"function_list_items\":[]}}}")));
        RequirementScope scope = scope();
        FunctionListExtractionInvocation legacy = new FunctionListExtractionInvocation("session-legacy",
                "agent-contract-001", scope, new FunctionListExtractionInput("material-meizhou-v1",
                        "功能候选协议合成样例", List.of(new FunctionListExtractionInput.Unit(
                                "unit-0010", 10, "3.1 用户中心 账号登录"))));

        assertThat(adapter().extractFunctionList(legacy).data().result().functionListItems()).isEmpty();
        kee.verify(1, postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-legacy/isolated-skill"))
                .withRequestBody(matchingJsonPath("$.input.operation",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("extract_function_list"))));
    }

    private static FunctionCandidateExtractionInput input(List<FunctionCandidateExtractionInput.Unit> targets,
            List<FunctionCandidateExtractionInput.Unit> context) {
        return new FunctionCandidateExtractionInput(
                "365b565a76db3fe91166af3dd1606113f064e63967f3baaeac8002d95245447d",
                "material-meizhou-v1", "功能候选协议合成样例", targets, context);
    }

    private static FunctionCandidateExtractionInput.Unit unit(String key, int ordinal) {
        return new FunctionCandidateExtractionInput.Unit(key, ordinal, "合成内容");
    }

    private static List<FunctionCandidateExtractionInput.Unit> units(int first, int last, String prefix) {
        List<FunctionCandidateExtractionInput.Unit> values = new ArrayList<>();
        for (int ordinal = first; ordinal <= last; ordinal++) {
            values.add(unit(prefix + ordinal, ordinal));
        }
        return values;
    }

    private static FunctionCandidateExtractionInvocation invocationFromFixture() throws Exception {
        JsonNode request = MAPPER.readTree(fixture("request.json"));
        FunctionCandidateExtractionInput input = MAPPER.treeToValue(request.path("input"),
                FunctionCandidateExtractionInput.class);
        return new FunctionCandidateExtractionInvocation(SESSION_ID, request.path("agent_id").asText(), scope(), input);
    }

    private static RequirementScope scope() {
        return new RequirementScope("7c0f5fdd-980d-4389-8105-ec97f675dac1", "system-contract-001",
                "version-contract-001", "function_list", "project-contract-001",
                List.of(new RequirementDocumentCoordinate("knowledge-contract-001", "function_list")));
    }

    private static WebClientKnowledgeAgentAdapter adapter() {
        return new WebClientKnowledgeAgentAdapter(kee.baseUrl() + "/api/v1", "test-key",
                Duration.ofSeconds(5), 1, 20_000);
    }

    private static String largeCanonicalResponse(int candidateCount) throws Exception {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("success", true);
        ObjectNode data = envelope.putObject("data");
        data.put("schema_version", "1.0");
        data.put("skill_name", "feature-scope-reconciliation");
        data.put("repair_attempted", false);
        ObjectNode result = data.putObject("result");
        result.put("operation", "extract_function_candidates");
        result.put("protocol_version", "1");
        result.put("window_key",
                "365b565a76db3fe91166af3dd1606113f064e63967f3baaeac8002d95245447d");
        ArrayNode refs = result.putArray("source_outcomes").addObject()
                .put("unit_key", "unit-0010")
                .put("disposition", "linked")
                .putArray("candidate_refs");
        ArrayNode candidates = result.putArray("candidates");
        for (int index = 0; index < candidateCount; index++) {
            String candidateRef = String.format("%064x", index + 1);
            refs.add(candidateRef);
            ObjectNode candidate = candidates.addObject();
            candidate.put("candidate_ref", candidateRef);
            candidate.put("path", "合成功能" + index);
            candidate.put("description", "x".repeat(15_000));
            candidate.put("target_quote", "3.1 用户中心 账号登录");
            candidate.putArray("evidence_keys").add("unit-0010");
            candidate.put("recommended_status", "accepted");
            candidate.put("reason_code", "grounded_function");
            candidate.putArray("missing_information");
        }
        ((ObjectNode) result.path("source_outcomes").path(0)).put("reason_code", "candidate_linked");
        ObjectNode summary = result.putObject("normalization_summary");
        summary.put("model_candidate_count", candidateCount);
        summary.put("downgraded_candidate_count", 0);
        summary.put("discarded_candidate_count", 0);
        summary.put("auto_unresolved_unit_count", 0);
        return MAPPER.writeValueAsString(envelope);
    }

    private static String fixture(String name) throws IOException {
        String path = "/contracts/function-candidate-protocol-v1/" + name;
        try (InputStream input = FunctionCandidateExtractionProtocolContractTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("missing contract fixture " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
