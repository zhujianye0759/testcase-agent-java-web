package com.testcaseagent.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests test-point to testcase acceptance, grounding, and formal-coverage rules. [Req-ID]: REQ-STG-001, REQ-STG-004, REQ-STG-005, REQ-FTG-001, REQ-FTG-002 */
class FunctionalTestcaseResultValidatorTest {
    private final FunctionalTestcaseResultValidator validator = new FunctionalTestcaseResultValidator();

    @Test
    void formalPointRequiresAtLeastOneFormalCaseAndDoesNotRequireAFixedCount() {
        FunctionalTestcaseResultValidator.WorkItem workItem = workItem(FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT);
        FunctionalTestcaseResultValidator.Result result = result("function-1", "point-1", FunctionalTestcaseResultValidator.CaseStatus.FORMAL);

        FunctionalTestcaseResultValidator.ValidationOutcome outcome = validator.validate(workItem, result);
        assertTrue(outcome.formalCoverageSatisfied());
    }

    @Test
    void rejectsFormalCasesWithoutACompleteRequirementAndEvidenceClosure() {
        FunctionalTestcaseResultValidator.WorkItem workItem = workItem(FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT);
        FunctionalTestcaseResultValidator.Testcase noFacts = new FunctionalTestcaseResultValidator.Testcase("case-1", "title", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected")), List.of(), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.FORMAL, List.of());
        FunctionalTestcaseResultValidator.Testcase noEvidence = new FunctionalTestcaseResultValidator.Testcase("case-2", "title", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected")), List.of("fact-1"), List.of(),
                FunctionalTestcaseResultValidator.CaseStatus.FORMAL, List.of());

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(noFacts))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(noEvidence))));
    }

    @Test
    void formalRequirementPointDoesNotTreatAPendingCandidateAsFormalCoverage() {
        FunctionalTestcaseResultValidator.WorkItem workItem = workItem(FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT);
        FunctionalTestcaseResultValidator.Testcase pending = new FunctionalTestcaseResultValidator.Testcase("case-1", "title", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected")), List.of("fact-1"), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.PENDING_CONFIRMATION, List.of("Awaiting confirmation"));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(pending))));
    }

    @Test
    void anyPendingCandidateRequiresNonblankMissingInformationEvenOnAFormalPoint() {
        FunctionalTestcaseResultValidator.WorkItem workItem = workItem(FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT);
        FunctionalTestcaseResultValidator.Testcase formal = new FunctionalTestcaseResultValidator.Testcase("case-formal", "formal", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected")), List.of("fact-1"), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.FORMAL, List.of());
        FunctionalTestcaseResultValidator.Testcase unexplainedPending = new FunctionalTestcaseResultValidator.Testcase("case-pending", "pending", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected")), List.of("fact-1"), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.PENDING_CONFIRMATION, List.of("  "));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(formal, unexplainedPending))));
    }

    @Test
    void generalExperienceCannotBeSilentlyPromotedToFormal() {
        FunctionalTestcaseResultValidator.WorkItem workItem = workItem(FunctionalTestcaseResultValidator.Basis.GENERAL_EXPERIENCE);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                result("function-1", "point-1", FunctionalTestcaseResultValidator.CaseStatus.FORMAL)));

        FunctionalTestcaseResultValidator.ValidationOutcome outcome = validator.validate(workItem,
                result("function-1", "point-1", FunctionalTestcaseResultValidator.CaseStatus.PENDING_CONFIRMATION));
        assertFalse(outcome.formalCoverageSatisfied());
    }

    @Test
    void generalExperienceRequiresExplicitMissingInformationForThePointAndEveryCandidateCase() {
        StructuredValidationRegistry registry = registry();
        assertThrows(IllegalArgumentException.class, () -> new FunctionalTestcaseResultValidator.WorkItem(registry, "function-1", "Function one", "point-1", "boundary test",
                FunctionalTestcaseResultValidator.TestPointType.BOUNDARY_VALUE, FunctionalTestcaseResultValidator.Basis.GENERAL_EXPERIENCE,
                List.of("fact-1"), List.of("evidence-1"), List.of(), List.of()));

        FunctionalTestcaseResultValidator.WorkItem workItem = workItem(FunctionalTestcaseResultValidator.Basis.GENERAL_EXPERIENCE);
        FunctionalTestcaseResultValidator.Testcase unexplained = new FunctionalTestcaseResultValidator.Testcase("case-1", "title", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected")), List.of("fact-1"), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.PENDING_CONFIRMATION, List.of());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(unexplained))));
    }

    @Test
    void rejectsMismatchedEchoAndNonConsecutiveSteps() {
        FunctionalTestcaseResultValidator.WorkItem workItem = workItem(FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                result("function-other", "point-1", FunctionalTestcaseResultValidator.CaseStatus.FORMAL)));
        FunctionalTestcaseResultValidator.Testcase malformed = new FunctionalTestcaseResultValidator.Testcase("case-1", "title", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(2, "action", "expected")), List.of("fact-1"), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.FORMAL, List.of());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(malformed))));
    }

    @Test
    void rejectsInternalKeysInVisibleCaseTextButAllowsThemInBindingClosures() {
        FunctionalTestcaseResultValidator.WorkItem workItem = workItem(FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT);
        FunctionalTestcaseResultValidator.Testcase unsafe = new FunctionalTestcaseResultValidator.Testcase(
                "case-1", "验证 fact-1724e7041424efc97c0cc3dc53109f39", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "提交", "成功")),
                List.of("fact-1"), List.of("evidence-1"), FunctionalTestcaseResultValidator.CaseStatus.FORMAL, List.of());

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(unsafe))));
        assertDoesNotThrow(() -> validator.validate(workItem,
                result("function-1", "point-1", FunctionalTestcaseResultValidator.CaseStatus.FORMAL)));
    }

    /** [Req-ID]: REQ-FTG-006 */
    @Test
    void acceptsTheFrozenGenericTerminationClauseWithoutTreatingItAsBusinessEvidence() {
        String genericTermination = "系统服务终止，或执行过程中无法执行下一步操作。";
        FunctionalTestcaseResultValidator.Testcase testcase = new FunctionalTestcaseResultValidator.Testcase(
                "case-1", "title", "title", FunctionalTestcaseResultValidator.Priority.MEDIUM, List.of(),
                FunctionalTestcaseResultValidator.Initialization.empty(), List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected",
                        FunctionalTestcaseResultValidator.STEP_EVALUATION, genericTermination,
                        FunctionalTestcaseResultValidator.RESULT_COLLECTION)),
                List.of("expected"), FunctionalTestcaseResultValidator.EVALUATION,
                FunctionalTestcaseResultValidator.RESULT_EVALUATION, List.of(genericTermination),
                FunctionalTestcaseResultValidator.RESULT_COLLECTION,
                FunctionalTestcaseResultValidator.AuthoringInformation.empty(),
                List.of("fact-1"), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.FORMAL, List.of());

        assertDoesNotThrow(() -> validator.validate(workItem(FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT),
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(testcase))));
    }

    /** [Req-ID]: REQ-FTG-006 */
    @Test
    void acceptsCompleteExpectedSourceValuesInEvaluationTerminationAndCollectionFields() {
        FunctionalTestcaseResultValidator.Testcase testcase = new FunctionalTestcaseResultValidator.Testcase(
                "case-1", "title", "title", FunctionalTestcaseResultValidator.Priority.MEDIUM, List.of(),
                FunctionalTestcaseResultValidator.Initialization.empty(), List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected",
                        "expected", "expected", "expected")), List.of("expected"), "expected", "expected",
                List.of("expected"), "expected", FunctionalTestcaseResultValidator.AuthoringInformation.empty(),
                List.of("fact-1"), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.FORMAL, List.of());

        assertDoesNotThrow(() -> validator.validate(workItem(FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT),
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(testcase))));
    }

    @Test
    void rejectsLiveFixtureFormalTitleThatExpandsAccountIntoUnsupportedAccountTypes() {
        FunctionalTestcaseResultValidator.Testcase testcase = controlledFormalCase(
                "已注册且状态正常的用户使用用户名、手机号或邮箱登录",
                List.of("用户必须已注册且状态正常"),
                "用户在登录页提交账号和正确密码",
                "系统进入首页并显示当前用户名称");

        assertThrows(IllegalArgumentException.class, () -> validator.validate(controlledFormalWorkItem(),
                controlledResult(testcase)));
    }

    @Test
    void rejectsLiveFixtureFormalPreconditionThatInventsUsernameAndLoginEndpoint() {
        FunctionalTestcaseResultValidator.Testcase testcase = controlledFormalCase(
                "账号登录",
                List.of("用户已设置用户名", "登录接口/页面可正常访问"),
                "用户在登录页提交账号和正确密码",
                "系统进入首页并显示当前用户名称");

        assertThrows(IllegalArgumentException.class, () -> validator.validate(controlledFormalWorkItem(),
                controlledResult(testcase)));
    }

    @Test
    void rejectsLiveFixtureFormalActionThatInventsTokenAndSessionMechanics() {
        FunctionalTestcaseResultValidator.Testcase testcase = controlledFormalCase(
                "账号登录",
                List.of("用户必须已注册且状态正常"),
                "检查当前用户会话状态中的 Token/Session 标识",
                "用户会话状态从匿名变为已登录");

        assertThrows(IllegalArgumentException.class, () -> validator.validate(controlledFormalWorkItem(),
                controlledResult(testcase)));
    }

    @Test
    void rejectsLiveFixtureFormalExpectedThatInventsProtectedResourceAccess() {
        FunctionalTestcaseResultValidator.Testcase testcase = controlledFormalCase(
                "账号登录",
                List.of("用户必须已注册且状态正常"),
                "用户在登录页提交账号和正确密码",
                "受保护资源可正常访问且不会被拒绝");

        assertThrows(IllegalArgumentException.class, () -> validator.validate(controlledFormalWorkItem(),
                controlledResult(testcase)));
    }

    @Test
    void doesNotTreatThePossiblyOverreachingTestPointDescriptionAsFormalSupport() {
        FunctionalTestcaseResultValidator.WorkItem workItem = new FunctionalTestcaseResultValidator.WorkItem(
                registry(), "function-1", "用户中心→账号登录", "point-1",
                "测试点叙述声称支持手机号登录和 Token/Session，但该叙述不是正式事实",
                FunctionalTestcaseResultValidator.TestPointType.NORMAL_BEHAVIOR,
                FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT,
                List.of("fact-1"), List.of("evidence-1"), List.of(), List.of(controlledFormalSupport()));
        FunctionalTestcaseResultValidator.Testcase testcase = controlledFormalCase(
                "手机号登录", List.of("用户必须已注册且状态正常"),
                "检查 Token/Session", "用户会话状态从匿名变为已登录");

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem, controlledResult(testcase)));
    }

    @Test
    void doesNotTreatTheConfirmedFunctionProjectionAsFormalTitleSupport() {
        FunctionalTestcaseResultValidator.WorkItem workItem = new FunctionalTestcaseResultValidator.WorkItem(
                registry(), "function-1", "用户中心/手机号登录入口", "point-1", "账号登录",
                FunctionalTestcaseResultValidator.TestPointType.NORMAL_BEHAVIOR,
                FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT,
                List.of("fact-1"), List.of("evidence-1"), List.of(), List.of(controlledFormalSupport()));
        FunctionalTestcaseResultValidator.Testcase testcase = controlledFormalCase(
                "用户中心/手机号登录入口", List.of("用户必须已注册且状态正常"),
                "用户在登录页提交账号和正确密码", "系统进入首页并显示当前用户名称");

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem, controlledResult(testcase)));
    }

    @Test
    void acceptsLiveFixtureFormalCaseWhenEveryVisibleBusinessFragmentIsDirectlySupported() {
        FunctionalTestcaseResultValidator.Testcase testcase = controlledFormalCase(
                "账号登录",
                List.of("用户必须已注册且状态正常"),
                "用户在登录页提交账号和正确密码",
                "系统进入首页");

        assertDoesNotThrow(() -> validator.validate(controlledFormalWorkItem(), controlledResult(testcase)));
    }

    @Test
    void acceptsPreviouslyAdversarialTermsWhenTheBoundFormalSourcesExplicitlyStateThem() {
        StructuredValidationRegistry registry = registry();
        FunctionalTestcaseResultValidator.FormalSupport support = new FunctionalTestcaseResultValidator.FormalSupport(
                "fact-1", "手机号登录", List.of("已绑定手机号的用户"),
                List.of("用户提交手机号和正确密码"), List.of("手机号", "正确密码"), List.of(),
                List.of("系统签发 Token/Session 标识并允许访问受保护资源"), List.of(), List.of(),
                List.of(), List.of(), Map.of("evidence-1",
                        "已绑定手机号的用户提交手机号和正确密码后，系统签发 Token/Session 标识并允许访问受保护资源"));
        FunctionalTestcaseResultValidator.WorkItem workItem = new FunctionalTestcaseResultValidator.WorkItem(
                registry, "function-1", "手机号登录", "point-1", "正式材料明确说明手机号和会话机制",
                FunctionalTestcaseResultValidator.TestPointType.NORMAL_BEHAVIOR,
                FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT,
                List.of("fact-1"), List.of("evidence-1"), List.of(), List.of(support));
        FunctionalTestcaseResultValidator.Testcase testcase = controlledFormalCase(
                "手机号登录", List.of("已绑定手机号的用户"), "用户提交手机号和正确密码",
                "系统签发 Token/Session 标识并允许访问受保护资源");

        assertDoesNotThrow(() -> validator.validate(workItem, controlledResult(testcase)));
    }

    @Test
    void rejectsFormerFieldWrappersBecauseFrozenResultsRequireCompleteNormalizedEquality() {
        FunctionalTestcaseResultValidator.Testcase testcase = controlledFormalCase(
                "验证账号登录",
                List.of("前置条件：用户必须已注册且状态正常"),
                "操作：用户在登录页提交账号和正确密码",
                "预期结果：系统进入首页并显示当前用户名称");

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(controlledFormalWorkItem(), controlledResult(testcase)));
    }

    @Test
    void fieldWrappersCannotHideUnsupportedBusinessSpecifics() {
        FunctionalTestcaseResultValidator.Testcase testcase = controlledFormalCase(
                "验证账号登录",
                List.of("前提：用户已设置用户名"),
                "执行：检查 Token/Session 标识",
                "预期：受保护资源可正常访问");

        assertThrows(IllegalArgumentException.class, () -> validator.validate(controlledFormalWorkItem(), controlledResult(testcase)));
    }

    /** [Req-ID]: REQ-FTG-001 */
    @Test
    void rejectsFormalClaimsThatOnlyMatchAfterPunctuationIsErased() {
        FunctionalTestcaseResultValidator.FormalSupport support = new FunctionalTestcaseResultValidator.FormalSupport(
                "fact-1", "账号；登录", List.of(), List.of("提交"), List.of(), List.of(), List.of("成功"),
                List.of(), List.of(), List.of(), List.of(), Map.of("evidence-1", "账号；登录。提交后成功"));
        FunctionalTestcaseResultValidator.WorkItem item = new FunctionalTestcaseResultValidator.WorkItem(
                registry(), "function-1", "账号；登录", "point-1", "账号；登录",
                FunctionalTestcaseResultValidator.TestPointType.NORMAL_BEHAVIOR,
                FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT,
                List.of("fact-1"), List.of("evidence-1"), List.of(), List.of(support));
        FunctionalTestcaseResultValidator.Testcase testcase = controlledFormalCase(
                "账号登录", List.of(), "提交", "成功");

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(item, controlledResult(testcase)));
    }

    @Test
    void retainsUnsupportedSpecificAsPendingOnlyWithMissingInformationAndWithoutFormalCoverageCredit() {
        FunctionalTestcaseResultValidator.Testcase formal = controlledFormalCase(
                "账号登录",
                List.of("用户必须已注册且状态正常"),
                "用户在登录页提交账号和正确密码",
                "系统进入首页");
        FunctionalTestcaseResultValidator.Testcase pending = new FunctionalTestcaseResultValidator.Testcase(
                "case-pending", "手机号登录候选", List.of("用户已绑定手机号"),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "输入手机号和正确密码", "登录成功")),
                List.of("fact-1"), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.PENDING_CONFIRMATION,
                List.of("正式材料未说明账号是否包含手机号"));

        FunctionalTestcaseResultValidator.ValidationOutcome outcome = validator.validate(controlledFormalWorkItem(),
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(formal, pending)));

        assertTrue(outcome.formalCoverageSatisfied());
    }

    private static FunctionalTestcaseResultValidator.WorkItem workItem(FunctionalTestcaseResultValidator.Basis basis) {
        StructuredValidationRegistry registry = registry();
        List<String> missingInformation = basis == FunctionalTestcaseResultValidator.Basis.GENERAL_EXPERIENCE
                ? List.of("No formal requirement evidence is available") : List.of();
        return new FunctionalTestcaseResultValidator.WorkItem(registry, "function-1", "Function one", "point-1", "boundary test",
                FunctionalTestcaseResultValidator.TestPointType.BOUNDARY_VALUE, basis, List.of("fact-1"), List.of("evidence-1"),
                missingInformation, basis == FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT
                        ? List.of(genericFormalSupport()) : List.of());
    }

    /**
     * Read-only fixture distilled from task a422272c-a993-4553-8c46-58a89e39c20b and artifact
     * bc0972fc-f860-4f2a-8903-72c889434a76. It contains only the accepted formal fact wording,
     * never the unsupported model expansion.
     */
    private static FunctionalTestcaseResultValidator.WorkItem controlledFormalWorkItem() {
        StructuredValidationRegistry registry = registry();
        return new FunctionalTestcaseResultValidator.WorkItem(registry, "function-1", "用户中心→账号登录",
                "point-1", "已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称",
                FunctionalTestcaseResultValidator.TestPointType.NORMAL_BEHAVIOR,
                FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT,
                List.of("fact-1"), List.of("evidence-1"), List.of(), List.of(controlledFormalSupport()));
    }

    private static FunctionalTestcaseResultValidator.FormalSupport controlledFormalSupport() {
        return new FunctionalTestcaseResultValidator.FormalSupport("fact-1", "账号登录",
                List.of("已注册且状态正常的用户"),
                List.of("用户在登录页提交账号和正确密码"),
                List.of("账号", "正确密码"),
                List.of("用户必须已注册且状态正常", "密码必须正确"),
                List.of("系统进入首页", "首页显示当前用户名称"),
                List.of(), List.of("用户会话状态从匿名变为已登录"), List.of(), List.of(),
                Map.of("evidence-1", "已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称"));
    }

    private static FunctionalTestcaseResultValidator.FormalSupport genericFormalSupport() {
        return new FunctionalTestcaseResultValidator.FormalSupport("fact-1", "title",
                List.of(), List.of("action"), List.of(), List.of(), List.of("expected"),
                List.of(), List.of(), List.of(), List.of(),
                Map.of("evidence-1", "title action expected"));
    }

    private static FunctionalTestcaseResultValidator.Testcase controlledFormalCase(
            String title, List<String> preconditions, String action, String expected) {
        return new FunctionalTestcaseResultValidator.Testcase("case-controlled", title, preconditions,
                List.of(new FunctionalTestcaseResultValidator.Step(1, action, expected)),
                List.of("fact-1"), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.FORMAL, List.of());
    }

    private static FunctionalTestcaseResultValidator.Result controlledResult(
            FunctionalTestcaseResultValidator.Testcase testcase) {
        return new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(testcase));
    }

    private static StructuredValidationRegistry registry() {
        return StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.FUNCTION, "function-1")
                .register(StructuredKeyType.TEST_POINT, "point-1")
                .register(StructuredKeyType.REQUIREMENT_FACT, "fact-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true));
    }

    private static FunctionalTestcaseResultValidator.Result result(
            String functionKey, String testPointKey, FunctionalTestcaseResultValidator.CaseStatus status) {
        FunctionalTestcaseResultValidator.Testcase testcase = new FunctionalTestcaseResultValidator.Testcase("case-1", "title", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected")), List.of("fact-1"), List.of("evidence-1"),
                status, status == FunctionalTestcaseResultValidator.CaseStatus.PENDING_CONFIRMATION
                        ? List.of("No formal requirement evidence is available") : List.of());
        return new FunctionalTestcaseResultValidator.Result(functionKey, testPointKey, List.of(testcase));
    }
}
