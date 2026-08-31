package com.testcaseagent.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests requirement-material result business acceptance. [Req-ID]: REQ-STG-001, REQ-STG-002, REQ-FTG-005 */
class RequirementMaterialReviewValidatorTest {
    private final RequirementMaterialReviewValidator validator = new RequirementMaterialReviewValidator();

    @Test
    void acceptsFindingsOnlyForSupplementaryPrototypeMaterial() {
        RequirementMaterialReviewValidator.WorkItem workItem = workItem("prototype");
        RequirementMaterialReviewValidator.Result result = new RequirementMaterialReviewValidator.Result(List.of(), List.of(finding()));

        assertDoesNotThrow(() -> validator.validate(workItem, result));
    }

    @Test
    void rejectsFormalFactsSupportedOnlyBySupplementaryMaterial() {
        RequirementMaterialReviewValidator.WorkItem workItem = workItem("prototype");
        RequirementMaterialReviewValidator.Result result = new RequirementMaterialReviewValidator.Result(List.of(fact()), List.of());

        StructuredValidationException failure = assertThrows(StructuredValidationException.class,
                () -> validator.validate(workItem, result));
        assertThat(failure.failure().code()).isEqualTo("REVIEW_FACT_SUPPLEMENTARY_SOURCE");
        assertThat(failure.failure().path()).isEqualTo("$.requirement_facts[0]");
    }

