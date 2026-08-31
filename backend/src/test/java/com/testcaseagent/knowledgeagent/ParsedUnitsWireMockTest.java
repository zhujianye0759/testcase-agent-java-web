package com.testcaseagent.knowledgeagent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.testcaseagent.scope.ParsedMaterial;
import com.testcaseagent.scope.ParsedMaterialPage;
import com.testcaseagent.scope.ParsedMaterialSummary;
import com.testcaseagent.scope.ParsedMaterialUnit;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementMaterialReaderPort;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.scope.ScopeViolation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Deterministic consumer tests for the fixed KEE parsed-units HTTP contract.
 *
 * [Req-ID]: REQ-SMR-001, REQ-SMR-004
 */
class ParsedUnitsWireMockTest {

    private static final String DOCUMENT_ID = "requirement-doc";
    private static final String CURSOR = "hmac-keyset:eyJvcmRpbmFsIjo1MH0.signature";
    private static final List<String> BUSINESS_ERRORS = List.of(
            "document_not_ready", "cursor_signing_unavailable", "invalid_cursor", "document_not_current",
            "parsed_unit_integrity_error", "unit_too_large");

    @RegisterExtension
    static WireMockExtension knowledgeEngine = WireMockExtension.newInstance().build();

    /** [Req-ID]: REQ-SMR-001, REQ-SMR-004 */
    @Test
    void readsTheFixedSuccessEnvelopeWithDefaultLimitAndOpaqueCursorContinuation() {
        stubPage(50, null, page(DOCUMENT_ID, 2, List.of(
                unit("chunk-1", 0, 1, "第一段", 0, 3)), CURSOR, false));
        stubPage(50, CURSOR, page(DOCUMENT_ID, 2, List.of(
                unit("chunk-2", 1, 2, "第二段", 4, 7)), null, true));

        ParsedMaterial material = reader().readAll(scope(), DOCUMENT_ID);

        assertThat(material.knowledgeId()).isEqualTo(DOCUMENT_ID);
        assertThat(material.totalUnits()).isEqualTo(2);
        assertThat(material.units()).extracting(ParsedMaterialUnit::unitId, ParsedMaterialUnit::chunkIndex,
                ParsedMaterialUnit::ordinal, ParsedMaterialUnit::content, ParsedMaterialUnit::startAt,
                ParsedMaterialUnit::endAt).containsExactly(
                        tuple("chunk-1", 0, 1, "第一段", 0L, 3L),
                        tuple("chunk-2", 1, 2, "第二段", 4L, 7L));
        knowledgeEngine.verify(getRequestedFor(urlPathEqualTo(path()))
                .withHeader("X-API-Key", equalTo("test-key"))
                .withQueryParam("limit", equalTo("50"))
                .withoutQueryParam("cursor"));
        knowledgeEngine.verify(getRequestedFor(urlPathEqualTo(path()))
                .withQueryParam("limit", equalTo("50"))
                .withQueryParam("cursor", equalTo(CURSOR)));
    }

    /** [Req-ID]: REQ-TGV2-003 */
    @Test
    void streamsValidatedPagesWithoutAccumulatingTheDocumentContentInTheAdapter() {
        stubPage(1, null, page(DOCUMENT_ID, 2, List.of(
                unit("chunk-1", 0, 1, "第一段", 0, 3)), CURSOR, false));
        stubPage(1, CURSOR, page(DOCUMENT_ID, 2, List.of(
                unit("chunk-2", 1, 2, "第二段", 4, 7)), null, true));
        List<ParsedMaterialPage> pages = new ArrayList<>();

        ParsedMaterialSummary summary = reader().scanAll(scope(), DOCUMENT_ID, 1, pages::add);

        assertThat(summary).isEqualTo(new ParsedMaterialSummary(DOCUMENT_ID, 2));
        assertThat(pages).extracting(page -> page.units().size(), ParsedMaterialPage::complete)
                .containsExactly(tuple(1, false), tuple(1, true));
        assertThat(pages).flatExtracting(ParsedMaterialPage::units)
                .extracting(ParsedMaterialUnit::ordinal).containsExactly(1, 2);
    }

