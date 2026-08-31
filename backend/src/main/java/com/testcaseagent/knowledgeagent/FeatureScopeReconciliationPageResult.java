package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Strict public result for one KEE V2 reconciliation owner window.
 * Machine identities are server-derived but remain subject to Java's independent task-level validation.
 *
 * [Req-ID]: REQ-FSC-008
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonPropertyOrder({"operation", "protocol_version", "run_key", "page_key",
        "completed_owner_source_refs", "reconciliations"})
public record FeatureScopeReconciliationPageResult(
        String operation,
        @JsonProperty("protocol_version") String protocolVersion,
        @JsonProperty("run_key") String runKey,
        @JsonProperty("page_key") String pageKey,
        @JsonProperty("completed_owner_source_refs")
        List<FeatureScopeReconciliationPageInput.SourceRef> completedOwnerSourceRefs,
        List<Reconciliation> reconciliations) {

    public FeatureScopeReconciliationPageResult {
        if (!FeatureScopeReconciliationPageInput.OPERATION.equals(operation)) {
            throw new IllegalArgumentException("operation must be reconcile_page");
        }
        if (!FeatureScopeReconciliationPageInput.PROTOCOL_VERSION.equals(protocolVersion)) {
            throw new IllegalArgumentException("protocolVersion must be 2");
        }
        runKey = StructuredSkillContract.key(runKey, "runKey");
        pageKey = FeatureScopeReconciliationPageInput.requireSha256(pageKey, "pageKey");
        completedOwnerSourceRefs = copyUnbounded(completedOwnerSourceRefs, "completedOwnerSourceRefs", true);
        requireStrictlyOrdered(completedOwnerSourceRefs, "completedOwnerSourceRefs");
        reconciliations = copyUnbounded(reconciliations, "reconciliations", false);
        StructuredSkillContract.uniqueKeys(reconciliations.stream().map(Reconciliation::reconciliationKey).toList(),
                "reconciliation");
    }

    /** One canonical, server-derived semantic relation. V2 intentionally has no 100-reference cap. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"reconciliation_key", "owner_source_ref", "function_list_item_keys",
            "requirement_fact_keys", "classification", "evidence_keys", "scope_recommendation",
            "confirmation_status"})
    public record Reconciliation(
            @JsonProperty("reconciliation_key") String reconciliationKey,
            @JsonProperty("owner_source_ref") FeatureScopeReconciliationPageInput.SourceRef ownerSourceRef,
            @JsonProperty("function_list_item_keys") List<String> functionListItemKeys,
            @JsonProperty("requirement_fact_keys") List<String> requirementFactKeys,
            FeatureScopeReconciliationResult.Classification classification,
            @JsonProperty("evidence_keys") List<String> evidenceKeys,
            @JsonProperty("scope_recommendation") String scopeRecommendation,
            @JsonProperty("confirmation_status") FeatureScopeReconciliationResult.ConfirmationStatus confirmationStatus) {
        public Reconciliation {
            reconciliationKey = FeatureScopeReconciliationPageInput.requireSha256(
                    reconciliationKey, "reconciliationKey");
            ownerSourceRef = Objects.requireNonNull(ownerSourceRef, "ownerSourceRef must not be null");
            functionListItemKeys = orderedKeys(functionListItemKeys, "functionListItemKeys", false);
            requirementFactKeys = orderedKeys(requirementFactKeys, "requirementFactKeys", false);
            if (functionListItemKeys.isEmpty() && requirementFactKeys.isEmpty()) {
                throw new IllegalArgumentException("reconciliation requires a source key");
            }
            if (classification == null || confirmationStatus == null) {
                throw new IllegalArgumentException("classification and confirmationStatus must not be null");
            }
            if (classification == FeatureScopeReconciliationResult.Classification.INSUFFICIENT_EVIDENCE
                    && confirmationStatus != FeatureScopeReconciliationResult.ConfirmationStatus.PENDING_CONFIRMATION) {
                throw new IllegalArgumentException("insufficient evidence reconciliation must remain pending");
            }
            evidenceKeys = orderedKeys(evidenceKeys, "evidenceKeys", true);
            scopeRecommendation = StructuredSkillContract.text(scopeRecommendation, "scopeRecommendation");
        }

        /** Reconstructs the exact canonical source-reference sequence used for relation identity. */
        public List<FeatureScopeReconciliationPageInput.SourceRef> sourceRefs() {
            return FeatureScopeReconciliationV2Canonicalizer.relationSourceRefs(
                    functionListItemKeys, requirementFactKeys);
        }
    }

    private static List<String> orderedKeys(List<String> values, String name, boolean requireNonEmpty) {
        List<String> copy = StructuredSkillContract.unboundedKeyReferences(values, name, requireNonEmpty);
        var order = FeatureScopeReconciliationV2Canonicalizer.utf8Order();
        for (int index = 1; index < copy.size(); index++) {
            if (order.compare(copy.get(index - 1), copy.get(index)) >= 0) {
                throw new IllegalArgumentException(name + " must be strictly ordered and unique");
            }
        }
        return copy;
    }

    private static <T> List<T> copyUnbounded(List<T> values, String name, boolean requireNonEmpty) {
        List<T> copy = List.copyOf(Objects.requireNonNull(values, name + " must not be null"));
        if (requireNonEmpty && copy.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        if (copy.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException(name + " must not contain null");
        return copy;
    }

    private static void requireStrictlyOrdered(
            List<FeatureScopeReconciliationPageInput.SourceRef> values, String name) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(name + " must be unique");
        }
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).compareTo(values.get(index)) >= 0) {
                throw new IllegalArgumentException(name + " must be canonically ordered");
            }
        }
    }
}
