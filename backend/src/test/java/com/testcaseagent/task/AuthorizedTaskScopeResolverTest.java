package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.markdown.MarkdownFeatureRow;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** [Req-ID]: REQ-KAG-006, REQ-SCP-004, REQ-FEW-001, REQ-WEB-008 */
class AuthorizedTaskScopeResolverTest {
    private final AuthorizedTaskScopeResolver resolver = new AuthorizedTaskScopeResolver(List.of(new TaskScopeOption(
            "strategic-v1-admission", "战略运管 V1.0 准入材料", "configured-agent",
            new RequirementScope("requirement-kb", "strategy-system", "version-v1", "admission_material", null,
                    List.of(new RequirementDocumentCoordinate("requirements-document"))),
            new ExampleScope("example-kb", List.of("example-doc")), "requirements_spec")));

    @Test
    void resolvesAllImmediatelyWithoutBrowserFeatureIdsOrSynchronousDiscovery() {
        CreateGenerationTaskRequest request = resolver.resolve(new CreateGenerationTaskCommand(GenerationTaskMode.ALL, "",
                FewShotPolicy.AUTO, "markdown-1.0", "1.0", "strategic-v1-admission", ""));
        assertThat(request.featureIds()).isEmpty();
        assertThat(request.featureId()).isEqualTo("all-pending");
    }

    @Test
    void resolvesNaturalLanguageFeatureToServerOwnedId() {
        CreateGenerationTaskRequest request = resolver.resolve(command("strategic-v1-admission"));
        assertThat(request.featureId()).startsWith("feature-");
        assertThat(request.prompt()).contains("用户登录与忘记密码");
    }

    @Test
    void freezesBackgroundDiscoveredRowsIntoStableServerOwnedBatchIdsInMarkdownOrder() {
        CreateGenerationTaskRequest pending = resolver.resolve(new CreateGenerationTaskCommand(GenerationTaskMode.ALL, "",
                FewShotPolicy.AUTO, "markdown-1.0", "1.0", "strategic-v1-admission", ""));
        CreateGenerationTaskRequest frozen = pending.withDiscoveredFeatures(List.of(
                new MarkdownFeatureRow(1, "用户登录"), new MarkdownFeatureRow(2, "用户退出")));
        assertThat(frozen.featureIds()).hasSize(2).doesNotContain("用户登录", "用户退出");
        assertThat(frozen.featureIds().stream().map(frozen.featurePaths()::get).toList())
                .containsExactly("用户登录", "用户退出");
    }

    @Test
    void rejectsArbitraryBrowserScopeIds() {
        assertThatThrownBy(() -> resolver.resolve(command("ungranted"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not authorized");
    }

    @Test
    void rejectsAnExampleLibraryThatReusesTheFormalRequirementKnowledgeBase() {
        assertThatThrownBy(() -> new TaskScopeOption("invalid", "不可用范围", "configured-agent",
                new RequirementScope("requirement-kb", "strategy-system", "version-v1", "admission_material", null,
                        List.of(new RequirementDocumentCoordinate("requirements-document"))),
                new ExampleScope("requirement-kb", List.of("example-document")), "requirements_spec"))
                .isInstanceOf(com.testcaseagent.fewshot.FewShotSelectionViolation.class);
    }

    private static CreateGenerationTaskCommand command(String scopeOptionId) {
        return new CreateGenerationTaskCommand(GenerationTaskMode.FEATURE, "用户登录与忘记密码", FewShotPolicy.AUTO,
                "markdown-1.0", "1.0", scopeOptionId, "");
    }
}