    /** [Req-ID]: REQ-SMR-001, REQ-SMR-004 */
    @Test
    void usesOneHundredForMaximumAndOverLimitRequests() {
        stubPage(100, null, singleUnitPage());
        assertThat(reader().readAll(scope(), DOCUMENT_ID, 100).units()).hasSize(1);

        knowledgeEngine.resetAll();
        stubPage(100, null, singleUnitPage());
        assertThat(reader().readAll(scope(), DOCUMENT_ID, 101).units()).hasSize(1);

        knowledgeEngine.verify(getRequestedFor(urlPathEqualTo(path())).withQueryParam("limit", equalTo("100")));
    }

    /** [Req-ID]: REQ-SMR-001, REQ-SMR-004 */
    @Test
    void preservesLargeCompleteContentWithoutLocallyTruncatingIt() {
        String content = "x".repeat(4 * 1024 * 1024 - 1_024);
        String response = page(DOCUMENT_ID, 1, List.of(unit("chunk-large", 0, 1, content, 0, content.length())), null, true);
        assertThat(response.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4 * 1024 * 1024);
        stubPage(50, null, response);

        ParsedMaterial material = reader().readAll(scope(), DOCUMENT_ID);

        assertThat(material.units()).singleElement().extracting(ParsedMaterialUnit::content).isEqualTo(content);
    }

    /** [Req-ID]: REQ-SMR-001, REQ-SMR-004 */
    @Test
    void rejectsFieldsOutsideTheFixedSuccessDataAndUnitContract() {
        stubPage(50, null, "{\"success\":true,\"data\":{\"knowledge_id\":\"requirement-doc\",\"total_units\":1,"
                + "\"units\":[{\"unit_id\":\"chunk-1\",\"chunk_index\":0,\"ordinal\":1,\"content\":\"text\","
                + "\"start_at\":0,\"end_at\":4,\"revision\":\"not-allowed\"}],\"next_cursor\":null,"
                + "\"complete\":true,\"snapshot_hash\":\"not-allowed\"}}");

        assertRejected("fixed parsed-units contract");
    }

    /** [Req-ID]: REQ-SMR-001, REQ-SMR-004 */
    @Test
    void rejectsEveryExplicitBusinessErrorWithoutLeakingTheResponseBody() {
        for (String code : BUSINESS_ERRORS) {
            knowledgeEngine.resetAll();
            stubPage(50, null, "{\"success\":false,\"error\":{\"code\":\"" + code
                    + "\",\"message\":\"secret error body\"}}");

            assertThatThrownBy(() -> reader().readAll(scope(), DOCUMENT_ID))
                    .isInstanceOf(KnowledgeAgentInvocationException.class)
                    .hasMessageContaining(code)
                    .hasMessageNotContaining("secret error body");
        }
    }

    /** [Req-ID]: REQ-SMR-001, REQ-SMR-004 */
    @Test
    void rejectsForbiddenAndOtherHttpErrorsWithoutRetryingOrLeakingBodies() {
        knowledgeEngine.stubFor(get(urlPathEqualTo(path())).withQueryParam("limit", equalTo("50"))
                .willReturn(aResponse().withStatus(403).withBody("forbidden-body")));

        assertThatThrownBy(() -> reader().readAll(scope(), DOCUMENT_ID))
                .isInstanceOf(KnowledgeAgentInvocationException.class)
                .hasMessageContaining("forbidden")
                .hasMessageNotContaining("forbidden-body");
        knowledgeEngine.verify(1, getRequestedFor(urlPathEqualTo(path())));
    }

    /** [Req-ID]: REQ-SMR-001, REQ-SMR-002, REQ-SMR-004 */
    @Test
    void rejectsMismatchedDocumentOrUnstableTotalUnitsAcrossPages() {
        stubPage(50, null, page("another-doc", 1, List.of(unit("chunk-1", 0, 1, "text", 0, 4)), null, true));
        assertRejected("knowledge_id");

        knowledgeEngine.resetAll();
        stubPage(50, null, page(DOCUMENT_ID, 2, List.of(unit("chunk-1", 0, 1, "text", 0, 4)), CURSOR, false));
        stubPage(50, CURSOR, page(DOCUMENT_ID, 3, List.of(unit("chunk-2", 1, 2, "next", 5, 9)), null, true));
        assertRejected("total_units changed");
    }

