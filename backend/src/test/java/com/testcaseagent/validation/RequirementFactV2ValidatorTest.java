package com.testcaseagent.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Input;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Result;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Result.FactType;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Result.ObservationType;
import com.testcaseagent.knowledgeagent.StructuredSourceQuoteV2;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Java-owned V2 fact acceptance tests. [Req-ID]: REQ-TGV2-004, REQ-TGV2-008 */
class RequirementFactV2ValidatorTest {
    private final RequirementFactV2Validator validator = new RequirementFactV2Validator();

    @Test
    void validatesEchoAndQuotesAcrossTargetAndContextThenAssignsAStableFactKey() {
        var input = window("function-a", "window-a",
                List.of(unit("target", 2, "订单提\n交后显示成功")),
                List.of(unit("context", 1, "角色：办理人员")));
        var result = new RequirementFactExtractionV2Result("function-a", "window-a", List.of(
                fact(FactType.BUSINESS_RULE, "订单提交", "target", "订单提交"),
                fact(FactType.ROLE, "办理人员", "context", "办理人员")), List.of(
                new RequirementFactExtractionV2Result.TestabilityObservation(ObservationType.UNQUANTIFIED,
                        "成功标准未量化", List.of(FactType.OUTPUT),
                        List.of(new StructuredSourceQuoteV2("target", "显示成功")))));

        var accepted = validator.validate(input, result);

        assertThat(accepted.facts()).hasSize(2);
        assertThat(accepted.facts()).allSatisfy(value -> assertThat(value.factKey()).matches("fact-[0-9a-f]{64}"));
        assertThat(accepted.observations()).hasSize(1);
    }

