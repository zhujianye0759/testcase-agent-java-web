package com.testcaseagent.scope;

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
}
