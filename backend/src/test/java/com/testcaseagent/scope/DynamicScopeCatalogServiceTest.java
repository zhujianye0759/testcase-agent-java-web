package com.testcaseagent.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** [Test-Ref]: DynamicScopeCatalogServiceTest [Req-ID]: REQ-CAT-001, REQ-CAT-002, REQ-CAT-003, REQ-FSC-006 */
class DynamicScopeCatalogServiceTest {

    @Test
    void exposesOnlyReadyAdmissionDocumentsBehindOpaqueBusinessOptions() {
        StubPort port = new StubPort();
        DynamicScopeCatalogService service = new DynamicScopeCatalogService(port, Duration.ofMinutes(5), Clock.systemUTC());

        ScopeCatalogSnapshot snapshot = service.catalog(false);

        assertThat(snapshot.view().knowledgeBases()).singleElement().satisfies(kb -> {
            assertThat(kb.id()).startsWith("kb-").doesNotContain("requirement-kb");
            assertThat(kb.label()).isEqualTo("战略运管知识库");
            assertThat(kb.systems()).singleElement().satisfies(system ->
                    assertThat(system.versions()).singleElement().satisfies(version -> {
                        assertThat(version.label()).isEqualTo("V1.0");
                        assertThat(version.materialTypes()).extracting(ScopeCatalogView.MaterialTypeOption::label)
                                .containsExactly("功能清单", "工单方案");
                        assertThat(version.materialTypes()).extracting(ScopeCatalogView.MaterialTypeOption::documentCount)
                                .containsExactly(1, 1);
                    }));
        });
        Set<String> selectedDocumentIds = snapshot.selections().values().stream()
                .flatMap(selection -> selection.documentIds().stream())
                .collect(Collectors.toSet());
        assertThat(selectedDocumentIds)
                .containsExactlyInAnyOrder("function-doc", "work-order-doc")
                .doesNotContain("disabled-doc", "other-category-doc");
        assertThat(snapshot.selections().values()).allSatisfy(selection -> {
            assertThat(selection.projectId()).isEqualTo("project-1");
            assertThat(selection.documentSha256ById().keySet()).containsExactlyElementsOf(selection.documentIds());
        });
    }

    @Test
    void usesOneImmutableSnapshotUntilRefreshIsExplicitlyRequested() {
        StubPort port = new StubPort();
        DynamicScopeCatalogService service = new DynamicScopeCatalogService(port, Duration.ofHours(1), Clock.systemUTC());

        ScopeCatalogSnapshot first = service.catalog(false);
        ScopeCatalogSnapshot second = service.catalog(false);
        ScopeCatalogSnapshot refreshed = service.catalog(true);

        assertThat(second).isSameAs(first);
        assertThat(refreshed).isNotSameAs(first);
        assertThat(port.knowledgeBaseReads).hasValue(2);
    }

    @Test
    void failsClosedWhenARequiredCatalogCallFailsAndKeepsThePreviousSnapshot() {
        StubPort port = new StubPort();
        DynamicScopeCatalogService service = new DynamicScopeCatalogService(port, Duration.ofHours(1), Clock.systemUTC());
        ScopeCatalogSnapshot first = service.catalog(false);
        port.fail = true;

        assertThatThrownBy(() -> service.catalog(true)).isInstanceOf(ScopeCatalogUnavailableException.class);
        port.fail = false;
        assertThat(service.catalog(false)).isSameAs(first);
    }

    /** [Req-ID]: REQ-FSC-006 */
    @Test
    void doesNotExposeDocumentsFromAnActiveButNonCurrentVersion() {
        StubPort port = new StubPort();
        port.currentVersion = false;

        ScopeCatalogSnapshot snapshot = new DynamicScopeCatalogService(port, Duration.ofMinutes(5), Clock.systemUTC())
                .catalog(false);

        assertThat(snapshot.view().knowledgeBases()).isEmpty();
        assertThat(snapshot.selections()).isEmpty();
    }

