# REQ-ESR-011 invalid reconciliation staging rebuild evidence

## TDD and regression

- RED: the 26-state real-MySQL near-state test exposed five production false positives: a second task run, page/run identity mismatch, page/catalog identity mismatch, artifact-hash-only residue, and artifact-path-only residue. A separate zero-page fixture assertion was corrected without changing production behavior.
- GREEN: `StructuredGenerationAcceptanceStoreIntegrationTest#invalidReconciliationStagingRecoveryRejectsEveryNearState` passed 26/26.
- Related gate: `StructuredGenerationAcceptanceStoreIntegrationTest`, `DefaultStructuredAllGenerationCoordinatorTest`, `GenerationTaskRepositoryRetryTest`, `GenerationTaskControllerRetryTest`, and `ScheduledStructuredWorkLeaseHeartbeatTest` passed 229/229 with zero failures, errors, or skips.
- Build: `./mvnw.cmd -q -DskipTests package` passed.
- Specification/static gates: `openspec validate add-explicit-structured-work-retry --strict` and `git diff --check` passed; only pre-existing LF/CRLF conversion warnings were emitted.
- Two-axis final read-only review reported zero actionable P0/P1/P2 findings after the task-wide run lock, page identity, and artifact-coordinate gates were added.

## Deployment and live zero-side-effect gate

- Immutable JAR: `backend/target/testcase-agent-backend-rebuild-invalid-reconciliation-staging-20260826.jar`.
- SHA-256: `2E34A221E7ABDACC4C8CE6CBDE83A3007BCFE1DF6C778318D8F204ED1F2784AE`; bytes: `54189866`.
- Runtime: PID `47916`, port `8082`, Knowledge Agent outer wait `3060s`.
- Flyway validated 17 migrations; schema remained at V19 with no migration required.
- `8082` task/task-options, `5173` page and both proxy endpoints returned HTTP 200; frontend PID `32096` was not restarted.
- KEE running/latest image remained `sha256:5e361a16087049119a526ae446a40eaa8eada4a3339c62fa1ed6e9238e122ea6`, container `df15465d5dc5759e6fda10a2dfad58cdb536d9217f268b5bd9ebd77bb067f747`, healthy with restart count 0.
- Live task `e189e412-4a6a-403e-9e45-09f9d8c4bca6` changed only from advisory `canRetry=false` to `canRetry=true`. It stayed `FAILED/FAILED/PENDING` with exact `RECONCILIATION_V2_RESULT_INVALID` at `$.reconciliation_run`.
- Before/after hashes were equal for the work set, attempt history, reconciliation run, eight completed pages, published relations, and all counted business rows. Counts remained: 503 facts, 12 findings, 294 function items, 972 bindings, one STAGING run, eight pages, 294 staged relations, 821 staged bindings, and zero formal reconciliations/source terminals/test points/test cases/steps/artifacts.
- No retry POST, task creation, KEE business request, migration, or MySQL/KEE/frontend lifecycle action occurred.

Ignored runtime evidence: `backend/target/acceptance/rebuild-invalid-reconciliation-staging-20260826/`.
