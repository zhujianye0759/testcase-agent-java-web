## ADDED Requirements

### Requirement: Explicit retry recovers one exact structured work
The Java service SHALL accept an explicit user retry only for a `FAILED` structured `ALL` task with no active execution slot, an unchanged frozen scope snapshot, and exactly one failed work item that has no accepted result and no work-owned business rows or bindings. The latest failed attempt MUST use an explicitly audited retryable terminal type. `[Req-ID]: REQ-ESR-001`

#### Scenario: Eligible failed structural output is queued
- **WHEN** a user explicitly retries a failed ALL task whose only failed zero-write work ended as `structured_output_invalid` and is not a V2 reconciliation work governed by the dedicated staging recovery requirements below
- **THEN** the service atomically queues that exact work and task while preserving every completed work and prior attempt

#### Scenario: Safely diagnosed business validation failure is queued
- **WHEN** the only failed zero-write work ended as `business_validation_failed`, is not a V2 reconciliation work governed by the dedicated recovery requirements below, and its latest failed attempt has the explicitly reviewed `REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED` code plus a bounded safe JSON field path
- **THEN** the service atomically queues that exact work and task, clears only the current work/task diagnostic projection, and preserves the failed attempt's safe diagnostic and all completed results

#### Scenario: Forbidden failure is rejected
- **WHEN** the failed work ended with an undiagnosed or unknown business validation failure, scope validation, cancellation, lease loss, database, concurrency, or another non-allowlisted failure
- **THEN** the service returns a conflict and changes no task, work, attempt, business row, binding, or artifact state

#### Scenario: Unsafe business diagnostic is rejected
- **WHEN** the latest `business_validation_failed` attempt has a missing, blank, unknown, or known-but-not-allowed diagnostic code, or a missing, blank, malformed, or oversized field path
- **THEN** the service returns a conflict without reading or exposing model output, material text, or arbitrary diagnostic text and changes no state

#### Scenario: Partial failed work is rejected
- **WHEN** the failed work already owns any typed business row, step, or reference binding
- **THEN** the service fails closed and preserves the complete existing state

### Requirement: Explicit retry is single-winner and single-attempt
Concurrent or repeated explicit retry calls SHALL allow at most one transition from the exact terminal state. One successful explicit call SHALL make at most one subsequent attempt claimable and MUST NOT add the failure type to automatic transient retry. `[Req-ID]: REQ-ESR-002`

#### Scenario: Concurrent retry requests race
- **WHEN** two callers retry the same eligible task concurrently
- **THEN** exactly one call queues the task and work and the other receives a conflict without creating another attempt

#### Scenario: Repeated structural failure remains terminal
- **WHEN** the new attempt again ends with an explicitly retryable terminal failure
- **THEN** Java records the new failed attempt and does not automatically create another attempt

### Requirement: Completed structured work remains immutable during recovery
Recovery SHALL retain completed work identities, accepted-result hashes, typed business rows, reference bindings, parsed-unit inventory, and frozen scope. The coordinator MUST skip completed work and invoke KEE only for the explicitly recovered identity before continuing later work. `[Req-ID]: REQ-ESR-003`

#### Scenario: Two completed slices precede one failed slice
- **WHEN** retry resumes a task with two completed review slices and one eligible failed slice
- **THEN** the completed slices are not invoked or accepted again, their rows and hashes remain unchanged, and only the failed slice receives the next attempt

#### Scenario: Frozen parsed input is reused
- **WHEN** the recovered task executes
- **THEN** it uses the original task scope and persisted parsed units without upload, reparse, OCR, PageIndex, graph processing, truncation, or scope expansion

### Requirement: Retry eligibility is truthful and reader-safe
Task detail SHALL expose backend-computed explicit retry eligibility and a fixed reader-safe reason. The frontend SHALL offer retry only when the task is eligible. The retry POST MUST independently revalidate eligibility and return HTTP 409 when it is not eligible. `[Req-ID]: REQ-ESR-004`

#### Scenario: Eligible task is shown as retryable
- **WHEN** task detail is read for an eligible failed structured task
- **THEN** the response marks it retryable and the page offers one confirmed retry action

#### Scenario: Ineligible failed task is not advertised
- **WHEN** a failed task has no eligible exact structured work
- **THEN** the page does not offer retry and shows a fixed Chinese reason without exposing failure internals, model output, evidence keys, or credentials

