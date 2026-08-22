package com.testcaseagent.knowledgeagent;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Synchronous HTTP consumer tests for the fixed isolated Skill endpoint. [Req-ID]: REQ-SKI-002, REQ-SKI-005 */
class StructuredSkillWireMockTest {
    @RegisterExtension static WireMockExtension kee = WireMockExtension.newInstance().build();

    /** [Req-ID]: REQ-SKI-002, REQ-SKI-004 */
    @Test
    void sendsOnlyTheSixStructuredFieldsAndDoesNotCreateSessionsOrUseSse() {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill")).willReturn(okJson(success())));

        StructuredSkillSuccessEnvelope<RequirementMaterialQualityReviewResult> response = adapter()
                .reviewRequirementMaterial(invocation());

        assertThat(response.success()).isTrue();
        assertThat(response.data().result().reviewFindings()).hasSize(1);
        kee.verify(postRequestedFor(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .withRequestBody(matchingJsonPath("$.agent_id", equalTo("agent-1")))
                .withRequestBody(matchingJsonPath("$.skill_name", equalTo("requirement-material-quality-review")))
                .withRequestBody(matchingJsonPath("$.knowledge_base_ids[0]", equalTo("kb-1")))
                .withRequestBody(matchingJsonPath("$.system_scopes[0].project_id", equalTo("project-1")))
                .withRequestBody(matchingJsonPath("$.input.units[0].ordinal", equalTo("33")))
                .withRequestBody(notMatching(".*\\\"query\\\".*")));
        kee.verify(0, postRequestedFor(urlEqualTo("/api/v1/sessions")));
    }

    /** [Req-ID]: REQ-SKI-005 */
    @Test
    void mapsOnlyTheStableErrorTypeWithoutRetainingErrorText() {
        kee.stubFor(post(urlEqualTo("/api/v1/agent-chat/session-1/isolated-skill"))
                .willReturn(okJson("{\"success\":false,\"error\":{\"details\":{\"type\":\"forbidden\",\"repair_attempted\":true},\"message\":\"secret\"}}")));

        assertThatThrownBy(() -> adapter().reviewRequirementMaterial(invocation()))
                .isInstanceOf(StructuredSkillExecutionException.class)
                .hasMessageContaining("forbidden").hasMessageNotContaining("secret");
    }

    private static WebClientKnowledgeAgentAdapter adapter() { return new WebClientKnowledgeAgentAdapter(kee.baseUrl() + "/api/v1", "test-key", Duration.ofSeconds(5), 1, 20_000); }
    private static RequirementMaterialQualityReviewInvocation invocation() { return new RequirementMaterialQualityReviewInvocation("session-1", "agent-1", new RequirementScope("kb-1", "system-1", "version-1", "requirements_spec", "project-1", List.of(new RequirementDocumentCoordinate("doc-1"))), new RequirementMaterialQualityReviewInput("material-1", MaterialContentTypeKey.REQUIREMENTS_SPEC, "需求", List.of(new RequirementMaterialQualityReviewInput.MaterialUnit("unit-33", 33, "内容")))); }
    private static String success() { return "{\"success\":true,\"data\":{\"schema_version\":\"1.0\",\"skill_name\":\"requirement-material-quality-review\",\"repair_attempted\":false,\"result\":{\"requirement_facts\":[],\"review_findings\":[{\"finding_key\":\"finding-1\",\"issue_type\":\"missing\",\"description\":\"描述\",\"evidence_keys\":[],\"test_design_impact\":\"影响\",\"current_project_recommendation\":\"建议\",\"design_center_guideline_recommendation\":\"规范建议\",\"handling_level\":\"improvement\"}]}}}"; }
}
