package com.testcaseagent.scope;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Specifies the public requirement-evidence trust boundary.
 *
 * [Test-Ref]: ScopePolicyTest
 * [Req-ID]: REQ-SCP-001, REQ-SCP-002
 */
class ScopePolicyTest {

    private final ScopePolicy scopePolicy = new ScopePolicy();

    @Test
    void rejectsEvidenceOutsideTheFrozenRequirementVersion() {
        RequirementScope scope = RequirementScope.freeze(
                "knowledge-strategy",
                "system-strategy",
                "version-1.0",
                "admission-material",
                "project-strategy",
                List.of(new RequirementDocumentCoordinate("document-login")));

        EvidenceCoordinate otherVersionEvidence = new EvidenceCoordinate(
                "knowledge-strategy",
                "system-strategy",
                "version-1.1",
                "admission-material",
                "project-strategy",
                "document-login",
                "功能清单/登录");

        assertThatThrownBy(() -> scopePolicy.requireInRequirementScope(scope, otherVersionEvidence))
                .isInstanceOf(ScopeViolation.class)
                .hasMessageContaining("outside frozen RequirementScope");
    }

    @Test
    void rejectsAChangedRetrySnapshotEvenWhenItsDocumentIsTheSame() {
        RequirementScope original = scope();
        RequirementScope changedVersion = RequirementScope.freeze(
                "knowledge-strategy",
                "system-strategy",
                "version-1.1",
                "admission-material",
                "project-strategy",
                List.of(new RequirementDocumentCoordinate("document-login")));

        assertThatThrownBy(() -> scopePolicy.requireSameRetryScope(original, changedVersion))
                .isInstanceOf(ScopeViolation.class)
                .hasMessageContaining("Retry scope differs");
    }

    @Test
    void createsTheSameHashForEquivalentDocumentOrder() {
        RequirementScope first = RequirementScope.freeze(
                "knowledge-strategy", "system-strategy", "version-1.0", "admission-material", "project-strategy",
                List.of(new RequirementDocumentCoordinate("document-a"), new RequirementDocumentCoordinate("document-b")));
        RequirementScope reordered = RequirementScope.freeze(
                "knowledge-strategy", "system-strategy", "version-1.0", "admission-material", "project-strategy",
                List.of(new RequirementDocumentCoordinate("document-b"), new RequirementDocumentCoordinate("document-a")));

        assertThat(first.scopeHash()).isEqualTo(reordered.scopeHash());
    }

    @Test
    void acceptsMissingEvidenceProjectWhenTheFrozenScopeHasNoProjectButStillRejectsOtherDocuments() {
        RequirementScope unprojectedScope = RequirementScope.freeze(
                "knowledge-strategy", "system-strategy", "version-1.0", "admission-material", null,
                List.of(new RequirementDocumentCoordinate("document-login")));
        EvidenceCoordinate permitted = new EvidenceCoordinate(
                "knowledge-strategy", "system-strategy", "version-1.0", "admission-material", " ",
                "document-login", "功能清单/登录");
        EvidenceCoordinate otherDocument = new EvidenceCoordinate(
                "knowledge-strategy", "system-strategy", "version-1.0", "admission-material", null,
                "document-admin", "功能清单/管理员");

        scopePolicy.requireInRequirementScope(unprojectedScope, permitted);
        assertThatThrownBy(() -> scopePolicy.requireInRequirementScope(unprojectedScope, otherDocument))
                .isInstanceOf(ScopeViolation.class);
        assertThat(unprojectedScope.scopeHash()).isEqualTo(RequirementScope.freeze(
                "knowledge-strategy", "system-strategy", "version-1.0", "admission-material", " ",
                List.of(new RequirementDocumentCoordinate("document-login"))).scopeHash());
    }

    @Test
    void rejectsDifferentEvidenceProjectWhenTheFrozenScopeSpecifiesOne() {
        EvidenceCoordinate otherProject = new EvidenceCoordinate(
                "knowledge-strategy", "system-strategy", "version-1.0", "admission-material", "project-other",
                "document-login", "功能清单/登录");

        assertThatThrownBy(() -> scopePolicy.requireInRequirementScope(scope(), otherProject))
                .isInstanceOf(ScopeViolation.class);
    }

    /** [Req-ID]: REQ-SMS-003 */
    @Test
    void derivesOneDocumentAuthorizationWithoutChangingTheFrozenTaskSnapshot() {
        RequirementScope frozen = RequirementScope.freeze(
                "knowledge-strategy", "system-strategy", "version-1.0", "admission-material", "project-strategy",
                List.of(new RequirementDocumentCoordinate("document-function"),
                        new RequirementDocumentCoordinate("document-review")));

        RequirementScope reviewScope = frozen.singleDocumentAuthorization("document-review");

        assertThat(reviewScope.documents()).extracting(RequirementDocumentCoordinate::documentId)
                .containsExactly("document-review");
        assertThat(reviewScope.knowledgeBaseId()).isEqualTo(frozen.knowledgeBaseId());
        assertThat(frozen.documents()).extracting(RequirementDocumentCoordinate::documentId)
                .containsExactly("document-function", "document-review");
        assertThatThrownBy(() -> frozen.singleDocumentAuthorization("document-outside"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the frozen RequirementScope");
    }

    private static RequirementScope scope() {
        return RequirementScope.freeze(
                "knowledge-strategy",
                "system-strategy",
                "version-1.0",
                "admission-material",
                "project-strategy",
                List.of(new RequirementDocumentCoordinate("document-login")));
    }
}