#### Scenario: Stale eligibility loses the race
- **WHEN** eligibility changes after the detail read but before POST
- **THEN** the POST returns 409 and does not mutate state

### Requirement: Exhausted single-unit review capacity has a narrow recovery path
The Java service SHALL expose an explicit retry for `response_too_large` only when the only failed structured work is a `requirement-material-quality-review` / `REQUIREMENT_MATERIAL_REVIEW` leaf whose frozen ordinal window and evidence closure both contain exactly one persisted unit. The failed work MUST have no accepted hash and no work-owned typed row, step, or binding. Every other unfinished work MUST be `QUEUED`, have no accepted hash, own no typed row, step, or binding, and have no running claim. The task MUST have no execution slot. These predicates SHALL be repeated under transaction locks before mutation. `[Req-ID]: REQ-ESR-005`

#### Scenario: Corrected KEE can resume the exhausted single-unit leaf
- **WHEN** the only failed leaf ended as `response_too_large`, the exact frozen request has independently returned HTTP 200 and passed the current Java mapper and business validator without database writes, and all durable predicates still hold
- **THEN** one explicit user action atomically queues that exact leaf and the owning task while preserving completed work, queued zero-write siblings, prior attempts, accepted hashes, typed rows, bindings, inventory, and scope

#### Scenario: Capacity recovery rejects a splittable or unrelated work
- **WHEN** `response_too_large` belongs to a multi-unit window, another Skill or operation, a work whose evidence closure is not exactly one unit, or more than one failed work exists
- **THEN** retry eligibility is false and the POST changes no state

#### Scenario: Capacity recovery rejects partial or active state
- **WHEN** the failed leaf or any queued unfinished sibling has an accepted hash, typed row, step, binding, running status, running attempt, or the task owns an execution slot
- **THEN** retry eligibility is false and the POST changes no task, work, attempt, artifact, or business row

#### Scenario: Capacity recovery is single-winner
- **WHEN** two callers concurrently submit the explicit retry for the same eligible single-unit leaf
- **THEN** exactly one transaction queues the task and leaf, the other receives a conflict, and no attempt is pre-created by either transaction

### Requirement: Coordinator failures retain only safe stage diagnostics
The Java coordinator SHALL maintain a fixed allowlist of execution stages covering task/state resume, session open, inventory traversal/resume, material review, function extraction/pre-split, reconciliation, and testcase/export. When a generic runtime failure escapes outside a structured work result, Java SHALL persist and log only a fixed coordinator failure code, the allowlisted stage, and a fixed exception category. It MUST NOT persist or expose the exception message, stack, request, response, material text, model output, URL, or credentials. `[Req-ID]: REQ-ESR-006`

#### Scenario: Failure before the first isolated Skill call is located safely
- **WHEN** session creation succeeds but function-extraction pre-split planning throws an exception containing a credential-shaped message before any isolated Skill call
- **THEN** task detail and diagnostics identify only the fixed function-extraction/pre-split stage and exception category, and contain neither the arbitrary message nor a stack

#### Scenario: Every public stage is allowlisted
- **WHEN** the coordinator advances through its structured phases
- **THEN** each persisted stage is selected from the fixed catalog and no caller-provided stage can enter the database or log

### Requirement: Explicit retry recovers the exact queued-work residue
The Java service SHALL recognize a failed structured ALL task whose prior explicit retry already queued its only unfinished work but whose coordinator failed before a new attempt was created. Eligibility requires no execution slot, exactly one unfinished `QUEUED` work, no accepted hash, no lease, no running attempt, no work-owned typed row, step, or binding, and a latest historical `FAILED` attempt that satisfies the existing explicit retry allowlist. No other `FAILED`, `QUEUED`, or `RUNNING` work may exist. `[Req-ID]: REQ-ESR-007`

#### Scenario: Exact residue resumes only the top-level task
- **WHEN** an eligible residue is explicitly retried
- **THEN** one transaction changes only the task execution status to queued/pending, preserves the queued work and historical attempt byte-for-byte, creates no attempt, and leaves completed siblings, accepted hashes, business rows, bindings, result snapshot, and artifact metadata unchanged

#### Scenario: Concurrent residue retries have one winner
- **WHEN** two callers retry the same eligible residue concurrently
- **THEN** exactly one changes the failed task to queued and the other receives a conflict without changing any work or attempt

