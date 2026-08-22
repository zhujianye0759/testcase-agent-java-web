package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.testcaseagent.fewshot.ExampleQualityKind;
import com.testcaseagent.scope.DynamicScopeCatalogService;
import com.testcaseagent.scope.KnowledgeScopeCatalogPort;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.ScopeCatalogSnapshot;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** [Test-Ref]: DynamicTaskScopeResolverTest [Req-ID]: REQ-CAT-004, REQ-CAT-005, REQ-SCP-001, REQ-SCP-004, REQ-FSC-006 */
class DynamicTaskScopeResolverTest {

    @Test
    void keepsLegacyMaterialTypeSelectionCompatibleAndFreezesItsExactAggregateDocumentUnion() {
        DynamicScopeCatalogService catalog = catalog();
        ScopeCatalogSnapshot snapshot = catalog.catalog(false);
        List<String> selected = legacyMaterialTypeSelectionIds(snapshot);
        DynamicTaskScopeResolver resolver = new DynamicTaskScopeResolver(catalog, profile());

        CreateGenerationTaskRequest request = resolver.resolve(command(selected));

        assertThat(request.requirementScope().knowledgeBaseId()).isEqualTo("requirement-kb");
        assertThat(request.requirementScope().projectId()).isEqualTo("project-1");
        assertThat(request.requirementScope().documents()).extracting(document -> document.documentId())
                .containsExactly("function-doc", "work-order-doc");
        assertThat(request.requirementScope().documents()).extracting(document -> document.materialTypeKey())
                .containsExactly("function_list", "work_order_plan");
        assertThat(request.requirementAdmissionTypeKeys()).containsExactlyInAnyOrder("function_list", "work_order_plan");
        assertThat(request.exampleScope().knowledgeBaseId()).isEqualTo("example-kb");
        assertThat(request.exampleScope().expectedQualityKinds()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "good-example", ExampleQualityKind.GOOD_CASE, "bad-example", ExampleQualityKind.BAD_CASE));
    }

    /** [Req-ID]: REQ-SMS-001 */
    @Test
    void freezesTheFiveMeizhouMaterialTypesWithoutPromotingSupplementaryDocumentsToFormalPrerequisites() {
        DynamicScopeCatalogService catalog = new DynamicScopeCatalogService(new FixedDocumentsCatalogPort(List.of(
                document("function-doc", "hash-function", "function_list"),
                document("work-order-doc", "hash-work-order", "work_order_plan"),
                document("requirement-list-a", "hash-requirement-list-a", "requirement_list"),
                document("requirement-list-b", "hash-requirement-list-b", "requirement_list"),
                document("prototype-doc", "hash-prototype", "prototype"))),
                Duration.ofHours(1), Clock.systemUTC());
        List<String> selections = legacyMaterialTypeSelectionIds(catalog.catalog(false));

        CreateGenerationTaskRequest request = new DynamicTaskScopeResolver(catalog, profile()).resolve(command(selections));

        assertThat(request.requirementScope().documents()).extracting(RequirementDocumentCoordinate::materialTypeKey)
                .containsExactlyInAnyOrder("function_list", "work_order_plan", "requirement_list", "requirement_list", "prototype");
        assertThat(request.requirementAdmissionTypeKeys())
                .containsExactlyInAnyOrder("function_list", "work_order_plan", "requirement_list", "prototype");
    }

    /** [Req-ID]: REQ-FSC-006 */
    @Test
    void freezesOnlyTheExplicitlySelectedDocumentWhenOneMaterialTypeHasTwoDocuments() {
        DynamicScopeCatalogService catalog = new DynamicScopeCatalogService(new FixedDocumentsCatalogPort(List.of(
                document("function-doc", "hash-function", "function_list"),
                document("work-order-doc", "hash-work-order", "work_order_plan"),
                document("prototype-selected", "hash-prototype-selected", "prototype"),
                document("prototype-excluded", "hash-prototype-excluded", "prototype"))),
                Duration.ofHours(1), Clock.systemUTC());
        ScopeCatalogSnapshot snapshot = catalog.catalog(false);
        List<String> selected = documentSelectionIds(snapshot).stream()
                .filter(id -> !snapshot.selections().get(id).documentIds().contains("prototype-excluded"))
                .toList();

        CreateGenerationTaskRequest request = new DynamicTaskScopeResolver(catalog, profile()).resolve(command(selected));

        assertThat(request.requirementScope().documents()).extracting(RequirementDocumentCoordinate::documentId)
                .containsExactly("function-doc", "prototype-selected", "work-order-doc");
    }

    /** [Req-ID]: REQ-FSC-006 */
    @Test
    void rejectsDuplicateDocumentLeafSelectionInsteadOfSilentlyDeduplicatingIt() {
        String documentSelectionId = documentSelectionIds(catalog().catalog(false)).get(0);

        assertThatThrownBy(() -> command(List.of(documentSelectionId, documentSelectionId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能重复选择");
    }

    /** [Req-ID]: REQ-FSC-006 */
    @Test
    void rejectsAnAggregateSelectionCombinedWithItsOverlappingDocumentLeaf() {
        ScopeCatalogSnapshot snapshot = catalog().catalog(false);
        String functionAggregate = snapshot.view().knowledgeBases().stream().flatMap(kb -> kb.systems().stream())
                .flatMap(system -> system.versions().stream()).flatMap(version -> version.materialTypes().stream())
                .filter(type -> type.label().equals("功能清单")).findFirst().orElseThrow().id();
        String functionLeaf = snapshot.selections().get(functionAggregate).documentIds().get(0);
        String functionLeafSelection = documentSelectionIds(snapshot).stream()
                .filter(id -> snapshot.selections().get(id).documentIds().contains(functionLeaf)).findFirst().orElseThrow();
        String workOrderAggregate = snapshot.view().knowledgeBases().stream().flatMap(kb -> kb.systems().stream())
                .flatMap(system -> system.versions().stream()).flatMap(version -> version.materialTypes().stream())
                .filter(type -> type.label().equals("工单方案")).findFirst().orElseThrow().id();

        assertThatThrownBy(() -> new DynamicTaskScopeResolver(catalog(), profile())
                .resolve(command(List.of(functionAggregate, functionLeafSelection, workOrderAggregate))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
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
        List<String> selections = legacyMaterialTypeSelectionIds(catalog.catalog(false));

        assertThatThrownBy(() -> new DynamicTaskScopeResolver(catalog, profile()).resolve(command(selections)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("同一知识库");
    }

    @Test
    void rejectsMissingRequiredMaterialCombinationsAndDuplicateFileHashes() {
        DynamicScopeCatalogService missingFormal = new DynamicScopeCatalogService(
                new FixedDocumentsCatalogPort(List.of(document("function-doc", "hash-function", "function_list"))),
                Duration.ofHours(1), Clock.systemUTC());
        List<String> missingFormalSelections = legacyMaterialTypeSelectionIds(missingFormal.catalog(false));
        assertThatThrownBy(() -> new DynamicTaskScopeResolver(missingFormal, profile())
                .resolve(command(missingFormalSelections)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("正式需求材料");

        DynamicScopeCatalogService duplicateHashes = new DynamicScopeCatalogService(
                new FixedDocumentsCatalogPort(List.of(document("function-doc", "same-hash", "function_list"),
                        document("work-order-doc", "same-hash", "work_order_plan"))),
                Duration.ofHours(1), Clock.systemUTC());
        List<String> duplicateSelections = legacyMaterialTypeSelectionIds(duplicateHashes.catalog(false));
        assertThatThrownBy(() -> new DynamicTaskScopeResolver(duplicateHashes, profile())
                .resolve(command(duplicateSelections)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SHA-256");
    }

    private static CreateGenerationTaskCommand command(List<String> selected) {
        return new CreateGenerationTaskCommand(GenerationTaskMode.ALL, "", FewShotPolicy.AUTO,
                "markdown-1.0", "1.0", selected, "");
    }

    private static List<String> legacyMaterialTypeSelectionIds(ScopeCatalogSnapshot snapshot) {
        return snapshot.view().knowledgeBases().stream().flatMap(kb -> kb.systems().stream())
                .flatMap(system -> system.versions().stream()).flatMap(version -> version.materialTypes().stream())
                .map(com.testcaseagent.scope.ScopeCatalogView.MaterialTypeOption::id).sorted().toList();
    }

    private static List<String> documentSelectionIds(ScopeCatalogSnapshot snapshot) {
        return snapshot.view().knowledgeBases().stream().flatMap(kb -> kb.systems().stream())
                .flatMap(system -> system.versions().stream()).flatMap(version -> version.materialTypes().stream())
                .flatMap(type -> type.documents().stream())
                .map(com.testcaseagent.scope.ScopeCatalogView.DocumentOption::id).sorted().toList();
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

    private static KnowledgeScopeCatalogPort.KnowledgeDocument document(String id, String hash, String type) {
        return new KnowledgeScopeCatalogPort.KnowledgeDocument(id, "requirement-kb", hash, "completed", "enabled",
                new KnowledgeScopeCatalogPort.DocumentScope("zlyg", "version-v1", "project-1",
                        "admission_material", type, type));
    }

    private static final class FixedDocumentsCatalogPort implements KnowledgeScopeCatalogPort {
        private final List<KnowledgeDocument> documents;

        private FixedDocumentsCatalogPort(List<KnowledgeDocument> documents) {
            this.documents = List.copyOf(documents);
        }

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
            return documents;
        }
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
            return new KnowledgeDocument(id, "requirement-kb", "sha256-" + id, "completed", "enabled",
                    new DocumentScope("zlyg", "version-v1", "project-1", "admission_material", key, label));
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
            return documentAvailable ? List.of(new KnowledgeDocument("function-doc", "requirement-kb", "sha256-function-doc",
                    "completed", "enabled", new DocumentScope("zlyg", "version-v1", "project-1",
                    "admission_material", "function_list", "功能清单"))) : List.of();
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
            return List.of(new KnowledgeDocument("document-" + knowledgeBaseId, knowledgeBaseId,
                    "sha256-document-" + knowledgeBaseId, "completed", "enabled",
                    new DocumentScope("system-" + knowledgeBaseId, "version-" + knowledgeBaseId, "project-" + knowledgeBaseId,
                            "admission_material", "function_list", "功能清单")));
        }
    }
}
