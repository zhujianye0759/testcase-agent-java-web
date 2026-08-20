package com.testcaseagent.knowledgeagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Consumer contract for the two strict feature-scope operations.
 *
 * [Req-ID]: REQ-SKI-003, REQ-SKI-004, REQ-STG-003
 */
class FeatureScopeOperationContractTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    void reconcileInputAndResultCarryTheExactMatchingOperation() throws Exception {
        var input = new FeatureScopeReconciliationInput(
                List.of(new FeatureScopeReconciliationInput.FunctionListItem(
                        "item-1", "订单/提交", "提交订单", List.of("evidence-1"))),
                List.of(new FeatureScopeReconciliationInput.RequirementFact(
                        "fact-1", "提交订单", List.of("evidence-1"))));
        var result = new FeatureScopeReconciliationResult(List.of(
                new FeatureScopeReconciliationResult.Reconciliation(
                        "rec-1", List.of("item-1"), List.of("fact-1"),
                        FeatureScopeReconciliationResult.Classification.EXACT_MATCH,
                        List.of("evidence-1"), "纳入范围",
                        FeatureScopeReconciliationResult.ConfirmationStatus.CONFIRMED)));

        assertThat(mapper.valueToTree(input).path("operation").asText()).isEqualTo("reconcile");
        assertThat(mapper.valueToTree(result).path("operation").asText()).isEqualTo("reconcile");
    }

    @Test
    void extractFunctionListUsesGlobalOrdinalSlicesAndNeverAcceptsAModelItemKey() throws Exception {
        Class<?> inputType = Class.forName(
                "com.testcaseagent.knowledgeagent.FunctionListExtractionInput");
        Class<?> resultType = Class.forName(
                "com.testcaseagent.knowledgeagent.FunctionListExtractionResult");

        Object input = mapper.readValue("""
                {"operation":"extract_function_list","material_key":"material-1","source_label":"功能清单",
                 "units":[{"unit_key":"unit-33","ordinal":33,"content":"功能一"},
                          {"unit_key":"unit-34","ordinal":34,"content":"功能二"}]}
                """, inputType);
        Object result = mapper.readValue("""
                {"operation":"extract_function_list","function_list_items":[
                  {"path":"订单/提交","description":"提交订单","evidence_keys":["unit-33"]}]}
                """, resultType);

        JsonNode serializedInput = mapper.valueToTree(input);
        JsonNode serializedResult = mapper.valueToTree(result);
        assertThat(serializedInput.path("units").get(0).path("ordinal").asInt()).isEqualTo(33);
        assertThat(serializedInput.path("units").get(1).path("ordinal").asInt()).isEqualTo(34);
        assertThat(serializedResult.path("function_list_items").get(0).has("item_key")).isFalse();
        assertThat(serializedResult.path("function_list_items").get(0).path("evidence_keys")).isNotEmpty();
    }

    @Test
    void rejectsUnknownMixedAndMismatchedOperations() {
        assertThatThrownBy(() -> new FeatureScopeReconciliationInput("extract_function_list", List.of(
                new FeatureScopeReconciliationInput.FunctionListItem("item-1", "订单", "提交", List.of("evidence-1"))), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FunctionListExtractionInput("reconcile", "material-1", "功能", List.of(
                new FunctionListExtractionInput.Unit("unit-1", 1, "内容"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.readValue("""
                {"operation":"extract_function_list","function_list_items":[]}
                """, FeatureScopeReconciliationResult.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue("""
                {"operation":"reconcile","function_list_items":[{"path":"订单","description":"提交","evidence_keys":["unit-1"]}]}
                """, FunctionListExtractionResult.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue("""
                {"operation":"extract_function_list","function_list_items":[{"item_key":"model-key","path":"订单","description":"提交","evidence_keys":["unit-1"]}]}
                """, FunctionListExtractionResult.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue("""
                {"operation":"unknown","material_key":"material-1","source_label":"功能","units":[{"unit_key":"unit-1","ordinal":1,"content":"内容"}]}
                """, FunctionListExtractionInput.class)).isInstanceOf(Exception.class);
    }
}
