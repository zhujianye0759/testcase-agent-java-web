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

/** Validates one bounded requirement-review result without accepting partial model output. [Req-ID]: REQ-FTG-005, REQ-FTG-007 */
public final class RequirementMaterialReviewValidator {
    /** Validates facts, actual quoted source, pending proposals, and bounded duplicate root causes. */
    public void validate(WorkItem workItem, Result result) {
        WorkItem item = Objects.requireNonNull(workItem, "workItem must not be null");
        Result checked = Objects.requireNonNull(result, "result must not be null");
        List<RequirementFact> facts = requiredList(checked.requirementFacts(), "requirementFacts");
        List<ReviewFinding> findings = requiredList(checked.reviewFindings(), "reviewFindings");
        if (facts.size() > 200 || findings.size() > 200 || facts.size() + findings.size() < 1) throw new IllegalArgumentException("Review result must contain 0..200 facts and findings with at least one total row");
        requireDistinct(facts.stream().map(RequirementFact::factKey).toList(), "factKey");
        requireDistinct(findings.stream().map(ReviewFinding::findingKey).toList(), "findingKey");
        Set<RootCauseKind> roots = new HashSet<>();
        for (RequirementFact fact : facts) validateFact(item, Objects.requireNonNull(fact, "requirement fact must not be null"));
        for (ReviewFinding finding : findings) { finding = Objects.requireNonNull(finding, "review finding must not be null"); if (finding.rootCauseKind() != null && !roots.add(finding.rootCauseKind())) throw new IllegalArgumentException("rootCauseKind must be unique within one bounded result"); validateFinding(item, finding); }
    }