#### Scenario: Near-miss residue fails closed
- **WHEN** the task has multiple unfinished works, a failed or running work, a lease coordinate, a running attempt, no historical failed attempt, a forbidden historical failure, an accepted hash, or any work-owned business row
- **THEN** eligibility is false and the transaction changes no task, work, attempt, completed result, binding, snapshot, or artifact

### Requirement: Recovery resumes material stages independently
The structured coordinator SHALL determine the durable completion of requirement-material review independently from function-list extraction. When every non-split review leaf is `COMPLETED` with a non-null accepted-result hash and the frozen material inventory is complete, recovery MUST rebuild the validation registry from that inventory and the accepted review facts without reconstructing, registering, invoking, or accepting any review work again. An unfinished function-list extraction MUST continue from its own persisted window while completed review work, attempts, facts, findings, bindings, and artifact metadata remain unchanged. `[Req-ID]: REQ-ESR-008`

#### Scenario: Completed reviews precede one queued extraction residue
- **WHEN** a failed ALL task resumes with all review leaves durably accepted, historical split parents retained, and exactly one zero-write function-list extraction work still queued
- **THEN** the coordinator skips the entire review stage, rebuilds evidence and accepted fact keys from persistence, and continues at function-list extraction without invoking or registering review work

#### Scenario: Incomplete review cannot masquerade as completed
- **WHEN** any non-split review leaf is queued, running, failed, missing an accepted-result hash, or the frozen inventory is absent or incomplete
- **THEN** the review stage is not treated as complete and the persisted-review registry seam fails closed

#### Scenario: Completed material stages retain restart compatibility
- **WHEN** both review and function-list extraction stages are durably complete
- **THEN** recovery continues to reconciliation from the same persisted registry and does not re-run either material stage

### Requirement: Explicit retry recovers one exhausted transient extraction leaf
The Java service SHALL permit an explicit user retry after the existing bounded automatic retry has been exhausted only when the sole failed unfinished work is a zero-write `feature-scope-reconciliation` / `FEATURE_SCOPE_EXTRACT` leaf whose latest terminal attempts are `model_execution_failed`. The failed leaf MUST have no accepted hash, lease, running attempt, typed business row, step, or binding. Historical `SPLIT` parents and completed siblings MAY coexist and MUST remain immutable. The task MUST retain its complete frozen inventory and own no execution slot. These predicates SHALL be repeated under transaction locks before mutation. `[Req-ID]: REQ-ESR-009`

#### Scenario: One split extraction child exhausted its transient attempts
- **WHEN** an extraction parent is `SPLIT`, one child is durably completed, and the only failed child has two terminal `model_execution_failed` attempts with no accepted or partial result
- **THEN** one explicit user action atomically queues only that failed child and the owning task while preserving the split parent, completed child, hashes, rows, bindings, inventory, and all historical attempts

#### Scenario: The transient branch is not an automatic retry expansion
- **WHEN** the explicit action queues the exact exhausted child
- **THEN** attempt creation remains coupled to the existing targeted worker claim and no automatic failure whitelist or retry count is widened

#### Scenario: A near-state extraction failure is rejected
- **WHEN** the failure belongs to another Skill or operation, has not exhausted the bounded transient attempts, is not `model_execution_failed`, owns an accepted hash or partial business data, has an active lease or running attempt, coexists with another failed work, or the task owns an execution slot
- **THEN** retry eligibility is false and no task, work, attempt, completed result, binding, inventory, snapshot, or artifact changes

### Requirement: Explicit retry resumes one expired reconciliation claim residue
The Java service SHALL recognize only a failed structured ALL task whose sole unfinished work is a `feature-scope-reconciliation` / `FEATURE_SCOPE_RECONCILIATION_V2` work left `RUNNING` after its exact lease expired. The work MUST have one complete expired lease coordinate, exactly one RUNNING first attempt, no accepted hash, no work-owned business row or binding, no published reconciliation/source-terminal/downstream row, and no execution slot. These predicates SHALL be repeated under transaction locks. `[Req-ID]: REQ-ESR-010`

Lease expiry SHALL be evaluated using the same absolute instant semantics as the targeted claimer and MUST NOT depend on the MySQL session time zone.

