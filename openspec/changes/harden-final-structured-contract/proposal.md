## Why

The final KEE structured-skill contract adds exact source-closure obligations that Java must enforce before it accepts, persists, displays, or exports results. Existing validators already cover much of the boundary, but reconciliation closure and high-granularity expected-result ordering need explicit Java-side fail-closed proof before the one final business task can be created.

## What Changes

- Prove that each requirement-review call uses a single-document authorization and preserves the completed parsed-unit snapshot without rewriting content, keys, or ordinals.
- Enforce bidirectional reconciliation source coverage, exact reconciliation evidence-set closure, and pending-only insufficient-evidence results.
- Enforce that each testcase's `expected_results` is exactly the ordered sequence of its persisted step expectations.
- Require full frozen review-finding identity, source quote, Chinese analysis, pending proposed-good example, and task-level root-cause consolidation at every Java acceptance boundary.
- Preserve the supplementary-material prohibition for formal requirement facts and the existing high-granularity formal/pending delivery boundary.
- Expose server-authorized document leaves beneath each task-option material type so callers can freeze an exact document set without widening to another document of the same type.
- Retain a safe field-level business-validation diagnostic on the failed attempt, work item, and task without persisting model output or material text.
- Adopt KEE reconciliation protocol V2 (`operation=reconcile_page`, `protocol_version=2`): every page carries the same complete canonical global catalog and one deterministic owner window, with configurable V2-only request/response byte budgets and no catalog truncation.

## Capabilities

### New Capabilities

- `final-structured-contract-enforcement`: Java-side validation and delivery guarantees for the final KEE structured-review, reconciliation, and high-granularity testcase contract.

### Modified Capabilities

- None.

## Impact

- Affected code: structured coordinator, material-slice planner, requirement-review, reconciliation DTO/validator, testcase validators, acceptance store, task detail projection, additive Flyway migrations, their tests, and Java OpenSpec artifacts.
- Affected external boundary: the feature-reconciliation isolated-skill request/result moves to the frozen V2 page protocol; other KEE Skills, normal Agent Chat, and task creation remain unchanged. Java's application-owned schema gains only additive diagnostics, V2 page staging, relation, binding, and terminal-ledger storage.

## Compatibility

V2 is additive. Java SHALL retain strict V1 `operation=reconcile` DTO/result parsing and previously committed task reads. New reconciliation work, including a failed structured ALL task that has completed review/extraction but has never registered or accepted a reconciliation, SHALL use only V2. Java SHALL NOT reinterpret, rewrite, or call KEE again for a previously completed V1 reconciliation.

- The task-creation endpoint does not gain a new route or accept raw KEE document IDs; it only accepts opaque Java-issued aggregate or document-leaf selection IDs.
- Legacy material-type selection IDs and existing task snapshots remain readable.
