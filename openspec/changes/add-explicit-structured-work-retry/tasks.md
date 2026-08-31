## 1. Retry transaction TDD

- [x] 1.1 Add MySQL RED tests for one eligible failed structured work, preserved completed hashes/rows/bindings, zero-write precondition, and exact attempt history. `[Req-ID]: REQ-ESR-001, REQ-ESR-003`
- [x] 1.2 Add concurrent/repeated retry RED tests and forbidden failure-type tests, including a second explicit action requirement after another structural failure. `[Req-ID]: REQ-ESR-001, REQ-ESR-002`
- [x] 1.3 Implement the atomic explicit structured retry transaction without schema changes or automatic retry whitelist expansion. `[Req-ID]: REQ-ESR-001, REQ-ESR-002, REQ-ESR-003`

## 2. Coordinator and HTTP behavior

- [x] 2.1 Add coordinator recovery tests proving completed slices are skipped, only the recovered identity creates the next attempt, and later work continues from persisted inputs. `[Req-ID]: REQ-ESR-003`
- [x] 2.2 Return safe HTTP 409 for ineligible/stale retry while preserving legacy eligible batch retry behavior. `[Req-ID]: REQ-ESR-001, REQ-ESR-004`

## 3. Truthful detail and frontend

- [x] 3.1 Project reader-safe retry eligibility/reason through task detail with legacy compatibility tests. `[Req-ID]: REQ-ESR-004`
- [x] 3.2 Make the task page use backend eligibility, show accurate structured retry wording, and reject stale action conflicts without losing context. `[Req-ID]: REQ-ESR-004`

## 4. Verification and deployment

- [x] 4.1 Run focused repository/coordinator/controller/MySQL tests plus ordinary retry and structured regression tests. `[Req-ID]: REQ-ESR-001~004`
- [x] 4.2 Run frontend unit/typecheck/lint/build, backend package/build, OpenSpec strict, diff and sensitive-data gates. `[Req-ID]: REQ-ESR-001~004`
- [x] 4.3 Build and replace the single Java 8082 service, verify JAR hash, health, 3060-second timeout, and unchanged KEE/MySQL lifecycle. `[Req-ID]: REQ-ESR-001~004`
- [x] 4.4 Recheck the exact failed task, invoke the official retry once, and collect either its same-task terminal failure boundary or, on success, its API/DB/page/two-Sheet evidence without creating another task. `[Req-ID]: REQ-ESR-001~004`

## 5. Safely diagnosed business-validation retry

- [x] 5.1 Add MySQL RED coverage for a diagnosed zero-write `business_validation_failed` work, missing/unknown code, blank/unsafe path, preserved 6 findings/48 bindings, partial-row refusal, and concurrent single-winner behavior. `[Req-ID]: REQ-ESR-001~004`
- [x] 5.2 Extend only the explicit user retry allowlist to the separately reviewed `REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED` code plus safe path, clear current work/task diagnostics atomically, and retain failed-attempt history without changing automatic retry policy. `[Req-ID]: REQ-ESR-001~003`
- [x] 5.3 Run focused/MySQL/concurrency regressions, strict OpenSpec, diff/review gates, then build and replace the single 8082 service. `[Req-ID]: REQ-ESR-001~004`
- [x] 5.4 Verify the exact task becomes retryable, invoke the official retry once, and monitor only that task to a new explicit terminal boundary. `[Req-ID]: REQ-ESR-001~004`

## 6. Exhausted single-unit review capacity retry

- [x] 6.1 Add a real-MySQL RED proving that one zero-write failed `response_too_large` material-review leaf at a single persisted unit is retryable while zero-write queued siblings and completed hashes/rows remain unchanged. `[Req-ID]: REQ-ESR-005`
- [x] 6.2 Add fail-closed MySQL coverage for multiple failed leaves, non-review work, multi-unit/evidence windows, accepted hashes, partial typed rows or bindings, running work/attempts, execution slots, repeated calls, and concurrent single-winner behavior. `[Req-ID]: REQ-ESR-002, REQ-ESR-005`
- [x] 6.3 Implement the minimal explicit-retry predicate and transaction-lock recheck without changing automatic retry, split logic, task creation, parsing, or KEE contracts. `[Req-ID]: REQ-ESR-001~005`
- [x] 6.4 Run focused MySQL/HTTP/recovery/lease/concurrency regressions, backend and frontend gates, strict OpenSpec, diff/sensitive review, then build and replace only the single Java 8082 service. `[Req-ID]: REQ-ESR-001~005`
- [ ] 6.5 Recheck the exact task and corrected KEE image, invoke the official retry once, and monitor only that task to a new terminal boundary; on completion, collect the same-task API/DB/page/two-Sheet matrix. `[Req-ID]: REQ-ESR-001~005`

