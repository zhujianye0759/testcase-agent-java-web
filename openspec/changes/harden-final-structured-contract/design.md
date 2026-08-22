## Context

The structured coordinator already reads complete parsed-unit inventories, invokes the isolated KEE Skills, and accepts results atomically. The final KEE contract narrows two result invariants that current Java validation only checks as subset membership: reconciliation evidence identity and high-granularity expected-result ordering. It also removes the remaining compatibility bypass that can construct a review finding without a root-cause identity.

## Goals / Non-Goals

**Goals:**

- Reject a result before persistence when it is not an exact closure of the frozen input and cited evidence.
- Retain the existing single-document review authorization, opaque material key, supplementary-material isolation, and durable task-level review consolidation.
- Use public validator and coordinator seams so tests prove accepted behavior rather than private implementation details.

**Non-Goals:**

- Change KEE request/result field names, KEE code, database schema, normal Agent Chat, or the task creation API.
- Recreate, retry, alter, or otherwise write a business task during contract verification.

## Decisions

### Keep validation at the pre-persistence Java boundary

`FeatureReconciliationValidator`, `FunctionalTestcaseResultValidator`, and `RequirementMaterialReviewValidator` are called before `StructuredGenerationAcceptanceStore` starts row writes. Strengthening these validators keeps failure atomic and automatically preserves the existing API/page/Excel same-record property. Moving checks into the exporter or detail projection would permit invalid rows to become durable.

### Compare ordered values where order has contractual meaning

`expected_results` is compared directly with the ordered `steps[].expected` list. Set comparison would incorrectly accept reordering and duplicate loss.

### Compute reconciliation evidence from referenced input rows

The reconciliation work item carries immutable item-to-evidence and fact-to-evidence maps derived from the submitted input. For every returned row Java recomputes the expected ordered-set union from exactly the referenced keys. This prevents a broad task evidence closure from authorizing unrelated proof.

### Reject missing review root causes at the acceptance seam

Wire DTOs are already strict; the Java validator will make the same condition mandatory so in-process callers cannot retain a legacy, incomplete finding. Root-cause merging remains task-scoped in the existing transactional store.

## Risks / Trade-offs

- [Older in-process fixtures construct legacy findings] → update only affected test fixtures to include the frozen finding fields; do not relax production acceptance.
- [Exact evidence union rejects formerly tolerated broad evidence] → this is intentional fail-closed behavior; errors are classified through the existing business-validation path with zero acceptance.
- [No schema migration] → this change validates and uses fields already persisted in V13; tests include the acceptance-store transaction seam to prove no partial rows.

## Migration Plan

1. Add watched-failure tests for each missing invariant.
2. Implement only validator/work-item data needed to satisfy them.
3. Run focused validator/coordinator/Testcontainers tests and the existing strict OpenSpec/build gates.
4. Commit and deploy the existing Java JAR/frontend only after review. Roll back by restoring the prior Java process/JAR; no database migration or data rewrite is required.
