package com.testcaseagent.knowledgeagent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Boundary tests for typed structured Skill results. [Req-ID]: REQ-SKI-004 */
class StructuredSkillResultContractTest {
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError("The JDK must provide SHA-256", exception);
        }
    }

    /** [Req-ID]: REQ-SKI-004 */
    @Test
    void rejectsAnEmptyMaterialReviewAndNonContinuousCaseSteps() {
        assertThatThrownBy(() -> new RequirementMaterialQualityReviewResult(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FunctionalTestcaseDesignResult("function-1", "point-1", List.of(
                new FunctionalTestcaseDesignResult.Testcase("case-1", "标题", List.of(), List.of(
                        new FunctionalTestcaseDesignResult.Step(2, "操作", "预期")), List.of(), List.of(),
                        FunctionalTestcaseDesignResult.CaseStatus.FORMAL, List.of()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** [Req-ID]: REQ-SKI-004 */
    @Test
    void deserializesFrozenV1ReconciliationAndTestcaseEnumsAndRejectsUnknownNestedFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        FeatureScopeReconciliationResult reconciliation = mapper.readValue("""
                {"operation":"reconcile","reconciliations":[{"reconciliation_key":"rec-1","function_list_item_keys":["item-1"],
                "requirement_fact_keys":[],"classification":"function_list_only","evidence_keys":["evidence-1"],
                "scope_recommendation":"保留待确认","confirmation_status":"pending_confirmation"}]}
                """, FeatureScopeReconciliationResult.class);
        FunctionalTestcaseDesignResult testcase = mapper.readValue("""
                {"function_key":"function-1","test_point_key":"point-1","testcases":[{"case_key":"case-1",
                "name":"依赖失败","title":"依赖失败","priority":"high","preconditions":[],
                "initialization":{"hardware_configuration":[],"software_configuration":[],"test_configuration":[],"parameter_configuration":[]},
                "inputs":[{"content":"调用","nature":"valid","source":"manual","method":"equivalence_partitioning","authenticity":"real","sequence":""}],
                "steps":[{"step_no":1,"action":"调用","expected":"提示失败","evaluation_criteria":"实际结果满足本步骤预期结果。","termination_or_error":"系统服务终止，或执行过程中无法执行下一步操作。","result_collection":"记录实际结果、提示信息及必要证据。"}],
                "expected_results":["提示失败"],"evaluation_criteria":"满足前提和约束且未触发终止条件，逐步执行并记录结果。","result_evaluation_criteria":"全部预期结果满足则通过，任一不满足则不通过。","termination_conditions":[],"result_collection":"记录实际结果、提示信息及必要证据。",
                "authoring_information":{"author":"","date":""},
                "requirement_fact_keys":[],"evidence_keys":[],"case_status":"pending_confirmation",
                "missing_information":["缺少超时阈值"]}]}
                """, FunctionalTestcaseDesignResult.class);

        assertThat(reconciliation.reconciliations().get(0).classification())
                .isEqualTo(FeatureScopeReconciliationResult.Classification.FUNCTION_LIST_ONLY);
        assertThat(testcase.testcases().get(0).caseStatus())
                .isEqualTo(FunctionalTestcaseDesignResult.CaseStatus.PENDING_CONFIRMATION);
        assertThatThrownBy(() -> mapper.readValue("""
                {"operation":"reconcile","reconciliations":[{"reconciliation_key":"rec-1","function_list_item_keys":["item-1"],
                "requirement_fact_keys":[],"classification":"exact_match","evidence_keys":[],
                "scope_recommendation":"保留","confirmation_status":"confirmed","unknown":true}]}
                """, FeatureScopeReconciliationResult.class))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> new FunctionalTestcaseDesignResult.Input("调用",
                FunctionalTestcaseDesignResult.InputNature.VALID, FunctionalTestcaseDesignResult.InputSource.MANUAL,
                FunctionalTestcaseDesignResult.TestMethod.OTHER, FunctionalTestcaseDesignResult.Authenticity.REAL, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FunctionalTestcaseDesignResult.AuthoringInformation(null, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void acceptsTheExactFrozenV2PageResultWithoutLegacyRelationCapsButKeepsOtherSkillBounds() throws Exception {
        assertThatThrownBy(() -> new FeatureScopeReconciliationResult(List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        var page = mapper.createObjectNode();
        page.put("operation", "reconcile_page");
        page.put("protocol_version", "2");
        page.put("run_key", "run-1");
        page.put("page_key", "b".repeat(64));
        var completedOwners = page.putArray("completed_owner_source_refs");
        completedOwners.addObject().put("source_type", "function_list_item").put("source_key", "item-000");
        var reconciliations = page.putArray("reconciliations");
        for (int index = 0; index < 201; index++) {
            var row = reconciliations.addObject();
            row.putObject("owner_source_ref")
                    .put("source_type", "function_list_item")
                    .put("source_key", "item-000");
            var itemKeys = row.putArray("function_list_item_keys");
            var evidenceKeys = row.putArray("evidence_keys");
            var referencedSources = mapper.createArrayNode();
            int referenceCount = index == 0 ? 101 : 1;
            for (int reference = 0; reference < referenceCount; reference++) {
                itemKeys.add("item-%03d".formatted(reference));
                evidenceKeys.add("evidence-%03d".formatted(reference));
                referencedSources.addObject()
                        .put("source_type", "function_list_item")
                        .put("source_key", "item-%03d".formatted(reference));
            }
            row.putArray("requirement_fact_keys").add("fact-%03d".formatted(index));
            referencedSources.addObject()
                    .put("source_type", "requirement_fact")
                    .put("source_key", "fact-%03d".formatted(index));
            row.put("classification", "insufficient_evidence");
            row.put("scope_recommendation", "完整核对后仍需确认");
            row.put("confirmation_status", "pending_confirmation");
            String relationIdentityBytes = "reconciliation-v2\nrun-1\ninsufficient_evidence\n"
                    + "pending_confirmation\n" + mapper.writeValueAsString(referencedSources);
            row.put("reconciliation_key", sha256(relationIdentityBytes));
            if (index == 0) {
                assertThat(row.path("reconciliation_key").asText())
                        .isNotEqualTo(sha256(relationIdentityBytes + "\n"));
            }
        }

        Class<?> resultType = Class.forName(
                "com.testcaseagent.knowledgeagent.FeatureScopeReconciliationPageResult");
        Object result = mapper.treeToValue(page, resultType);
        var resultJson = mapper.valueToTree(result);

        assertThat(resultJson.path("reconciliations")).hasSize(201);
        assertThat(resultJson.path("reconciliations").path(0).path("function_list_item_keys")).hasSize(101);
        assertThat(resultJson.path("reconciliations").path(0).path("evidence_keys")).hasSize(101);
        var unknownRelationField = page.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknownRelationField.path("reconciliations").path(0))
                .put("provider_explanation", "不得进入公开结果");
        assertThatThrownBy(() -> mapper.treeToValue(unknownRelationField, resultType))
                .isInstanceOf(Exception.class);

        List<String> tooManyReviewEvidence = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> "review-evidence-" + index).toList();
        assertThatThrownBy(() -> new RequirementMaterialQualityReviewResult.RequirementFact(
                "fact-1", "功能", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), tooManyReviewEvidence))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequirementMaterialQualityReviewResult.ReviewFinding("finding-1", null,
                "业务规则缺失", null, null, null, "需要补充规则", List.of("unit-1"),
                "影响测试设计", "本项目待确认", "设计中心补充规范",
                RequirementMaterialQualityReviewResult.HandlingLevel.BLOCKING))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
