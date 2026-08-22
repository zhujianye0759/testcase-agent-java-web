package com.testcaseagent.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem, result));
        assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("supplementary"));
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

    private static RequirementMaterialReviewValidator.RequirementFact fact() {
        return new RequirementMaterialReviewValidator.RequirementFact("fact-1", "submit application", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of("evidence-1"));
    }

    private static RequirementMaterialReviewValidator.ReviewFinding finding() {
        return new RequirementMaterialReviewValidator.ReviewFinding("finding-1", "ambiguous", "description", List.of("evidence-1"),
                "impact", "project", "center", RequirementMaterialReviewValidator.HandlingLevel.CONTINUE_INCOMPLETE);
    }
}
