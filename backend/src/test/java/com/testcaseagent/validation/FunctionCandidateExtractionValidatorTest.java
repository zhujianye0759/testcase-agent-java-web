package com.testcaseagent.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.testcaseagent.knowledgeagent.FunctionCandidateExtractionInput;
import com.testcaseagent.knowledgeagent.FunctionCandidateExtractionResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Java-owned candidate integrity and downgrade tests. [Req-ID]: REQ-AFCE-002, REQ-AFCE-003, REQ-AFCE-004 */
class FunctionCandidateExtractionValidatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TASK_ID = "task-meizhou-acceptance-001";
    private final FunctionCandidateExtractionValidator validator = new FunctionCandidateExtractionValidator();

    /** [Req-ID]: REQ-AFCE-002, REQ-AFCE-004 */
    @Test
    void acceptsEveryFrozenCanonicalVariantAndRecomputesLiteralIdentities() throws Exception {
        var success = validator.validate(TASK_ID, requestInput(), result("canonical-success.json"));
        var omitted = validator.validate(TASK_ID, requestInput(), result("canonical-omitted-unit.json"));
        var unusableSibling = validator.validate(TASK_ID, requestInput(),
                result("canonical-unusable-sibling.json"));

        assertThat(success.windowKey())
                .isEqualTo("365b565a76db3fe91166af3dd1606113f064e63967f3baaeac8002d95245447d");
        assertThat(success.candidates()).extracting(FunctionCandidateExtractionValidator.ValidatedCandidate::candidateRef)
                .containsExactly(
                        "5126c6322d99dc312683675603c45be4025d27e6607ce161378fbccec3ad67f3",
                        "1a3e42020a240393654bedabdb2065f7ec66c4dec14a2035dfc2a094595ae604");
        assertThat(omitted.sourceOutcomes().get(3).finalDecision())
                .isEqualTo(FunctionCandidateExtractionValidator.FinalDecision.REJECTED);
        assertThat(unusableSibling.candidates()).hasSize(2);
    }

    /** [Req-ID]: REQ-AFCE-002 */
    @Test
    void rejectsMissingReorderedOrContextOwnedSourceOutcomes() throws Exception {
        ObjectNode missing = canonicalResult();
        ((ArrayNode) missing.path("source_outcomes")).remove(3);
        ObjectNode reordered = canonicalResult();
        ArrayNode outcomes = (ArrayNode) reordered.path("source_outcomes");
        JsonNode first = outcomes.remove(0);
        outcomes.insert(1, first);
        ObjectNode contextOwned = canonicalResult();
        ((ObjectNode) contextOwned.path("source_outcomes").path(0)).put("unit_key", "unit-0009");

        assertInvalid(missing);
        assertInvalid(reordered);
        assertInvalid(contextOwned);
    }

    /** [Req-ID]: REQ-AFCE-002, REQ-AFCE-004 */
    @Test
    void rejectsDanglingCandidateReferencesAndWrongCandidateHashes() throws Exception {
        ObjectNode dangling = canonicalResult();
        ((ArrayNode) dangling.path("source_outcomes").path(0).path("candidate_refs")).set(0,
                MAPPER.getNodeFactory().textNode("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        ObjectNode wrongHash = canonicalResult();
        ((ObjectNode) wrongHash.path("candidates").path(0)).put("candidate_ref",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertInvalid(dangling);
        assertInvalid(wrongHash);
    }

    /** [Req-ID]: REQ-AFCE-002 */
    @Test
    void rejectsACandidateLinkedToTheWrongTargetEvenWhenItRemainsReachableElsewhere() throws Exception {
        ObjectNode wrongOwner = canonicalResult();
        ((ArrayNode) wrongOwner.path("source_outcomes").path(1).path("candidate_refs")).removeAll();

        assertInvalid(wrongOwner);
    }

    /** [Req-ID]: REQ-AFCE-002, REQ-AFCE-003 */
    @Test
    void rejectsContextEvidenceAndUnquotedTargetText() throws Exception {
        ObjectNode contextEvidence = canonicalResult();
        ArrayNode evidence = (ArrayNode) contextEvidence.path("candidates").path(0).path("evidence_keys");
        evidence.removeAll().add("unit-0009");
        ObjectNode unquoted = canonicalResult();
        ((ObjectNode) unquoted.path("candidates").path(0)).put("target_quote", "目标原文不存在的内容");

        assertInvalid(contextEvidence);
        assertInvalid(unquoted);
    }

    /** [Req-ID]: REQ-AFCE-002, REQ-AFCE-003 */
    @Test
    void rejectsIllegalStatusReasonAndMissingInformationCombinations() throws Exception {
        ObjectNode acceptedWithMissing = canonicalResult();
        ((ArrayNode) acceptedWithMissing.path("candidates").path(0).path("missing_information"))
                .add("不应存在");
        ObjectNode pendingWithoutMissing = canonicalResult();
        ((ArrayNode) pendingWithoutMissing.path("candidates").path(1).path("missing_information"))
                .removeAll();
        ObjectNode wrongReason = canonicalResult();
        ((ObjectNode) wrongReason.path("source_outcomes").path(2)).put("reason_code", "candidate_linked");

        assertInvalid(acceptedWithMissing);
        assertInvalid(pendingWithoutMissing);
        assertInvalid(wrongReason);
    }

    /** [Req-ID]: REQ-AFCE-002 */
    @ParameterizedTest(name = "accepts authoritative unresolved reason {0}")
    @ValueSource(strings = {
            "ambiguous_content", "conflicting_content", "model_omitted_unit", "model_item_unusable"
    })
    void acceptsEveryAuthoritativeUnresolvedReason(String reasonCode) throws Exception {
        ObjectNode value = canonicalResult();
        ObjectNode outcome = (ObjectNode) value.path("source_outcomes").path(2);
        outcome.put("disposition", "unresolved");
        outcome.put("reason_code", reasonCode);
        ((ObjectNode) value.path("normalization_summary")).put("auto_unresolved_unit_count",
                "model_omitted_unit".equals(reasonCode) ? 1 : 0);

        assertThat(validator.validate(TASK_ID, requestInput(),
                MAPPER.treeToValue(value, FunctionCandidateExtractionResult.class)).sourceOutcomes())
                .hasSize(4);
    }

    /** [Req-ID]: REQ-AFCE-003 */
    @ParameterizedTest(name = "accepts authoritative pending reason {0}")
    @ValueSource(strings = {"ambiguous_scope", "insufficient_detail", "conflicting_evidence"})
    void acceptsEveryAuthoritativePendingConfirmationReason(String reasonCode) throws Exception {
        ObjectNode value = canonicalResult();
        ((ObjectNode) value.path("candidates").path(1)).put("reason_code", reasonCode);

        assertThat(validator.validate(TASK_ID, requestInput(),
                MAPPER.treeToValue(value, FunctionCandidateExtractionResult.class)).candidates())
                .hasSize(2);
    }

    /** [Req-ID]: REQ-AFCE-002 */
    @Test
    void acceptsDeduplicatedModelCountsAndDoesNotCountExplicitUnresolvedAsAutoFilled() throws Exception {
        ObjectNode value = canonicalResult();
        ObjectNode outcome = (ObjectNode) value.path("source_outcomes").path(2);
        outcome.put("disposition", "unresolved");
        outcome.put("reason_code", "ambiguous_content");
        ObjectNode summary = (ObjectNode) value.path("normalization_summary");
        summary.put("model_candidate_count", 5);
        summary.put("discarded_candidate_count", 1);
        summary.put("auto_unresolved_unit_count", 0);

        assertThat(validator.validate(TASK_ID, requestInput(),
                MAPPER.treeToValue(value, FunctionCandidateExtractionResult.class)).normalizationSummary()
                .modelCandidateCount()).isEqualTo(5);
    }

    /** [Req-ID]: REQ-AFCE-002 */
    @Test
    void acceptsDowngradedCountAbovePublicCandidatesAfterDuplicateCandidatesAreMerged() throws Exception {
        ObjectNode value = canonicalResult();
        ObjectNode summary = (ObjectNode) value.path("normalization_summary");
        summary.put("model_candidate_count", 5);
        summary.put("discarded_candidate_count", 1);
        summary.put("downgraded_candidate_count", 3);

        assertThat(validator.validate(TASK_ID, requestInput(),
                MAPPER.treeToValue(value, FunctionCandidateExtractionResult.class)).normalizationSummary()
                .downgradedCandidateCount()).isEqualTo(3);
    }

    /** [Req-ID]: REQ-AFCE-002 */
    @Test
    void rejectsUnresolvedCandidateReferencesAndUnknownUnresolvedReasons() throws Exception {
        ObjectNode nonemptyReferences = canonicalResult();
        ObjectNode linked = (ObjectNode) nonemptyReferences.path("source_outcomes").path(0);
        linked.put("disposition", "unresolved");
        linked.put("reason_code", "ambiguous_content");
        ObjectNode illegalReason = canonicalResult();
        ObjectNode noFunction = (ObjectNode) illegalReason.path("source_outcomes").path(2);
        noFunction.put("disposition", "unresolved");
        noFunction.put("reason_code", "unknown_reason");

        assertInvalid(nonemptyReferences);
        assertInvalid(illegalReason);
    }

    /** [Req-ID]: REQ-AFCE-002 */
    @Test
    void rejectsInconsistentNormalizationCountsAndUnreachableCandidates() throws Exception {
        ObjectNode badSummary = canonicalResult();
        ObjectNode summary = (ObjectNode) badSummary.path("normalization_summary");
        summary.put("model_candidate_count", 2);
        summary.put("discarded_candidate_count", 1);
        ObjectNode unreachable = canonicalResult();
        ((ArrayNode) unreachable.path("source_outcomes").path(3).path("candidate_refs")).removeAll();

        assertInvalid(badSummary);
        assertInvalid(unreachable);
    }

    /** [Req-ID]: REQ-AFCE-002 */
    @Test
    void rejectsEveryNormalizationSummaryUpperBoundViolation() throws Exception {
        ObjectNode discardedAboveModel = canonicalResult();
        ObjectNode discardedSummary = (ObjectNode) discardedAboveModel.path("normalization_summary");
        discardedSummary.put("model_candidate_count", 2);
        discardedSummary.put("discarded_candidate_count", 3);

        ObjectNode downgradedAboveRemainingModel = canonicalResult();
        ObjectNode downgradedSummary = (ObjectNode) downgradedAboveRemainingModel.path("normalization_summary");
        downgradedSummary.put("model_candidate_count", 3);
        downgradedSummary.put("discarded_candidate_count", 1);
        downgradedSummary
                .put("downgraded_candidate_count", 3);

        ObjectNode autoAboveUnresolved = canonicalResult();
        ((ObjectNode) autoAboveUnresolved.path("normalization_summary"))
                .put("auto_unresolved_unit_count", 1);

        ObjectNode autoWithoutOmittedOutcome = canonicalResult();
        ObjectNode explicit = (ObjectNode) autoWithoutOmittedOutcome.path("source_outcomes").path(2);
        explicit.put("disposition", "unresolved");
        explicit.put("reason_code", "ambiguous_content");
        ((ObjectNode) autoWithoutOmittedOutcome.path("normalization_summary"))
                .put("auto_unresolved_unit_count", 1);

        assertInvalid(discardedAboveModel);
        assertInvalid(downgradedAboveRemainingModel);
        assertInvalid(autoAboveUnresolved);
        assertInvalid(autoWithoutOmittedOutcome);
    }

    /** [Req-ID]: REQ-AFCE-003 */
    @Test
    void permitsOnlyRetainingOrDowngradingKeeRecommendations() throws Exception {
        var validated = validator.validate(TASK_ID, requestInput(), result("canonical-success.json"));
        var accepted = validated.candidates().get(0);
        var pending = validated.candidates().get(1);

        assertThat(accepted.decide(FunctionCandidateExtractionValidator.FinalDecision.PENDING_CONFIRMATION)
                .finalDecision()).isEqualTo(FunctionCandidateExtractionValidator.FinalDecision.PENDING_CONFIRMATION);
        assertThat(accepted.decide(FunctionCandidateExtractionValidator.FinalDecision.REJECTED).finalDecision())
                .isEqualTo(FunctionCandidateExtractionValidator.FinalDecision.REJECTED);
        assertThat(pending.decide(FunctionCandidateExtractionValidator.FinalDecision.REJECTED).finalDecision())
                .isEqualTo(FunctionCandidateExtractionValidator.FinalDecision.REJECTED);
        assertThatThrownBy(() -> pending.decide(FunctionCandidateExtractionValidator.FinalDecision.ACCEPTED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upgrade");
    }

    /** [Req-ID]: REQ-AFCE-004 */
    @Test
    void rejectsAWindowIdentityComputedForAnotherTask() throws Exception {
        assertThatThrownBy(() -> validator.validate("another-task", requestInput(),
                result("canonical-success.json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    private void assertInvalid(ObjectNode result) throws Exception {
        FunctionCandidateExtractionResult value = MAPPER.treeToValue(result,
                FunctionCandidateExtractionResult.class);
        assertThatThrownBy(() -> validator.validate(TASK_ID, requestInput(), value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static FunctionCandidateExtractionInput requestInput() throws Exception {
        JsonNode request = MAPPER.readTree(fixture("request.json"));
        return MAPPER.treeToValue(request.path("input"), FunctionCandidateExtractionInput.class);
    }

    private static FunctionCandidateExtractionResult result(String name) throws Exception {
        JsonNode envelope = MAPPER.readTree(fixture(name));
        return MAPPER.treeToValue(envelope.path("data").path("result"),
                FunctionCandidateExtractionResult.class);
    }

    private static ObjectNode canonicalResult() throws Exception {
        JsonNode envelope = MAPPER.readTree(fixture("canonical-success.json"));
        return (ObjectNode) envelope.path("data").path("result").deepCopy();
    }

    private static String fixture(String name) throws IOException {
        String path = "/contracts/function-candidate-protocol-v1/" + name;
        try (InputStream input = FunctionCandidateExtractionValidatorTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("missing contract fixture " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
