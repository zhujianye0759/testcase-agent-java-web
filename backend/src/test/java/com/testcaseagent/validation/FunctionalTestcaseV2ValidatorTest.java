package com.testcaseagent.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Input;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result.CaseStatus;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result.GenerationOutcome;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Result.FactType;
import com.testcaseagent.knowledgeagent.StructuredSourceQuoteV2;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** V2 outcome/business-closure acceptance tests. [Req-ID]: REQ-TGV2-005, REQ-TGV2-006, REQ-TGV2-014, REQ-TGV2-015 */
class FunctionalTestcaseV2ValidatorTest {
    private final FunctionalTestcaseV2Validator validator = new FunctionalTestcaseV2Validator();

    @Test
    void acceptsGeneratedFormalCaseAndAssignsStableJavaCaseKey() {
        var accepted = validator.validate(input(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT),
                result(GenerationOutcome.GENERATED, List.of(), List.of(testcase(CaseStatus.FORMAL, List.of()))));

        assertThat(accepted.formalCoverageSatisfied()).isTrue();
        assertThat(accepted.testcases()).singleElement().satisfies(value ->
                assertThat(value.caseKey()).matches("case-[0-9a-f]{64}"));
    }

    /** [Req-ID]: REQ-TGV2-006 */
    @Test
    void assignsDifferentStableKeysWhenSameNamedCasesHaveDifferentExecutionContent() {
        var base = testcase(CaseStatus.FORMAL, List.of());
        var withInput = new FunctionalTestcaseDesignV2Result.Testcase(base.name(), base.title(), base.priority(),
                base.preconditions(), base.initialization(),
                List.of(new FunctionalTestcaseDesignV2Result.Input("提交订单",
                        FunctionalTestcaseDesignV2Result.InputNature.UNSPECIFIED,
                        FunctionalTestcaseDesignV2Result.InputSource.UNSPECIFIED,
                        FunctionalTestcaseDesignV2Result.TestMethod.UNSPECIFIED,
                        FunctionalTestcaseDesignV2Result.Authenticity.UNSPECIFIED, "")),
                base.steps(), base.expectedResults(), base.evaluationCriteria(), base.resultEvaluationCriteria(),
                base.terminationConditions(), base.resultCollection(), base.requirementFactKeys(),
                base.evidenceKeys(), base.caseStatus(), base.missingInformation());

        var accepted = validator.validate(input(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT),
                result(GenerationOutcome.GENERATED, List.of(), List.of(base, withInput)));

        assertThat(accepted.testcases()).extracting(FunctionalTestcaseV2Validator.AcceptedTestcase::caseKey)
                .doesNotHaveDuplicates();
    }

