package com.testcaseagent.knowledgeagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract tests for the three fixed structured Skill inputs. [Req-ID]: REQ-SKI-003 */
class StructuredSkillInputContractTest {

    /** [Req-ID]: REQ-SKI-003 */
    @Test
    void preservesAContinuousGlobalMaterialSliceWithoutRenumbering() {
        List<RequirementMaterialQualityReviewInput.MaterialUnit> units = new ArrayList<>();
        for (int ordinal = 33; ordinal <= 64; ordinal++) {
            units.add(new RequirementMaterialQualityReviewInput.MaterialUnit("unit-" + ordinal, ordinal, "内容"));
        }
        RequirementMaterialQualityReviewInput input = new RequirementMaterialQualityReviewInput("material-1",
                MaterialContentTypeKey.REQUIREMENTS_SPEC, "需求说明", units);

        assertThat(input.units()).extracting(RequirementMaterialQualityReviewInput.MaterialUnit::ordinal)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(33, 64).boxed().toList());
    }

    /** [Req-ID]: REQ-SKI-003 */
    @Test
    void rejectsMaterialSliceGapsAndDuplicateKeys() {
        assertThatThrownBy(() -> new RequirementMaterialQualityReviewInput("material-1",
                MaterialContentTypeKey.REQUIREMENTS_SPEC, "需求说明", List.of(
                        new RequirementMaterialQualityReviewInput.MaterialUnit("unit-33", 33, "内容一"),
                        new RequirementMaterialQualityReviewInput.MaterialUnit("unit-35", 35, "内容二"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeatureScopeReconciliationInput(List.of(
                new FeatureScopeReconciliationInput.FunctionListItem("item-1", "功能", "描述", List.of("evidence-1")),
                new FeatureScopeReconciliationInput.FunctionListItem("item-1", "功能2", "描述", List.of("evidence-2"))), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** [Req-ID]: REQ-SKI-003 */
    @Test
    void countsKeyBoundsAsUnicodeCharactersRatherThanUtf8Bytes() {
        String chineseKey = "键".repeat(100);

        assertThat(new RequirementMaterialQualityReviewInput.MaterialUnit(chineseKey, 1, "内容").unitKey())
                .isEqualTo(chineseKey);
        assertThatThrownBy(() -> new RequirementMaterialQualityReviewInput.MaterialUnit("k".repeat(129), 1, "内容"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** [Req-ID]: REQ-SKI-003 */
    @Test
    void serializesTheFrozenFeatureAndTestPointEnumsAndFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FeatureScopeReconciliationInput reconciliation = new FeatureScopeReconciliationInput(
                List.of(new FeatureScopeReconciliationInput.FunctionListItem(
                        "item-1", "订单/提交", "提交订单", List.of("evidence-1"))),
                List.of(new FeatureScopeReconciliationInput.RequirementFact(
                        "fact-1", "提交订单", List.of("evidence-1"))));
        FunctionalTestcaseDesignInput testcase = new FunctionalTestcaseDesignInput("function-1", "提交订单",
                new FunctionalTestcaseDesignInput.TestPoint("point-1",
                        FunctionalTestcaseDesignInput.TestPointType.DEPENDENCY_FAILURE, "支付网关失败",
                        List.of("fact-1"), List.of("evidence-1"),
                        FunctionalTestcaseDesignInput.Basis.GENERAL_EXPERIENCE, List.of("缺少超时阈值")));

        assertThat(mapper.writeValueAsString(reconciliation))
                .contains("\"function_list_items\"", "\"requirement_facts\"", "\"item_key\"", "\"evidence_keys\"");
        assertThat(mapper.writeValueAsString(testcase))
                .contains("\"test_point_key\":\"point-1\"", "\"type\":\"dependency_failure\"",
                        "\"basis\":\"general_experience\"", "\"missing_information\"",
                        "\"formal_supports\":[]");
        assertThat(mapper.writeValueAsString(testcase)).doesNotContain("authoring_information");
    }

    /** [Req-ID]: REQ-FTG-004 */
    @Test
    void preservesCompleteBoundFormalSupportsInTheTestcaseDesignInput() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        FunctionalTestcaseDesignInput input = mapper.readValue(formalSupportInputJson(),
                FunctionalTestcaseDesignInput.class);
        var json = mapper.valueToTree(input);

        assertThat(json.path("formal_supports")).hasSize(1);
        assertThat(json.path("formal_supports").path(0).fieldNames()).toIterable().containsExactlyInAnyOrder(
                "fact_key", "function", "roles", "trigger_conditions", "inputs", "business_rules", "outputs",
                "permissions", "state_changes", "exception_handling", "external_dependencies", "evidence_keys",
                "evidence_texts");
        assertThat(List.of("roles", "trigger_conditions", "inputs", "business_rules", "outputs", "permissions",
                "state_changes", "exception_handling", "external_dependencies", "evidence_keys", "evidence_texts"))
                .allSatisfy(field -> assertThat(json.path("formal_supports").path(0).path(field).isArray()).isTrue());
        assertThat(json.path("formal_supports").path(0).path("fact_key").asText()).isEqualTo("fact-1");
        assertThat(json.path("formal_supports").path(0).path("inputs").path(0).asText()).isEqualTo("账号");
        assertThat(json.path("formal_supports").path(0).path("evidence_texts")).extracting(node -> node.asText())
                .containsExactly("用户提交账号和正确密码", "登录成功后进入首页");
        assertThat(json.path("formal_supports").path(0).path("evidence_keys"))
                .extracting(node -> node.asText()).containsExactly("unit-1", "unit-2");
    }

    /** [Req-ID]: REQ-FTG-004 */
    @Test
    void rejectsMissingEmptyOrMismatchedFormalSupportsBeforeTheNetworkBoundary() {
        var point = new FunctionalTestcaseDesignInput.TestPoint("point-1",
                FunctionalTestcaseDesignInput.TestPointType.NORMAL_BEHAVIOR, "账号登录",
                List.of("fact-1"), List.of("unit-1"),
                FunctionalTestcaseDesignInput.Basis.FORMAL_REQUIREMENT, List.of());
        var scope = new RequirementScope("kb-1", "system-1", "version-1", "requirements_spec", "project-1",
                List.of(new RequirementDocumentCoordinate("doc-1")));

        assertThatThrownBy(() -> new FunctionalTestcaseDesignInvocation("session-1", "agent-1", scope,
                new FunctionalTestcaseDesignInput("function-1", "账号登录", point)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("support text");
        assertThatThrownBy(() -> new FunctionalTestcaseDesignInvocation("session-1", "agent-1", scope,
                new FunctionalTestcaseDesignInput("function-1", "账号登录", point,
                        List.of(support("fact-other", List.of("账号正文"))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fact order");
        assertThatThrownBy(() -> new FormalSupport("fact-1", "账号登录", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of("unit-1", "unit-1"),
                List.of("重复正文", "重复正文")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> new FormalSupport("fact-1", "账号登录", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence");
        assertThatThrownBy(() -> new FunctionalTestcaseDesignInput.AuthoringInformation(null, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** [Req-ID]: REQ-FTG-004 */
    @Test
    void rejectsFormalSupportsOnAGeneralExperiencePoint() {
        var point = new FunctionalTestcaseDesignInput.TestPoint("point-1",
                FunctionalTestcaseDesignInput.TestPointType.BOUNDARY_VALUE, "未明确的经验边界",
                List.of(), List.of("unit-1"), FunctionalTestcaseDesignInput.Basis.GENERAL_EXPERIENCE,
                List.of("正式材料未说明边界"));
        var input = new FunctionalTestcaseDesignInput("function-1", "账号登录", point,
                List.of(support("fact-1", List.of("账号正文"))));

        assertThatThrownBy(input::requireExecutable)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry formal supports");
    }

    /** [Req-ID]: REQ-FTG-004 */
    @Test
    void preservesPreviouslyAdversarialTermsWhenTheFormalSupportExplicitlyContainsThem() {
        FormalSupport supported = new FormalSupport("fact-1", "手机号登录", List.of("已绑定手机号的用户"),
                List.of("用户提交手机号和正确密码"), List.of("手机号", "正确密码"), List.of(),
                List.of("系统签发 Token/Session 并允许访问受保护资源"), List.of(), List.of(), List.of(),
                List.of(), List.of("unit-1"), List.of("手机号登录成功后系统签发 Token/Session 并允许访问受保护资源"));
        var point = new FunctionalTestcaseDesignInput.TestPoint("point-1",
                FunctionalTestcaseDesignInput.TestPointType.NORMAL_BEHAVIOR, "手机号登录",
                List.of("fact-1"), List.of("unit-1"),
                FunctionalTestcaseDesignInput.Basis.FORMAL_REQUIREMENT, List.of());
        var input = new FunctionalTestcaseDesignInput("function-1", "手机号登录", point, List.of(supported));

        assertThat(input.formalSupports()).singleElement().satisfies(value -> {
            assertThat(value.inputs()).contains("手机号");
            assertThat(value.outputs()).contains("系统签发 Token/Session 并允许访问受保护资源");
        });
        assertThatCode(input::requireExecutable).doesNotThrowAnyException();
    }

    private static FormalSupport support(String factKey, List<String> evidenceTexts) {
        return new FormalSupport(factKey, "账号登录", List.of("已注册用户"), List.of("提交账号"),
                List.of("账号"), List.of(), List.of("进入首页"), List.of(), List.of(), List.of(), List.of(),
                List.of("unit-1"), evidenceTexts);
    }

    private static String formalSupportInputJson() {
        return """
                {
                  "function_key":"function-1",
                  "function_name":"账号登录",
                  "test_point":{
                    "test_point_key":"point-1",
                    "type":"normal_behavior",
                    "description":"用户提交账号和正确密码后进入首页",
                    "requirement_fact_keys":["fact-1"],
                    "evidence_keys":["unit-1","unit-2"],
                    "basis":"formal_requirement",
                    "missing_information":[]
                  },
                  "formal_supports":[{
                    "fact_key":"fact-1",
                    "function":"账号登录",
                    "roles":["已注册用户"],
                    "trigger_conditions":["用户提交账号和正确密码"],
                    "inputs":["账号","正确密码"],
                    "business_rules":[],
                    "outputs":["进入首页"],
                    "permissions":[],
                    "state_changes":["匿名变为已登录"],
                    "exception_handling":[],
                    "external_dependencies":[],
                    "evidence_keys":["unit-1","unit-2"],
                    "evidence_texts":["用户提交账号和正确密码","登录成功后进入首页"]
                  }]
                }
                """;
    }
}
