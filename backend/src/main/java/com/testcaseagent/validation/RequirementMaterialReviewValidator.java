package com.testcaseagent.validation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.testcaseagent.validation.StructuredValidationFailure.Code;

/** Validates one bounded requirement-review result without accepting partial model output. [Req-ID]: REQ-FTG-005, REQ-FTG-007 */
public final class RequirementMaterialReviewValidator {
    /** Validates facts, actual quoted source, pending proposals, and bounded duplicate root causes. */
    public void validate(WorkItem workItem, Result result) {
        WorkItem item = Objects.requireNonNull(workItem, "workItem must not be null");
        Result checked = Objects.requireNonNull(result, "result must not be null");
        List<RequirementFact> facts = resultList(checked.requirementFacts(), "$.requirement_facts");
        List<ReviewFinding> findings = resultList(checked.reviewFindings(), "$.review_findings");
        if (facts.size() > 200 || findings.size() > 200 || facts.size() + findings.size() < 1) {
            reject(Code.REVIEW_RESULT_INVALID, "$");
        }
        Set<RootCauseKind> roots = new HashSet<>();
        for (int index = 0; index < facts.size(); index++) {
            String path = "$.requirement_facts[" + index + "]";
            if (facts.get(index) == null) reject(Code.REVIEW_FIELD_REQUIRED, path);
        }
        for (int index = 0; index < findings.size(); index++) {
            String path = "$.review_findings[" + index + "]";
            if (findings.get(index) == null) reject(Code.REVIEW_FIELD_REQUIRED, path);
        }
        requireResultDistinct(facts.stream().map(RequirementFact::factKey).toList(), "$.requirement_facts");
        requireResultDistinct(findings.stream().map(ReviewFinding::findingKey).toList(), "$.review_findings");
        for (int index = 0; index < facts.size(); index++) {
            validateFact(item, facts.get(index), "$.requirement_facts[" + index + "]");
        }
        for (int index = 0; index < findings.size(); index++) {
            String path = "$.review_findings[" + index + "]";
            ReviewFinding finding = findings.get(index);
            if (finding.rootCauseKind() != null && !roots.add(finding.rootCauseKind())) {
                reject(Code.REVIEW_FINDING_ROOT_CAUSE_DUPLICATE, path + ".root_cause_kind");
            }
            validateFinding(item, finding, path);
        }
    }

    private static void validateFact(WorkItem item, RequirementFact fact, String path) {
        resultRequired(fact.factKey(), path + ".fact_key");
        resultSafe(fact.function(), path + ".function");
        List<String> roles = resultSafeItems(fact.roles(), path + ".roles");
        List<String> triggers = resultSafeItems(fact.triggerConditions(), path + ".trigger_conditions");
        List<String> inputs = resultSafeItems(fact.inputs(), path + ".inputs");
        List<String> rules = resultSafeItems(fact.businessRules(), path + ".business_rules");
        List<String> outputs = resultSafeItems(fact.outputs(), path + ".outputs");
        List<String> permissions = resultSafeItems(fact.permissions(), path + ".permissions");
        List<String> states = resultSafeItems(fact.stateChanges(), path + ".state_changes");
        List<String> exceptions = resultSafeItems(fact.exceptionHandling(), path + ".exception_handling");
        List<String> dependencies = resultSafeItems(fact.externalDependencies(), path + ".external_dependencies");
        if (item.supplementaryMaterial()) reject(Code.REVIEW_FACT_SUPPLEMENTARY_SOURCE, path);
        List<String> evidence = resultList(fact.evidenceKeys(), path + ".evidence_keys");
        if (evidence.isEmpty()) reject(Code.REVIEW_FACT_EVIDENCE_REQUIRED, path + ".evidence_keys");
        requireResultDistinct(evidence, path + ".evidence_keys");
        for (int index = 0; index < evidence.size(); index++) {
            item.requireSliceEvidence(evidence.get(index), path + ".evidence_keys[" + index + "]");
        }
        item.requireDirectEvidence(fact.function(), path + ".function", evidence);
        item.requireDirectEvidence(roles, path + ".roles", evidence);
        item.requireDirectEvidence(triggers, path + ".trigger_conditions", evidence);
        item.requireDirectEvidence(inputs, path + ".inputs", evidence);
        item.requireDirectEvidence(rules, path + ".business_rules", evidence);
        item.requireDirectEvidence(outputs, path + ".outputs", evidence);
        item.requireDirectEvidence(permissions, path + ".permissions", evidence);
        item.requireDirectEvidence(states, path + ".state_changes", evidence);
        item.requireDirectEvidence(exceptions, path + ".exception_handling", evidence);
        item.requireDirectEvidence(dependencies, path + ".external_dependencies", evidence);
    }

