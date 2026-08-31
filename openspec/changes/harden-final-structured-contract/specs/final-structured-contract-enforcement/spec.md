## ADDED Requirements

### Requirement: [REQ-FSC-001] Review invocations preserve one immutable material source
Java SHALL build each `requirement-material-quality-review` request from one completed material inventory and SHALL authorize exactly that document in the structured Skill scope. `material_key` SHALL remain a caller-owned opaque key and MUST NOT be required to equal the document ID. Each sent unit key, ordinal, and content SHALL be the corresponding immutable parsed-unit snapshot value; incomplete traversal, duplicated units, discontinuous ordinals, or source substitution SHALL fail closed before invocation.

#### Scenario: One review slice keeps its persisted unit identity
- **WHEN** a completed material inventory supplies a slice with global ordinals 33 through 64
- **THEN** Java SHALL send those same unit keys, ordinals, and contents under an authorization containing only that material document

#### Scenario: Supplemental material is reviewed but cannot create a formal fact
- **WHEN** a prototype or requirement-list review result contains a requirement fact
- **THEN** Java SHALL reject the complete result before any fact, finding, or binding is persisted

### Requirement: [REQ-FSC-002] Reconciliation results exactly close their submitted sources
Java SHALL require every function-list item and requirement fact submitted to one reconciliation request to appear in at least one returned reconciliation. Every returned reference key SHALL belong to that submitted input. For each reconciliation, returned evidence-key set SHALL exactly equal the de-duplicated union of evidence keys on the specific submitted items and facts that it references; unrelated task evidence MUST be rejected. `insufficient_evidence` SHALL require `pending_confirmation`.

#### Scenario: Broad evidence cannot authorize a narrow reconciliation
- **WHEN** a reconciliation references one function item and one fact but also returns an evidence key owned only by another submitted source
- **THEN** Java SHALL reject the complete reconciliation result before persistence

#### Scenario: Insufficient evidence remains pending
- **WHEN** a reconciliation has classification `insufficient_evidence`
- **THEN** Java SHALL reject it unless its confirmation status is `pending_confirmation`

### Requirement: [REQ-FSC-003] High-granularity expectations preserve step order
Java SHALL require each testcase `expected_results` array to equal, in both order and multiplicity, the testcase's ordered `steps[].expected` values. Java SHALL continue to validate every high-granularity reader-facing field, explicit empty source dimensions, author/date echo, formal evidence closure, and formal/pending separation before atomic acceptance.

#### Scenario: Reordered expected results are rejected
- **WHEN** two valid step expectations are returned in the reverse order in `expected_results`
- **THEN** Java SHALL reject the complete testcase result before the test point, testcase, step, or binding is persisted

### Requirement: [REQ-FSC-004] Review findings always retain frozen root-cause proof
Java SHALL require every accepted review finding to include one frozen root-cause kind, nonempty affected scope, one continuous quoted source fragment, Chinese analysis fields, and an explicitly pending proposed-good example. Duplicate root causes in one bounded result SHALL fail closed. The existing task-level transactional merge SHALL consolidate only the same root-cause kind across bounded calls while retaining source quote and pending proposal; different kinds SHALL remain separate.

#### Scenario: Legacy incomplete review finding is rejected
- **WHEN** an in-process caller attempts to accept a finding without a root-cause identity or frozen source/proposal fields
- **THEN** Java SHALL reject the complete review result before persistence

#### Scenario: Same root cause from two slices is consolidated
- **WHEN** two validated review slices for the same task report the same root-cause kind with different cited units
- **THEN** Java SHALL persist and export one merged finding that retains both scope/evidence sets and a pending proposed-good example

### Requirement: [REQ-FSC-005] Validated structured delivery remains the sole projection source
Task details, the structured page projection, and the two fixed Excel Sheets SHALL read only rows accepted after the requirements above succeed. Failed validation SHALL leave no partial business rows and SHALL not be repaired by the exporter or UI. Pending candidates SHALL remain outside formal coverage.

#### Scenario: Rejected result has no reader-facing projection
- **WHEN** any final-contract validator rejects a Skill result
- **THEN** the task detail and Excel projection SHALL contain no rows from that result

### Requirement: [REQ-FSC-006] Task material selection supports exact document leaves