    /** [Req-ID]: REQ-SMR-001, REQ-SMR-002, REQ-SMR-004 */
    @Test
    void rejectsDuplicateOrNonContinuousUnits() {
        stubPage(50, null, page(DOCUMENT_ID, 1, List.of(unit("chunk-1", 0, 2, "text", 0, 4)), null, true));
        assertRejected("ordinal");

        knowledgeEngine.resetAll();
        stubPage(50, null, page(DOCUMENT_ID, 2, List.of(unit("chunk-1", 0, 1, "text", 0, 4)), CURSOR, false));
        stubPage(50, CURSOR, page(DOCUMENT_ID, 2, List.of(unit("chunk-1", 1, 2, "next", 5, 9)), null, true));
        assertRejected("unit_id repeats");
    }

    /** [Req-ID]: REQ-SKI-001 */
    @Test
    void rejectsUnitsOutsideTheStableChunkIndexAndUnitIdOrder() {
        stubPage(50, null, page(DOCUMENT_ID, 2, List.of(
                unit("chunk-z", 2, 1, "later", 5, 10),
                unit("chunk-a", 1, 2, "earlier", 0, 4)), null, true));
        assertRejected("stable order");

        knowledgeEngine.resetAll();
        stubPage(50, null, page(DOCUMENT_ID, 2, List.of(
                unit("chunk-z", 1, 1, "same-index-z", 0, 12),
                unit("chunk-a", 1, 2, "same-index-a", 13, 25)), null, true));
        assertRejected("stable order");
    }

    /** [Req-ID]: REQ-SMR-001, REQ-SMR-002, REQ-SMR-004 */
    @Test
    void rejectsCursorLoopsEmptyProgressAndInvalidTerminalCombinations() {
        stubPage(50, null, page(DOCUMENT_ID, 2, List.of(unit("chunk-1", 0, 1, "text", 0, 4)), CURSOR, false));
        stubPage(50, CURSOR, page(DOCUMENT_ID, 2, List.of(unit("chunk-2", 1, 2, "next", 5, 9)), CURSOR, false));
        assertRejected("loop");

        knowledgeEngine.resetAll();
        stubPage(50, null, page(DOCUMENT_ID, 1, List.of(), CURSOR, false));
        assertRejected("no progress");

        knowledgeEngine.resetAll();
        stubPage(50, null, page(DOCUMENT_ID, 1, List.of(unit("chunk-1", 0, 1, "text", 0, 4)), CURSOR, true));
        assertRejected("must not include next_cursor");

        knowledgeEngine.resetAll();
        stubPage(50, null, page(DOCUMENT_ID, 1, List.of(unit("chunk-1", 0, 1, "text", 0, 4)), null, false));
        assertRejected("missing next_cursor");
    }

    /** [Req-ID]: REQ-SMR-001, REQ-SMR-002, REQ-SMR-004 */
    @Test
    void callerRestartAfterAnExplicitReplacementBeginsAtTheFirstPageWithoutPriorUnits() {
        String scenario = "explicit replacement restart";
        knowledgeEngine.stubFor(get(urlEqualTo(path() + "?limit=50")).withHeader("X-API-Key", equalTo("test-key"))
                .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED).willSetStateTo("first-page-read")
                .willReturn(okJson(page(DOCUMENT_ID, 2, List.of(unit("old-chunk", 0, 1, "old", 0, 3)), CURSOR, false))));
        knowledgeEngine.stubFor(get(urlPathEqualTo(path())).withHeader("X-API-Key", equalTo("test-key"))
                .withQueryParam("limit", equalTo("50")).withQueryParam("cursor", equalTo(CURSOR))
                .inScenario(scenario).whenScenarioStateIs("first-page-read").willSetStateTo("replacement-reported")
                .willReturn(okJson("{\"success\":false,\"error\":{\"code\":\"document_not_current\"}}")));
        knowledgeEngine.stubFor(get(urlEqualTo(path() + "?limit=50")).withHeader("X-API-Key", equalTo("test-key"))
                .inScenario(scenario).whenScenarioStateIs("replacement-reported")
                .willReturn(okJson(page(DOCUMENT_ID, 1, List.of(unit("replacement-chunk", 0, 1, "replacement", 0, 11)), null, true))));

