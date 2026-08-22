## Why

The final KEE structured-skill contract adds exact source-closure obligations that Java must enforce before it accepts, persists, displays, or exports results. Existing validators already cover much of the boundary, but reconciliation closure and high-granularity expected-result ordering need explicit Java-side fail-closed proof before the one final business task can be created.

## What Changes

- Prove that each requirement-review call uses a single-document authorization and preserves the completed parsed-unit snapshot without rewriting content, keys, or ordinals.
- Enforce bidirectional reconciliation source coverage, exact reconciliation evidence-set closure, and pending-only insufficient-evidence results.
- Enforce that each testcase's `expected_results` is exactly the ordered sequence of its persisted step expectations.
- Require full frozen review-finding identity, source quote, Chinese analysis, pending proposed-good example, and task-level root-cause consolidation at every Java acceptance boundary.
- Preserve the supplementary-material prohibition for formal requirement facts and the existing high-granularity formal/pending delivery boundary.
- Expose server-authorized document leaves beneath each task-option material type so callers can freeze an exact document set without widening to another document of the same type.

## Capabilities

### New Capabilities

- `final-structured-contract-enforcement`: Java-side validation and delivery guarantees for the final KEE structured-review, reconciliation, and high-granularity testcase contract.

### Modified Capabilities

- None.

## Impact

- Affected code: structured coordinator, material-slice planner, requirement-review, reconciliation, testcase validators, acceptance store, their tests, and Java OpenSpec artifacts.
- Affected external boundary: existing KEE isolated-skill JSON calls and Java task-options/task-creation selection. No KEE implementation, normal Agent Chat, or database schema is changed.

## Compatibility

- The task-creation endpoint does not gain a new route or accept raw KEE document IDs; it only accepts opaque Java-issued aggregate or document-leaf selection IDs.
- Legacy material-type selection IDs and existing task snapshots remain readable.
