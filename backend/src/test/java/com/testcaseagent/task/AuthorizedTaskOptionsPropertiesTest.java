package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.testcaseagent.fewshot.ExampleQualityKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the server-only option fixture retains every narrowed admission type.
 *
 * [Test-Ref]: AuthorizedTaskOptionsPropertiesTest
 * [Req-ID]: REQ-KAG-006, REQ-SCP-004
 */
class AuthorizedTaskOptionsPropertiesTest {

    @Test
    void convertsOneConfiguredOptionWithMultipleAdmissionTypesWithoutWideningItsDocumentList() {
        AuthorizedTaskOptionsProperties.Option option = new AuthorizedTaskOptionsProperties.Option();
        option.setId("strategic-v1-admission");
        option.setLabel("战略运管 V1.0 准入材料");
        option.setAgentId("agent-1");
        option.setRequirementKnowledgeBaseId("requirement-kb");
        option.setSystemId("zlyg");
        option.setVersionId("version-v1");
        option.setProjectId("project-1");
        option.setRequirementDocumentIds(List.of("function-list", "work-order-plan"));
        option.setRequirementAdmissionTypeKeys(List.of("function_list", "work_order_plan"));
        option.setExampleKnowledgeBaseId("example-kb");
        option.setExampleGoodDocumentIds(List.of("good-example"));
        option.setExampleBadDocumentIds(List.of("bad-example"));

        AuthorizedTaskOptionsProperties properties = new AuthorizedTaskOptionsProperties();
        properties.setOptions(List.of(option));

        TaskScopeOption resolved = properties.toTaskScopeOptions().get(0);

        assertThat(resolved.agentId()).isEqualTo("agent-1");
        assertThat(resolved.requirementScope().documents()).extracting(document -> document.documentId())
                .containsExactly("function-list", "work-order-plan");
        assertThat(resolved.requirementAdmissionTypeKeys()).containsExactly("function_list", "work_order_plan");
        assertThat(resolved.exampleScope().expectedQualityKinds()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "good-example", ExampleQualityKind.GOOD_CASE, "bad-example", ExampleQualityKind.BAD_CASE));
    }
}
