package com.testcaseagent.scope;

/**
 * Reads the current persisted parsed units for one document in a frozen requirement scope.
 *
 * <p>The port deliberately returns only document/chunk evidence supplied by the knowledge engine;
 * callers cannot substitute preview text or a model response for requirement material.</p>
 *
 * [Req-ID]: REQ-SMR-001, REQ-SMR-002, REQ-SMR-003
 */
public interface RequirementMaterialReaderPort {

    int DEFAULT_PAGE_SIZE = 50;

    /**
     * Reads all pages using the contract default page size.
     *
     * [Req-ID]: REQ-SMR-001
     */
    default ParsedMaterial readAll(RequirementScope scope, String knowledgeId) {
        return readAll(scope, knowledgeId, DEFAULT_PAGE_SIZE);
    }

    /**
     * Reads all pages using a caller-requested page size, subject to the endpoint maximum.
     *
     * [Req-ID]: REQ-SMR-001, REQ-SMR-002
     */
    ParsedMaterial readAll(RequirementScope scope, String knowledgeId, int requestedLimit);
}