    private static void validateFact(WorkItem item, RequirementFact fact) {
        required(fact.factKey(), "factKey"); ReaderFacingTextPolicy.requireSafe(fact.function(), "function");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.roles(), "roles"), "role"); ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.triggerConditions(), "triggerConditions"), "triggerCondition"); ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.inputs(), "inputs"), "input"); ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.businessRules(), "businessRules"), "businessRule"); ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.outputs(), "outputs"), "output"); ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.permissions(), "permissions"), "permission"); ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.stateChanges(), "stateChanges"), "stateChange"); ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.exceptionHandling(), "exceptionHandling"), "exceptionHandling"); ReaderFacingTextPolicy.requireSafeItems(requiredList(fact.externalDependencies(), "externalDependencies"), "externalDependency");
        if (item.supplementaryMaterial()) throw new IllegalArgumentException("Supplementary material cannot independently support a formal requirement fact");
        List<String> evidence = requiredList(fact.evidenceKeys(), "fact evidenceKeys"); if (evidence.isEmpty()) throw new IllegalArgumentException("A formal requirement fact requires evidence"); requireDistinct(evidence, "fact evidenceKey"); evidence.forEach(item::requireSliceEvidence);
        item.requireDirectEvidence(fact.function(), "function", evidence); item.requireDirectEvidence(fact.roles(), "role", evidence); item.requireDirectEvidence(fact.triggerConditions(), "triggerCondition", evidence); item.requireDirectEvidence(fact.inputs(), "input", evidence); item.requireDirectEvidence(fact.businessRules(), "businessRule", evidence); item.requireDirectEvidence(fact.outputs(), "output", evidence); item.requireDirectEvidence(fact.permissions(), "permission", evidence); item.requireDirectEvidence(fact.stateChanges(), "stateChange", evidence); item.requireDirectEvidence(fact.exceptionHandling(), "exceptionHandling", evidence); item.requireDirectEvidence(fact.externalDependencies(), "externalDependency", evidence);
    }

    private static void validateFinding(WorkItem item, ReviewFinding finding) {
        required(finding.findingKey(), "findingKey"); ReaderFacingTextPolicy.requireSafe(finding.issueType(), "issueType"); ReaderFacingTextPolicy.requireSafe(finding.description(), "description"); ReaderFacingTextPolicy.requireSafe(finding.testDesignImpact(), "testDesignImpact"); ReaderFacingTextPolicy.requireSafe(finding.currentProjectRecommendation(), "currentProjectRecommendation"); ReaderFacingTextPolicy.requireSafe(finding.designCenterGuidelineRecommendation(), "designCenterGuidelineRecommendation"); if (finding.handlingLevel() == null) throw new IllegalArgumentException("handlingLevel must not be null");
        List<String> evidence = requiredList(finding.evidenceKeys(), "finding evidenceKeys"); requireDistinct(evidence, "finding evidenceKey"); evidence.forEach(item::requireSliceEvidence);
        if (finding.rootCauseKind() == null) {
            throw new IllegalArgumentException("review finding must retain frozen root-cause proof");
        }
        AffectedScope scope = Objects.requireNonNull(finding.affectedScope(), "affectedScope must not be null"); BadSourceExample bad = Objects.requireNonNull(finding.badSourceExample(), "badSourceExample must not be null"); ProposedGoodExample proposal = Objects.requireNonNull(finding.proposedGoodExample(), "proposedGoodExample must not be null");
        List<String> units = requiredList(scope.unitKeys(), "affectedScope.unitKeys"); if (units.isEmpty()) throw new IllegalArgumentException("affectedScope.unitKeys must not be empty"); requireSubset(units, evidence, "affectedScope unit");
        requireChinese(scope.summary(), "affectedScope.summary"); requireChinese(finding.issueType(), "issueType"); requireChinese(finding.description(), "description"); requireChinese(finding.testDesignImpact(), "testDesignImpact"); requireChinese(finding.currentProjectRecommendation(), "currentProjectRecommendation"); requireChinese(finding.designCenterGuidelineRecommendation(), "designCenterGuidelineRecommendation");
        if (!evidence.contains(bad.evidenceKey())) throw new IllegalArgumentException("bad source evidence must belong to finding evidence"); item.requireSourceQuote(bad.evidenceKey(), bad.quote());
        if (proposal.status() != ProposalStatus.PENDING_CONFIRMATION || !proposal.text().contains("待需求方确认")) throw new IllegalArgumentException("proposed good example must remain pending_confirmation and explicitly await confirmation"); requireChinese(proposal.text(), "proposedGoodExample.text");
    }

    private static void requireChinese(String value, String field) { required(value, field); if (value.codePoints().noneMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) throw new IllegalArgumentException(field + " must contain Chinese analysis"); }
    private static void requireSubset(List<String> values, List<String> expected, String field) { for (String value : values) if (!expected.contains(required(value, field))) throw new IllegalArgumentException(field + " is outside finding evidence"); }
    private static void requireDistinct(List<String> values, String field) { Set<String> distinct = new HashSet<>(); for (String value : values) if (!distinct.add(required(value, field))) throw new IllegalArgumentException(field + " must be unique"); }
    private static <T> List<T> requiredList(List<T> values, String field) { if (values == null) throw new IllegalArgumentException(field + " must not be null"); return List.copyOf(values); }
    private static String required(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank"); return value; }
    private static String normalize(String value) { return Normalizer.normalize(required(value, "grounding text"), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip(); }

    /** Frozen material coordinates and parsed-unit text for exactly one review invocation. */
    public record WorkItem(StructuredValidationRegistry registry, String materialKey, String contentTypeKey, List<String> allowedEvidenceKeys, Map<String, String> evidenceTexts) {
        public WorkItem { registry = Objects.requireNonNull(registry, "registry must not be null"); required(materialKey, "materialKey"); if (!("requirements_spec".equals(contentTypeKey) || "work_order_plan".equals(contentTypeKey) || "prototype".equals(contentTypeKey) || "requirement_list".equals(contentTypeKey))) throw new IllegalArgumentException("Unsupported requirement material content type"); registry.require(StructuredKeyType.MATERIAL, materialKey); List<String> evidence = requiredList(allowedEvidenceKeys, "allowedEvidenceKeys"); if (evidence.isEmpty()) throw new IllegalArgumentException("allowedEvidenceKeys must not be empty"); requireDistinct(evidence, "allowedEvidenceKey"); for (String key : evidence) registry.requireEvidence(key, materialKey); allowedEvidenceKeys = evidence; Map<String,String> supplied = Objects.requireNonNull(evidenceTexts, "evidenceTexts must not be null"); if (!supplied.keySet().equals(new LinkedHashSet<>(evidence))) throw new IllegalArgumentException("evidenceTexts must exactly match allowedEvidenceKeys"); Map<String,String> checked = new LinkedHashMap<>(); for (String key : evidence) checked.put(key, required(supplied.get(key), "evidenceText")); evidenceTexts = Collections.unmodifiableMap(checked); }
        private void requireSliceEvidence(String key) { if (!allowedEvidenceKeys.contains(key)) throw new IllegalArgumentException("Review evidence is outside the current parsed-unit slice"); registry.requireEvidence(key, materialKey); }
        private void requireDirectEvidence(String value, String field, List<String> cited) { String claim = normalize(value); boolean supported = cited.stream().map(evidenceTexts::get).map(RequirementMaterialReviewValidator::normalize).anyMatch(source -> source.contains(claim)); if (!supported) throw new IllegalArgumentException(field + " is not directly supported by its cited parsed-unit evidence"); }
        private void requireDirectEvidence(List<String> values, String field, List<String> cited) { values.forEach(value -> requireDirectEvidence(value, field, cited)); }
        private void requireSourceQuote(String key, String quote) { required(quote, "badSourceExample.quote"); if (!normalize(evidenceTexts.get(key)).contains(normalize(quote))) throw new IllegalArgumentException("bad source quote must be a continuous fragment of its cited parsed unit"); }
        boolean supplementaryMaterial() { return "prototype".equals(contentTypeKey) || "requirement_list".equals(contentTypeKey); }
    }

    /** Validated facts and findings accepted or rejected as one unit. [Req-ID]: REQ-FTG-007 */
    public record Result(List<RequirementFact> requirementFacts, List<ReviewFinding> reviewFindings) { }

    /** One formal fact whose every nonempty item is directly supported by cited units. [Req-ID]: REQ-FTG-007 */
    public record RequirementFact(String factKey, String function, List<String> roles, List<String> triggerConditions, List<String> inputs, List<String> businessRules, List<String> outputs, List<String> permissions, List<String> stateChanges, List<String> exceptionHandling, List<String> externalDependencies, List<String> evidenceKeys) { }

    /** One bounded root-cause finding and its reader-facing examples. [Req-ID]: REQ-FTG-007 */
    public record ReviewFinding(String findingKey, RootCauseKind rootCauseKind, String issueType, AffectedScope affectedScope, BadSourceExample badSourceExample, ProposedGoodExample proposedGoodExample, String description, List<String> evidenceKeys, String testDesignImpact, String currentProjectRecommendation, String designCenterGuidelineRecommendation, HandlingLevel handlingLevel) { public ReviewFinding(String findingKey, String issueType, String description, List<String> evidenceKeys, String testDesignImpact, String currentProjectRecommendation, String designCenterGuidelineRecommendation, HandlingLevel handlingLevel) { this(findingKey, null, issueType, null, null, null, description, evidenceKeys, testDesignImpact, currentProjectRecommendation, designCenterGuidelineRecommendation, handlingLevel); } }

    /** Units and Chinese summary affected by one root cause. [Req-ID]: REQ-FTG-007 */
    public record AffectedScope(List<String> unitKeys, String summary) { }

    /** Actual continuous source quote retained as the bad example. [Req-ID]: REQ-FTG-007 */
    public record BadSourceExample(String evidenceKey, String quote) { }

    /** Suggested wording that remains explicitly pending confirmation. [Req-ID]: REQ-FTG-007 */
    public record ProposedGoodExample(ProposalStatus status, String text) { }

    /** Frozen requirement-review root-cause taxonomy. [Req-ID]: REQ-FTG-007 */
    public enum RootCauseKind { MISSING_DOCUMENT_TRACEABILITY, MISSING_FUNCTION_SCOPE, MISSING_ROLE_PERMISSION_MATRIX, MISSING_PROCESS_OR_STATE, MISSING_INPUT_OR_DATA_DICTIONARY, MISSING_BUSINESS_RULE, MISSING_OUTPUT, MISSING_EXCEPTION_HANDLING, MISSING_EXTERNAL_DEPENDENCY, MISSING_SECURITY_OR_AUDIT, MISSING_ENVIRONMENT_OR_CONFIGURATION, CONFLICTING_REQUIREMENT, AMBIGUOUS_REQUIREMENT }

    /** A proposed good example is never a confirmed requirement. [Req-ID]: REQ-FTG-007 */
    public enum ProposalStatus { PENDING_CONFIRMATION }

    /** Reader-facing severity used by the existing delivery model. [Req-ID]: REQ-FTG-007 */
    public enum HandlingLevel { BLOCKING, CONTINUE_INCOMPLETE, IMPROVEMENT }
}