    @Test
    void stableKeyAndCrossWindowMergeAreOrderIndependentAndDoNotDuplicateFacts() {
        var first = validator.validate(window("function-a", "window-a", List.of(unit("u1", 1, "订单提交")), List.of()),
                result("function-a", "window-a", List.of(fact(FactType.BUSINESS_RULE, "订单提交", "u1", "订单提交"))));
        var second = validator.validate(window("function-a", "window-b", List.of(unit("u2", 2, "订单提\n交操作")), List.of()),
                result("function-a", "window-b", List.of(fact(FactType.BUSINESS_RULE, "订单提交", "u2", "订单提交"))));

        assertThat(first.facts().get(0).factKey()).isEqualTo(second.facts().get(0).factKey());
        assertThat(validator.merge(List.of(second, first)).facts()).singleElement().satisfies(merged ->
                assertThat(merged.sourceQuotes()).extracting(StructuredSourceQuoteV2::evidenceKey)
                        .containsExactly("u1", "u2"));
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void crossWindowMergeRetainsDistinctExactQuotesThatShareOneNormalizedForm() {
        var inputA = window("function-a", "window-a", List.of(unit("u1", 1, "ABC abc")), List.of());
        var inputB = window("function-a", "window-b", List.of(unit("u1", 1, "ABC abc")), List.of());
        var first = validator.validate(inputA,
                result("function-a", "window-a", List.of(fact(FactType.OUTPUT, "ABC", "u1", "ABC"))));
        var second = validator.validate(inputB,
                result("function-a", "window-b", List.of(fact(FactType.OUTPUT, "abc", "u1", "abc"))));

        var forward = validator.merge(List.of(first, second)).facts().get(0).sourceQuotes();
        var reverse = validator.merge(List.of(second, first)).facts().get(0).sourceQuotes();

        assertThat(forward).extracting(StructuredSourceQuoteV2::quote).containsExactly("ABC", "abc");
        assertThat(reverse).isEqualTo(forward);
    }

    @Test
    void rejectsUnknownEvidenceNonContinuousQuoteAndDuplicateFactIdentityWithoutPartialAcceptance() {
        var input = window("function-a", "window-a", List.of(unit("u1", 1, "订单提交成功")), List.of());

        assertThatThrownBy(() -> validator.validate(input,
                result("function-a", "window-a", List.of(fact(FactType.OUTPUT, "成功", "other", "成功")))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure ->
                        assertThat(failure.failure().code()).isEqualTo("FACT_EVIDENCE_OUT_OF_SCOPE"));
        assertThatThrownBy(() -> validator.validate(input,
                result("function-a", "window-a", List.of(fact(FactType.OUTPUT, "成功", "u1", "订单失败")))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure ->
                        assertThat(failure.failure().code()).isEqualTo("FACT_QUOTE_NOT_GROUNDED"));
        assertThatThrownBy(() -> validator.validate(input,
                result("function-a", "window-a", List.of(
                        fact(FactType.OUTPUT, "成功", "u1", "成功"),
                        fact(FactType.OUTPUT, "成功", "u1", "成功")))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure ->
                        assertThat(failure.failure().code()).isEqualTo("FACT_DUPLICATE"));
    }

    @Test
    void emptyFactsAndObservationsAreAValidCompletedWindow() {
        var accepted = validator.validate(window("function-a", "window-a", List.of(unit("u1", 1, "目录")), List.of()),
                result("function-a", "window-a", List.of()));

        assertThat(accepted.facts()).isEmpty();
        assertThat(accepted.observations()).isEmpty();
    }

    @Test
    void rejectsFactStatementThatIsNotDirectlySupportedByOneCitedUnit() {
        var input = window("function-a", "window-a", List.of(
                unit("u1", 1, "订单可提交"), unit("u2", 2, "提交后显示成功")), List.of());

        assertThatThrownBy(() -> validator.validate(input,
                result("function-a", "window-a", List.of(
                        fact(FactType.BUSINESS_RULE, "管理员可提交订单", "u1", "订单可提交")))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure -> {
                    assertThat(failure.failure().code()).isEqualTo("FACT_DIRECT_EVIDENCE_UNSUPPORTED");
                    assertThat(failure.failure().path()).isEqualTo("$.requirement_facts[0].statement");
                });
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsAnObligationThatDropsItsInputTriggerCondition() {
        String source = "输入起止时间和批次号后，系统按检测时间倒序返回匹配记录";
        var input = window("function-a", "window-a", List.of(unit("u1", 1, source)), List.of());

        assertFactFailure(input,
                fact(FactType.BUSINESS_RULE, "系统按检测时间倒序返回匹配记录", "u1", source),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void acceptsAConventionalInitialismWhenTheSameQuoteCarriesTheCompleteObligation() {
        String source = "The application programming interface returns status";
        var input = window("function-a", "window-a", List.of(unit("u1", 1, source)), List.of());
        var result = result("function-a", "window-a", List.of(
                fact(FactType.OUTPUT, "API returns status", "u1", source)));

        assertThat(validator.validate(input, result).facts()).hasSize(1);
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void acceptsRepeatedOccurrencesOfTheSameUniqueInitialismExpansion() {
        String source = "The application programming interface returns status; the application programming interface logs requests";
        var input = window("function-a", "window-a", List.of(unit("u1", 1, source)), List.of());

        assertThat(validator.validate(input, result("function-a", "window-a", List.of(
                fact(FactType.OUTPUT, "API returns status", "u1",
                        "The application programming interface returns status")))).facts()).hasSize(1);
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void acceptsFieldReorderingWithinOneContinuousQuote() {
        String source = "系统结果包含“批次号”、“检测时间”、“判定状态”";
        var input = window("function-a", "window-a", List.of(unit("u1", 1, source)), List.of());
        var result = result("function-a", "window-a", List.of(
                fact(FactType.OUTPUT, "系统结果包含“判定状态”、“批次号”", "u1", source)));

        assertThat(validator.validate(input, result).facts()).hasSize(1);
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsChangedBusinessMeaningLiteralAndCrossQuoteStitching() {
        var input = window("function-a", "window-a", List.of(
                unit("u1", 1, "系统不得删除订单，温度必须低于8℃"),
                unit("u2", 2, "订单提交"),
                unit("u3", 3, "显示成功")), List.of());

        assertFactFailure(input,
                fact(FactType.BUSINESS_RULE, "系统可以删除订单", "u1", "系统不得删除订单"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.BUSINESS_RULE, "温度必须低于5℃", "u1", "温度必须低于8℃"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        var stitched = new RequirementFactExtractionV2Result.RequirementFact(FactType.OUTPUT, "订单提交显示成功",
                List.of(new StructuredSourceQuoteV2("u2", "订单提交"),
                        new StructuredSourceQuoteV2("u3", "显示成功")));
        assertFactFailure(input, stitched, "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsNegatedSubstringAndReorderedRoleThresholdOrLiteralBindings() {
        var input = window("function-a", "window-a", List.of(
                unit("u1", 1, "系统不允许删除订单"),
                unit("u2", 2, "管理员向用户分配查看权限"),
                unit("u3", 3, "温度低于5℃且压力高于8Pa"),
                unit("u4", 4, "状态‘A’表示通过，‘B’表示拒绝")), List.of());

        assertFactFailure(input,
                fact(FactType.BUSINESS_RULE, "允许删除订单", "u1", "系统不允许删除订单"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.BUSINESS_RULE, "用户向管理员分配查看权限", "u2", "管理员向用户分配查看权限"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.BUSINESS_RULE, "温度低于8℃且压力高于5Pa", "u3", "温度低于5℃且压力高于8Pa"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.OUTPUT, "状态‘B’表示通过，‘A’表示拒绝", "u4", "状态‘A’表示通过，‘B’表示拒绝"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsDroppedConditionsCrossClauseStitchingAndSignedThresholdChanges() {
        var input = window("function-a", "window-a", List.of(
                unit("u1", 1, "When approved, administrators may delete records"),
                unit("u2", 2, "管理员已停用，用户可以删除订单"),
                unit("u3", 3, "温度必须高于-5℃"),
                unit("u4", 4, "Only administrators may delete records")), List.of());

        assertFactFailure(input,
                fact(FactType.BUSINESS_RULE, "administrators may delete records", "u1",
                        "When approved, administrators may delete records"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.BUSINESS_RULE, "管理员可以删除订单", "u2", "管理员已停用，用户可以删除订单"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.BUSINESS_RULE, "温度必须高于5℃", "u3", "温度必须高于-5℃"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.BUSINESS_RULE, "administrators may delete records", "u4",
                        "Only administrators may delete records"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsDroppedScopeConditionsAndNonAdjacentClauseStitching() {
        var input = window("function-a", "window-a", List.of(
                unit("u1", 1, "Only if approved, administrators may delete records"),
                unit("u2", 2, "在审批通过的情况下系统允许提交订单"),
                unit("u3", 3, "Administrators may delete archived records"),
                unit("u4", 4, "系统接收申请，审批人审核通过，系统发送通知")), List.of());

        assertFactFailure(input,
                fact(FactType.BUSINESS_RULE, "administrators may delete records", "u1",
                        "Only if approved, administrators may delete records"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.BUSINESS_RULE, "系统允许提交订单", "u2", "在审批通过的情况下系统允许提交订单"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.PERMISSION, "Administrators may delete records", "u3",
                        "Administrators may delete archived records"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.STATE_CHANGE, "系统接收申请，系统发送通知", "u4",
                        "系统接收申请，审批人审核通过，系统发送通知"),
                "FACT_ATOMICITY_INVALID");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsDroppedLeadingOrTrailingConditionsThatChangeTheObligationScope() {
        var input = window("function-a", "window-a", List.of(
                unit("u1", 1, "Unless approved, administrators may delete records"),
                unit("u2", 2, "Administrators may delete records after approval"),
                unit("u3", 3, "只允许管理员删除订单"),
                unit("u4", 4, "系统在审批通过的情况下允许提交订单")), List.of());

        assertFactFailure(input,
                fact(FactType.PERMISSION, "administrators may delete records", "u1",
                        "Unless approved, administrators may delete records"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.PERMISSION, "Administrators may delete records", "u2",
                        "Administrators may delete records after approval"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.PERMISSION, "允许管理员删除订单", "u3", "只允许管理员删除订单"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.PERMISSION, "允许提交订单", "u4", "系统在审批通过的情况下允许提交订单"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsAnIndependentClauseMisparsedAsAFieldCollectionMember() {
        List<String> sources = List.of(
                "The output includes name and address, and the system sends an alert",
                "输出包含姓名和地址，系统发送通知",
                "输出包含姓名，系统发送通知",
                "输出包含姓名、系统发送通知、平台记录日志",
                "The output includes name, users send alerts",
                "The output includes name, and the system wrote logs");

        for (int index = 0; index < sources.size(); index++) {
            String source = sources.get(index);
            String evidenceKey = "u" + index;
            var input = window("function-a", "window-" + index,
                    List.of(unit(evidenceKey, 1, source)), List.of());
            String statement = switch (index) {
                case 0 -> "The output includes the system sends an alert";
                case 1, 2 -> "输出包含系统发送通知";
                case 3 -> "输出包含系统发送通知、平台记录日志";
                case 4 -> "The output includes users send alerts";
                default -> "The output includes the system wrote logs";
            };
            assertFactFailure(input, fact(FactType.OUTPUT, statement, evidenceKey, source),
                    "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        }

        String mixedSource = "输出必须包含姓名，地址，系统发送通知";
        var mixedInput = window("function-a", "window-mixed",
                List.of(unit("u-mixed", 1, mixedSource)), List.of());
        assertFactFailure(mixedInput,
                fact(FactType.OUTPUT, "输出必须包含姓名和系统发送通知", "u-mixed", mixedSource),
                "FACT_ATOMICITY_INVALID");

        String twoActionSource = "输出包含姓名，系统发送通知，平台记录日志";
        var twoActionInput = window("function-a", "window-two-actions",
                List.of(unit("u-two-actions", 1, twoActionSource)), List.of());
        assertFactFailure(twoActionInput,
                fact(FactType.OUTPUT, "输出包含系统发送通知和平台记录日志",
                        "u-two-actions", twoActionSource),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");

        String explicitObligationSource = "输出包含用户姓名，必须通知";
        var explicitObligationInput = window("function-a", "window-explicit-obligation",
                List.of(unit("u-explicit-obligation", 1, explicitObligationSource)), List.of());
        assertFactFailure(explicitObligationInput,
                fact(FactType.OUTPUT, "输出包含必须通知", "u-explicit-obligation", explicitObligationSource),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsDroppingRoleModifiersOrRelationshipParticipants() {
        var input = window("function-a", "window-a", List.of(
                unit("u1", 1, "Authorized administrators may delete records"),
                unit("u2", 2, "管理员向用户分配查看权限")), List.of());

        assertFactFailure(input,
                fact(FactType.PERMISSION, "administrators may delete records", "u1",
                        "Authorized administrators may delete records"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.PERMISSION, "用户分配查看权限", "u2", "管理员向用户分配查看权限"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsDroppingUnknownConditionPreamblesOrInputCollectionBusinessTerms() {
        var input = window("function-a", "window-a", List.of(
                unit("u1", 1, "Under authenticated access, the system returns records"),
                unit("u2", 2, "输入账号后，系统按审核状态返回记录"),
                unit("u3", 3, "For authenticated users, the system returns records"),
                unit("u4", 4, "审批通过后，系统返回记录"),
                unit("u5", 5, "With an active session, the system returns records"),
                unit("u6", 6, "The user is authenticated, the system returns records")), List.of());

        assertFactFailure(input,
                fact(FactType.OUTPUT, "the system returns records", "u1",
                        "Under authenticated access, the system returns records"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.OUTPUT, "系统按账号返回记录", "u2", "输入账号后，系统按审核状态返回记录"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.OUTPUT, "the system returns records", "u3",
                        "For authenticated users, the system returns records"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.OUTPUT, "系统返回记录", "u4", "审批通过后，系统返回记录"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.OUTPUT, "the system returns records", "u5",
                        "With an active session, the system returns records"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.OUTPUT, "the system returns records", "u6",
                        "The user is authenticated, the system returns records"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsDroppingChineseRoleScopeOrRelationshipParticipants() {
        var input = window("function-a", "window-a", List.of(
                unit("u1", 1, "具备删除权限的管理员可以删除记录"),
                unit("u2", 2, "管理员为用户分配权限"),
                unit("u3", 3, "普通管理员可以查看记录"),
                unit("u4", 4, "管理员替用户分配权限"),
                unit("u5", 5, "高级用户查看记录"),
                unit("u6", 6, "Authorized administrators delete records")), List.of());

        assertFactFailure(input,
                fact(FactType.PERMISSION, "管理员可以删除记录", "u1", "具备删除权限的管理员可以删除记录"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.PERMISSION, "用户分配权限", "u2", "管理员为用户分配权限"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.PERMISSION, "管理员可以查看记录", "u3", "普通管理员可以查看记录"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.PERMISSION, "用户分配权限", "u4", "管理员替用户分配权限"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.PERMISSION, "用户查看记录", "u5", "高级用户查看记录"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.PERMISSION, "administrators delete records", "u6",
                        "Authorized administrators delete records"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");

        for (FactType factType : FactType.values()) {
            assertFactFailure(input,
                    fact(factType, "用户查看记录", "u5", "高级用户查看记录"),
                    "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
            assertFactFailure(input,
                    fact(factType, "用户分配权限", "u4", "管理员替用户分配权限"),
                    "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void acceptsOmittingOneGenericSystemSubjectWithoutDroppingARole() {
        String source = "系统必须删除记录";
        var input = window("function-a", "window-subject", List.of(unit("u1", 1, source)), List.of());

        assertThat(validator.validate(input, result("function-a", "window-subject", List.of(
                fact(FactType.STATE_CHANGE, "必须删除记录", "u1", source)))).facts()).hasSize(1);

        String permissionSource = "系统允许管理员删除记录";
        var permissionInput = window("function-a", "window-permission",
                List.of(unit("u2", 1, permissionSource)), List.of());
        assertThat(validator.validate(permissionInput, result("function-a", "window-permission", List.of(
                fact(FactType.PERMISSION, "允许管理员删除记录", "u2", permissionSource)))).facts()).hasSize(1);
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void acceptsSafeSystemSubjectCompressionWithoutDroppingPreservedModifiers() {
        List<String> sources = List.of(
                "该系统进入首页并显示当前用户名称",
                "系统在审批通过后必须返回记录",
                "系统当前必须返回记录",
                "系统当前在审批通过后必须返回记录",
                "系统在审批通过后当前必须返回记录",
                "系统当前只允许管理员查看记录",
                "系统仅限管理员查看记录",
                "系统只有管理员可以查看记录",
                "The system when approval succeeds must return a record",
                "The system currently must return a record");
        List<String> statements = List.of(
                "系统进入首页并显示当前用户名称",
                "在审批通过后必须返回记录",
                "当前必须返回记录",
                "当前在审批通过后必须返回记录",
                "在审批通过后当前必须返回记录",
                "当前只允许管理员查看记录",
                "仅限管理员查看记录",
                "只有管理员可以查看记录",
                "when approval succeeds must return a record",
                "currently must return a record");

        for (int index = 0; index < sources.size(); index++) {
            int fixtureIndex = index;
            String evidenceKey = "u" + index;
            var input = window("function-a", "window-system-compression-" + index,
                    List.of(unit(evidenceKey, 1, sources.get(index))), List.of());
            assertThatCode(() -> validator.validate(input,
                    result("function-a", "window-system-compression-" + fixtureIndex, List.of(
                            fact(FactType.OUTPUT, statements.get(fixtureIndex), evidenceKey,
                                    sources.get(fixtureIndex))))))
                    .as("safe system compression fixture %s", index)
                    .doesNotThrowAnyException();
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsTreatingSystemInsideARoleNameAsAGenericSubject() {
        var input = window("function-a", "window-system-role", List.of(
                unit("u1", 1, "系统管理员可以删除记录"),
                unit("u2", 2, "System administrators may delete records")), List.of());

        assertFactFailure(input,
                fact(FactType.PERMISSION, "管理员可以删除记录", "u1", "系统管理员可以删除记录"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.PERMISSION, "administrators may delete records", "u2",
                        "System administrators may delete records"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsDroppingLongChineseConditionsWithoutUsingATextLengthThreshold() {
        String source = "如果" + "甲".repeat(81) + "时系统返回记录";
        var input = window("function-a", "window-long-condition", List.of(unit("u1", 1, source)), List.of());

        assertFactFailure(input,
                fact(FactType.OUTPUT, "系统返回记录", "u1", source),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsDroppingUnitsOrIdentifierSymbols() {
        var input = window("function-a", "window-symbols", List.of(
                unit("u1", 1, "成功率不得低于99%"),
                unit("u2", 2, "服务使用C#接口"),
                unit("u3", 3, "接口标识为urn:foo"),
                unit("u4", 4, "字段标识为x[y]")), List.of());

        assertFactFailure(input,
                fact(FactType.BUSINESS_RULE, "成功率不得低于99", "u1", "成功率不得低于99%"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.INPUT, "服务使用C接口", "u2", "服务使用C#接口"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.INPUT, "接口标识为urn foo", "u3", "接口标识为urn:foo"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.INPUT, "字段标识为x y", "u4", "字段标识为x[y]"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsRemovingAnIndependentClauseBoundary() {
        String source = "管理员审核，发布结果";
        var input = window("function-a", "window-clause-boundary", List.of(unit("u1", 1, source)), List.of());

        assertFactFailure(input,
                fact(FactType.STATE_CHANGE, "管理员审核发布结果", "u1", source),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsOneFactThatJoinsIndependentUnmodalizedActionsWithAComma() {
        String source = "系统接收申请，平台发送通知";
        var input = window("function-a", "window-independent-actions", List.of(unit("u1", 1, source)), List.of());

        assertFactFailure(input,
                fact(FactType.STATE_CHANGE, source, "u1", source),
                "FACT_ATOMICITY_INVALID");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsIndependentCommaActionsWithoutDependingOnAProjectRoleDictionary() {
        List<String> sources = List.of(
                "当前系统接收申请，平台发送通知",
                "管理员接收申请，审批人发送通知",
                "仓库接收申请，网关发送通知");
        for (int index = 0; index < sources.size(); index++) {
            String source = sources.get(index);
            String evidenceKey = "u" + index;
            var input = window("function-a", "window-independent-role-actions-" + index,
                    List.of(unit(evidenceKey, 1, source)), List.of());

            assertFactFailure(input,
                    fact(FactType.STATE_CHANGE, source, evidenceKey, source),
                    "FACT_ATOMICITY_INVALID");
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void acceptsAnEventConditionAndItsCommaSeparatedConsequenceAsOneFact() {
        String source = "在系统管理员提交申请后，平台返回状态";
        var input = window("function-a", "window-event-condition",
                List.of(unit("u1", 1, source)), List.of());

        assertThat(validator.validate(input, result("function-a", "window-event-condition", List.of(
                fact(FactType.OUTPUT, source, "u1", source)))).facts()).hasSize(1);
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsDeletingAConnectorThatWouldHideTwoRepeatedObligations() {
        String source = "用户可以提交订单并且用户可以取消订单";
        var input = window("function-a", "window-hidden-obligation-connector",
                List.of(unit("u1", 1, source)), List.of());

        assertFactFailure(input,
                fact(FactType.PERMISSION, "用户可以提交订单用户可以取消订单", "u1", source),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void acceptsAConditionWhenOnlyItsClausePunctuationIsRemoved() {
        List<String> sources = List.of(
                "如果审批通过则，系统返回记录",
                "如果审批通过，系统返回记录",
                "在审批完成之后，系统返回记录",
                "仅当审批通过时，系统返回记录",
                "只在审批通过时，系统返回记录",
                "仅在审批完成后，系统返回记录",
                "只要审批通过，系统就返回记录",
                "Only when approved, the system returns records");
        List<String> statements = List.of(
                "如果审批通过则系统返回记录",
                "如果审批通过系统返回记录",
                "在审批完成之后系统返回记录",
                "仅当审批通过时系统返回记录",
                "只在审批通过时系统返回记录",
                "仅在审批完成后系统返回记录",
                "只要审批通过系统就返回记录",
                "Only when approved the system returns records");

        for (int index = 0; index < sources.size(); index++) {
            String evidenceKey = "u" + index;
            var input = window("function-a", "window-" + index,
                    List.of(unit(evidenceKey, 1, sources.get(index))), List.of());
            assertThat(validator.validate(input, result("function-a", "window-" + index, List.of(
                    fact(FactType.OUTPUT, statements.get(index), evidenceKey, sources.get(index))))).facts())
                    .hasSize(1);
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsTreatingACommonNounSuffixAsAConditionBoundary() {
        List<String> sources = List.of(
                "系统记录工时，平台返回状态",
                "若干员工记录工时，平台返回状态",
                "当班人员记录工时，平台返回状态",
                "仅当班人员记录工时，平台返回状态",
                "只在岗人员记录工时，平台返回状态");
        for (int index = 0; index < sources.size(); index++) {
            String source = sources.get(index);
            String evidenceKey = "u" + index;
            var input = window("function-a", "window-common-noun-suffix-" + index,
                    List.of(unit(evidenceKey, 1, source)), List.of());

            assertFactFailure(input,
                    fact(FactType.OUTPUT, source.replace("，", ""), evidenceKey, source),
                    "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void acceptsRemovingOnlyAGenericSystemSubjectWhilePreservingScopeControls() {
        List<String> sources = List.of(
                "系统只允许管理员查看记录",
                "系统仅当审批通过时允许提交");
        List<String> statements = List.of(
                "只允许管理员查看记录",
                "仅当审批通过时允许提交");

        for (int index = 0; index < sources.size(); index++) {
            String evidenceKey = "u" + index;
            var input = window("function-a", "window-system-scope-" + index,
                    List.of(unit(evidenceKey, 1, sources.get(index))), List.of());
            assertThat(validator.validate(input, result("function-a", "window-system-scope-" + index, List.of(
                    fact(FactType.PERMISSION, statements.get(index), evidenceKey, sources.get(index))))).facts())
                    .hasSize(1);
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsDroppingCurrentStateModifiers() {
        var input = window("function-a", "window-a", List.of(
                unit("u1", 1, "Users must enter the current password"),
                unit("u2", 2, "用户必须输入当前密码")), List.of());

        assertFactFailure(input,
                fact(FactType.INPUT, "Users must enter the password", "u1",
                        "Users must enter the current password"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.INPUT, "用户必须输入密码", "u2", "用户必须输入当前密码"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void acceptsMixedLiteralAndUniquelyExpandedInitialismsFromTheSameClause() {
        String source = "API satisfies the Service Level Agreement";
        var input = window("function-a", "window-a", List.of(unit("u1", 1, source)), List.of());

        assertThat(validator.validate(input, result("function-a", "window-a", List.of(
                fact(FactType.OUTPUT, "API satisfies SLA", "u1", source)))).facts()).hasSize(1);
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void preservesTheCaseOfQuotedBusinessLiterals() {
        var input = window("function-a", "window-case", List.of(
                unit("u1", 1, "状态“A”表示通过")), List.of());

        assertThat(validator.validate(input, result("function-a", "window-case", List.of(
                fact(FactType.OUTPUT, "状态“A”表示通过", "u1", "状态“A”表示通过")))).facts())
                .hasSize(1);
        assertFactFailure(input,
                fact(FactType.OUTPUT, "状态“a”表示通过", "u1", "状态“A”表示通过"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void acceptsStructurallyExplicitFieldCollectionsWithoutTreatingTheirMembersAsClauses() {
        List<FactFixture> facts = List.of(
                new FactFixture(FactType.OUTPUT, "The output includes \"status\" and \"identifier\""),
                new FactFixture(FactType.OUTPUT, "输出包含“手机号”、“姓名”"),
                new FactFixture(FactType.OUTPUT,
                        "The output must include whether administrators can delete"),
                new FactFixture(FactType.OUTPUT,
                        "输出必须包含用户是否可以编辑和用户是否可以删除"),
                new FactFixture(FactType.OUTPUT, "输出包含“手机号”、“姓名”"),
                new FactFixture(FactType.OUTPUT, "输出包含“姓名”"),
                new FactFixture(FactType.OUTPUT, "输出包含“用户创建时间”"),
                new FactFixture(FactType.OUTPUT, "输出包含“省”"));
        List<String> sources = List.of(
                "The output includes \"identifier\", \"timestamp\", and \"status\"",
                "输出包含“姓名”、“地址”、“手机号”",
                "The output must include whether administrators can edit, whether administrators can delete",
                "输出必须包含用户是否可以编辑和用户是否可以删除",
                "输出包含“姓名”、“地址”、“手机号”",
                "输出包含“姓名”、“地址”",
                "输出包含“ID”、“用户创建时间”",
                "输出包含“省”、“市”");

        for (int index = 0; index < facts.size(); index++) {
            int fixtureIndex = index;
            FactFixture fact = facts.get(index);
            String evidenceKey = "u" + index;
            var input = window("function-a", "window-" + index,
                    List.of(unit(evidenceKey, 1, sources.get(index))), List.of());
            assertThatCode(() -> validator.validate(input, result("function-a", "window-" + fixtureIndex, List.of(
                    fact(fact.type(), fact.statement(), evidenceKey, sources.get(fixtureIndex))))))
                    .as("field collection fixture %s", index)
                    .doesNotThrowAnyException();
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void preservesCollectionDelimitersInsideQuotedFieldLabels() {
        List<String> sources = List.of(
                "The output includes \"Last, First\" and \"ID\"",
                "The output includes \"Terms and Conditions\" and \"ID\"",
                "输出包含“省、市”、“编号”",
                "输出包含“姓名”和“地址”",
                "The output includes ‘user’s name, display’ and ‘identifier’",
                "The output includes whether user's name is present and whether identifier is present",
                "The output includes whether the user's address is present and whether the user's account is active");
        List<String> statements = List.of(
                "The output includes \"Last, First\"",
                "The output includes \"Terms and Conditions\"",
                "输出包含“省、市”",
                "输出包含“地址”",
                "The output includes ‘identifier’",
                "The output includes whether user's name is present",
                "The output includes whether the user's account is active");

        for (int index = 0; index < sources.size(); index++) {
            String evidenceKey = "u" + index;
            var input = window("function-a", "window-quoted-field-" + index,
                    List.of(unit(evidenceKey, 1, sources.get(index))), List.of());
            assertThat(validator.validate(input, result("function-a", "window-quoted-field-" + index, List.of(
                    fact(FactType.OUTPUT, statements.get(index), evidenceKey, sources.get(index))))).facts())
                    .hasSize(1);
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void preservesConnectorCharactersInsideFieldLabels() {
        String source = "输出包含和解状态与金额";
        var input = window("function-a", "window-label", List.of(unit("u1", 1, source)), List.of());

        assertThat(validator.validate(input, result("function-a", "window-label", List.of(
                fact(FactType.OUTPUT, source, "u1", source)))).facts()).hasSize(1);
        assertFactFailure(input,
                fact(FactType.OUTPUT, "输出包含解状态与金额", "u1", source),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsChineseAndWhitespaceSeparatedNegativeThresholdChanges() {
        List<String> sources = List.of("温度必须高于负5℃", "温度必须高于- 5℃", "温度必须高于−\u30005℃");
        for (int index = 0; index < sources.size(); index++) {
            String evidenceKey = "u" + index;
            var input = window("function-a", "window-" + index,
                    List.of(unit(evidenceKey, 1, sources.get(index))), List.of());
            assertFactFailure(input,
                    fact(FactType.BUSINESS_RULE, "温度必须高于5℃", evidenceKey, sources.get(index)),
                    "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void acceptsAUniqueInitialismExpansionPerClauseAndUsesNormalizedIndices() {
        List<String> sources = List.of(
                "API is deprecated; the application programming interface returns status",
                "\u3000Application Programming Interface returns status");
        for (int index = 0; index < sources.size(); index++) {
            String evidenceKey = "u" + index;
            var input = window("function-a", "window-" + index,
                    List.of(unit(evidenceKey, 1, sources.get(index))), List.of());
            String sourceQuote = index == 0
                    ? "the application programming interface returns status" : sources.get(index);
            assertThat(validator.validate(input, result("function-a", "window-" + index, List.of(
                    fact(FactType.OUTPUT, "API returns status", evidenceKey, sourceQuote)))).facts()).hasSize(1);
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsMovingAFieldBetweenDistinctCollectionsOrDeletingItsOtherOccurrence() {
        var input = window("function-a", "window-a", List.of(
                unit("u1", 1, "输出包含姓名和地址；输入包含手机号和验证码"),
                unit("u2", 2, "系统根据状态配置权限，结果包含角色")), List.of());

        assertFactFailure(input,
                fact(FactType.OUTPUT, "输出包含姓名和手机号", "u1", "输出包含姓名和地址；输入包含手机号和验证码"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertFactFailure(input,
                fact(FactType.BUSINESS_RULE, "结果包含状态和角色", "u2",
                        "系统根据状态配置权限，结果包含角色"),
                "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void acceptsAFieldSubsetWhoseLabelsContainModalWords() {
        String source = "The output must include whether administrators can edit and whether administrators can delete";
        var input = window("function-a", "window-a", List.of(unit("u1", 1, source)), List.of());

        assertThat(validator.validate(input, result("function-a", "window-a", List.of(
                fact(FactType.OUTPUT, "The output must include whether administrators can edit", "u1", source))))
                .facts()).hasSize(1);
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void acceptsRepeatedModifierOmissionAndOneLocalClauseFromALargerQuote() {
        String bounded = "温度必须高于2℃且必须低于8℃";
        var boundedInput = window("function-a", "window-a", List.of(unit("u1", 1, bounded)), List.of());
        assertThat(validator.validate(boundedInput, result("function-a", "window-a", List.of(
                fact(FactType.BUSINESS_RULE, "温度必须高于2℃且低于8℃", "u1", bounded)))).facts()).hasSize(1);

        String larger = "系统必须加密上传文件，并且管理员可以删除过期文件";
        var largerInput = window("function-a", "window-b", List.of(unit("u2", 1, larger)), List.of());
        assertThat(validator.validate(largerInput, result("function-a", "window-b", List.of(
                fact(FactType.BUSINESS_RULE, "系统必须加密上传文件", "u2", "系统必须加密上传文件"))))
                .facts()).hasSize(1);
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsNonContinuousAmbiguousOrDetachedInitialismExpansion() {
        List<String> sources = List.of(
                "Application, programming interface returns status",
                "Application programming interface returns status; Automated process integration logs errors",
                "Application programming interface returns status; the service logs errors");
        List<String> statements = List.of("API returns status", "API returns status", "API logs errors");
        for (int index = 0; index < sources.size(); index++) {
            String windowKey = "window-" + index;
            String evidenceKey = "u" + index;
            var input = window("function-a", windowKey,
                    List.of(unit(evidenceKey, 1, sources.get(index))), List.of());
            assertFactFailure(input,
                    fact(FactType.OUTPUT, statements.get(index), evidenceKey, sources.get(index)),
                    "FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        }
    }

    @Test
    void rejectsCompositeFactButKeepsAConditionalAtomicFact() {
        var input = window("function-a", "window-a", List.of(
                unit("u1", 1, "订单提交后显示成功；同时发送短信通知"),
                unit("u2", 2, "当订单有效时允许提交")), List.of());

        assertThatThrownBy(() -> validator.validate(input,
                result("function-a", "window-a", List.of(
                        fact(FactType.OUTPUT, "订单提交后显示成功；同时发送短信通知", "u1",
                                "订单提交后显示成功；同时发送短信通知")))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure ->
                        assertThat(failure.failure().code()).isEqualTo("FACT_ATOMICITY_INVALID"));

        var accepted = validator.validate(input, result("function-a", "window-a", List.of(
                fact(FactType.BUSINESS_RULE, "当订单有效时允许提交", "u2", "当订单有效时允许提交"))));
        assertThat(accepted.facts()).hasSize(1);
    }

    /** [Req-ID]: REQ-TGV2-004 */
    @Test
    void rejectsCommaSeparatedAndEnglishCoordinatedObligations() {
        var chinese = window("function-a", "window-a",
                List.of(unit("u1", 1, "系统必须新增用户，系统必须修改用户，系统必须删除用户")), List.of());
        assertThatThrownBy(() -> validator.validate(chinese,
                result("function-a", "window-a", List.of(
                        fact(FactType.BUSINESS_RULE, "系统必须新增用户，系统必须修改用户，系统必须删除用户", "u1",
                                "系统必须新增用户，系统必须修改用户，系统必须删除用户")))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure ->
                        assertThat(failure.failure().code()).isEqualTo("FACT_ATOMICITY_INVALID"));

        var english = window("function-a", "window-b",
                List.of(unit("u2", 1, "The system must create users and must delete users")), List.of());
        assertThatThrownBy(() -> validator.validate(english,
                result("function-a", "window-b", List.of(
                        fact(FactType.BUSINESS_RULE, "The system must create users and must delete users", "u2",
                                "The system must create users and must delete users")))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure ->
                        assertThat(failure.failure().code()).isEqualTo("FACT_ATOMICITY_INVALID"));
    }

    @Test
    void rejectsMultipleIndependentObligationsWithoutSentencePunctuation() {
        List<String> composites = List.of(
                "系统必须创建用户并删除用户",
                "系统必须创建和删除用户",
                "系统必须加密并记录",
                "系统必须生成报告并发送通知",
                "系统必须整合并发布报告",
                "系统必须验证并发出通知",
                "用户可以提交订单并可以取消订单",
                "系统必须返回成功结果同时必须发送通知",
                "管理员允许编辑以及允许删除",
                "系统必须保存订单并且必须发送通知",
                "系统必须校验订单并禁止重复提交",
                "系统必须显示创建时间，系统必须保存审计记录",
                "系统必须保存订单且必须发送通知",
                "输出必须包含姓名和地址，并且系统必须发送通知",
                "输出必须包含姓名和系统必须发送通知",
                "输出必须包含姓名和系统必须记录用户是否登录",
                "输出必须包含状态和平台发送通知",
                "系统必须在响应时间低于1秒时返回结果并且系统必须在错误率高于5%时发送告警",
                "当输入有效时，系统必须显示创建时间并且系统必须发送通知",
                "The system must create users and must delete users",
                "The system must create users and delete users",
                "The output must include an identifier and the system must send a notification",
                "The output must include status and the platform sends a notification",
                "The output must include an identifier and the system must record whether a user is signed in");

        for (String composite : composites) {
            var input = window("function-a", "window-a", List.of(unit("u1", 1, composite)), List.of());

            Throwable failure = catchThrowable(() -> validator.validate(input,
                    result("function-a", "window-a", List.of(
                            fact(FactType.BUSINESS_RULE, composite, "u1", composite)))));
            assertThat(failure)
                    .as("composite fact: %s", composite)
                    .isInstanceOfSatisfying(StructuredValidationException.class, validationFailure ->
                            assertThat(validationFailure.failure().code()).isEqualTo("FACT_ATOMICITY_INVALID"));
        }

        String conditional = "当订单有效且金额充足时，系统可以提交订单";
        var accepted = validator.validate(
                window("function-a", "window-a", List.of(unit("u1", 1, conditional)), List.of()),
                result("function-a", "window-a", List.of(
                        fact(FactType.BUSINESS_RULE, conditional, "u1", conditional))));
        assertThat(accepted.facts()).hasSize(1);

        String indivisiblePredicate = "系统必须合并订单";
        var indivisibleAccepted = validator.validate(
                window("function-a", "window-a", List.of(unit("u1", 1, indivisiblePredicate)), List.of()),
                result("function-a", "window-a", List.of(
                        fact(FactType.BUSINESS_RULE, indivisiblePredicate, "u1", indivisiblePredicate))));
        assertThat(indivisibleAccepted.facts()).hasSize(1);
    }

    /** [Req-ID]: REQ-TGV2-004 */
    @Test
    void acceptsOneAtomicObligationWithFieldListsComparisonsAndCompoundConditions() {
        List<FactFixture> atomicFacts = List.of(
                new FactFixture(FactType.INPUT, "输入包含起止时间和批次号"),
                new FactFixture(FactType.OUTPUT, "结果展示批次号、实测温度、判定结果和检测时间"),
                new FactFixture(FactType.OUTPUT, "输出包含创建时间、更新时间和删除标记"),
                new FactFixture(FactType.BUSINESS_RULE, "温度低于2.0℃或高于8.0℃时拒绝放行"),
                new FactFixture(FactType.BUSINESS_RULE, "导出记录数必须与当前查询结果一致"),
                new FactFixture(FactType.BUSINESS_RULE, "温度必须高于2℃且必须低于8℃"),
                new FactFixture(FactType.OUTPUT, "输出必须包含是否允许编辑、是否允许删除"),
                new FactFixture(FactType.OUTPUT, "输出必须包含用户是否可以编辑和用户是否可以删除"),
                new FactFixture(FactType.OUTPUT, "页面必须展示创建时间和允许操作列表"),
                new FactFixture(FactType.OUTPUT, "状态‘A’表示通过，‘B’表示拒绝"),
                new FactFixture(FactType.OUTPUT, "状态‘A’表示通过，状态‘B’表示拒绝"),
                new FactFixture(FactType.BUSINESS_RULE, "如果输入有效，系统保存订单"),
                new FactFixture(FactType.BUSINESS_RULE, "当输入有效时，系统保存订单"),
                new FactFixture(FactType.BUSINESS_RULE, "当创建时间早于更新时间时，系统保存订单"),
                new FactFixture(FactType.BUSINESS_RULE, "当创建时间、更新时间必须一致，且状态必须有效时，系统允许提交"),
                new FactFixture(FactType.BUSINESS_RULE, "当创建时间，更新时间必须一致时，系统允许提交"),
                new FactFixture(FactType.BUSINESS_RULE, "当温度必须高于2℃且必须低于8℃时系统允许放行"),
                new FactFixture(FactType.BUSINESS_RULE, "仅当管理员安排工时，系统允许提交"),
                new FactFixture(FactType.BUSINESS_RULE, "系统必须支持并发任务"),
                new FactFixture(FactType.BUSINESS_RULE, "系统必须支持并发任务调度"),
                new FactFixture(FactType.BUSINESS_RULE, "系统必须合并重复记录"),
                new FactFixture(FactType.BUSINESS_RULE, "系统必须同时支持中文和英文"),
                new FactFixture(FactType.OUTPUT,
                        "The output must include whether administrators can edit and whether administrators can delete"),
                new FactFixture(FactType.OUTPUT, "The page must display created and updated timestamps"));

        for (FactFixture fixture : atomicFacts) {
            Throwable failure = catchThrowable(() -> validator.validate(
                    window("function-a", "window-a", List.of(unit("u1", 1, fixture.statement())), List.of()),
                    result("function-a", "window-a", List.of(
                            fact(fixture.type(), fixture.statement(), "u1", fixture.statement())))));

            assertThat(failure).as("atomic fact: %s", fixture.statement()).isNull();
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void distinguishesGenericConditionConsequencesFromIndependentCommaActions() {
        List<String> accepted = List.of(
                "只要满足规定工时，系统允许提交",
                "仅当管理员批准时，系统返回记录",
                "在订单提交后，平台返回状态",
                "在用户提交后，平台返回状态");
        for (String statement : accepted) {
            assertThatCode(() -> validator.validate(
                    window("function-a", "window-a", List.of(unit("u1", 1, statement)), List.of()),
                    result("function-a", "window-a", List.of(
                            fact(FactType.BUSINESS_RULE, statement, "u1", statement)))))
                    .as("condition fixture: %s", statement)
                    .doesNotThrowAnyException();
        }

        List<String> rejected = List.of(
                "用户提交后，平台返回状态",
                "管理员负责售后，平台发送通知",
                "系统记录用时，平台返回状态",
                "记录用时，平台返回状态",
                "仅当审批通过时，系统返回记录，平台发送通知");
        for (String statement : rejected) {
            assertFactFailure(window("function-a", "window-a", List.of(unit("u1", 1, statement)), List.of()),
                    fact(FactType.BUSINESS_RULE, statement, "u1", statement), "FACT_ATOMICITY_INVALID");
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void acceptsOnlyOneCollectionPredicateAndRejectsIndependentQuotedOrCollectionActions() {
        List<String> accepted = List.of(
                "输出包含“状态”，“标识”",
                "输出包含是否启用，是否可编辑",
                "状态'A'表示通过，'B'表示拒绝");
        for (String statement : accepted) {
            assertThatCode(() -> validator.validate(
                    window("function-a", "window-a", List.of(unit("u1", 1, statement)), List.of()),
                    result("function-a", "window-a", List.of(
                            fact(FactType.OUTPUT, statement, "u1", statement)))))
                    .as("collection fixture: %s", statement)
                    .doesNotThrowAnyException();
        }

        List<String> rejected = List.of(
                "输出包含状态，记录日志",
                "输出包含状态，外部系统发送通知",
                "输出包含状态，账户",
                "输出包含用户标识，系统状态",
                "系统发送通知，输出包含状态",
                "系统返回‘成功’，平台记录‘日志’",
                "状态“A”表示通过，“B”表示拒绝并且平台发送通知");
        for (String statement : rejected) {
            assertFactFailure(window("function-a", "window-a", List.of(unit("u1", 1, statement)), List.of()),
                    fact(FactType.OUTPUT, statement, "u1", statement), "FACT_ATOMICITY_INVALID");
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsUnclosedQuotedCommaClausesInsteadOfSkippingAtomicityValidation() {
        String statement = "系统接收申请，“平台发送通知，系统记录日志";

        assertFactFailure(window("function-a", "window-a", List.of(unit("u1", 1, statement)), List.of()),
                fact(FactType.BUSINESS_RULE, statement, "u1", statement), "FACT_ATOMICITY_INVALID");
    }

    @Test
    void doesNotMistakeLexicalConcurrencyOrMergeTermsForCoordinatedObligations() {
        for (String atomic : List.of("系统支持并发任务", "系统合并重复记录", "系统及时通知用户")) {
            var accepted = validator.validate(
                    window("function-a", "window-a", List.of(unit("u1", 1, atomic)), List.of()),
                    result("function-a", "window-a", List.of(
                            fact(FactType.BUSINESS_RULE, atomic, "u1", atomic))));

            assertThat(accepted.facts()).hasSize(1);
        }
    }

    private static RequirementFactExtractionV2Input window(String functionKey, String windowKey,
            List<RequirementFactExtractionV2Input.MaterialUnit> units,
            List<RequirementFactExtractionV2Input.MaterialUnit> context) {
        return new RequirementFactExtractionV2Input(functionKey, "功能", "模块/功能", "",
                "material", com.testcaseagent.knowledgeagent.MaterialContentTypeKey.REQUIREMENTS_SPEC,
                windowKey, units, context);
    }

    private static RequirementFactExtractionV2Input.MaterialUnit unit(String key, int ordinal, String content) {
        return new RequirementFactExtractionV2Input.MaterialUnit(key, ordinal, content);
    }

    private static RequirementFactExtractionV2Result result(String functionKey, String windowKey,
            List<RequirementFactExtractionV2Result.RequirementFact> facts) {
        return new RequirementFactExtractionV2Result(functionKey, windowKey, facts, List.of());
    }

    private static RequirementFactExtractionV2Result.RequirementFact fact(FactType type, String statement,
            String evidenceKey, String quote) {
        return new RequirementFactExtractionV2Result.RequirementFact(type, statement,
                List.of(new StructuredSourceQuoteV2(evidenceKey, quote)));
    }

    private void assertFactFailure(RequirementFactExtractionV2Input input,
            RequirementFactExtractionV2Result.RequirementFact fact, String expectedCode) {
        assertThatThrownBy(() -> validator.validate(input,
                result(input.functionKey(), input.windowKey(), List.of(fact))))
                .isInstanceOfSatisfying(StructuredValidationException.class, failure ->
                        assertThat(failure.failure().code()).isEqualTo(expectedCode));
    }

    private record FactFixture(FactType type, String statement) {
    }
}