    /** [Req-ID]: REQ-TGV2-006 */
    @Test
    void stableCaseIdentityCoversEveryPublishableExecutionFieldAndIgnoresReferenceOrdering() {
        var base = testcase(CaseStatus.FORMAL, List.of());
        List<FunctionalTestcaseDesignV2Result.Testcase> variants = List.of(
                identityVariant(base, "name"), identityVariant(base, "title"),
                identityVariant(base, "preconditions"), identityVariant(base, "hardware"),
                identityVariant(base, "software"), identityVariant(base, "testConfiguration"),
                identityVariant(base, "parameterConfiguration"), identityVariant(base, "inputContent"),
                identityVariant(base, "inputSequence"), identityVariant(base, "stepAction"),
                identityVariant(base, "stepExpected"), identityVariant(base, "stepEvaluation"),
                identityVariant(base, "stepTermination"), identityVariant(base, "stepCollection"),
                identityVariant(base, "evaluation"), identityVariant(base, "resultEvaluation"),
                identityVariant(base, "termination"), identityVariant(base, "resultCollection"));

        var accepted = validator.validate(identityInput(),
                result(GenerationOutcome.GENERATED, List.of(),
                        java.util.stream.Stream.concat(java.util.stream.Stream.of(base), variants.stream()).toList()));

        assertThat(accepted.testcases()).hasSize(variants.size() + 1);
        LinkedHashSet<String> caseKeys = accepted.testcases().stream()
                .map(FunctionalTestcaseV2Validator.AcceptedTestcase::caseKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertThat(caseKeys).hasSize(variants.size() + 1);

        var ordered = testcaseWithReferences(CaseStatus.FORMAL, List.of(),
                List.of("fact-1", "fact-2"), List.of("unit-1", "unit-2"));
        var reversed = testcaseWithReferences(CaseStatus.FORMAL, List.of(),
                List.of("fact-2", "fact-1"), List.of("unit-2", "unit-1"));
        String orderedKey = validator.validate(identityInputWithTwoFacts(),
                result(GenerationOutcome.GENERATED, List.of(), List.of(ordered))).testcases().get(0).caseKey();
        String reversedKey = validator.validate(identityInputWithTwoFacts(),
                result(GenerationOutcome.GENERATED, List.of(), List.of(reversed))).testcases().get(0).caseKey();
        assertThat(reversedKey).isEqualTo(orderedKey);
    }

    @Test
    void acceptsPendingOnlyAndUnableWithoutCountingFormalCoverage() {
        var pending = validator.validate(input(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT),
                result(GenerationOutcome.PENDING_ONLY, List.of("缺少角色权限"),
                        List.of(testcase(CaseStatus.PENDING_CONFIRMATION, List.of("缺少角色权限")))));
        var unable = validator.validate(input(FunctionalTestcaseDesignV2Input.Basis.GENERAL_EXPERIENCE),
                result(GenerationOutcome.UNABLE_TO_GENERATE, List.of("缺少可执行步骤"), List.of()));

        assertThat(pending.formalCoverageSatisfied()).isFalse();
        assertThat(unable.testcases()).isEmpty();
    }

    @Test
    void rejectsTopLevelMissingInformationThatIsNotClosedByPendingCandidates() {
        assertThatThrownBy(() -> validator.validate(input(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT),
                result(GenerationOutcome.GENERATED, List.of("缺少角色权限"),
                        List.of(testcase(CaseStatus.FORMAL, List.of())))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure ->
                        assertThat(failure.failure().code()).isEqualTo("TESTCASE_OUTCOME_INCONSISTENT"));

        var mixed = validator.validate(input(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT),
                result(GenerationOutcome.GENERATED, List.of("缺少角色权限"), List.of(
                        testcase(CaseStatus.FORMAL, List.of()),
                        testcase(CaseStatus.PENDING_CONFIRMATION, List.of("缺少角色权限")))));
        assertThat(mixed.formalCoverageSatisfied()).isTrue();
        assertThat(mixed.missingInformation()).containsExactly("缺少角色权限");
    }

    @Test
    void normalizesModelOwnedExecutionMetadataToJavaSafeDefaults() {
        var base = testcase(CaseStatus.FORMAL, List.of());
        var modelSpecific = new FunctionalTestcaseDesignV2Result.Testcase(base.name(), base.title(),
                FunctionalTestcaseDesignV2Result.Priority.HIGH, base.preconditions(), base.initialization(),
                List.of(new FunctionalTestcaseDesignV2Result.Input("提交订单",
                        FunctionalTestcaseDesignV2Result.InputNature.VALID,
                        FunctionalTestcaseDesignV2Result.InputSource.MANUAL,
                        FunctionalTestcaseDesignV2Result.TestMethod.EQUIVALENCE_PARTITIONING,
                        FunctionalTestcaseDesignV2Result.Authenticity.REAL, "")),
                base.steps(), base.expectedResults(), base.evaluationCriteria(), base.resultEvaluationCriteria(),
                base.terminationConditions(), base.resultCollection(), base.requirementFactKeys(), base.evidenceKeys(),
                base.caseStatus(), base.missingInformation());

        var accepted = validator.validate(input(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT),
                result(GenerationOutcome.GENERATED, List.of(), List.of(modelSpecific)));
        var neutralMetadata = new FunctionalTestcaseDesignV2Result.Testcase(base.name(), base.title(),
                FunctionalTestcaseDesignV2Result.Priority.MEDIUM, base.preconditions(), base.initialization(),
                List.of(unspecifiedInput("提交订单", "")), base.steps(), base.expectedResults(),
                base.evaluationCriteria(), base.resultEvaluationCriteria(), base.terminationConditions(),
                base.resultCollection(), base.requirementFactKeys(), base.evidenceKeys(), base.caseStatus(),
                base.missingInformation());
        String baselineKey = validator.validate(input(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT),
                result(GenerationOutcome.GENERATED, List.of(), List.of(neutralMetadata)))
                .testcases().get(0).caseKey();

        assertThat(accepted.testcases()).singleElement().satisfies(value -> {
            assertThat(value.caseKey()).isEqualTo(baselineKey);
            assertThat(value.testcase().priority()).isEqualTo(FunctionalTestcaseDesignV2Result.Priority.MEDIUM);
            assertThat(value.testcase().inputs()).singleElement().satisfies(input -> {
                assertThat(input.nature()).isEqualTo(FunctionalTestcaseDesignV2Result.InputNature.UNSPECIFIED);
                assertThat(input.source()).isEqualTo(FunctionalTestcaseDesignV2Result.InputSource.UNSPECIFIED);
                assertThat(input.method()).isEqualTo(FunctionalTestcaseDesignV2Result.TestMethod.UNSPECIFIED);
                assertThat(input.authenticity()).isEqualTo(FunctionalTestcaseDesignV2Result.Authenticity.UNSPECIFIED);
            });
        });
    }

    @Test
    void rejectsOutcomeContradictionsAndReferenceClosureWithoutAcceptingTheBatch() {
        assertThatThrownBy(() -> validator.validate(input(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT),
                result(GenerationOutcome.GENERATED, List.of(),
                        List.of(testcase(CaseStatus.PENDING_CONFIRMATION, List.of("缺少角色"))))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure ->
                        assertThat(failure.failure().code()).isEqualTo("TESTCASE_OUTCOME_INCONSISTENT"));
        assertThatThrownBy(() -> validator.validate(input(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT),
                result(GenerationOutcome.PENDING_ONLY, List.of("缺少信息"),
                        List.of(testcase(CaseStatus.FORMAL, List.of())))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure ->
                        assertThat(failure.failure().code()).isEqualTo("TESTCASE_OUTCOME_INCONSISTENT"));
        assertThatThrownBy(() -> validator.validate(input(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT),
                result(GenerationOutcome.GENERATED, List.of(),
                        List.of(testcaseWithReferences(CaseStatus.FORMAL, List.of(), List.of("other-fact"),
                                List.of("unit-1"))))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure ->
                        assertThat(failure.failure().code()).isEqualTo("TESTCASE_FACT_OUT_OF_SCOPE"));
        assertThatThrownBy(() -> validator.validate(input(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT),
                result(GenerationOutcome.GENERATED, List.of(),
                        List.of(testcaseWithReferences(CaseStatus.FORMAL, List.of(), List.of("fact-1"),
                                List.of("other-unit"))))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure ->
                        assertThat(failure.failure().code()).isEqualTo("TESTCASE_EVIDENCE_CLOSURE_INVALID"));
    }

    @Test
    void acceptsOverallExpectedResultWhoseTextDiffersFromStepExpectation() {
        var base = testcase(CaseStatus.FORMAL, List.of());
        var supportedInput = inputSupporting("提交订单", "订单提交完成");
        var candidate = new FunctionalTestcaseDesignV2Result.Testcase(base.name(), base.title(), base.priority(),
                base.preconditions(), base.initialization(), base.inputs(), base.steps(),
                List.of("订单提交完成"), base.evaluationCriteria(), base.resultEvaluationCriteria(),
                base.terminationConditions(), base.resultCollection(), base.requirementFactKeys(),
                base.evidenceKeys(), base.caseStatus(), base.missingInformation());

        assertThat(validator.validate(supportedInput,
                result(GenerationOutcome.GENERATED, List.of(), List.of(candidate))).formalCoverageSatisfied())
                .isTrue();
    }

    @Test
    void acceptsOneOverallExpectedResultForMultipleConsecutiveSteps() {
        var base = testcase(CaseStatus.FORMAL, List.of());
        var supportedInput = inputSupporting("准备提交订单", "提交订单", "订单提交完成");
        var steps = List.of(
                new FunctionalTestcaseDesignV2Result.Step(1, "准备提交订单", "准备提交订单",
                        "实际结果符合预期", "", "记录结果"),
                new FunctionalTestcaseDesignV2Result.Step(2, "提交订单", "提交订单",
                        "实际结果符合预期", "", "记录结果"));
        var candidate = new FunctionalTestcaseDesignV2Result.Testcase(base.name(), base.title(), base.priority(),
                base.preconditions(), base.initialization(), base.inputs(), steps, List.of("订单提交完成"),
                base.evaluationCriteria(), base.resultEvaluationCriteria(), base.terminationConditions(),
                base.resultCollection(), base.requirementFactKeys(), base.evidenceKeys(), base.caseStatus(),
                base.missingInformation());

        assertThat(validator.validate(supportedInput,
                result(GenerationOutcome.GENERATED, List.of(), List.of(candidate))).formalCoverageSatisfied())
                .isTrue();
    }

    /** [Req-ID]: REQ-TGV2-014 */
    @Test
    void rejectsUnsupportedOverallExpectedResultFromAFormalCase() {
        assertUnsupportedOverallExpectedResult(
                CaseStatus.FORMAL, GenerationOutcome.GENERATED, List.of());
    }

    /** [Req-ID]: REQ-TGV2-014 */
    @Test
    void rejectsUnsupportedOverallExpectedResultFromAPendingCase() {
        assertUnsupportedOverallExpectedResult(
                CaseStatus.PENDING_CONFIRMATION, GenerationOutcome.PENDING_ONLY, List.of("缺少审核状态依据"));
    }

    @Test
    void rejectsUnsupportedRoleCredentialEnvironmentInterfaceStateThresholdErrorAndResultDetails() {
        assertUnsupportedDetail("管理员输入账号和密码", "$.testcases[0].steps[0].action");
        assertUnsupportedDetail("在Chrome浏览器中提交订单", "$.testcases[0].steps[0].action");
        assertUnsupportedDetail("调用订单API提交", "$.testcases[0].steps[0].action");
        assertUnsupportedDetail("订单进入待审核状态", "$.testcases[0].steps[0].action");
        assertUnsupportedDetail("响应时间小于3秒", "$.testcases[0].steps[0].action");
        assertUnsupportedDetail("显示错误码E100", "$.testcases[0].steps[0].action");
        assertUnsupportedDetail("返回提交成功", "$.testcases[0].steps[0].action");
    }

    @Test
    void acceptsProtectedBusinessDetailWhenSelectedFactSupportsIt() {
        var supportedInput = new FunctionalTestcaseDesignV2Input("function-1", "提交订单", "订单/提交", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.PERMISSION,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "管理员提交订单", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.ROLE,
                        "管理员提交订单", List.of(new StructuredSourceQuoteV2("unit-1", "管理员提交订单")))));
        var base = testcase(CaseStatus.FORMAL, List.of());
        var step = new FunctionalTestcaseDesignV2Result.Step(1, "管理员提交订单", "管理员提交订单",
                base.steps().get(0).evaluationCriteria(), "", base.steps().get(0).resultCollection());
        var candidate = new FunctionalTestcaseDesignV2Result.Testcase(base.name(), base.title(), base.priority(),
                base.preconditions(), base.initialization(), base.inputs(), List.of(step), List.of("管理员提交订单"),
                base.evaluationCriteria(), base.resultEvaluationCriteria(), base.terminationConditions(),
                base.resultCollection(), base.requirementFactKeys(), base.evidenceKeys(), base.caseStatus(),
                base.missingInformation());

        assertThat(validator.validate(supportedInput,
                result(GenerationOutcome.GENERATED, List.of(), List.of(candidate))).formalCoverageSatisfied())
                .isTrue();
    }

    @Test
    void rejectsUnsupportedBusinessActionsAndEvaluationResultsEvenWhenTheRoleIsGrounded() {
        var supportedInput = new FunctionalTestcaseDesignV2Input("function-1", "提交订单", "订单/提交", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "管理员提交订单", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        "管理员提交订单", List.of(new StructuredSourceQuoteV2("unit-1", "管理员提交订单")))));

        assertUnsupportedCase(supportedInput, testcaseWithStep("管理员删除订单", "提交订单", "实际结果符合预期"),
                "$.testcases[0].steps[0].action");
        assertUnsupportedCase(supportedInput, testcaseWithStep("管理员提交订单", "订单自动作废", "实际结果符合预期"),
                "$.testcases[0].steps[0].expected");
        assertUnsupportedCase(supportedInput, testcaseWithStep("管理员提交订单", "提交订单", "响应时间不超过3秒"),
                "$.testcases[0].steps[0].evaluation_criteria");

        var base = testcaseWithStep("管理员提交订单", "提交订单", "实际结果符合预期");
        var caseEvaluation = new FunctionalTestcaseDesignV2Result.Testcase(base.name(), base.title(), base.priority(),
                base.preconditions(), base.initialization(), base.inputs(), base.steps(), base.expectedResults(),
                "必须生成订单号", base.resultEvaluationCriteria(), base.terminationConditions(),
                base.resultCollection(), base.requirementFactKeys(), base.evidenceKeys(), base.caseStatus(),
                base.missingInformation());
        assertUnsupportedCase(supportedInput, caseEvaluation, "$.testcases[0].evaluation_criteria");

        var resultEvaluation = new FunctionalTestcaseDesignV2Result.Testcase(base.name(), base.title(),
                base.priority(), base.preconditions(), base.initialization(), base.inputs(), base.steps(),
                base.expectedResults(), base.evaluationCriteria(), "订单必须进入已完成状态",
                base.terminationConditions(), base.resultCollection(), base.requirementFactKeys(),
                base.evidenceKeys(), base.caseStatus(), base.missingInformation());
        assertUnsupportedCase(supportedInput, resultEvaluation, "$.testcases[0].result_evaluation_criteria");
    }

    @Test
    void formalCaseCannotUseApprovedScopeTextAsEvidenceOrHideUnsupportedSequenceDetails() {
        var scopeOnlyRole = new FunctionalTestcaseDesignV2Input("function-1", "管理员提交订单",
                "管理员/提交订单", "管理员执行操作",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.PERMISSION,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交订单", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        "提交订单", List.of(new StructuredSourceQuoteV2("unit-1", "提交订单")))));
        assertUnsupportedCase(scopeOnlyRole, testcaseWithStep("管理员提交订单", "提交订单", "实际结果符合预期"),
                "$.testcases[0].steps[0].action");

        var base = testcase(CaseStatus.FORMAL, List.of());
        var sequenced = new FunctionalTestcaseDesignV2Result.Testcase(base.name(), base.title(), base.priority(),
                base.preconditions(), base.initialization(),
                List.of(new FunctionalTestcaseDesignV2Result.Input("提交订单",
                        FunctionalTestcaseDesignV2Result.InputNature.VALID,
                        FunctionalTestcaseDesignV2Result.InputSource.MANUAL,
                        FunctionalTestcaseDesignV2Result.TestMethod.EQUIVALENCE_PARTITIONING,
                        FunctionalTestcaseDesignV2Result.Authenticity.SIMULATED, "审核完成后第3步")),
                base.steps(), base.expectedResults(), base.evaluationCriteria(),
                base.resultEvaluationCriteria(), base.terminationConditions(), base.resultCollection(),
                base.requirementFactKeys(), base.evidenceKeys(), base.caseStatus(), base.missingInformation());
        assertUnsupportedCase(input(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT), sequenced,
                "$.testcases[0].inputs[0].sequence");
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void acceptsNameAndTitleComposedFromCurrentFunctionPointAndGenericWrappers() {
        var supportedInput = new FunctionalTestcaseDesignV2Input("function-1", "批次处理", "业务→批次处理",
                "该描述不能支撑执行字段",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交批次", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        "提交批次", List.of(new StructuredSourceQuoteV2("unit-1", "提交批次")))));
        var candidate = identityCandidate(
                testcaseWithStep("提交批次", "提交批次", "实际结果符合预期"),
                "验证批次处理提交批次", "批次处理提交批次测试用例");

        assertThat(validator.validate(supportedInput,
                result(GenerationOutcome.GENERATED, List.of(), List.of(candidate))).formalCoverageSatisfied())
                .isTrue();
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void acceptsCurrentPointIdentityTogetherWithOneCompleteFact() {
        var supportedInput = new FunctionalTestcaseDesignV2Input("function-1", "批次处理", "业务/批次处理", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "校验提交入口", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        "保存批次", List.of(new StructuredSourceQuoteV2("unit-1", "保存批次")))));
        var candidate = identityCandidate(
                testcaseWithStep("保存批次", "保存批次", "实际结果符合预期"),
                "批次处理校验提交入口保存批次", "校验提交入口保存批次测试用例");

        assertThat(validator.validate(supportedInput,
                result(GenerationOutcome.GENERATED, List.of(), List.of(candidate))).formalCoverageSatisfied())
                .isTrue();
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void rejectsGenericOnlyNameThatDoesNotIdentifyTheCurrentScope() {
        var genericSupportingInput = new FunctionalTestcaseDesignV2Input("function-1", "提交订单", "订单/提交", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交订单", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        "提交后记录结果", List.of(new StructuredSourceQuoteV2("unit-1", "提交后记录结果")))));
        var candidate = identityCandidate(testcase(CaseStatus.FORMAL, List.of()),
                "记录结果", "提交订单");

        assertUnsupportedCase(genericSupportingInput, candidate, "$.testcases[0].name");
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void matchesARealIdentityBeforeTreatingItsPrefixAsGenericScaffolding() {
        var supportedInput = new FunctionalTestcaseDesignV2Input("function-1", "操作记录", "审计/操作记录", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "查看记录", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        "查看记录", List.of(new StructuredSourceQuoteV2("unit-1", "查看记录")))));
        var candidate = identityCandidate(
                testcaseWithStep("查看记录", "查看记录", "实际结果符合预期"), "操作记录", "验证操作记录");

        assertThat(validator.validate(supportedInput,
                result(GenerationOutcome.GENERATED, List.of(), List.of(candidate))).formalCoverageSatisfied())
                .isTrue();
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void executionFieldCannotUseTheArtificialBoundaryBetweenTwoFacts() {
        var multiFactInput = new FunctionalTestcaseDesignV2Input("function-1", "批次处理", "业务/批次处理", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "处理批次", List.of()),
                List.of(
                        new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                                "开启批次", List.of(new StructuredSourceQuoteV2("unit-1", "开启批次"))),
                        new FunctionalTestcaseDesignV2Input.RequirementFact("fact-2", FactType.BUSINESS_RULE,
                                "关闭批次", List.of(new StructuredSourceQuoteV2("unit-2", "关闭批次")))));
        var base = testcaseWithReferences(CaseStatus.FORMAL, List.of(), List.of("fact-1", "fact-2"),
                List.of("unit-1", "unit-2"));
        var step = new FunctionalTestcaseDesignV2Result.Step(1, "开启批次关闭批次", "关闭批次",
                "实际结果符合预期", "", "记录结果");
        var candidate = new FunctionalTestcaseDesignV2Result.Testcase("批次处理", "处理批次", base.priority(),
                base.preconditions(), base.initialization(), base.inputs(), List.of(step), List.of("关闭批次"),
                base.evaluationCriteria(), base.resultEvaluationCriteria(), base.terminationConditions(),
                base.resultCollection(), base.requirementFactKeys(), base.evidenceKeys(), base.caseStatus(),
                base.missingInformation());

        assertUnsupportedCase(multiFactInput, candidate, "$.testcases[0].steps[0].action");
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void executionFieldCannotSupplyTheInternalSourceSeparator() {
        var multiFactInput = new FunctionalTestcaseDesignV2Input("function-1", "批次处理", "业务/批次处理", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "处理批次", List.of()),
                List.of(
                        new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                                "开启批次", List.of(new StructuredSourceQuoteV2("unit-1", "开启批次"))),
                        new FunctionalTestcaseDesignV2Input.RequirementFact("fact-2", FactType.BUSINESS_RULE,
                                "关闭批次", List.of(new StructuredSourceQuoteV2("unit-2", "关闭批次")))));
        var base = testcaseWithReferences(CaseStatus.FORMAL, List.of(), List.of("fact-1", "fact-2"),
                List.of("unit-1", "unit-2"));
        var step = new FunctionalTestcaseDesignV2Result.Step(1, "开启批次\u0000关闭批次", "关闭批次",
                "实际结果符合预期", "", "记录结果");
        var candidate = new FunctionalTestcaseDesignV2Result.Testcase("批次处理", "处理批次", base.priority(),
                base.preconditions(), base.initialization(), base.inputs(), List.of(step), List.of("关闭批次"),
                base.evaluationCriteria(), base.resultEvaluationCriteria(), base.terminationConditions(),
                base.resultCollection(), base.requirementFactKeys(), base.evidenceKeys(), base.caseStatus(),
                base.missingInformation());

        assertUnsupportedCase(multiFactInput, candidate, "$.testcases[0].steps[0].action");
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void acceptsCurrentScopeBeforeClassifyingItsLiteralTextAsGeneric() {
        var supportedInput = new FunctionalTestcaseDesignV2Input("function-1", "记录结果", "审计/记录结果", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "查看审计", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        "查看审计", List.of(new StructuredSourceQuoteV2("unit-1", "查看审计")))));
        var candidate = identityCandidate(
                testcaseWithStep("查看审计", "查看审计", "实际结果符合预期"), "记录结果", "查看审计");

        assertThat(validator.validate(supportedInput,
                result(GenerationOutcome.GENERATED, List.of(), List.of(candidate))).formalCoverageSatisfied())
                .isTrue();
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void acceptsPrefixWrappedScopeWithoutStrippingItsBusinessSuffix() {
        var supportedInput = new FunctionalTestcaseDesignV2Input(
                "function-1", "批次异常场景", "业务/批次异常场景", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交批次", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        "提交批次", List.of(new StructuredSourceQuoteV2("unit-1", "提交批次")))));
        var candidate = identityCandidate(
                testcaseWithStep("提交批次", "提交批次", "实际结果符合预期"),
                "验证批次异常场景", "批次异常场景");

        assertThat(validator.validate(supportedInput,
                result(GenerationOutcome.GENERATED, List.of(), List.of(candidate))).formalCoverageSatisfied())
                .isTrue();
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void rejectsWrapperOnlyIdentityEvenWhenOneFactContainsIt() {
        var supportedInput = new FunctionalTestcaseDesignV2Input("function-1", "批次处理", "业务/批次处理", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交批次", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        "验证测试用例", List.of(new StructuredSourceQuoteV2("unit-1", "验证测试用例")))));
        var candidate = identityCandidate(
                testcaseWithStep("验证测试用例", "验证测试用例", "实际结果符合预期"),
                "验证", "批次处理");

        assertUnsupportedCase(supportedInput, candidate, "$.testcases[0].name");
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @ParameterizedTest
    @ValueSource(strings = {"验证记录结果", "记录结果测试用例", "验证记录结果测试用例",
            "验证检查记录结果", "记录结果测试用例测试用例"})
    void rejectsWrappedGenericOnlyIdentityEvenWhenOneFactContainsIt(String genericIdentity) {
        var supportedInput = new FunctionalTestcaseDesignV2Input("function-1", "批次处理", "业务/批次处理", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交批次", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        genericIdentity, List.of(new StructuredSourceQuoteV2("unit-1", genericIdentity)))));
        var candidate = identityCandidate(
                testcaseWithStep(genericIdentity, genericIdentity, "实际结果符合预期"),
                genericIdentity, "批次处理");

        assertUnsupportedCase(supportedInput, candidate, "$.testcases[0].name");
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void validatesDeepRepeatedWrappersWithoutMaterializingTheWrapperCrossProduct() {
        var supportedInput = new FunctionalTestcaseDesignV2Input("function-1", "批次处理", "业务/批次处理", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交批次", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        "提交批次", List.of(new StructuredSourceQuoteV2("unit-1", "提交批次")))));
        String wrappedName = "验证".repeat(8) + "批次处理" + "测试用例".repeat(8);
        var candidate = identityCandidate(
                testcaseWithStep("提交批次", "提交批次", "实际结果符合预期"), wrappedName, "批次处理");

        assertThat(validator.validate(supportedInput,
                result(GenerationOutcome.GENERATED, List.of(), List.of(candidate)))
                .formalCoverageSatisfied()).isTrue();
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void rejectsUnsupportedLongCoreWithOverlappingSuffixesWithoutScanningDominatedSlices() {
        String longCore = "甲".repeat(128) + "乙";
        List<FunctionalTestcaseDesignV2Input.RequirementFact> facts = new java.util.ArrayList<>();
        facts.add(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-0", FactType.BUSINESS_RULE,
                "提交批次", List.of(new StructuredSourceQuoteV2("unit-0", "提交批次"))));
        for (int index = 1; index < 4; index++) {
            String nearMatch = "甲".repeat(128) + "丙" + (char) (0x4e00 + index);
            facts.add(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-" + index, FactType.BUSINESS_RULE,
                    nearMatch, List.of(new StructuredSourceQuoteV2("unit-" + index, nearMatch))));
        }
        var supportedInput = new FunctionalTestcaseDesignV2Input("function-1", "批次处理", "业务/批次处理", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交批次", List.of()),
                facts);
        String wrappedName = longCore + "测试用例".repeat(8);
        var base = testcaseWithStep("提交批次", "提交批次", "实际结果符合预期");
        var candidate = new FunctionalTestcaseDesignV2Result.Testcase(wrappedName, "批次处理", base.priority(),
                base.preconditions(), base.initialization(), base.inputs(), base.steps(), base.expectedResults(),
                base.evaluationCriteria(), base.resultEvaluationCriteria(), base.terminationConditions(),
                base.resultCollection(), facts.stream().map(FunctionalTestcaseDesignV2Input.RequirementFact::factKey).toList(),
                facts.stream().flatMap(fact -> fact.sourceQuotes().stream()).map(StructuredSourceQuoteV2::evidenceKey).toList(),
                base.caseStatus(), base.missingInformation());

        assertUnsupportedCase(supportedInput, candidate, "$.testcases[0].name");
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void rejectsDeeplyWrappedUnsupportedCoreWithoutCartesianBoundaryScan() {
        List<FunctionalTestcaseDesignV2Input.RequirementFact> facts = List.of(
                new FunctionalTestcaseDesignV2Input.RequirementFact("fact-0", FactType.BUSINESS_RULE,
                        "fact0", List.of(new StructuredSourceQuoteV2("unit-0", "fact0quote"))));
        var supportedInput = new FunctionalTestcaseDesignV2Input("function-1", "批次处理", "业务/批次处理", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交批次", List.of()),
                facts);
        String unsupportedName = "验证".repeat(20) + "x".repeat(100) + "用例".repeat(20);
        var base = testcaseWithStep("fact0", "fact0", "实际结果符合预期");
        var candidate = new FunctionalTestcaseDesignV2Result.Testcase(unsupportedName, "批次处理", base.priority(),
                base.preconditions(), base.initialization(), base.inputs(), base.steps(), base.expectedResults(),
                base.evaluationCriteria(), base.resultEvaluationCriteria(), base.terminationConditions(),
                base.resultCollection(), facts.stream().map(FunctionalTestcaseDesignV2Input.RequirementFact::factKey).toList(),
                facts.stream().flatMap(fact -> fact.sourceQuotes().stream()).map(StructuredSourceQuoteV2::evidenceKey).toList(),
                base.caseStatus(), base.missingInformation());

        assertUnsupportedCase(supportedInput, candidate, "$.testcases[0].name");
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void rejectsLongUnwrappedIdentityWithoutScanningUnreachableBoundariesForEveryFact() {
        String unsupportedName = "x".repeat(15_000);
        List<FunctionalTestcaseDesignV2Input.RequirementFact> facts = List.of(
                new FunctionalTestcaseDesignV2Input.RequirementFact("fact-0", FactType.BUSINESS_RULE,
                        "提交批次", List.of(new StructuredSourceQuoteV2("unit-0", "提交批次"))));
        var supportedInput = new FunctionalTestcaseDesignV2Input("function-1", "批次处理", "业务/批次处理", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交批次", List.of()),
                facts);
        var base = testcaseWithStep("fact0", "fact0", "实际结果符合预期");
        var candidate = new FunctionalTestcaseDesignV2Result.Testcase(unsupportedName, "批次处理", base.priority(),
                base.preconditions(), base.initialization(), base.inputs(), base.steps(), base.expectedResults(),
                base.evaluationCriteria(), base.resultEvaluationCriteria(), base.terminationConditions(),
                base.resultCollection(), facts.stream().map(FunctionalTestcaseDesignV2Input.RequirementFact::factKey).toList(),
                facts.stream().flatMap(fact -> fact.sourceQuotes().stream()).map(StructuredSourceQuoteV2::evidenceKey).toList(),
                base.caseStatus(), base.missingInformation());

        assertUnsupportedCase(supportedInput, candidate, "$.testcases[0].name");
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void rejectsNameThatUsesAnotherFunctionOrTestPointLabel() {
        var candidate = identityCandidate(testcase(CaseStatus.FORMAL, List.of()),
                "验证退款处理提交批次", "提交批次");
        var currentInput = new FunctionalTestcaseDesignV2Input("function-1", "批次处理", "业务/批次处理", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交批次", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        "提交批次", List.of(new StructuredSourceQuoteV2("unit-1", "提交批次")))));

        assertUnsupportedCase(currentInput, candidate, "$.testcases[0].name");
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void rejectsNameThatCombinesScatteredFactsIntoANewBusinessConclusion() {
        var multiFactInput = new FunctionalTestcaseDesignV2Input("function-1", "批次处理", "业务/批次处理", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "处理批次", List.of()),
                List.of(
                        new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                                "开启批次", List.of(new StructuredSourceQuoteV2("unit-1", "开启批次"))),
                        new FunctionalTestcaseDesignV2Input.RequirementFact("fact-2", FactType.BUSINESS_RULE,
                                "关闭批次", List.of(new StructuredSourceQuoteV2("unit-2", "关闭批次")))));
        var candidate = identityCandidate(
                testcaseWithReferences(CaseStatus.FORMAL, List.of(), List.of("fact-1", "fact-2"),
                        List.of("unit-1", "unit-2")),
                "验证批次关闭批次", "批次处理");

        assertUnsupportedCase(multiFactInput, candidate, "$.testcases[0].name");
    }

    /** [Req-ID]: REQ-TGV2-015 */
    @Test
    void formalCandidateCannotUsePendingPointDescriptionAsIdentitySupport() {
        var pendingInput = new FunctionalTestcaseDesignV2Input("function-1", "批次处理", "业务/批次处理", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.GENERAL_EXPERIENCE, "人工补录批次", List.of("缺少正式依据")),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        "提交批次", List.of(new StructuredSourceQuoteV2("unit-1", "提交批次")))));
        var candidate = identityCandidate(testcase(CaseStatus.FORMAL, List.of()),
                "验证批次处理人工补录批次", "人工补录批次测试用例");

        assertThatThrownBy(() -> validator.validate(pendingInput,
                result(GenerationOutcome.GENERATED, List.of(), List.of(candidate))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure -> {
                    assertThat(failure.failure().code()).isEqualTo("TESTCASE_UNSUPPORTED_BUSINESS_DETAIL");
                    assertThat(failure.failure().path()).isEqualTo("$.testcases[0].name");
                });
    }

    private void assertUnsupportedCase(FunctionalTestcaseDesignV2Input input,
            FunctionalTestcaseDesignV2Result.Testcase candidate, String expectedPath) {
        assertThatThrownBy(() -> validator.validate(input,
                result(GenerationOutcome.GENERATED, List.of(), List.of(candidate))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure -> {
                    assertThat(failure.failure().code()).isEqualTo("TESTCASE_UNSUPPORTED_BUSINESS_DETAIL");
                    assertThat(failure.failure().path()).isEqualTo(expectedPath);
                });
    }

    private void assertUnsupportedOverallExpectedResult(
            CaseStatus status, GenerationOutcome outcome, List<String> missingInformation) {
        var base = testcase(status, missingInformation);
        var candidate = new FunctionalTestcaseDesignV2Result.Testcase(
                base.name(), base.title(), base.priority(), base.preconditions(), base.initialization(), base.inputs(),
                base.steps(), List.of("订单自动进入已审核状态"), base.evaluationCriteria(),
                base.resultEvaluationCriteria(), base.terminationConditions(), base.resultCollection(),
                base.requirementFactKeys(), base.evidenceKeys(), base.caseStatus(), base.missingInformation());

        assertThatThrownBy(() -> validator.validate(
                input(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT),
                result(outcome, missingInformation, List.of(candidate))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure -> {
                    assertThat(failure.failure().code()).isEqualTo("TESTCASE_UNSUPPORTED_BUSINESS_DETAIL");
                    assertThat(failure.failure().path()).isEqualTo("$.testcases[0].expected_results[0]");
                });
    }

    private static FunctionalTestcaseDesignV2Result.Testcase testcaseWithStep(
            String action, String expected, String evaluationCriteria) {
        var base = testcase(CaseStatus.FORMAL, List.of());
        var step = new FunctionalTestcaseDesignV2Result.Step(1, action, expected,
                evaluationCriteria, "", base.steps().get(0).resultCollection());
        return new FunctionalTestcaseDesignV2Result.Testcase(base.name(), base.title(), base.priority(),
                base.preconditions(), base.initialization(), base.inputs(), List.of(step), List.of(expected),
                base.evaluationCriteria(), base.resultEvaluationCriteria(), base.terminationConditions(),
                base.resultCollection(), base.requirementFactKeys(), base.evidenceKeys(), base.caseStatus(),
                base.missingInformation());
    }

    private static FunctionalTestcaseDesignV2Result.Testcase identityCandidate(
            FunctionalTestcaseDesignV2Result.Testcase base, String name, String title) {
        return new FunctionalTestcaseDesignV2Result.Testcase(name, title, base.priority(), base.preconditions(),
                base.initialization(), base.inputs(), base.steps(), base.expectedResults(), base.evaluationCriteria(),
                base.resultEvaluationCriteria(), base.terminationConditions(), base.resultCollection(),
                base.requirementFactKeys(), base.evidenceKeys(), base.caseStatus(), base.missingInformation());
    }

    private void assertUnsupportedDetail(String action, String expectedPath) {
        var base = testcase(CaseStatus.FORMAL, List.of());
        var step = new FunctionalTestcaseDesignV2Result.Step(1, action, base.steps().get(0).expected(),
                base.steps().get(0).evaluationCriteria(), "", base.steps().get(0).resultCollection());
        var candidate = new FunctionalTestcaseDesignV2Result.Testcase(base.name(), base.title(), base.priority(),
                base.preconditions(), base.initialization(), base.inputs(), List.of(step), List.of(step.expected()),
                base.evaluationCriteria(), base.resultEvaluationCriteria(), base.terminationConditions(),
                base.resultCollection(), base.requirementFactKeys(), base.evidenceKeys(), base.caseStatus(),
                base.missingInformation());

        assertThatThrownBy(() -> validator.validate(input(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT),
                result(GenerationOutcome.GENERATED, List.of(), List.of(candidate))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure -> {
                    assertThat(failure.failure().code()).isEqualTo("TESTCASE_UNSUPPORTED_BUSINESS_DETAIL");
                    assertThat(failure.failure().path()).isEqualTo(expectedPath);
                });
    }

    private static FunctionalTestcaseDesignV2Input input(FunctionalTestcaseDesignV2Input.Basis basis) {
        return new FunctionalTestcaseDesignV2Input("function-1", "提交订单", "订单/提交", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR, basis, "提交订单",
                        basis == FunctionalTestcaseDesignV2Input.Basis.GENERAL_EXPERIENCE
                                ? List.of("缺少正式需求") : List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        "提交订单", List.of(new StructuredSourceQuoteV2("unit-1", "提交订单")))));
    }

    private static FunctionalTestcaseDesignV2Input identityInput() {
        String support = String.join(" ", "提交订单", "变体名称", "变体标题", "变体前置", "硬件变体", "软件变体",
                "测试配置变体", "参数配置变体", "输入变体", "顺序变体", "操作变体", "预期变体", "步骤评价变体",
                "步骤终止变体", "步骤采集变体", "总体评价变体", "结果评价变体", "总体终止变体", "总体采集变体");
        return new FunctionalTestcaseDesignV2Input("function-1", "提交订单", "订单/提交", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交订单", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        support, List.of(new StructuredSourceQuoteV2("unit-1", support)))));
    }

    private static FunctionalTestcaseDesignV2Input identityInputWithTwoFacts() {
        return new FunctionalTestcaseDesignV2Input("function-1", "提交订单", "订单/提交", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交订单", List.of()),
                List.of(
                        new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                                "提交订单", List.of(new StructuredSourceQuoteV2("unit-1", "提交订单"))),
                        new FunctionalTestcaseDesignV2Input.RequirementFact("fact-2", FactType.OUTPUT,
                                "提交订单", List.of(new StructuredSourceQuoteV2("unit-2", "提交订单")))));
    }

    private static FunctionalTestcaseDesignV2Input inputSupporting(String... phrases) {
        String support = String.join(" ", phrases);
        return new FunctionalTestcaseDesignV2Input("function-1", "提交订单", "订单/提交", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交订单", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1", FactType.BUSINESS_RULE,
                        support, List.of(new StructuredSourceQuoteV2("unit-1", support)))));
    }

    private static FunctionalTestcaseDesignV2Result.Testcase identityVariant(
            FunctionalTestcaseDesignV2Result.Testcase base, String field) {
        var initialization = base.initialization();
        List<FunctionalTestcaseDesignV2Result.Input> inputs = base.inputs();
        List<FunctionalTestcaseDesignV2Result.Step> steps = base.steps();
        List<String> expected = base.expectedResults();
        List<String> preconditions = base.preconditions();
        List<String> termination = base.terminationConditions();
        String name = base.name();
        String title = base.title();
        String evaluation = base.evaluationCriteria();
        String resultEvaluation = base.resultEvaluationCriteria();
        String resultCollection = base.resultCollection();
        switch (field) {
            case "name" -> name = "变体名称";
            case "title" -> title = "变体标题";
            case "preconditions" -> preconditions = List.of("变体前置");
            case "hardware" -> initialization = new FunctionalTestcaseDesignV2Result.Initialization(
                    List.of("硬件变体"), List.of(), List.of(), List.of());
            case "software" -> initialization = new FunctionalTestcaseDesignV2Result.Initialization(
                    List.of(), List.of("软件变体"), List.of(), List.of());
            case "testConfiguration" -> initialization = new FunctionalTestcaseDesignV2Result.Initialization(
                    List.of(), List.of(), List.of("测试配置变体"), List.of());
            case "parameterConfiguration" -> initialization = new FunctionalTestcaseDesignV2Result.Initialization(
                    List.of(), List.of(), List.of(), List.of("参数配置变体"));
            case "inputContent" -> inputs = List.of(unspecifiedInput("输入变体", ""));
            case "inputSequence" -> inputs = List.of(unspecifiedInput("提交订单", "顺序变体"));
            case "stepAction" -> steps = List.of(step("操作变体", "提交订单", "实际结果符合预期", "", "记录结果"));
            case "stepExpected" -> {
                steps = List.of(step("提交订单", "预期变体", "实际结果符合预期", "", "记录结果"));
                expected = List.of("预期变体");
            }
            case "stepEvaluation" -> steps = List.of(step("提交订单", "提交订单", "步骤评价变体", "", "记录结果"));
            case "stepTermination" -> steps = List.of(step("提交订单", "提交订单", "实际结果符合预期", "步骤终止变体", "记录结果"));
            case "stepCollection" -> steps = List.of(step("提交订单", "提交订单", "实际结果符合预期", "", "步骤采集变体"));
            case "evaluation" -> evaluation = "总体评价变体";
            case "resultEvaluation" -> resultEvaluation = "结果评价变体";
            case "termination" -> termination = List.of("总体终止变体");
            case "resultCollection" -> resultCollection = "总体采集变体";
            default -> throw new IllegalArgumentException("Unknown identity field");
        }
        return new FunctionalTestcaseDesignV2Result.Testcase(name, title, base.priority(), preconditions,
                initialization, inputs, steps, expected, evaluation, resultEvaluation, termination, resultCollection,
                base.requirementFactKeys(), base.evidenceKeys(), base.caseStatus(), base.missingInformation());
    }

    private static FunctionalTestcaseDesignV2Result.Input unspecifiedInput(String content, String sequence) {
        return new FunctionalTestcaseDesignV2Result.Input(content,
                FunctionalTestcaseDesignV2Result.InputNature.UNSPECIFIED,
                FunctionalTestcaseDesignV2Result.InputSource.UNSPECIFIED,
                FunctionalTestcaseDesignV2Result.TestMethod.UNSPECIFIED,
                FunctionalTestcaseDesignV2Result.Authenticity.UNSPECIFIED, sequence);
    }

    private static FunctionalTestcaseDesignV2Result.Step step(
            String action, String expected, String evaluation, String termination, String collection) {
        return new FunctionalTestcaseDesignV2Result.Step(1, action, expected, evaluation, termination, collection);
    }

    private static FunctionalTestcaseDesignV2Result result(GenerationOutcome outcome, List<String> missing,
            List<FunctionalTestcaseDesignV2Result.Testcase> cases) {
        return new FunctionalTestcaseDesignV2Result("function-1", "point-1", outcome, missing, cases);
    }

    private static FunctionalTestcaseDesignV2Result.Testcase testcase(CaseStatus status, List<String> missing) {
        return testcaseWithReferences(status, missing, List.of("fact-1"), List.of("unit-1"));
    }

    private static FunctionalTestcaseDesignV2Result.Testcase testcaseWithReferences(CaseStatus status,
            List<String> missing, List<String> factKeys, List<String> evidenceKeys) {
        var step = new FunctionalTestcaseDesignV2Result.Step(1, "提交订单", "提交订单",
                "实际结果符合预期", "", "记录结果");
        return new FunctionalTestcaseDesignV2Result.Testcase("提交订单", "提交订单",
                FunctionalTestcaseDesignV2Result.Priority.MEDIUM, List.of(),
                new FunctionalTestcaseDesignV2Result.Initialization(List.of(), List.of(), List.of(), List.of()),
                List.of(), List.of(step), List.of("提交订单"), "全部步骤符合预期", "任一步失败则不通过",
                List.of(), "记录结果", factKeys, evidenceKeys, status, missing);
    }
}