Java SHALL expose an opaque selection ID for every eligible completed and enabled document beneath its material type. A document leaf SHALL belong to the selected knowledge base, system, current active version, project, admission category, and material type; task creation SHALL revalidate those properties and the document SHA-256 before freezing only the submitted document leaves. A legacy material-type selection ID SHALL remain valid for older clients and task snapshots. In the Java frontend, only an older catalog response that omits the document-leaf field may fall back to the aggregate ID; an explicit empty leaf array SHALL be unselectable and SHALL NOT fall back to the aggregate ID. Unknown, duplicated, stale, cross-coordinate, cross-version, type-inconsistent, disabled, incomplete, or non-current selections SHALL fail closed. Reader-facing material labels SHALL use the KEE Chinese type label or the fixed fallback `未命名材料类型`, never an internal type key. Reader-facing document labels SHALL use only a basename with path/control characters removed, or the fixed fallback `材料文档`; UUIDs and opaque selection IDs SHALL NOT be used as display fallbacks.

#### Scenario: One document is selected from a shared material type
- **WHEN** two eligible prototype documents appear under one material type and a caller submits only the leaf ID for one of them with its required companion leaves
- **THEN** the frozen requirement scope SHALL contain the chosen prototype and SHALL NOT contain the other prototype

#### Scenario: An active but non-current version is not selectable
- **WHEN** a document is completed and enabled but belongs to an active version that is not current
- **THEN** Java SHALL not expose a selection ID for that document

#### Scenario: Legacy type selection remains compatible
- **WHEN** an older caller submits the existing opaque material-type selection ID
- **THEN** Java SHALL freeze the same aggregate document set as before this capability

### Requirement: [REQ-FSC-007] Business-validation failures retain safe field diagnostics

When Java rejects a structured requirement-review result after KEE returns, Java SHALL retain one enumerated validation error code, one bounded field path, and one fixed reader-safe Chinese message on the failed attempt, work item, and task. The three levels SHALL be updated atomically with the work failure. The task detail SHALL expose only the same safe diagnostic. Logs SHALL correlate task, work item, and attempt with the same code/path/message. Java MUST NOT persist or log the rejected model response, material text, credentials, URLs, stack traces, arbitrary exception messages, or business keys through this diagnostic path. `business_validation_failed` SHALL remain non-retryable.

#### Scenario: Unsupported fact wording identifies its field without retaining content
- **WHEN** one requirement fact business-rule item is not a continuous fragment of any cited parsed unit
- **THEN** Java SHALL fail the whole work as `business_validation_failed` and retain a diagnostic whose path identifies that business-rule item while its code and message reveal no source or model text

#### Scenario: Unknown runtime text cannot become a diagnostic payload
- **WHEN** a non-enumerated runtime exception contains credential-like or material-like text
- **THEN** Java SHALL NOT copy that exception message into the attempt, work item, task, task detail, or dedicated diagnostic log

### Requirement: [REQ-FSC-008] Protocol V2 preserves global comparison while paging output ownership

Java SHALL retain strict V1 `operation=reconcile` DTO/result parsing and previously committed task reads. New reconciliation work and a stage-gap recovery with no reconciliation work/result SHALL use `operation=reconcile_page` and `protocol_version=2`; they MUST NOT invoke V1. A completed V1 reconciliation SHALL remain unchanged and SHALL resume downstream processing from committed rows with zero KEE calls.

Every V2 page request SHALL carry the same complete canonical `global_catalog` and `run`; only `owner_window` SHALL vary. The catalog SHALL contain all durably accepted function-list items and formal requirement facts ordered by source type (`function_list_item` before `requirement_fact`) and source key, with every evidence-key array sorted and unique. `catalog_sha256` SHALL be the lowercase SHA-256 of the compact canonical catalog JSON. The run SHALL carry an opaque nonempty Java task-and-catalog `run_key`, that digest, and exact item/fact counts. Java MUST NOT sample, truncate, or split the catalog into mutually unaware comparisons.

