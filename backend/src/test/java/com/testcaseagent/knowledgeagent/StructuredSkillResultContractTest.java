package com.testcaseagent.knowledgeagent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Boundary tests for typed structured Skill results. [Req-ID]: REQ-SKI-004 */
class StructuredSkillResultContractTest {
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
    void deserializesAllFrozenResultEnumsAndRejectsUnknownNestedFields() throws Exception {
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

    /** [Req-ID]: REQ-SKI-004 */
    @Test
    void rejectsFrozenResultArrayBounds() {
        List<FeatureScopeReconciliationResult.Reconciliation> tooMany = new ArrayList<>();
        for (int index = 1; index <= 201; index++) {
            tooMany.add(new FeatureScopeReconciliationResult.Reconciliation("rec-" + index,
                    List.of("item-" + index), List.of(), FeatureScopeReconciliationResult.Classification.FUNCTION_LIST_ONLY,
                    List.of(), "保留", FeatureScopeReconciliationResult.ConfirmationStatus.CONFIRMED));
        }

        assertThatThrownBy(() -> new FeatureScopeReconciliationResult(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeatureScopeReconciliationResult(tooMany))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequirementMaterialQualityReviewResult.ReviewFinding("finding-1", null,
                "业务规则缺失", null, null, null, "需要补充规则", List.of("unit-1"),
                "影响测试设计", "本项目待确认", "设计中心补充规范",
                RequirementMaterialQualityReviewResult.HandlingLevel.BLOCKING))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
