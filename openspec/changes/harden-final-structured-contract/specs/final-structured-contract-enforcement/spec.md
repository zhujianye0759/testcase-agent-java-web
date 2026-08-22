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