        assertThatThrownBy(() -> reader().readAll(scope(), DOCUMENT_ID))
                .isInstanceOf(KnowledgeAgentInvocationException.class)
                .hasMessageContaining("document_not_current");

        ParsedMaterial restarted = reader().readAll(scope(), DOCUMENT_ID);

        assertThat(restarted.totalUnits()).isEqualTo(1);
        assertThat(restarted.units()).extracting(ParsedMaterialUnit::unitId, ParsedMaterialUnit::content)
                .containsExactly(tuple("replacement-chunk", "replacement"));
        knowledgeEngine.verify(2, getRequestedFor(urlEqualTo(path() + "?limit=50")));
        knowledgeEngine.verify(1, getRequestedFor(urlPathEqualTo(path())).withQueryParam("cursor", equalTo(CURSOR)));
    }

    /** [Req-ID]: REQ-SMR-001, REQ-SMR-002 */
    @Test
    void validatesTheScopeWhitelistAndRequestedPageSizeBeforeHttp() {
        RequirementScope otherScope = RequirementScope.freeze("requirement-kb", "system-1", "version-1", "admission_material", null,
                List.of(new RequirementDocumentCoordinate("other-doc")));

        assertThatThrownBy(() -> reader().readAll(otherScope, DOCUMENT_ID))
                .isInstanceOf(ScopeViolation.class);
        assertThatThrownBy(() -> reader().readAll(scope(), DOCUMENT_ID, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestedLimit");
    }

    private void assertRejected(String expectedMessage) {
        assertThatThrownBy(() -> reader().readAll(scope(), DOCUMENT_ID))
                .isInstanceOf(KnowledgeAgentInvocationException.class)
                .hasMessageContaining(expectedMessage);
    }

    private RequirementMaterialReaderPort reader() {
        return new WebClientKnowledgeAgentAdapter(knowledgeEngine.baseUrl() + "/api/v1", "test-key",
                Duration.ofSeconds(10), 1, 20_000);
    }

    private static RequirementScope scope() {
        return RequirementScope.freeze("requirement-kb", "system-1", "version-1", "admission_material", "project-1",
                List.of(new RequirementDocumentCoordinate(DOCUMENT_ID)));
    }

    private static void stubPage(int limit, String cursor, String body) {
        var request = get(urlPathEqualTo(path())).withHeader("X-API-Key", equalTo("test-key"))
                .withQueryParam("limit", equalTo(String.valueOf(limit)));
        if (cursor != null) request.withQueryParam("cursor", equalTo(cursor));
        knowledgeEngine.stubFor(request.willReturn(okJson(body)));
    }

    private static String singleUnitPage() {
        return page(DOCUMENT_ID, 1, List.of(unit("chunk-1", 0, 1, "current", 0, 7)), null, true);
    }

    private static String page(String knowledgeId, int totalUnits, List<String> units, String cursor, boolean complete) {
        return "{\"success\":true,\"data\":{\"knowledge_id\":\"" + knowledgeId + "\",\"total_units\":" + totalUnits
                + ",\"units\":[" + String.join(",", units) + "],\"next_cursor\":"
                + (cursor == null ? "null" : "\"" + cursor + "\"") + ",\"complete\":" + complete + "}}";
    }

    private static String unit(String unitId, int chunkIndex, int ordinal, String content, long startAt, long endAt) {
        return "{\"unit_id\":\"" + unitId + "\",\"chunk_index\":" + chunkIndex + ",\"ordinal\":" + ordinal
                + ",\"content\":\"" + content + "\",\"start_at\":" + startAt + ",\"end_at\":" + endAt + "}";
    }

    private static String path() {
        return "/api/v1/knowledge/" + DOCUMENT_ID + "/parsed-units";
    }
}