    private static void validateFinding(WorkItem item, ReviewFinding finding, String path) {
        resultRequired(finding.findingKey(), path + ".finding_key");
        resultSafe(finding.issueType(), path + ".issue_type");
        resultSafe(finding.description(), path + ".description");
        resultSafe(finding.testDesignImpact(), path + ".test_design_impact");
        resultSafe(finding.currentProjectRecommendation(), path + ".current_project_recommendation");
        resultSafe(finding.designCenterGuidelineRecommendation(), path + ".design_center_guideline_recommendation");
        if (finding.handlingLevel() == null) reject(Code.REVIEW_FINDING_HANDLING_LEVEL_REQUIRED, path + ".handling_level");
        List<String> evidence = resultList(finding.evidenceKeys(), path + ".evidence_keys");
        requireResultDistinct(evidence, path + ".evidence_keys");
        for (int index = 0; index < evidence.size(); index++) {
            item.requireSliceEvidence(evidence.get(index), path + ".evidence_keys[" + index + "]");
        }
        if (finding.rootCauseKind() == null) reject(Code.REVIEW_FINDING_ROOT_CAUSE_REQUIRED, path + ".root_cause_kind");
        if (finding.affectedScope() == null) reject(Code.REVIEW_FINDING_AFFECTED_SCOPE_INVALID, path + ".affected_scope");
        if (finding.badSourceExample() == null) reject(Code.REVIEW_FINDING_BAD_SOURCE_INVALID, path + ".bad_source_example");
        if (finding.proposedGoodExample() == null) reject(Code.REVIEW_FINDING_PENDING_PROPOSAL_INVALID, path + ".proposed_good_example");
        AffectedScope scope = finding.affectedScope();
        BadSourceExample bad = finding.badSourceExample();
        ProposedGoodExample proposal = finding.proposedGoodExample();
        List<String> units = resultList(scope.unitKeys(), path + ".affected_scope.unit_keys");
        if (units.isEmpty()) reject(Code.REVIEW_FINDING_AFFECTED_SCOPE_INVALID, path + ".affected_scope.unit_keys");
        requireResultSubset(units, evidence, path + ".affected_scope.unit_keys");
        requireResultChinese(scope.summary(), path + ".affected_scope.summary");
        requireResultChinese(finding.issueType(), path + ".issue_type");
        requireResultChinese(finding.description(), path + ".description");
        requireResultChinese(finding.testDesignImpact(), path + ".test_design_impact");
        requireResultChinese(finding.currentProjectRecommendation(), path + ".current_project_recommendation");
        requireResultChinese(finding.designCenterGuidelineRecommendation(), path + ".design_center_guideline_recommendation");
        if (!evidence.contains(bad.evidenceKey())) reject(Code.REVIEW_FINDING_BAD_SOURCE_INVALID, path + ".bad_source_example.evidence_key");
        item.requireSourceQuote(bad.evidenceKey(), bad.quote(), path + ".bad_source_example.quote");
        if (proposal.status() != ProposalStatus.PENDING_CONFIRMATION || proposal.text() == null
                || !proposal.text().contains("待需求方确认")) {
            reject(Code.REVIEW_FINDING_PENDING_PROPOSAL_INVALID, path + ".proposed_good_example");
        }
        requireResultChinese(proposal.text(), path + ".proposed_good_example.text");
    }

    private static String resultRequired(String value, String path) {
        if (value == null || value.isBlank()) reject(Code.REVIEW_FIELD_REQUIRED, path);
        return value;
    }

    private static String resultSafe(String value, String path) {
        resultRequired(value, path);
        try {
            return ReaderFacingTextPolicy.requireSafe(value, path);
        } catch (IllegalArgumentException exception) {
            reject(Code.REVIEW_READER_TEXT_UNSAFE, path);
            throw new AssertionError("unreachable");
        }
    }

    private static List<String> resultSafeItems(List<String> values, String path) {
        List<String> checked = resultList(values, path);
        for (int index = 0; index < checked.size(); index++) resultSafe(checked.get(index), path + "[" + index + "]");
        return checked;
    }

    private static void requireResultChinese(String value, String path) {
        resultRequired(value, path);
        if (value.codePoints().noneMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) {
            reject(Code.REVIEW_FINDING_CHINESE_ANALYSIS_REQUIRED, path);
        }
    }

