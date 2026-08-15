package com.testcaseagent.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** [Test-Ref]: DynamicScopeCatalogServiceTest [Req-ID]: REQ-CAT-001, REQ-CAT-002, REQ-CAT-003 */
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
        assertThat(snapshot.selections().values()).flatExtracting(ScopeSelection::documentIds)
                .containsExactlyInAnyOrder("function-doc", "work-order-doc")
                .doesNotContain("disabled-doc", "other-category-doc");
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

    private static final class StubPort implements KnowledgeScopeCatalogPort {
        private final AtomicInteger knowledgeBaseReads = new AtomicInteger();
        private boolean fail;

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
            return List.of(new SystemVersion("version-v1", "zlyg", "V1.0", "active", true),
                    new SystemVersion("version-old", "zlyg", "V0.9", "disabled", false));
        }

        @Override
        public List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
            return List.of(
                    document("function-doc", "completed", "enabled", "admission_material", "function_list", "功能清单"),
                    document("work-order-doc", "completed", "enabled", "admission_material", "work_order_plan", "工单方案"),
                    document("disabled-doc", "completed", "disabled", "admission_material", "requirements_spec", "需求规格说明书"),
                    document("other-category-doc", "completed", "enabled", "test_process", "case", "测试用例"));
        }

        private KnowledgeDocument document(String id, String parse, String enable, String category, String key, String label) {
            return new KnowledgeDocument(id, "requirement-kb", parse, enable,
                    new DocumentScope("zlyg", "version-v1", null, category, key, label));
        }
    }
}