    /** [Req-ID]: REQ-FSC-006 */
    @Test
    void replacesMissingChineseMaterialLabelsAndUnsafeDocumentNamesBeforeTheyReachTheBrowser() {
        StubPort port = new StubPort();
        port.functionTypeLabel = "";
        port.functionFileName = "C:\\private\\scope\\功能清单.xlsx\u0001";

        ScopeCatalogView.MaterialTypeOption type = onlyFunctionType(new DynamicScopeCatalogService(
                port, Duration.ofMinutes(5), Clock.systemUTC()).catalog(false));

        assertThat(type.label()).isEqualTo("未命名材料类型");
        assertThat(type.label()).doesNotContain("function_list");
        assertThat(type.documents()).extracting(ScopeCatalogView.DocumentOption::label)
                .containsExactly("功能清单.xlsx");
    }

    /** [Req-ID]: REQ-FSC-006 */
    @Test
    void replacesAnOpaqueUuidFileNameWithTheFixedChineseDocumentLabel() {
        StubPort port = new StubPort();
        port.functionFileName = "37d6e052-b877-4a5c-b360-2805466c6a14";

        ScopeCatalogView.MaterialTypeOption type = onlyFunctionType(new DynamicScopeCatalogService(
                port, Duration.ofMinutes(5), Clock.systemUTC()).catalog(false));

        assertThat(type.documents()).extracting(ScopeCatalogView.DocumentOption::label)
                .containsExactly("材料文档");
    }

    private static ScopeCatalogView.MaterialTypeOption onlyFunctionType(ScopeCatalogSnapshot snapshot) {
        return snapshot.view().knowledgeBases().get(0).systems().get(0).versions().get(0).materialTypes().stream()
                .filter(type -> "function_list".equals(snapshot.selections().get(type.id()).admissionTypeKey()))
                .findFirst().orElseThrow();
    }

    private static final class StubPort implements KnowledgeScopeCatalogPort {
        private final AtomicInteger knowledgeBaseReads = new AtomicInteger();
        private boolean fail;
        private boolean currentVersion = true;
        private String functionTypeLabel = "功能清单";
        private String functionFileName = "功能清单.xlsx";

        @Override
        public List<KnowledgeBase> listKnowledgeBases() {
            knowledgeBaseReads.incrementAndGet();
            if (fail) throw new ScopeCatalogUnavailableException("KEE unavailable");
            return List.of(new KnowledgeBase("requirement-kb", "战略运管知识库", "document"),
                    new KnowledgeBase("example-kb", "示例库", "document"));
        }

        @Override
        public Optional<ScopeContainer> getScopeContainer(String knowledgeBaseId) {
            return "requirement-kb".equals(knowledgeBaseId)
                    ? Optional.of(new ScopeContainer(knowledgeBaseId, "system", "zlyg", "战略运管系统"))
                    : Optional.of(new ScopeContainer(knowledgeBaseId, "department_public", null, null));
        }

        @Override
        public List<SystemVersion> listSystemVersions(String knowledgeBaseId) {
            return List.of(new SystemVersion("version-v1", "zlyg", "V1.0", "active", currentVersion),
                    new SystemVersion("version-old", "zlyg", "V0.9", "disabled", false));
        }

        @Override
        public List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
            return List.of(
                    document("function-doc", "completed", "enabled", "admission_material", "function_list", functionTypeLabel,
                            functionFileName),
                    document("work-order-doc", "completed", "enabled", "admission_material", "work_order_plan", "工单方案"),
                    document("disabled-doc", "completed", "disabled", "admission_material", "requirements_spec", "需求规格说明书"),
                    document("other-category-doc", "completed", "enabled", "test_process", "case", "测试用例"));
        }

        private KnowledgeDocument document(String id, String parse, String enable, String category, String key, String label) {
            return document(id, parse, enable, category, key, label, "材料文档");
        }

        private KnowledgeDocument document(String id, String parse, String enable, String category, String key, String label,
                String fileName) {
            return new KnowledgeDocument(id, "requirement-kb", "sha256-" + id, parse, enable,
                    new DocumentScope("zlyg", "version-v1", "project-1", category, key, label), fileName);
        }
    }
}
