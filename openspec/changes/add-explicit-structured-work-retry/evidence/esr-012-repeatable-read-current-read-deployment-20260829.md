# REQ-ESR-012 repeatable-read current-read and deployment evidence

## Scope

This gate covers the explicit recovery eligibility for one failed `FEATURE_SCOPE_RECONCILIATION_V2` work whose durable state proves zero accepted output. It does not authorize or invoke the business retry endpoint.

## RED and GREEN

- The original real-MySQL RED demonstrated that a normal `REPEATABLE READ` snapshot could hide a binding committed while the retry transaction waited for the unfinished-work lock: expected retry result `0`, old implementation returned `1`.
- The mutation path now locks the task and unfinished work before its first ordinary read and uses locking current reads for the fixed business tables, downstream rows, artifact, run, page, and staging predicates.
- The 11-table advisory query remains task-scoped, uses a fixed table-name allowlist and `LIMIT 1`, and does not materialize an all-history `UNION ALL`.
- The deterministic concurrency test triggers immediately before the actual unfinished-work `FOR UPDATE` query, proves that retry is blocked at that boundary, commits the concurrent binding, and verifies fail-closed result `0`.
- Focused REQ-ESR-012 tests: `27/27` passed.
- Pure Java regression: `434/434` passed.
- Related real-MySQL/Testcontainers regression: `245/245` passed, comprising acceptance-store `218`, task-detail `10`, queue `4`, and migration `13`; failures `0`, errors `0`, skipped `0`.

## Static and review gates

- `openspec validate add-explicit-structured-work-retry --strict`: passed.
- `git diff --check`: exit `0`; only existing LF-to-CRLF notices were emitted.
- Added production/spec-line sensitive scan: `0` matches.
- Deployment backend/frontend logs: `0` credential/body patterns and `0` severe error/exception/failure patterns.
- Spec-axis review: P0 `0`, P1 `0`, P2 `0`, P3 `0`.
- Standards-axis review: P0 `0`, P1 `0`, P2 `0`, P3 `0`.

## Build and immutable runtime

- Build command: Maven Wrapper `package` with `-DskipTests`; result `BUILD SUCCESS`.
- Immutable JAR: `backend/target/testcase-agent-backend-esr012-repeatable-read-current-read-20260829.jar`.
- Size: `54,192,016` bytes.
- SHA-256: `EDEE4223E80B4239E723657B59B2AD4C7973411381BE22C896FBC658A33B3F5B`.
- Java PID: `20660`; single listener on `8082`.
- KEE outer wait: `3060s`.
- Flyway: V19, successful and already current; no new migration was applied.
- Frontend PID: `29024`; root and `/api/tasks` proxy both HTTP `200` on `5173`.
- KEE was inspected read-only: container `7b0f74b394176eecd2aa45033e1947ba3d4162d5aee4e8d3d74d8007661ddb4d`, image `sha256:34ae7dfa3f695f0627eb0a38e6db8358ee237fadb1a18af9d5d964f27a85ff57`, running and healthy; it was not modified.

## Live zero-side-effect gate

Target task: `e189e412-4a6a-403e-9e45-09f9d8c4bca6`. Target work: `23bab5f0-7c7e-437c-b5c7-eaf5fba64005`.

- Task count `20`, inflight count `0`.
- Task remains `FAILED / FAILED / PENDING`; artifact remains absent.
- Work remains failed with null accepted hash and no lease.
- Reconciliation pages remain `2 COMPLETED + 6 PLANNED`; staging remains `200` relations and `590` bindings.
- Formal reconciliation, source terminals, test points, test cases, steps, and artifact remain `0`.
- Upstream facts/findings/functions/reference bindings remain `503 / 12 / 294 / 972`.
- Candidate audit counts remain `95 / 303 / 303`.
- Frozen inventory remains `5` documents and `470` units.
- The SHA-256 digest across the task, all work and attempts, the eight business-result tables, V19 audit tables, run/pages/staging, source terminals, and frozen inventory was identical before any runtime, under the previous immutable JAR, and under the new JAR: `29B5D464FB3DC5AC9039CC77A214C6C5D2F7B173C2C0BD5CD4D618087616A43A`.
- The latest previously deployed immutable JAR already returned `canRetry=true` for this exact legitimate state, so the observed live transition is truthfully `true -> true`, not the anticipated `false -> true`. This deployment hardens concurrent visibility and near-state rejection without changing the legitimate task's final eligibility.
- No task creation, cancellation, resume, retry, KEE invocation, or business data mutation was performed.

Ignored runtime evidence: `backend/target/acceptance/esr012-repeatable-read-current-read-deploy-20260829`.
