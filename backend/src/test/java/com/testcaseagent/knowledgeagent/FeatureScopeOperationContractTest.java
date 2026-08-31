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

    /** [Req-ID]: REQ-FTG-013 */
    @Test
    void extractionCarriesOptionalAdjacentContextButKeepsTargetOwnershipSeparate() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var input = new FunctionListExtractionInput("material-1", "功能清单",
                List.of(new FunctionListExtractionInput.Unit("unit-10", 10, "目标功能")),
                List.of(new FunctionListExtractionInput.Unit("unit-9", 9, "前文"),
                        new FunctionListExtractionInput.Unit("unit-11", 11, "后文")));

        String json = mapper.writeValueAsString(input);

        assertThat(json).contains("\"context_units\"");
        assertThat(input.units()).extracting(FunctionListExtractionInput.Unit::unitKey).containsExactly("unit-10");
        assertThat(input.contextUnits()).extracting(FunctionListExtractionInput.Unit::unitKey)
                .containsExactly("unit-9", "unit-11");
    }
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    /** Historical V1 payloads remain readable; production stage-gap orchestration is separately locked to V2. */
    @Test
    void v1ReconcileInputAndResultRemainReadableForHistoricalCompatibility() throws Exception {
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

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void v2CanonicalBytesMatchTheFrozenCrossRepositoryFixturesWithoutATrailingNewline() {
        var catalog = new FeatureScopeReconciliationPageInput.GlobalCatalog(
                List.of(new FeatureScopeReconciliationPageInput.FunctionListItem(
                        "item-1", "登录", "desc", List.of("u-item"))), List.of());
        var refs = List.of(new FeatureScopeReconciliationPageInput.SourceRef(
                FeatureScopeReconciliationPageInput.SourceType.FUNCTION_LIST_ITEM, "item-1"));

        assertThat(FeatureScopeReconciliationV2Canonicalizer.catalogSha256(catalog))
                .isEqualTo("19ad1b939ba1ad03bf5e30772839a0754b789d09e07b048f37638c5e976c7a28");
        assertThat(FeatureScopeReconciliationV2Canonicalizer.pageKey("run-handler", refs))
                .isEqualTo("975d318e9fc3cb2a8802d25fc43234537e8494987fd31a428a3bb054696ec463");
        assertThat(FeatureScopeReconciliationV2Canonicalizer.reconciliationKey("run-handler",
                FeatureScopeReconciliationResult.Classification.FUNCTION_LIST_ONLY,
                FeatureScopeReconciliationResult.ConfirmationStatus.CONFIRMED, refs))
                .isEqualTo("7bfe836f7b4f18eda3ef69d1e22c2cb2aa5fa0dfcb0d907613a1e7bc12a1b03e");
        assertThat(new String(FeatureScopeReconciliationV2Canonicalizer.canonicalSourceRefsJson(refs),
                java.nio.charset.StandardCharsets.UTF_8)).doesNotEndWith("\n");
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
                  {"path":"订单/提交","description":"提交订单","target_quote":"功能一","evidence_keys":["unit-33"]}]}
                """, resultType);

        JsonNode serializedInput = mapper.valueToTree(input);
        JsonNode serializedResult = mapper.valueToTree(result);
        assertThat(serializedInput.path("units").get(0).path("ordinal").asInt()).isEqualTo(33);
        assertThat(serializedInput.path("units").get(1).path("ordinal").asInt()).isEqualTo(34);
        assertThat(serializedResult.path("function_list_items").get(0).has("item_key")).isFalse();
        assertThat(serializedResult.path("function_list_items").get(0).path("target_quote").asText())
                .isEqualTo("功能一");
        assertThat(serializedResult.path("function_list_items").get(0).path("evidence_keys")).isNotEmpty();
    }

    /** [Req-ID]: REQ-FTG-014 */
    @Test
    void rejectsAnExtractionItemWithoutTheRequiredTargetQuote() {
        assertThatThrownBy(() -> mapper.readValue("""
                {"operation":"extract_function_list","function_list_items":[
                  {"path":"订单/提交","description":"提交订单","evidence_keys":["unit-33"]}]}
                """, FunctionListExtractionResult.class)).isInstanceOf(Exception.class);
    }

    /** [Req-ID]: REQ-FTG-014 */
    @Test
    void rejectsFunctionListTargetQuotesLongerThan512UnicodeCodePoints() {
        String quote = "功".repeat(513);
        String json = """
                {"operation":"extract_function_list","function_list_items":[
                  {"path":"功能","description":"说明","target_quote":"%s","evidence_keys":["unit-33"]}]}
                """.formatted(quote);

        assertThatThrownBy(() -> mapper.readValue(json, FunctionListExtractionResult.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
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