    private static void requireResultSubset(List<String> values, List<String> expected, String path) {
        for (int index = 0; index < values.size(); index++) {
            String value = resultRequired(values.get(index), path + "[" + index + "]");
            if (!expected.contains(value)) reject(Code.REVIEW_FINDING_AFFECTED_SCOPE_INVALID, path + "[" + index + "]");
        }
    }

    private static void requireResultDistinct(List<String> values, String path) {
        Set<String> distinct = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String value = resultRequired(values.get(index), path + "[" + index + "]");
            if (!distinct.add(value)) reject(path.endsWith("evidence_keys") ? Code.REVIEW_EVIDENCE_DUPLICATE
                    : Code.REVIEW_KEY_DUPLICATE, path + "[" + index + "]");
        }
    }

    private static <T> List<T> resultList(List<T> values, String path) {
        if (values == null) reject(Code.REVIEW_FIELD_REQUIRED, path);
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static void reject(Code code, String path) {
        throw new StructuredValidationException(StructuredValidationFailure.of(code, path));
    }

    private static void requireDistinct(List<String> values, String field) { Set<String> distinct = new HashSet<>(); for (String value : values) if (!distinct.add(required(value, field))) throw new IllegalArgumentException(field + " must be unique"); }
    private static <T> List<T> requiredList(List<T> values, String field) { if (values == null) throw new IllegalArgumentException(field + " must not be null"); return List.copyOf(values); }
    private static String required(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank"); return value; }
    private static String normalizeFactGrounding(String value) {
        String normalized = Normalizer.normalize(required(value, "grounding text"), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder withoutLayoutWhitespace = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint))
                .forEach(withoutLayoutWhitespace::appendCodePoint);
        return withoutLayoutWhitespace.toString();
    }

    private static String normalizeSourceQuote(String value) {
        return Normalizer.normalize(required(value, "grounding text"), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }

    /** Frozen material coordinates and parsed-unit text for exactly one review invocation. */
    public record WorkItem(StructuredValidationRegistry registry, String materialKey, String contentTypeKey, List<String> allowedEvidenceKeys, Map<String, String> evidenceTexts) {
        public WorkItem { registry = Objects.requireNonNull(registry, "registry must not be null"); required(materialKey, "materialKey"); if (!("requirements_spec".equals(contentTypeKey) || "work_order_plan".equals(contentTypeKey) || "prototype".equals(contentTypeKey) || "requirement_list".equals(contentTypeKey))) throw new IllegalArgumentException("Unsupported requirement material content type"); registry.require(StructuredKeyType.MATERIAL, materialKey); List<String> evidence = requiredList(allowedEvidenceKeys, "allowedEvidenceKeys"); if (evidence.isEmpty()) throw new IllegalArgumentException("allowedEvidenceKeys must not be empty"); requireDistinct(evidence, "allowedEvidenceKey"); for (String key : evidence) registry.requireEvidence(key, materialKey); allowedEvidenceKeys = evidence; Map<String,String> supplied = Objects.requireNonNull(evidenceTexts, "evidenceTexts must not be null"); if (!supplied.keySet().equals(new LinkedHashSet<>(evidence))) throw new IllegalArgumentException("evidenceTexts must exactly match allowedEvidenceKeys"); Map<String,String> checked = new LinkedHashMap<>(); for (String key : evidence) checked.put(key, required(supplied.get(key), "evidenceText")); evidenceTexts = Collections.unmodifiableMap(checked); }
        private void requireSliceEvidence(String key, String path) { if (!allowedEvidenceKeys.contains(key)) reject(Code.REVIEW_EVIDENCE_OUT_OF_SLICE, path); try { registry.requireEvidence(key, materialKey); } catch (IllegalArgumentException exception) { reject(Code.REVIEW_EVIDENCE_OUT_OF_SLICE, path); } }
        private void requireDirectEvidence(String value, String path, List<String> cited) { String claim = normalizeFactGrounding(value); boolean supported = cited.stream().map(evidenceTexts::get).map(RequirementMaterialReviewValidator::normalizeFactGrounding).anyMatch(source -> source.contains(claim)); if (!supported) reject(Code.REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED, path); }
        private void requireDirectEvidence(List<String> values, String path, List<String> cited) { for (int index = 0; index < values.size(); index++) requireDirectEvidence(values.get(index), path + "[" + index + "]", cited); }
        private void requireSourceQuote(String key, String quote, String path) { if (quote == null || quote.isBlank()) reject(Code.REVIEW_FINDING_BAD_SOURCE_INVALID, path); if (!normalizeSourceQuote(evidenceTexts.get(key)).contains(normalizeSourceQuote(quote))) reject(Code.REVIEW_FINDING_BAD_SOURCE_INVALID, path); }
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
