package com.testcaseagent.scope;

import java.util.function.Consumer;

/**
 * Enumerates every persisted parsed unit for one document inside a frozen requirement scope.
 *
 * <p>The port returns a usable material only after the remote pagination contract has reached its
 * explicit complete terminal page and all cross-page invariants pass.</p>
 *
 * [Req-ID]: REQ-SKI-001
 */
public interface ParsedUnitCatalogPort {

    int DEFAULT_PAGE_SIZE = 50;

    /** Reads all pages using the contract default page size. [Req-ID]: REQ-SKI-001 */
    default ParsedMaterial readAll(RequirementScope scope, String knowledgeId) {
        return readAll(scope, knowledgeId, DEFAULT_PAGE_SIZE);
    }

    /**
     * Reads all pages using a caller-requested page size, capped by the remote contract.
     *
     * [Req-ID]: REQ-SKI-001
     */
    ParsedMaterial readAll(RequirementScope scope, String knowledgeId, int requestedLimit);

    /**
     * Scans one frozen document page by page and emits no terminal summary until every remote invariant passes.
     *
     * <p>Implementations must be genuinely page-bounded. Historical V1 readers retain the separate
     * {@link #readAll} contract; an adapter may not silently implement this method by materializing that result.</p>
     *
     * [Req-ID]: REQ-TGV2-003
     */
    default ParsedMaterialSummary scanAll(RequirementScope scope, String knowledgeId, int requestedLimit,
            Consumer<ParsedMaterialPage> pageConsumer) {
        throw new UnsupportedOperationException("Page-bounded parsed-unit scanning is not implemented");
    }
}
