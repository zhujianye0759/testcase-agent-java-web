## Context

The structured coordinator already reads complete parsed-unit inventories, invokes the isolated KEE Skills, and accepts results atomically. The final KEE contract narrows two result invariants that current Java validation only checks as subset membership: reconciliation evidence identity and high-granularity expected-result ordering. It also removes the remaining compatibility bypass that can construct a review finding without a root-cause identity.

## Goals / Non-Goals

**Goals:**

- Reject a result before persistence when it is not an exact closure of the frozen input and cited evidence.
- Retain the existing single-document review authorization, opaque material key, supplementary-material isolation, and durable task-level review consolidation.
- Use public validator and coordinator seams so tests prove accepted behavior rather than private implementation details.

**Non-Goals:**

- Change KEE code, normal Agent Chat, or the task creation API.
- Remove V1 reconciliation DTO/result compatibility, rewrite an accepted V1 task, or route a new/stage-gap reconciliation through V1.
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

### Keep legacy type IDs while offering document-leaf IDs

The catalog retains its opaque material-type selection ID for older callers and returns one opaque document-leaf ID per completed, enabled document under the current version. The resolver re-reads the selected knowledge base and requires the full leaf coordinate, document set, and file hashes to be unchanged before freezing only those documents. The frontend uses leaf IDs whenever the leaf field is present; an explicit empty array disables selection, while only a missing field falls back to the aggregate type ID. Catalog display values use Chinese type labels and cleaned basenames, never transport IDs or internal type keys.

### Persist only enumerated validation diagnostics

Requirement-review validation raises a dedicated business-validation exception carrying an enumerated code, a bounded JSON-style field path, and a fixed Chinese message. The exception never carries source text or model output. The acceptance store writes those three safe values to the running attempt, its work item, and the owning task in the same locked transaction that marks the attempt and work failed. The coordinator records only task/work/attempt correlation plus those safe fields in the dedicated diagnostic log. Unknown runtime exception messages are never persisted or logged through this path.

Task detail may expose the task-level safe diagnostic, but not a stable business key, evidence coordinate, source text, model response, credential, URL, or stack trace. Existing `failure_type` remains the retry classifier; `business_validation_failed` remains non-retryable.

### Freeze one canonical global catalog for protocol V2

V2 is additive: Java retains the strict V1 `FeatureScopeReconciliationInput`, `FeatureScopeReconciliationResult`, and `operation=reconcile` result projection. V2 uses separate strict `FeatureScopeReconciliationPageInput` / `FeatureScopeReconciliationPageResult` records and a separate page execution method on the structured Skill port, while reusing the same authorized KEE Skill and isolated HTTP route. A new reconciliation work, or a failed task with completed review/extraction and no reconciliation work/result, uses only the authoritative `operation=reconcile_page` and `protocol_version=2` method. A previously completed V1 reconciliation remains immutable and resumes downstream processing from its committed rows with zero KEE calls.

Java builds one canonical `global_catalog` from every durably accepted function-list item and formal requirement fact. Canonical source order is `(source_type, source_key)` with `function_list_item` before `requirement_fact`; each source's evidence keys are sorted and unique. `catalog_sha256` is the lowercase SHA-256 of the compact canonical UTF-8 `global_catalog` JSON. `run_key` is an opaque nonempty Java task-and-catalog identity that Java binds to the task id, catalog digest, and exact item/fact counts. Every page in one run carries the same complete catalog and run object. Java MUST NOT sample, truncate, partition, or otherwise make pages compare mutually unaware catalog subsets.

V2 uses a dedicated configurable request budget whose default is 16 MiB and a separately configurable page-response budget whose default is 4 MiB. Other structured Skill operations retain the existing 2 MiB request boundary and their existing array/reference limits. Java serializes the complete outer V2 request before HTTP and fails closed when it exceeds the configured budget. If the full catalog exceeds the configured request budget or the selected model context, the global reconciliation fails closed; increasing verified capacity or selecting a suitable executor is required.

### Page only canonical relation ownership

An `owner_window` limits output responsibility, not model comparison visibility. It contains a stable `page_key` and a nonempty, unique, canonically ordered list of `owner_source_refs`. Owner windows partition all catalog source references exactly once. `page_key` is the lowercase SHA-256 of the UTF-8 sequence `reconcile-page-v2\n`, `run_key`, `\n`, and the compact canonical owner-reference JSON, with no trailing newline after the JSON. A relation's canonical owner is its minimum `(source_type, source_key)` member; KEE may emit the relation only on the page that owns that reference. Sources may participate in multiple overlapping duplicate, split, merge, or conflict relations.

