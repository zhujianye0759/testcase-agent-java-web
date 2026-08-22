package com.testcaseagent.validation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validates that a reconciliation result gives every current source a traceable terminal disposition. [Req-ID]: REQ-STG-001, REQ-STG-003 */
public final class FeatureReconciliationValidator {

    /** Validates one complete reconciliation result; it does not normalize classifications or confirmations. */
    public void validate(WorkItem workItem, Result result) {
        WorkItem item = Objects.requireNonNull(workItem, "workItem must not be null");
        Result checked = Objects.requireNonNull(result, "result must not be null");
        List<Reconciliation> reconciliations = requiredList(checked.reconciliations(), "reconciliations");
        if (reconciliations.isEmpty() || reconciliations.size() > 200) {
            throw new IllegalArgumentException("Reconciliations must contain 1..200 rows");
        }
        Set<String> reconciliationKeys = new HashSet<>();
        Set<String> coveredItems = new HashSet<>();
        Set<String> coveredFacts = new HashSet<>();
        for (Reconciliation reconciliation : reconciliations) {
            Reconciliation row = Objects.requireNonNull(reconciliation, "reconciliation must not be null");
            if (!reconciliationKeys.add(required(row.reconciliationKey(), "reconciliationKey"))) {
                throw new IllegalArgumentException("reconciliationKey must be unique");
            }
            List<String> itemKeys = requiredList(row.functionListItemKeys(), "functionListItemKeys");
            List<String> factKeys = requiredList(row.requirementFactKeys(), "requirementFactKeys");
            if (itemKeys.isEmpty() && factKeys.isEmpty()) throw new IllegalArgumentException("A reconciliation needs at least one source key");
            requireSubset(itemKeys, item.functionListItemKeys(), StructuredKeyType.FUNCTION_LIST_ITEM, item.registry(), coveredItems);
            requireSubset(factKeys, item.requirementFactKeys(), StructuredKeyType.REQUIREMENT_FACT, item.registry(), coveredFacts);
            if (row.classification() == null || row.confirmationStatus() == null) {
                throw new IllegalArgumentException("classification and confirmationStatus must not be null");
            }
            requireEvidence(item, requiredList(row.evidenceKeys(), "reconciliation evidenceKeys"));
        }
        if (!coveredItems.equals(Set.copyOf(item.functionListItemKeys())) || !coveredFacts.equals(Set.copyOf(item.requirementFactKeys()))) {
            throw new IllegalArgumentException("Every input source requires a terminal reconciliation disposition");
        }
    }

    private static void requireSubset(List<String> values, List<String> expected, StructuredKeyType type,
            StructuredValidationRegistry registry, Set<String> covered) {
        Set<String> distinct = new HashSet<>();
        for (String value : values) {
            String key = required(value, "source key");
            if (!distinct.add(key) || !expected.contains(key)) throw new IllegalArgumentException("Reconciliation references a duplicate or out-of-work source key");
            registry.require(type, key);
            covered.add(key);
        }
    }

    private static void requireEvidence(WorkItem item, List<String> evidenceKeys) {
        Set<String> distinct = new HashSet<>();
        for (String key : evidenceKeys) {
            String evidenceKey = required(key, "evidenceKey");
            if (!distinct.add(evidenceKey) || !item.allowedEvidenceKeys().contains(evidenceKey)) {
                throw new IllegalArgumentException("Reconciliation evidence is duplicate or outside the current work item");
            }
            item.registry().requireEvidence(evidenceKey);
        }
    }

    private static <T> List<T> requiredList(List<T> values, String field) {
        if (values == null) throw new IllegalArgumentException(field + " must not be null");
        return List.copyOf(values);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    /** Current source and evidence closure for one reconciliation work item. */
    public record WorkItem(StructuredValidationRegistry registry, List<String> functionListItemKeys,
            List<String> requirementFactKeys, List<String> allowedEvidenceKeys) {
        public WorkItem {
            registry = Objects.requireNonNull(registry, "registry must not be null");
            functionListItemKeys = requiredList(functionListItemKeys, "functionListItemKeys");
            requirementFactKeys = requiredList(requirementFactKeys, "requirementFactKeys");
            allowedEvidenceKeys = requiredList(allowedEvidenceKeys, "allowedEvidenceKeys");
            if (functionListItemKeys.isEmpty() || functionListItemKeys.size() > 200 || requirementFactKeys.size() > 200) {
                throw new IllegalArgumentException("Invalid reconciliation input source count");
            }
            requireDistinct(functionListItemKeys, StructuredKeyType.FUNCTION_LIST_ITEM, registry);
            requireDistinct(requirementFactKeys, StructuredKeyType.REQUIREMENT_FACT, registry);
            for (String evidenceKey : allowedEvidenceKeys) registry.requireEvidence(evidenceKey);
        }
    }

    /** Exact reconciliation result content returned by the Skill. */
    public record Result(List<Reconciliation> reconciliations) { }

    /** One immutable disposition; enum values are stored as returned and never upgraded. */
    public record Reconciliation(
            String reconciliationKey, List<String> functionListItemKeys, List<String> requirementFactKeys,
            Classification classification, List<String> evidenceKeys, String scopeRecommendation,
            ConfirmationStatus confirmationStatus) { }

    /** Frozen reconciliation classifications. */
    public enum Classification { EXACT_MATCH, FUNCTION_LIST_ONLY, REQUIREMENTS_ONLY, CONFLICT, DUPLICATE, SPLIT, MERGE, INSUFFICIENT_EVIDENCE }

    /** Frozen confirmation states. */
    public enum ConfirmationStatus { CONFIRMED, PENDING_CONFIRMATION }

    private static void requireDistinct(List<String> values, StructuredKeyType type, StructuredValidationRegistry registry) {
        Set<String> distinct = new HashSet<>();
        for (String value : values) {
            String key = required(value, "source key");
            if (!distinct.add(key)) throw new IllegalArgumentException("Source keys must be unique");
            registry.require(type, key);
        }
    }
}