## 7. Safe coordinator diagnostics and queued-work residue recovery

- [x] 7.1 Add coordinator and public-detail RED tests for the fixed stage catalog, fixed exception categories, pre-isolated-call location, and absence of arbitrary messages, stacks, requests, responses, material text, URLs, and credentials. `[Req-ID]: REQ-ESR-006`
- [x] 7.2 Add real-MySQL RED tests for the exact one-queued-work residue, task-only mutation, historical attempt preservation, completed rows/hashes/bindings/artifact immutability, rollback, near-miss refusal, and concurrent single winner. `[Req-ID]: REQ-ESR-002, REQ-ESR-003, REQ-ESR-007`
- [x] 7.3 Implement the allowlisted coordinator diagnostic and task-only residue transaction without changing automatic retry, creating an attempt, modifying the queued work, or widening any KEE failure allowlist. `[Req-ID]: REQ-ESR-006, REQ-ESR-007`
- [x] 7.4 Run focused coordinator/HTTP/real-MySQL/concurrency/recovery/lease tests, related regressions, strict OpenSpec, diff/sensitive/security review, and an isolated backend build. `[Req-ID]: REQ-ESR-001~007`
- [x] 7.5 Build an immutable JAR, replace only the single 8082 Java service, and prove the live failed task/work/attempt/business rows/artifact are unchanged while advisory eligibility becomes true. Do not call retry. `[Req-ID]: REQ-ESR-006, REQ-ESR-007`

## 8. Independent material-stage recovery

- [x] 8.1 Add RED coverage for the durable review-stage predicate, including accepted leaves, historical split parents, queued extraction, missing hashes, incomplete inventory, and near-state rejection. `[Req-ID]: REQ-ESR-008`
- [x] 8.2 Add coordinator RED coverage proving completed reviews are neither reconstructed nor invoked when one persisted extraction window remains, while the accepted fact/evidence registry is restored. `[Req-ID]: REQ-ESR-003, REQ-ESR-008`
- [x] 8.3 Implement the minimal independent review/extraction stage gates and persisted registry guard without weakening work registration, split lineage, evidence, retry, or acceptance checks. `[Req-ID]: REQ-ESR-008`
- [x] 8.4 Run focused coordinator and real-MySQL recovery/idempotency/concurrency tests, related regressions, strict OpenSpec, diff/sensitive/security review, and an isolated backend build. `[Req-ID]: REQ-ESR-001~008`
- [ ] 8.5 Build an immutable JAR, replace only the single 8082 Java service, prove the live task is unchanged and retryable, then invoke one authorized same-task retry and collect its new terminal boundary or final same-task acceptance evidence. `[Req-ID]: REQ-ESR-003, REQ-ESR-007, REQ-ESR-008]`

## 9. Exhausted transient extraction recovery

- [x] 9.1 Add a real-MySQL RED for a `SPLIT` extraction parent with one completed 1..16 child and one zero-write 17..32 child that exhausted two `model_execution_failed` attempts; prove completed rows, bindings, hashes, lineage, and attempts remain immutable. `[Req-ID]: REQ-ESR-002, REQ-ESR-003, REQ-ESR-009`
- [x] 9.2 Add fail-closed and concurrency coverage, then implement only the explicit exhausted `FEATURE_SCOPE_EXTRACT` predicate without widening automatic retries or unrelated failures. `[Req-ID]: REQ-ESR-001, REQ-ESR-002, REQ-ESR-009`
- [x] 9.3 Run focused/real-MySQL/coordinator/HTTP/recovery regressions, strict OpenSpec, diff/sensitive review, build an immutable JAR, replace only 8082, and prove the unchanged live task becomes retryable. Do not call retry. `[Req-ID]: REQ-ESR-004, REQ-ESR-009`

