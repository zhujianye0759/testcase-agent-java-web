## Why

Structured ALL tasks can preserve completed work yet become permanently stranded when one KEE call ends with an audited structural/model terminal error. The generic retry endpoint currently requeues the task without making that exact failed work claimable, so the task immediately fails again and the UI overstates the available recovery capability.

## What Changes

- Add an explicit user-triggered retry transaction for one eligible failed structured work item in a failed ALL task.
- Preserve every completed work item, accepted result hash, typed business row, reference binding, frozen scope, and prior attempt.
- Permit audited KEE structural/model terminal failures and an explicit `business_validation_failed` retry only when the latest failed attempt carries a code from a separately reviewed retry allowlist plus a bounded safe field path. The initial business allowlist contains only `REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED`; all other known or unknown codes remain closed.
- Make concurrent or repeated retry requests single-winner and return a safe conflict when no exact retry is eligible.
- Expose reader-safe retry eligibility in task detail so the frontend offers retry only when the backend can perform it.
- Resume through the existing coordinator: completed identities are skipped, the exact failed identity creates one new attempt, and later work is planned only after that work succeeds.
- Admit a `response_too_large` material-review failure only after deterministic splitting has reached one persisted unit and a zero-write replay has proved the corrected KEE result passes the current Java mapper and business validator.
- Persist a fixed, reader-safe coordinator failure code, allowlisted stage, and exception category when execution fails outside an individual structured work attempt.
- Recover the exact post-retry residue where the task failed after its only zero-write work had already been queued, without mutating that work or creating an attempt in the retry transaction.
- Rebuild one fully staged but globally invalid reconciliation run only after an audited task-level reconciliation validation failure proves that no formal or downstream result was published.
- Resume one page-level `structured_output_invalid` reconciliation failure only when completed pages remain immutable and every unfinished leaf is durably zero-write; this is a dedicated V2 state proof, not a generic failure allowlist.

## Capabilities

### New Capabilities

- `explicit-structured-work-retry`: Controlled same-task recovery of one eligible failed structured work item without repeating completed work or changing the frozen material scope.

### Modified Capabilities

None.

## Impact

- Backend task retry controller, workflow, repository transaction, structured work store, detail DTO, and queue/coordinator tests.
- Coordinator stage tracking and task-level safe diagnostics; no raw exception message, stack, request, response, material text, or credential is stored or exposed.
- The new capacity branch changes only explicit user retry eligibility; it does not add `response_too_large` to automatic retry or permit another split below one persisted unit.
- Frontend task-detail retry eligibility and conflict handling.
- MySQL schema remains unchanged unless implementation proves an additional durable coordinate is necessary.
- The reconciliation rebuild uses the existing V15 foreign-key ownership graph and adds no migration: page deletion cascades only that run's staged relations and bindings before the run is deleted.
- The page-level structural-failure branch preserves the existing V15 run, completed-page hashes, and completed staging rows; it requeues only the same work so the next claim visits the first deterministic `PLANNED` leaf.
- KEE APIs, parsed documents, OCR, PageIndex, graph processing, automatic retry policy, and task creation are unchanged.