For `feature-scope-reconciliation` protocol V2 only, KEE may report an HTTP 400 `response_too_large` before result normalization when the initial model call or the one allowed repair ends with a normalized provider finish reason of `length`. The initial-call form omits `repair_attempted`; the repair-call form carries `repair_attempted=true`. Java treats both as the same capacity signal while retaining the repair flag for diagnosis. It bisects only the current owner window under the unchanged run key, catalog digest, and full catalog. A one-owner window cannot be reduced further and therefore fails closed without staging or publishing a partial result.

KEE returns the exact completed owner window and semantic relations, while deriving `owner_source_ref` as the canonical minimum source reference and `evidence_keys` as the sorted unique evidence union from validated source references. `reconciliation_key` is the lowercase SHA-256 of the UTF-8 sequence `reconciliation-v2\n`, `run_key`, `\n`, `classification`, `\n`, `confirmation_status`, `\n`, and the compact canonical referenced-source JSON, with no trailing newline after the JSON. Java independently recomputes the catalog digest, run/page identity, canonical owner, relation identity, and exact evidence union before staging any page. A `response_too_large` page may be deterministically bisected only by its ordered owner window under the unchanged run key and catalog digest. No other error permits implicit page splitting or catalog changes.

### Stage all pages, then publish one atomic task result

V15 adds run/page staging plus a task-level source-terminal ledger whose unique key is `(task_id, source_type, source_key)`. A terminal row means only that the source's owner responsibility was completed under the globally validated run; it does not restrict that source to one relation. Successful pages remain non-business staging data until every owner window is complete and one task-level validation proves: unchanged run/catalog, exact owner-window partition, completed-owner echo, canonical owner uniqueness, derived key/evidence equality, no duplicate relation identity, same-path exact/confirmed closure, and full catalog-source coverage. The final accepted-result digest includes each canonical leaf page identity and its validated result digest, so changed reader-facing relation content cannot masquerade as an idempotent replay merely because the machine relation keys are unchanged.

One transaction then writes all relations, overlapping bindings, and one terminal per catalog source and marks the reconciliation work complete. Any missing/mixed/invalid page or database conflict produces zero accepted reconciliation business rows. Because run/page identities are deterministic, restart skips staged completed pages; a fully completed work rebuilds downstream state from committed relations and terminals with zero KEE calls.

### Diagnose and recover a failure before reconciliation work registration

Planning can fail after extraction has committed but before a reconciliation work exists. Java SHALL persist only an enumerated planning code, a bounded stage path, source counts, and a fixed reader-safe message on the task and in the dedicated diagnostic log; it SHALL NOT retain source text, model output, keys, credentials, URLs, or a stack trace through this diagnostic field. Explicit user retry MAY requeue the exact stage-gap shape only under a task row lock: structured ALL is failed, scope is complete, no slot is active, all existing work is completed with accepted hashes, no reconciliation work/result/terminal/artifact exists, and the task is not cancelled. Completed reviews and extractions remain unchanged and are skipped by stable identity.

## Risks / Trade-offs

- [Older in-process fixtures construct legacy findings] → update only affected test fixtures to include the frozen finding fields; do not relax production acceptance.
- [Exact evidence union rejects formerly tolerated broad evidence] → this is intentional fail-closed behavior; errors are classified through the existing business-validation path with zero acceptance.
- [Diagnostic schema drift] → V14 adds only nullable code/path/message columns to the Java-owned task, work, and attempt tables; it does not alter KEE or existing business rows.
- [Old callers submit material-type IDs] → aggregate IDs remain resolvable; new callers can select one leaf from a shared type without widening the frozen scope.
- [A full catalog exceeds V2 transport or model capacity] → serialize and measure the complete outer request, then fail closed with a safe planning diagnostic; never truncate or split the comparison scope.
- [A V2 page exceeds only the response budget] → deterministically bisect that page's ordered owner window while preserving the same run key and complete catalog; never repeat or omit an owner.
- [Overlapping relation rows are mistaken for duplicate terminals] → relation bindings may overlap, while the separate terminal ledger contains exactly one participation row per accepted source.
- [A process restarts during paging] → stable run/page identities rebuild staging and invoke only missing owner windows; after acceptance the terminal ledger rebuilds downstream state without another KEE call.

## Migration Plan

1. Add watched-failure tests for each missing invariant.
2. Implement only validator/work-item data needed to satisfy them.
3. Run focused validator/coordinator/Testcontainers tests and the existing strict OpenSpec/build gates.
4. Commit and deploy the existing Java JAR/frontend only after review. Roll back the application by restoring the prior Java process/JAR; the additive nullable V14 columns may remain inert and require no data rewrite.

The diagnostic slice is verified separately and is not deployed or used to rerun the failed business task in this change session.

V15 creates Java-owned V2 run/page staging, final reconciliation source terminals, and supporting constraints. It does not rewrite existing accepted facts/function items or any KEE data. The failed task with 297 items, 514 facts, and zero reconciliation rows is compatible with explicit same-task stage recovery after both sides deploy V2; no material traversal or parsing is repeated.
