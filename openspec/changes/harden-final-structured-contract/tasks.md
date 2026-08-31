## 1. Contract RED baseline

- [x] 1.1 Add watched-failure tests for one-document parsed-unit preservation and supplemental-material fact isolation.
- [x] 1.2 Add watched-failure tests for reconciliation evidence exact closure, complete input coverage, and insufficient-evidence confirmation.
- [x] 1.3 Add watched-failure tests for ordered testcase expected-results equality and mandatory frozen review finding identity.
- [x] 1.4 Add watched-failure tests for exact document selection, current-version eligibility, and legacy material-type compatibility.

## 2. Java acceptance implementation

- [x] 2.1 Preserve and assert immutable parsed-unit review-slice identity at the coordinator/validator seam.
- [x] 2.2 Strengthen reconciliation work-item metadata and validator exact closure rules.
- [x] 2.3 Strengthen testcase expected-results ordering and review-finding mandatory identity validation.
- [x] 2.4 Verify transactional acceptance and same-record detail/Excel projection do not admit rejected results.
- [x] 2.5 Expose revalidated document-leaf selections and render them without exposing transport IDs, while preserving aggregate legacy selection.

## 3. Verification and release preparation

- [x] 3.1 Run focused validators, coordinator, and MySQL/Testcontainers acceptance regressions.
- [x] 3.2 Run affected backend/frontend regression, OpenSpec strict validation, build, diff, and sensitive-content checks. (The full backend run retains only the separately established legacy fixed-2N/JSON-whitespace failures; affected structured tests pass.)
- [x] 3.3 Re-run selection-focused backend/frontend and scope regression gates after the document-leaf capability.
- [x] 3.4 Review the Java-only diff, commit locally, deploy the existing Java runtime, and run the final GET-only scope gate.
- [x] 3.5 Create exactly one final structured ALL task only after all preceding gates pass; record API, database, page, and Excel evidence without retrying or creating another task. Task `39e6bbac-f24d-45ba-bfbd-848c346920ee` was created once and failed at Java business validation after KEE returned HTTP 200; no artifact was produced, and no retry or replacement task was created.

## 4. Safe business-validation diagnostics

- [x] 4.1 Add watched-failure tests for enumerated requirement-review code/path/message, coordinator propagation, and credential-free correlated logging. `[Req-ID]: REQ-FSC-007`
- [x] 4.2 Add V14 and MySQL acceptance tests that atomically persist the same safe diagnostic on attempt/work/task, project it through task detail without rejected content, and reject generic task retry for `business_validation_failed`. `[Req-ID]: REQ-FSC-007`
- [x] 4.3 Audit the frozen requirement-review validator against contract-positive fixtures; fix only a test-proven false rejection and otherwise retain the current strict boundary. No contract-positive false rejection was proven, so the strict validator was not relaxed. `[Req-ID]: REQ-FSC-007`
- [x] 4.4 Run focused validators, coordinator, diagnostics, MySQL migration/acceptance, related backend build, OpenSpec strict, diff, and sensitive-content gates without creating or replaying a business task. `[Req-ID]: REQ-FSC-007`

## 5. Global reconciliation protocol V2

- [x] 5.1 Add strict DTO RED for retained V1 historical records plus separate V2 page input/result records, the exact `reconcile_page` fields, unknown-field rejection, canonical catalog/run/owner objects, server-derived relation fields, and V2-only configurable 16 MiB/4 MiB budgets while other Skills remain 2 MiB. `[Req-ID]: REQ-FSC-008`
- [x] 5.2 Add coordinator/planner RED proving stable catalog order and digest, identical global catalog on every page, exact owner-window partition, deterministic run/page identities using the frozen byte sequence with no trailing JSON newline, no catalog sampling, current 297/514 stage-gap recovery through V2 without material reprocessing, and completed V1 downstream recovery with zero KEE calls. `[Req-ID]: REQ-FSC-008`
- [x] 5.3 Add validator RED for independent reconciliation-key/owner/evidence recomputation using the frozen byte sequence with no trailing JSON newline, overlapping relations, completed-owner echo, all-page exact closure, same-path exact/confirmed semantics, and deterministic `response_too_large` owner-window bisection only. `[Req-ID]: REQ-FSC-008`
- [x] 5.4 Add V15/store RED for non-business page staging, run/catalog mismatch rejection, atomic final relations/overlapping bindings/one-terminal-per-source publication, rollback, concurrency, restart, and completed-work zero-call recovery. `[Req-ID]: REQ-FSC-008`
- [x] 5.5 After KEE V2 GREEN and frozen derivation fixtures are available, implement Java DTOs, canonicalization, page planning, strict validation, staging/finalization, safe planning diagnostics, and row-locked stage-gap retry eligibility. `[Req-ID]: REQ-FSC-008`
- [x] 5.6 Run focused and MySQL transaction/concurrency/restart/retry tests, V15 migration validation, affected regressions, strict OpenSpec, diff/sensitive gates, build, and Java-only deployment. Do not call business retry until KEE and Java zero-write V2 gates are separately authorized. `[Req-ID]: REQ-FSC-008`
- [x] 5.7 Lock the deployed KEE provider-length capacity contract with HTTP 400 error-mapping and minimum-owner fail-closed regressions; re-run V2 coordinator, adapter, restart/atomic-publish, strict OpenSpec, and build gates without creating a business task. `[Req-ID]: REQ-FSC-008`
