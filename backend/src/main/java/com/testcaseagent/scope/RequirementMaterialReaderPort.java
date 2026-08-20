package com.testcaseagent.scope;

/**
 * Reads the current persisted parsed units for one document in a frozen requirement scope.
 *
 * <p>The port deliberately returns only document/chunk evidence supplied by the knowledge engine;
 * callers cannot substitute preview text or a model response for requirement material.</p>
 *
 * [Req-ID]: REQ-SMR-001, REQ-SMR-002, REQ-SMR-003
 */
public interface RequirementMaterialReaderPort extends ParsedUnitCatalogPort { }
