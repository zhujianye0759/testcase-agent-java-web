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
                {"reconciliations":[{"reconciliation_key":"rec-1","function_list_item_keys":["item-1"],
                "requirement_fact_keys":[],"classification":"function_list_only","evidence_keys":["evidence-1"],
                "scope_recommendation":"保留待确认","confirmation_status":"pending_confirmation"}]}
                """, FeatureScopeReconciliationResult.class);
        FunctionalTestcaseDesignResult testcase = mapper.readValue("""
                {"function_key":"function-1","test_point_key":"point-1","testcases":[{"case_key":"case-1",
                "title":"依赖失败","preconditions":[],"steps":[{"step_no":1,"action":"调用","expected":"提示失败"}],
                "requirement_fact_keys":[],"evidence_keys":[],"case_status":"pending_confirmation",
                "missing_information":["缺少超时阈值"]}]}
                """, FunctionalTestcaseDesignResult.class);

        assertThat(reconciliation.reconciliations().get(0).classification())
                .isEqualTo(FeatureScopeReconciliationResult.Classification.FUNCTION_LIST_ONLY);
        assertThat(testcase.testcases().get(0).caseStatus())
                .isEqualTo(FunctionalTestcaseDesignResult.CaseStatus.PENDING_CONFIRMATION);
        assertThatThrownBy(() -> mapper.readValue("""
                {"reconciliations":[{"reconciliation_key":"rec-1","function_list_item_keys":["item-1"],
                "requirement_fact_keys":[],"classification":"exact_match","evidence_keys":[],
                "scope_recommendation":"保留","confirmation_status":"confirmed","unknown":true}]}
                """, FeatureScopeReconciliationResult.class))
                .isInstanceOf(Exception.class);
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
    }
}