    @Test
    void rejectsEmptyResultAndOutOfMaterialEvidence() {
        RequirementMaterialReviewValidator.WorkItem workItem = workItem("requirements_spec");
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(workItem, new RequirementMaterialReviewValidator.Result(List.of(), List.of())));
        RequirementMaterialReviewValidator.ReviewFinding outsideEvidence = new RequirementMaterialReviewValidator.ReviewFinding(
                "finding-1", "ambiguous", "description", List.of("evidence-other"), "impact", "project", "center",
                RequirementMaterialReviewValidator.HandlingLevel.BLOCKING);
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(workItem, new RequirementMaterialReviewValidator.Result(List.of(), List.of(outsideEvidence))));
    }

    @Test
    void rejectsInternalPlaceholdersInReaderFacingFindingText() {
        RequirementMaterialReviewValidator.ReviewFinding unsafe = new RequirementMaterialReviewValidator.ReviewFinding(
                "finding-1", "异常处理缺失", "账号被禁用<internal-path>", List.of("evidence-1"),
                "影响异常分支设计", "补齐失败场景", "建立审查准则",
                RequirementMaterialReviewValidator.HandlingLevel.CONTINUE_INCOMPLETE);

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem("requirements_spec"),
                new RequirementMaterialReviewValidator.Result(List.of(), List.of(unsafe))));
    }

    @Test
    void rejectsInternalStableKeysInRequirementFactNarration() {
        RequirementMaterialReviewValidator.RequirementFact unsafe = new RequirementMaterialReviewValidator.RequirementFact(
                "fact-1", "订单提交", List.of("角色 fact-1724e7041424efc97c0cc3dc53109f39"), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of("evidence-1"));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem("requirements_spec"),
                new RequirementMaterialReviewValidator.Result(List.of(unsafe), List.of())));
    }

    /** [Req-ID]: REQ-SMS-001 */
    @Test
    void acceptsFindingsButRejectsFormalFactsForRequirementListMaterial() {
        RequirementMaterialReviewValidator.WorkItem workItem = workItem("requirement_list");

        assertDoesNotThrow(() -> validator.validate(workItem,
                new RequirementMaterialReviewValidator.Result(List.of(), List.of(finding()))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new RequirementMaterialReviewValidator.Result(List.of(fact()), List.of())));
    }

    /** [Req-ID]: REQ-FTG-005 */
    @Test
    void requiresAnExactNonblankParsedUnitTextForEveryAllowedEvidenceKey() {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true));

        assertThrows(IllegalArgumentException.class, () -> new RequirementMaterialReviewValidator.WorkItem(
                registry, "material-1", "requirements_spec", List.of("evidence-1"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RequirementMaterialReviewValidator.WorkItem(
                registry, "material-1", "requirements_spec", List.of("evidence-1"), Map.of("evidence-1", " ")));
        assertThrows(IllegalArgumentException.class, () -> new RequirementMaterialReviewValidator.WorkItem(
                registry, "material-1", "requirements_spec", List.of("evidence-1"),
                Map.of("evidence-1", "submit application", "evidence-extra", "other text")));
    }

    /** [Req-ID]: REQ-FTG-005 */
    @Test
    void rejectsARequirementFactAssembledAcrossTwoParsedUnits() {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true))
                .registerEvidence(new StructuredEvidence("evidence-2", "task-1", "material-1", false, false, true));
        RequirementMaterialReviewValidator.WorkItem item = new RequirementMaterialReviewValidator.WorkItem(
                registry, "material-1", "requirements_spec", List.of("evidence-1", "evidence-2"),
                Map.of("evidence-1", "用户提交账号", "evidence-2", "和正确密码"));
        RequirementMaterialReviewValidator.RequirementFact stitched = new RequirementMaterialReviewValidator.RequirementFact(
                "fact-1", "用户", List.of(), List.of(), List.of(), List.of("用户提交账号和正确密码"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of("evidence-1", "evidence-2"));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                item, new RequirementMaterialReviewValidator.Result(List.of(stitched), List.of())));
    }

    /** [Req-ID]: REQ-FTG-005 */
    @Test
    void rejectsRequirementFactsThatOnlyMatchAfterPunctuationIsErased() {
        RequirementMaterialReviewValidator.WorkItem item = new RequirementMaterialReviewValidator.WorkItem(
                registryForPunctuation(), "material-1", "requirements_spec", List.of("evidence-1"),
                Map.of("evidence-1", "账号；登录"));
        RequirementMaterialReviewValidator.RequirementFact stitched = new RequirementMaterialReviewValidator.RequirementFact(
                "fact-1", "账号登录", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of("evidence-1"));

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(item, new RequirementMaterialReviewValidator.Result(List.of(stitched), List.of())));
    }

    /** [Req-ID]: REQ-FTG-005 */
    @Test
    void acceptsRequirementFactSplitByPdfLayoutWhitespace() {
        RequirementMaterialReviewValidator.WorkItem item = workItemWithEvidence(
                Map.of("evidence-1", "系统支持电表智\n能\u3000验收。"));
        RequirementMaterialReviewValidator.RequirementFact fact = factWithFunction("电表智能验收");

        assertDoesNotThrow(() -> validator.validate(
                item, new RequirementMaterialReviewValidator.Result(List.of(fact), List.of())));
    }

    /** [Req-ID]: REQ-FTG-005 */
    @Test
    void acceptsRequirementFactAcrossOrdinarySpaceDifferences() {
        RequirementMaterialReviewValidator.WorkItem item = workItemWithEvidence(
                Map.of("evidence-1", "系统支持 电表 智能 验收。"));
        RequirementMaterialReviewValidator.RequirementFact fact = factWithFunction("电表智能验收");

        assertDoesNotThrow(() -> validator.validate(
                item, new RequirementMaterialReviewValidator.Result(List.of(fact), List.of())));
    }

    /** [Req-ID]: REQ-FTG-005 */
    @Test
    void rejectsBadSourceQuoteWhenItsInternalSpaceIsMissing() {
        RequirementMaterialReviewValidator.WorkItem item = workItemWithEvidence(
                Map.of("evidence-1", "材料说明账号 登录入口。"));
        RequirementMaterialReviewValidator.ReviewFinding finding = findingWithBadSourceQuote("账号登录入口");

        StructuredValidationException failure = assertThrows(StructuredValidationException.class,
                () -> validator.validate(item,
                        new RequirementMaterialReviewValidator.Result(List.of(), List.of(finding))));

        assertThat(failure.failure().code()).isEqualTo("REVIEW_FINDING_BAD_SOURCE_INVALID");
        assertThat(failure.failure().path()).isEqualTo("$.review_findings[0].bad_source_example.quote");
    }

    /** [Req-ID]: REQ-FSC-007 */
    @Test
    void reportsTheUnsupportedFactFieldWithoutRetainingRejectedText() {
        RequirementMaterialReviewValidator.RequirementFact unsupported =
                new RequirementMaterialReviewValidator.RequirementFact(
                        "fact-1", "submit application", List.of(), List.of(), List.of(),
                        List.of("password=do-not-persist"), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of("evidence-1"));

        StructuredValidationException failure = assertThrows(StructuredValidationException.class,
                () -> validator.validate(workItem("requirements_spec"),
                        new RequirementMaterialReviewValidator.Result(List.of(unsupported), List.of())));

        assertThat(failure.failure().code()).isEqualTo("REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertThat(failure.failure().path()).isEqualTo("$.requirement_facts[0].business_rules[0]");
        assertThat(failure.failure().message()).isEqualTo("正式需求事实未在引用材料单元中直接出现");
        assertThat(failure.failure().toString()).doesNotContain("do-not-persist", "submit application");
    }

    @Test
    void reportsMissingRequiredTextWithoutMisclassifyingItAsUnsafe() {
        RequirementMaterialReviewValidator.RequirementFact missingFunction =
                new RequirementMaterialReviewValidator.RequirementFact(
                        "fact-1", null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of("evidence-1"));

        StructuredValidationException failure = assertThrows(StructuredValidationException.class,
                () -> validator.validate(workItem("requirements_spec"),
                        new RequirementMaterialReviewValidator.Result(List.of(missingFunction), List.of())));

        assertThat(failure.failure().code()).isEqualTo("REVIEW_FIELD_REQUIRED");
        assertThat(failure.failure().path()).isEqualTo("$.requirement_facts[0].function");
    }

    /** [Req-ID]: REQ-FTG-005 */
    @Test
    void acceptsACompletePendingChineseFindingAndRejectsItsDuplicateRootCause() {
        RequirementMaterialReviewValidator.ReviewFinding finding = new RequirementMaterialReviewValidator.ReviewFinding(
                "finding-1", RequirementMaterialReviewValidator.RootCauseKind.MISSING_EXCEPTION_HANDLING,
                "异常处理缺失", new RequirementMaterialReviewValidator.AffectedScope(List.of("evidence-1"), "当前订单提交场景"),
                new RequirementMaterialReviewValidator.BadSourceExample("evidence-1", "订单提交"),
                new RequirementMaterialReviewValidator.ProposedGoodExample(
                        RequirementMaterialReviewValidator.ProposalStatus.PENDING_CONFIRMATION,
                        "建议需求写法（待需求方确认）：补充订单提交失败时的系统行为。"),
                "材料未说明订单提交失败时的处理。", List.of("evidence-1"), "无法形成失败场景的正式预期。",
                "请当前材料负责人补充失败处理。", "建议需求规格同时说明成功和失败结果。",
                RequirementMaterialReviewValidator.HandlingLevel.CONTINUE_INCOMPLETE);

        assertDoesNotThrow(() -> validator.validate(workItem("requirements_spec"),
                new RequirementMaterialReviewValidator.Result(List.of(), List.of(finding))));
        RequirementMaterialReviewValidator.ReviewFinding duplicate = new RequirementMaterialReviewValidator.ReviewFinding(
                "finding-2", finding.rootCauseKind(), finding.issueType(), finding.affectedScope(), finding.badSourceExample(),
                finding.proposedGoodExample(), finding.description(), finding.evidenceKeys(), finding.testDesignImpact(),
                finding.currentProjectRecommendation(), finding.designCenterGuidelineRecommendation(), finding.handlingLevel());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem("requirements_spec"),
                new RequirementMaterialReviewValidator.Result(List.of(), List.of(finding, duplicate))));
    }

    /** [Req-ID]: REQ-FSC-004 */
    @Test
    void rejectsLegacyFindingWithoutTheFrozenRootCauseProof() {
        RequirementMaterialReviewValidator.ReviewFinding legacy = new RequirementMaterialReviewValidator.ReviewFinding(
                "finding-legacy", "异常处理缺失", "材料未说明失败处理。", List.of("evidence-1"),
                "无法形成异常场景。", "请补充失败处理。", "建议补充异常结果。",
                RequirementMaterialReviewValidator.HandlingLevel.CONTINUE_INCOMPLETE);

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem("requirements_spec"),
                new RequirementMaterialReviewValidator.Result(List.of(), List.of(legacy))));
    }

    private static RequirementMaterialReviewValidator.WorkItem workItem(String contentType) {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true))
                .registerEvidence(new StructuredEvidence("evidence-other", "task-1", "material-2", false, false, true));
        return new RequirementMaterialReviewValidator.WorkItem(registry, "material-1", contentType,
                List.of("evidence-1"), Map.of("evidence-1", "submit application 订单提交"));
    }

    private static StructuredValidationRegistry registryForPunctuation() {
        return StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true));
    }

    private static RequirementMaterialReviewValidator.WorkItem workItemWithEvidence(Map<String, String> evidenceTexts) {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-1");
        evidenceTexts.keySet().forEach(key -> registry.registerEvidence(
                new StructuredEvidence(key, "task-1", "material-1", false, false, true)));
        return new RequirementMaterialReviewValidator.WorkItem(registry, "material-1", "requirements_spec",
                evidenceTexts.keySet().stream().toList(), evidenceTexts);
    }

    private static RequirementMaterialReviewValidator.RequirementFact factWithFunction(String function) {
        return new RequirementMaterialReviewValidator.RequirementFact(
                "fact-1", function, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of("evidence-1"));
    }

    private static RequirementMaterialReviewValidator.ReviewFinding findingWithBadSourceQuote(String quote) {
        return new RequirementMaterialReviewValidator.ReviewFinding(
                "finding-1", RequirementMaterialReviewValidator.RootCauseKind.AMBIGUOUS_REQUIREMENT,
                "需求表述存在歧义", new RequirementMaterialReviewValidator.AffectedScope(
                        List.of("evidence-1"), "账号登录入口范围"),
                new RequirementMaterialReviewValidator.BadSourceExample("evidence-1", quote),
                new RequirementMaterialReviewValidator.ProposedGoodExample(
                        RequirementMaterialReviewValidator.ProposalStatus.PENDING_CONFIRMATION,
                        "建议明确账号登录入口（待需求方确认）。"),
                "材料中的账号登录入口表述存在歧义。", List.of("evidence-1"),
                "影响账号登录入口测试设计。", "请确认账号登录入口。", "建议明确账号登录入口规则。",
                RequirementMaterialReviewValidator.HandlingLevel.CONTINUE_INCOMPLETE);
    }

    private static RequirementMaterialReviewValidator.RequirementFact fact() {
        return new RequirementMaterialReviewValidator.RequirementFact("fact-1", "submit application", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of("evidence-1"));
    }

    private static RequirementMaterialReviewValidator.ReviewFinding finding() {
        return new RequirementMaterialReviewValidator.ReviewFinding(
                "finding-1", RequirementMaterialReviewValidator.RootCauseKind.AMBIGUOUS_REQUIREMENT,
                "需求表述存在歧义", new RequirementMaterialReviewValidator.AffectedScope(List.of("evidence-1"), "订单提交范围"),
                new RequirementMaterialReviewValidator.BadSourceExample("evidence-1", "订单提交"),
                new RequirementMaterialReviewValidator.ProposedGoodExample(
                        RequirementMaterialReviewValidator.ProposalStatus.PENDING_CONFIRMATION,
                        "建议补充订单提交的明确条件（待需求方确认）。"),
                "材料未明确订单提交的执行条件。", List.of("evidence-1"), "无法形成可执行的正式测试场景。",
                "请补充当前项目的执行条件。", "建议明确需求条件。",
                RequirementMaterialReviewValidator.HandlingLevel.CONTINUE_INCOMPLETE);
    }
}
