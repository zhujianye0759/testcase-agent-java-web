package com.testcaseagent.knowledgeagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Scope construction tests for isolated structured Skills. [Req-ID]: REQ-SKI-002 */
class StructuredSkillScopeTest {

    /** [Req-ID]: REQ-SKI-002 */
    @Test
    void createsOneExactSystemScopeFromTheFrozenRequirementScope() {
        StructuredSkillScope scope = StructuredSkillScope.from(new RequirementScope("kb-1", "system-1", "version-1",
                "requirements_spec", "project-1", List.of(new RequirementDocumentCoordinate("doc-2"),
                        new RequirementDocumentCoordinate("doc-1"))));

        assertThat(scope.knowledgeBaseIds()).containsExactly("kb-1");
        assertThat(scope.knowledgeIds()).containsExactly("doc-1", "doc-2");
        assertThat(scope.systemScopes()).singleElement().satisfies(value -> {
            assertThat(value.knowledgeBaseId()).isEqualTo("kb-1");
            assertThat(value.versionId()).isEqualTo("version-1");
            assertThat(value.projectId()).isEqualTo("project-1");
            assertThat(value.knowledgeIds()).containsExactly("doc-1", "doc-2");
        });
    }

    /** [Req-ID]: REQ-SKI-002 */
    @Test
    void rejectsRequirementScopesWithoutAProjectBeforeNetworkAccess() {
        assertThatThrownBy(() -> StructuredSkillScope.from(new RequirementScope("kb-1", "system-1", "version-1",
                "requirements_spec", null, List.of(new RequirementDocumentCoordinate("doc-1")))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