Owner windows SHALL be nonempty, unique, canonically ordered subsets that partition all canonical source references exactly once. `page_key` SHALL be the lowercase SHA-256 of `reconcile-page-v2\n`, `run_key`, `\n`, and compact canonical owner-reference JSON, with no trailing newline after the JSON. A relation's canonical owner SHALL be the minimum source reference in that relation and MUST belong to the current owner window. Sources MAY participate in multiple relations. Java SHALL independently recompute KEE-derived reconciliation key, owner reference, and sorted exact evidence union before staging a page. The reconciliation key SHALL be the lowercase SHA-256 of `reconciliation-v2\n`, `run_key`, `\n`, classification, `\n`, confirmation status, `\n`, and compact canonical referenced-source JSON, with no trailing newline after the JSON. All pages SHALL be staged under one run and SHALL produce zero business reconciliation rows until global validation succeeds.

V2 SHALL use a configurable dedicated request budget defaulting to 16 MiB and a configurable page-response budget defaulting to 4 MiB. Other Skills SHALL retain their 2 MiB and bounded-array contracts. A full catalog that exceeds request/model capacity SHALL fail closed without truncation. Only `response_too_large` MAY deterministically bisect the current owner window under the unchanged run/catalog; all other failures SHALL leave the run incomplete.

After all pages complete, Java SHALL validate exact run/catalog identity, owner-window partition and echo, canonical ownership, derived key/evidence closure, relation identity uniqueness, same-path exact/confirmed closure, and complete source coverage. One transaction SHALL then persist every relation, overlapping bindings, and exactly one processing terminal per catalog source. A terminal proves processing completion but SHALL NOT constrain the source to one relation. The accepted-result digest SHALL include every canonical leaf page identity and its validated result digest, so different reader-facing relation content is not treated as an idempotent replay under unchanged machine keys. Any invalid page or persistence conflict SHALL produce zero accepted reconciliation business rows. Stable run/page identities SHALL make restart invoke only missing pages, while a completed reconciliation work SHALL cause zero KEE calls.

#### Scenario: A large task keeps one globally visible catalog
- **WHEN** accepted inputs contain 297 function items and 514 facts, or a larger catalog from a work order exceeding one thousand pages
- **THEN** every owner page SHALL carry the identical complete catalog and SHALL vary only its deterministic owner window

#### Scenario: V1 history remains readable but new orchestration is V2-only
- **WHEN** Java reads a previously accepted V1 reconciliation or plans a new/stage-gap reconciliation
- **THEN** the V1 result SHALL remain readable without another KEE call, while the new/stage-gap work SHALL emit only V2 pages

#### Scenario: Canonical identities are independently verified
- **WHEN** KEE returns one V2 page
- **THEN** Java SHALL reject the page unless its run key, catalog digest, page key, completed owners, canonical relation owner, derived reconciliation key, and exact evidence union all recompute from frozen task data

#### Scenario: Overlapping relations retain one processing terminal
- **WHEN** one fact participates in both a split relation and a conflict relation
- **THEN** Java SHALL retain both valid relation rows after global acceptance but SHALL publish exactly one source terminal for that fact

#### Scenario: Response overflow splits only output responsibility
- **WHEN** one owner window returns `response_too_large`
- **THEN** Java MAY replace it with two deterministic child owner windows under the same run and complete catalog, with no owner omitted or repeated

#### Scenario: KEE reports a provider length cutoff as page capacity
- **WHEN** a V2 reconciliation page receives HTTP 400 with `error.details.type=response_too_large` because the initial or repair model response ended at its provider length limit
- **THEN** Java SHALL preserve the optional `repair_attempted` flag and SHALL apply the same deterministic owner-window-only bisection without changing the task, work, run, global catalog, or catalog digest

#### Scenario: A minimum owner window still exceeds response capacity
- **WHEN** a one-owner V2 page returns `response_too_large`
- **THEN** Java SHALL fail the reconciliation work closed without staging that response, publishing partial relations, creating another task, or changing the global catalog

#### Scenario: Global acceptance is all-or-nothing and restart-safe
- **WHEN** any page is missing, mixed-run, invalid, duplicated, or unowned
- **THEN** Java SHALL publish zero reconciliation business rows; after restart it SHALL reuse staged valid pages and call KEE only for missing windows

#### Scenario: Pre-work planning failure is safely recoverable
- **WHEN** all review/extraction work is complete but no V2 run/work/result exists
- **THEN** Java SHALL retain a safe stage diagnostic and expose explicit retry eligibility only for the exact row-locked, zero-in-flight, zero-reconciliation stage-gap shape without reparsing materials
