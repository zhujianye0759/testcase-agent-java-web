package com.testcaseagent.knowledgeagent;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.testcaseagent.scope.KnowledgeScopeCatalogPort;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** [Test-Ref]: WebClientKnowledgeScopeCatalogAdapterTest [Req-ID]: REQ-KAG-009, REQ-CAT-001, REQ-CAT-003 */
class WebClientKnowledgeScopeCatalogAdapterTest {
    private WireMockServer server;
    private KnowledgeScopeCatalogPort port;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        port = new WebClientKnowledgeScopeCatalogAdapter(server.baseUrl(), "server-secret", Duration.ofSeconds(2), 2, 2);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void readsCursorPagedKnowledgeBasesAndPagedDocuments() {
        server.stubFor(get(urlEqualTo("/knowledge-bases/selector-options?limit=2"))
                .willReturn(okJson("""
                        {"success":true,"data":[{"id":"kb-1","name":"战略运管","type":"document"}],
                         "has_more":true,"next_cursor":"cursor-2"}
                        """)));
        server.stubFor(get(urlEqualTo("/knowledge-bases/selector-options?limit=2&cursor=cursor-2"))
                .willReturn(okJson("""
                        {"success":true,"data":[{"id":"kb-2","name":"其他系统","type":"document"}],
                         "has_more":false,"next_cursor":null}
                        """)));
        server.stubFor(get(urlEqualTo("/knowledge-bases/kb-1/knowledge?page=1&page_size=2"))
                .willReturn(okJson(documentPage(1, 2, "doc-1", "function_list", "功能清单"))));
        server.stubFor(get(urlEqualTo("/knowledge-bases/kb-1/knowledge?page=2&page_size=2"))
                .willReturn(okJson(documentPage(2, 2, "doc-2", "work_order_plan", "工单方案"))));

        assertThat(port.listKnowledgeBases()).extracting(KnowledgeScopeCatalogPort.KnowledgeBase::id)
                .containsExactly("kb-1", "kb-2");
        assertThat(port.listDocuments("kb-1")).extracting(KnowledgeScopeCatalogPort.KnowledgeDocument::id)
                .containsExactly("doc-1", "doc-2");
        server.verify(getRequestedFor(urlEqualTo("/knowledge-bases/selector-options?limit=2&cursor=cursor-2")));
        server.verify(getRequestedFor(urlEqualTo("/knowledge-bases/kb-1/knowledge?page=2&page_size=2")));
    }

    @Test
    void mapsContainerVersionsAndDocumentScopeWithoutLeakingTransportFields() {
        server.stubFor(get(urlEqualTo("/knowledge-bases/kb-1/scope-container"))
                .willReturn(okJson("""
                        {"success":true,"data":{"knowledge_base_id":"kb-1","container_type":"system",
                         "system_id":"zlyg","system_name":"战略运管系统"}}
                        """)));
        server.stubFor(get(urlEqualTo("/knowledge-bases/kb-1/system-versions"))
                .willReturn(okJson("""
                        {"success":true,"data":[{"id":"version-1","system_id":"zlyg","display_name":"V1.0",
                         "status":"active","is_current":true}]}
                        """)));
        server.stubFor(get(urlEqualTo("/knowledge-bases/kb-1/knowledge?page=1&page_size=2"))
                .willReturn(okJson(documentPage(1, 1, "doc-1", "function_list", "功能清单"))));

        assertThat(port.getScopeContainer("kb-1")).get().satisfies(container -> {
            assertThat(container.containerType()).isEqualTo("system");
            assertThat(container.systemId()).isEqualTo("zlyg");
        });
        assertThat(port.listSystemVersions("kb-1")).singleElement().satisfies(version -> {
            assertThat(version.id()).isEqualTo("version-1");
            assertThat(version.active()).isTrue();
        });
        assertThat(port.listDocuments("kb-1")).singleElement().satisfies(document -> {
            assertThat(document.parseStatus()).isEqualTo("completed");
            assertThat(document.fileSha256()).isEqualTo("sha256-doc-1");
            assertThat(document.fileName()).isEqualTo("材料-doc-1");
            assertThat(document.scope().projectId()).isEqualTo("project-1");
            assertThat(document.scope().contentTypeLabel()).isEqualTo("功能清单");
        });
    }

    @Test
    void acceptsBoundedKeePagesThatContainLargeIgnoredMetadata() {
        String response = documentPage(1, 1, "doc-1", "function_list", "功能清单")
                .replace("\"parse_status\":\"completed\"", "\"description\":\"" + "x".repeat(300_000)
                        + "\",\"parse_status\":\"completed\"");
        server.stubFor(get(urlEqualTo("/knowledge-bases/kb-1/knowledge?page=1&page_size=2"))
                .willReturn(okJson(response)));

        assertThat(port.listDocuments("kb-1")).singleElement()
                .extracting(KnowledgeScopeCatalogPort.KnowledgeDocument::id).isEqualTo("doc-1");
    }

    @Test
    void rejectsAnUnsuccessfulKeeEnvelope() {
        server.stubFor(get(urlEqualTo("/knowledge-bases/selector-options?limit=2"))
                .willReturn(okJson("{\"success\":false,\"error\":{\"message\":\"denied\"}}")));

        assertThatThrownBy(port::listKnowledgeBases)
                .isInstanceOf(com.testcaseagent.scope.ScopeCatalogUnavailableException.class)
                .hasMessageContaining("unsuccessful");
    }

    private static String documentPage(int page, int total, String id, String typeKey, String typeName) {
        return """
                {"success":true,"data":[{"id":"%s","knowledge_base_id":"kb-1","file_name":"材料-%s","file_hash":"sha256-%s","parse_status":"completed",
                 "enable_status":"enabled","knowledge_scope":{"system_id":"zlyg","version_id":"version-1",
                 "project_id":"project-1","content_category":"admission_material","content_type_key":"%s","content_type_name":"%s"}}],
                 "page":%d,"page_size":2,"total":%d}
                """.formatted(id, id, id, typeKey, typeName, page, total);
    }
}