## 10. Expired reconciliation claim residue recovery

- [x] 10.1 Add real-MySQL RED coverage for one expired RUNNING V2 reconciliation claim, task-only mutation, preserved staged/upstream state, targeted attempt recovery, near-state rejection, rollback, and concurrent single winner. `[Req-ID]: REQ-ESR-002, REQ-ESR-003, REQ-ESR-010`
- [x] 10.2 Implement the narrow locked predicate and task-only mutation without reviving an expired claim inside the retry transaction or widening automatic retry policy. `[Req-ID]: REQ-ESR-010`
- [x] 10.3 Run focused heartbeat/retry/coordinator/real-MySQL regressions, strict OpenSpec, diff/security gates, build and deploy one immutable Java JAR, then resume only the authorized existing task. `[Req-ID]: REQ-SEW-003, REQ-ESR-004, REQ-ESR-010`

## 11. Invalid completed reconciliation staging rebuild

- [x] 11.1 Add real-MySQL RED coverage for the exact invalid completed reconciliation staging shape, preserved upstream and attempt history, all near-state rejection predicates, rollback, and concurrent single winner. `[Req-ID]: REQ-ESR-002, REQ-ESR-003, REQ-ESR-011`
- [x] 11.2 Implement the locked `REBUILD_INVALID_RECONCILIATION_STAGING` mutation using the existing V15 staging ownership graph without a migration or broader retry allowlist. `[Req-ID]: REQ-ESR-011`
- [x] 11.3 Prove the rebuilt coordinator recreates planned pages and invokes KEE, while restart, idempotency, completed upstream work, and all rejected states remain unchanged. `[Req-ID]: REQ-ESR-003, REQ-ESR-011`
- [x] 11.4 Run focused and related real-MySQL/concurrency/coordinator/HTTP regressions, strict OpenSpec, diff/security/build gates, deploy one immutable Java JAR, and prove the live task becomes retryable with zero side effects. Do not call retry. `[Req-ID]: REQ-ESR-004, REQ-ESR-011`

## 12. Zero-write reconciliation model-failure resume

- [x] 12.1 Add real-MySQL RED coverage for the exact failed V2 work with completed pages, planned pages, preserved staging, a latest `model_execution_failed` attempt, and every fail-closed near-state. `[Req-ID]: REQ-ESR-002, REQ-ESR-012`
- [x] 12.2 Implement the locked zero-write predicate and existing same-work requeue mutation without persisting or claiming KEE's log-only network category. `[Req-ID]: REQ-ESR-001, REQ-ESR-012`
- [x] 12.3 Prove retry preserves the run/pages/staging graph, the next claim creates one new attempt, and coordinator recovery invokes only deterministic `PLANNED` pages while completed pages remain untouched. `[Req-ID]: REQ-ESR-003, REQ-ESR-012`
- [x] 12.4 Run focused, real-MySQL, concurrency, rollback, HTTP, coordinator, strict OpenSpec, diff/security/build, two-axis review, immutable deployment, and live zero-side-effect eligibility gates. Do not call retry. `[Req-ID]: REQ-ESR-004, REQ-ESR-012`

## 13. Zero-write reconciliation structural-page resume

- [x] 13.1 Add watched RED coverage for the exact V2 `structured_output_invalid` page-level shape, completed-page hash/staging preservation, at least one executable planned leaf, and every fail-closed near-state. `[Req-ID]: REQ-ESR-002, REQ-ESR-013`
- [x] 13.2 Implement the dedicated locked predicate and existing same-work requeue mutation without widening the generic structural-failure retry rule. `[Req-ID]: REQ-ESR-001, REQ-ESR-013`
- [x] 13.3 Prove concurrent single-winner, transaction rollback, next-attempt creation, and coordinator traversal that skips all completed pages and starts from the first deterministic planned leaf. `[Req-ID]: REQ-ESR-003, REQ-ESR-013`
- [ ] 13.4 Run focused, pure-Java, real-MySQL, HTTP/coordinator/recovery regressions, strict OpenSpec, diff/sensitive checks, two-axis review, immutable deployment, and live zero-side-effect eligibility gates. Do not call retry. `[Req-ID]: REQ-ESR-004, REQ-ESR-013`