#### Scenario: Expired exact reconciliation claim resumes through the existing claimer
- **WHEN** an eligible failed task is explicitly retried
- **THEN** the retry transaction changes only the top-level task to queued/pending and preserves the expired work, running attempt, staged pages, completed siblings, accepted hashes, upstream rows, bindings, inventory, snapshot, and artifact
- **AND** the existing targeted claimer subsequently atomically marks the expired attempt failed and creates the sole next running attempt for the same work identity

#### Scenario: Live or ambiguous reconciliation ownership is rejected
- **WHEN** the lease is unexpired or incomplete, more than one work or running attempt exists, the attempt is not the first attempt, any accepted or published row exists, or the operation is not reconciliation V2
- **THEN** retry eligibility is false and no task, work, attempt, stage, business row, binding, snapshot, or artifact changes

#### Scenario: Concurrent expired-claim retries have one winner
- **WHEN** two callers retry the same eligible expired claim residue concurrently
- **THEN** exactly one queues the top-level task and neither caller directly changes the work or attempt

### Requirement: Explicit retry rebuilds one invalid reconciliation staging run
The Java service SHALL recognize only a failed structured ALL task whose sole unfinished work is `feature-scope-reconciliation` / `FEATURE_SCOPE_RECONCILIATION_V2`, whose task and latest failed attempt both carry `RECONCILIATION_V2_RESULT_INVALID` at `$.reconciliation_run`, and whose work has no accepted hash, lease, running attempt, owned final business row, or binding. The task MUST own exactly one reconciliation run, that run MUST belong to the failed work, be `STAGING`, and have a null accepted hash. The run MUST have at least one page, every page MUST be `COMPLETED`, and every page MUST carry the same `run_key` and `catalog_sha256` as the run. No formal reconciliation, source-terminal, downstream test point/case/step, or artifact ID/hash/path may exist. Every predicate SHALL be repeated under transaction row locks. `[Req-ID]: REQ-ESR-011`

#### Scenario: Invalid completed staging is rebuilt atomically
- **WHEN** an eligible invalid reconciliation run is explicitly retried
- **THEN** one transaction deletes only that run's completed pages and their cascading staged relations/bindings, deletes the run, queues the same work and task, and preserves every historical attempt, frozen scope, completed upstream work, fact, finding, function item, and upstream binding
- **AND** the next targeted claim creates one new attempt and the coordinator recreates the run and invokes KEE instead of reusing the invalid completed pages

#### Scenario: Published, active, partial, or ambiguous reconciliation is rejected
- **WHEN** the error code or path differs, the run is not `STAGING`, a page is missing or not `COMPLETED`, a page run/catalog identity differs, an accepted hash, lease, running attempt, formal reconciliation, source terminal, downstream row, any artifact coordinate, second unfinished work, or second run exists
- **THEN** eligibility is false and the retry transaction deletes, queues, or changes nothing

#### Scenario: Concurrent rebuild requests have one winner
- **WHEN** two callers submit explicit retry for the same eligible invalid staging run
- **THEN** exactly one transaction removes the invalid staging graph and queues the work, while the other receives a conflict and all preserved data remains unchanged

#### Scenario: Cleanup failure rolls back the rebuild
- **WHEN** any locked staging page or run cannot be deleted with the expected ownership and row count
- **THEN** the transaction rolls back all deletion and task/work state changes, leaving the original failed run fully intact

### Requirement: Explicit retry resumes one zero-write reconciliation model failure
The Java service SHALL recognize only a failed structured ALL task whose sole unfinished work is `feature-scope-reconciliation` / `FEATURE_SCOPE_RECONCILIATION_V2` and whose latest terminal attempt is `model_execution_failed`. Because the persisted Java contract does not retain KEE's log-only network category, eligibility SHALL be based only on durable zero-write state and SHALL NOT claim that the failure was a network error. The work MUST have no accepted hash, lease, running attempt, owned final business row, candidate-audit row, or binding. The task MUST own exactly one `STAGING` reconciliation run for that work with a null accepted hash, at least one `PLANNED` leaf, and no formal reconciliation, source terminal, downstream row, or artifact ID/hash/path. Every `PLANNED` page MUST match the run identity, retain null completion metadata, own no staged relation, and not be referenced as the parent of another persisted page. Completed pages MAY coexist and MUST remain immutable. Every predicate SHALL be repeated under transaction row locks. `[Req-ID]: REQ-ESR-012`

