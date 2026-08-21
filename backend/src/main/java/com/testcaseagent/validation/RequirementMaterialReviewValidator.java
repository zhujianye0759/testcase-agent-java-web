package com.testcaseagent.validation;

import java.text.Normalizer;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates one requirement-material review result without repairing or retaining partial model output. [Req-ID]: REQ-STG-001, REQ-STG-002, REQ-FTG-005 */
public final class RequirementMaterialReviewValidator {

    /** Validates all result rows before the caller performs its atomic persistence. */
    public void validate(WorkItem workItem, Result result) {
        WorkItem item = Objects.requireNonNull(workItem, "workItem must not be null");
        Result checked = Objects.requireNonNull(result, "result must not be null");
        List<RequirementFact> facts = requiredList(checked.requirementFacts(), "requirementFacts");
        List<ReviewFinding> findings = requiredList(checked.reviewFindings(), "reviewFindings");
        if (facts.size() > 200 || findings.size() > 200 || facts.size() + findings.size() < 1) {
            throw new IllegalArgumentException("Review result must contain 0..200 facts and findings with at least one total row");
        }
        requireDistinct(facts.stream().map(RequirementFact::factKey).toList(), "factKey");
        requireDistinct(findings.stream().map(ReviewFinding::findingKey).toList(), "findingKey");
        for (RequirementFact fact : facts) validateFact(item, Objects.requireNonNull(fact, "requirement fact must not be null"));
        for (ReviewFinding finding : findings) validateFinding(item, Objects.requireNonNull(finding, "review finding must not be null"));
    }

