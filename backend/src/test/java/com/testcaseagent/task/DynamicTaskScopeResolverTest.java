package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.testcaseagent.fewshot.ExampleQualityKind;
import com.testcaseagent.scope.DynamicScopeCatalogService;
import com.testcaseagent.scope.KnowledgeScopeCatalogPort;
import com.testcaseagent.scope.ScopeCatalogSnapshot;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** [Test-Ref]: DynamicTaskScopeResolverTest [Req-ID]: REQ-CAT-004, REQ-CAT-005, REQ-SCP-001, REQ-SCP-004 */
class DynamicTaskScopeResolverTest {

    @Test
    void revalidatesSelectedLeavesAndFreezesTheirExactDocumentUnion() {
        DynamicScopeCatalogService catalog = catalog();
        ScopeCatalogSnapshot snapshot = catalog.catalog(false);
        List<String> selected = snapshot.selections().keySet().stream().sorted().toList();
        DynamicTaskScopeResolver resolver = new DynamicTaskScopeResolver(catalog, profile());

        CreateGenerationTaskRequest request = resolver.resolve(command(selected));

        assertThat(request.requirementScope().knowledgeBaseId()).isEqualTo("requirement-kb");
        assertThat(request.requirementScope().projectId()).isNull();
        assertThat(request.requirementScope().documents()).extracting(document -> document.documentId())
                .containsExactly("function-doc", "work-order-doc");
        assertThat(request.requirementScope().documents()).extracting(document -> document.materialTypeKey())
                .containsExactly("function_list", "work_order_plan");
        assertThat(request.requirementAdmissionTypeKeys()).containsExactlyInAnyOrder("function_list", "work_order_plan");
        assertThat(request.exampleScope().knowledgeBaseId()).isEqualTo("example-kb");
        assertThat(request.exampleScope().expectedQualityKinds()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "good-example", ExampleQualityKind.GOOD_CASE, "bad-example", ExampleQualityKind.BAD_CASE));
    }

    @Test
    void rejectsUnknownRawOrEmptySelectionsWithoutWidening() {
        DynamicTaskScopeResolver resolver = new DynamicTaskScopeResolver(catalog(), profile());

        assertThatThrownBy(() -> resolver.resolve(command(List.of("requirement-kb"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("刷新");
        assertThatThrownBy(() -> new CreateGenerationTaskCommand(GenerationTaskMode.ALL, "", FewShotPolicy.AUTO,
                "markdown-1.0", "1.0", List.of(), ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("材料范围");
    }

    @Test
    void rejectsASelectionWhoseDocumentWhitelistChangedAfterThePageWasRendered() {
        MutableCatalogPort port = new MutableCatalogPort();
        DynamicScopeCatalogService catalog = new DynamicScopeCatalogService(port, Duration.ofHours(1), Clock.systemUTC());
        String selected = catalog.catalog(false).selections().keySet().iterator().next();
        port.documentAvailable = false;

        DynamicTaskScopeResolver resolver = new DynamicTaskScopeResolver(catalog, profile());
        assertThatThrownBy(() -> resolver.resolve(command(List.of(selected))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("刷新");
    }

    @Test
    void rejectsMaterialLeavesFromDifferentKnowledgeCoordinates() {
        DynamicScopeCatalogService catalog = new DynamicScopeCatalogService(
                new MixedCatalogPort(), Duration.ofHours(1), Clock.systemUTC());
        List<String> selections = catalog.catalog(false).selections().keySet().stream().sorted().toList();

        assertThatThrownBy(() -> new DynamicTaskScopeResolver(catalog, profile()).resolve(command(selections)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("同一知识库");
    }

    private static CreateGenerationTaskCommand command(List<String> selected) {
        return new CreateGenerationTaskCommand(GenerationTaskMode.ALL, "", FewShotPolicy.AUTO,
                "markdown-1.0", "1.0", selected, "");
    }

    private static TaskGenerationProfileProperties profile() {
        TaskGenerationProfileProperties properties = new TaskGenerationProfileProperties();
        properties.setAgentId("agent-1");
        properties.setExampleKnowledgeBaseId("example-kb");
        properties.setExampleGoodDocumentIds(List.of("good-example"));
        properties.setExampleBadDocumentIds(List.of("bad-example"));
        return properties;
    }

    private static DynamicScopeCatalogService catalog() {
        return new DynamicScopeCatalogService(new CatalogPort(), Duration.ofHours(1), Clock.systemUTC());
    }

    private static final class CatalogPort implements KnowledgeScopeCatalogPort {
        @Override public List<KnowledgeBase> listKnowledgeBases() {
            return List.of(new KnowledgeBase("requirement-kb", "战略运管知识库", "document"));
        }
        @Override public Optional<ScopeContainer> getScopeContainer(String knowledgeBaseId) {
            return Optional.of(new ScopeContainer(knowledgeBaseId, "system", "zlyg", "战略运管系统"));
        }
        @Override public List<SystemVersion> listSystemVersions(String knowledgeBaseId) {
            return List.of(new SystemVersion("version-v1", "zlyg", "V1.0", "active", true));
        }
        @Override public List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
            return List.of(document("function-doc", "function_list", "功能清单"),
                    document("work-order-doc", "work_order_plan", "工单方案"));
        }
        private KnowledgeDocument document(String id, String key, String label) {
            return new KnowledgeDocument(id, "requirement-kb", "completed", "enabled",
                    new DocumentScope("zlyg", "version-v1", null, "admission_material", key, label));
        }
    }

    private static final class MutableCatalogPort implements KnowledgeScopeCatalogPort {
        private boolean documentAvailable = true;

        @Override public List<KnowledgeBase> listKnowledgeBases() {
            return List.of(new KnowledgeBase("requirement-kb", "战略运管知识库", "document"));
        }
        @Override public Optional<ScopeContainer> getScopeContainer(String knowledgeBaseId) {
            return Optional.of(new ScopeContainer(knowledgeBaseId, "system", "zlyg", "战略运管系统"));
        }
        @Override public List<SystemVersion> listSystemVersions(String knowledgeBaseId) {
            return List.of(new SystemVersion("version-v1", "zlyg", "V1.0", "active", true));
        }
        @Override public List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
            return documentAvailable ? List.of(new KnowledgeDocument("function-doc", "requirement-kb", "completed", "enabled",
                    new DocumentScope("zlyg", "version-v1", null, "admission_material", "function_list", "功能清单"))) : List.of();
        }
    }

    private static final class MixedCatalogPort implements KnowledgeScopeCatalogPort {
        @Override public List<KnowledgeBase> listKnowledgeBases() {
            return List.of(new KnowledgeBase("kb-a", "系统甲知识库", "document"),
                    new KnowledgeBase("kb-b", "系统乙知识库", "document"));
        }
        @Override public Optional<ScopeContainer> getScopeContainer(String knowledgeBaseId) {
            return Optional.of(new ScopeContainer(knowledgeBaseId, "system", "system-" + knowledgeBaseId,
                    "kb-a".equals(knowledgeBaseId) ? "系统甲" : "系统乙"));
        }
        @Override public List<SystemVersion> listSystemVersions(String knowledgeBaseId) {
            return List.of(new SystemVersion("version-" + knowledgeBaseId, "system-" + knowledgeBaseId,
                    "V1.0", "active", true));
        }
        @Override public List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
            return List.of(new KnowledgeDocument("document-" + knowledgeBaseId, knowledgeBaseId, "completed", "enabled",
                    new DocumentScope("system-" + knowledgeBaseId, "version-" + knowledgeBaseId, null,
                            "admission_material", "function_list", "功能清单")));
        }
    }
}