#### Scenario: One planned page failed before producing a candidate result
- **WHEN** an eligible V2 reconciliation work has completed staging pages and its latest model attempt fails before the current planned page writes staging data
- **THEN** one explicit user action atomically queues only the same work and task, preserves the run, all pages, completed staging, historical attempts, upstream rows, and frozen scope, and creates no attempt inside the retry transaction
- **AND** the next targeted claim creates exactly one new attempt and the coordinator invokes only the first deterministic `PLANNED` leaf

#### Scenario: Business, partial, active, or ambiguous failures remain closed
- **WHEN** the failure type is `business_validation_failed`, or a `structured_output_invalid` failure does not satisfy the dedicated REQ-ESR-013 page-level contract, or the Skill or operation differs, an accepted hash, lease, running attempt, second unfinished work, published run, formal/downstream row, artifact coordinate, candidate-audit row, planned-page staging row, stale planned parent with a child, or missing planned leaf is present
- **THEN** eligibility is false and the retry transaction changes no task, work, attempt, run, page, staging row, completed result, or upstream row

#### Scenario: Concurrent model-failure retries have one winner
- **WHEN** two callers submit explicit retry for the same eligible reconciliation model failure
- **THEN** task/work/run/page locks allow exactly one transaction to queue the work and task while the other observes the changed state and changes nothing

#### Scenario: Retry rollback preserves the complete staging graph
- **WHEN** the retry transaction rolls back after choosing the eligible mutation
- **THEN** the failed task and work, every historical attempt, run, page, staged relation, and staged binding remain byte-for-byte unchanged

### Requirement: Explicit retry resumes one zero-write reconciliation structural page failure
The Java service SHALL recognize a latest `structured_output_invalid` attempt for `feature-scope-reconciliation` / `FEATURE_SCOPE_RECONCILIATION_V2` only through a dedicated V2 page-level predicate, never through the generic structural-failure retry rule. The failed ALL task MUST own exactly one unfinished failed work, no execution slot, a complete frozen scope, and exactly one run belonging to that work. The run MUST remain `STAGING` with a null accepted hash and MUST contain at least one executable `PLANNED` leaf. Every page MUST match the run identity. Every `PLANNED` or `SPLIT` page MUST own no staged relation or binding; each `PLANNED` page MUST retain null completion metadata and MUST NOT parent another page. Every `COMPLETED` page MUST retain non-null completed-owner metadata, a nonblank result hash, and its existing staging rows unchanged. The work MUST have no accepted hash, lease, running attempt, owned final/audit row, formal reconciliation, source terminal, downstream row, or artifact coordinate. Every predicate SHALL be repeated under transaction locks. `[Req-ID]: REQ-ESR-013`

#### Scenario: One structural page failure resumes without replaying completed pages
- **WHEN** an eligible V2 work has completed pages and its latest attempt ends as `structured_output_invalid` before the current planned leaf writes staging data
- **THEN** one explicit user action atomically queues only that work and task, preserves the run, every page, completed result hash, completed staging row, historical attempt, upstream row, and frozen scope, and creates no attempt in the retry transaction
- **AND** the next targeted claim creates one new attempt and the coordinator invokes only the first deterministic `PLANNED` leaf

#### Scenario: Near states remain closed
- **WHEN** the error type, Skill, operation, task/work status, unfinished-work count, run identity/status/hash, lease, running attempt, accepted hash, artifact, publication, downstream row, audit row, page status, page completion metadata, page lineage, or unfinished staging differs from the exact eligible shape
- **THEN** eligibility is false and no task, work, attempt, run, page, staging row, completed result, upstream row, or artifact changes

#### Scenario: Fully completed globally invalid staging stays on the rebuild path
- **WHEN** every page is `COMPLETED` and the task-level reconciliation closure fails with `RECONCILIATION_V2_RESULT_INVALID` at `$.reconciliation_run`
- **THEN** this page-level branch is ineligible and only the ESR-011 full staging rebuild contract may recover the task

#### Scenario: Concurrent retry and rollback are atomic
- **WHEN** concurrent callers submit retry or the chosen transaction rolls back
- **THEN** at most one transaction queues the task/work, and a rollback leaves the complete failed run/page/staging graph byte-for-byte unchanged