    private static void validateFact(WorkItem item, RequirementFact fact) {
        required(fact.factKey(), "factKey");
        ReaderFacingTextPolicy.requireSafe(fact.function(), "function");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.roles(), "roles"), "role");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.triggerConditions(), "triggerConditions"), "triggerCondition");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.inputs(), "inputs"), "input");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.businessRules(), "businessRules"), "businessRule");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.outputs(), "outputs"), "output");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.permissions(), "permissions"), "permission");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.stateChanges(), "stateChanges"), "stateChange");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.exceptionHandling(), "exceptionHandling"), "exceptionHandling");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.externalDependencies(), "externalDependencies"), "externalDependency");
        if (item.supplementaryMaterial()) {
            throw new IllegalArgumentException("Supplementary material cannot independently support a formal requirement fact");
        }
        List<String> evidence = requiredList(fact.evidenceKeys(), "fact evidenceKeys");
        if (evidence.isEmpty()) throw new IllegalArgumentException("A formal requirement fact requires evidence");
        requireDistinct(evidence, "fact evidenceKey");
        evidence.forEach(key -> item.requireSliceEvidence(key));
        item.requireDirectEvidence(fact.function(), "function", evidence);
        item.requireDirectEvidence(fact.roles(), "role", evidence);
        item.requireDirectEvidence(fact.triggerConditions(), "triggerCondition", evidence);
        item.requireDirectEvidence(fact.inputs(), "input", evidence);
        item.requireDirectEvidence(fact.businessRules(), "businessRule", evidence);
        item.requireDirectEvidence(fact.outputs(), "output", evidence);
        item.requireDirectEvidence(fact.permissions(), "permission", evidence);
        item.requireDirectEvidence(fact.stateChanges(), "stateChange", evidence);
        item.requireDirectEvidence(fact.exceptionHandling(), "exceptionHandling", evidence);
        item.requireDirectEvidence(fact.externalDependencies(), "externalDependency", evidence);
    }

    private static void validateFinding(WorkItem item, ReviewFinding finding) {
        required(finding.findingKey(), "findingKey");
        ReaderFacingTextPolicy.requireSafe(finding.issueType(), "issueType");
        ReaderFacingTextPolicy.requireSafe(finding.description(), "description");
        ReaderFacingTextPolicy.requireSafe(finding.testDesignImpact(), "testDesignImpact");
        ReaderFacingTextPolicy.requireSafe(finding.currentProjectRecommendation(), "currentProjectRecommendation");
        ReaderFacingTextPolicy.requireSafe(finding.designCenterGuidelineRecommendation(), "designCenterGuidelineRecommendation");
        if (finding.handlingLevel() == null) throw new IllegalArgumentException("handlingLevel must not be null");
        List<String> evidence = requiredList(finding.evidenceKeys(), "finding evidenceKeys");
        requireDistinct(evidence, "finding evidenceKey");
        evidence.forEach(key -> item.requireSliceEvidence(key));
    }

    private static void requireDistinct(List<String> values, String field) {
        Set<String> distinct = new HashSet<>();
        for (String value : values) if (!distinct.add(required(value, field))) throw new IllegalArgumentException(field + " must be unique");
    }

    private static <T> List<T> requiredList(List<T> values, String field) {
        if (values == null) throw new IllegalArgumentException(field + " must not be null");
        return List.copyOf(values);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(required(value, "grounding text"), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder compact = new StringBuilder(normalized.length());
        normalized.codePoints().filter(Character::isLetterOrDigit).forEach(compact::appendCodePoint);
        return compact.toString();
    }

    /** Frozen material coordinates and parsed-unit text for exactly one review invocation. */
    public record WorkItem(StructuredValidationRegistry registry, String materialKey, String contentTypeKey,
            List<String> allowedEvidenceKeys, Map<String, String> evidenceTexts) {
        public WorkItem {
            registry = Objects.requireNonNull(registry, "registry must not be null");
            required(materialKey, "materialKey");
            if (!("requirements_spec".equals(contentTypeKey) || "work_order_plan".equals(contentTypeKey)
                    || "prototype".equals(contentTypeKey) || "requirement_list".equals(contentTypeKey))) {
                throw new IllegalArgumentException("Unsupported requirement material content type");
            }
            registry.require(StructuredKeyType.MATERIAL, materialKey);
            List<String> checkedEvidence = requiredList(allowedEvidenceKeys, "allowedEvidenceKeys");
            if (checkedEvidence.isEmpty()) throw new IllegalArgumentException("allowedEvidenceKeys must not be empty");
            requireDistinct(checkedEvidence, "allowedEvidenceKey");
            for (String key : checkedEvidence) registry.requireEvidence(key, materialKey);
            allowedEvidenceKeys = checkedEvidence;
            Map<String, String> suppliedTexts = Objects.requireNonNull(evidenceTexts, "evidenceTexts must not be null");
            if (!suppliedTexts.keySet().equals(new LinkedHashSet<>(checkedEvidence))) {
                throw new IllegalArgumentException("evidenceTexts must exactly match allowedEvidenceKeys");
            }
            Map<String, String> checkedTexts = new LinkedHashMap<>();
            for (String key : checkedEvidence) {
                checkedTexts.put(key, required(suppliedTexts.get(key), "evidenceText"));
            }
            evidenceTexts = Collections.unmodifiableMap(checkedTexts);
        }

        private void requireSliceEvidence(String evidenceKey) {
            if (!allowedEvidenceKeys.contains(evidenceKey)) {
                throw new IllegalArgumentException("Review evidence is outside the current parsed-unit slice");
            }
            registry.requireEvidence(evidenceKey, materialKey);
        }

        private void requireDirectEvidence(String value, String field, List<String> citedEvidenceKeys) {
            String claim = normalize(value);
            if (claim.isEmpty()) throw new IllegalArgumentException(field + " has no business content after normalization");
            boolean supported = citedEvidenceKeys.stream()
                    .map(evidenceTexts::get)
                    .map(RequirementMaterialReviewValidator::normalize)
                    .anyMatch(source -> !source.isEmpty() && source.contains(claim));
            if (!supported) {
                throw new IllegalArgumentException(field + " is not directly supported by its cited parsed-unit evidence");
            }
        }

        private void requireDirectEvidence(List<String> values, String field, List<String> citedEvidenceKeys) {
            values.forEach(value -> requireDirectEvidence(value, field, citedEvidenceKeys));
        }

        boolean supplementaryMaterial() {
            return "prototype".equals(contentTypeKey) || "requirement_list".equals(contentTypeKey);
        }
    }

    /** Exact result envelope content for one review result. */
    public record Result(List<RequirementFact> requirementFacts, List<ReviewFinding> reviewFindings) { }

    /** One formal requirement fact returned by the review Skill. */
    public record RequirementFact(
            String factKey, String function, List<String> roles, List<String> triggerConditions, List<String> inputs,
            List<String> businessRules, List<String> outputs, List<String> permissions, List<String> stateChanges,
            List<String> exceptionHandling, List<String> externalDependencies, List<String> evidenceKeys) { }

    /** One candidate review finding returned by the review Skill. */
    public record ReviewFinding(
            String findingKey, String issueType, String description, List<String> evidenceKeys, String testDesignImpact,
            String currentProjectRecommendation, String designCenterGuidelineRecommendation, HandlingLevel handlingLevel) { }

    /** Frozen handling levels preserved without automatic escalation. */
    public enum HandlingLevel { BLOCKING, CONTINUE_INCOMPLETE, IMPROVEMENT }
}
