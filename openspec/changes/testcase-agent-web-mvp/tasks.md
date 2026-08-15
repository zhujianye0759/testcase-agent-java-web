## 1. Independent project foundation

- [x] 1.1 Scaffold the Java 17 Spring Boot backend and Maven Wrapper with one watched baseline test. `[Req-ID]: REQ-TSK-001`
  - Scope: `backend/pom.xml`, `backend/.mvn/**`, `backend/mvnw*`, `backend/src/main/**`, `backend/src/test/**`.
  - Depends: none.
  - Verify: `./backend/mvnw.cmd -f backend/pom.xml test` and `./backend/mvnw.cmd -f backend/pom.xml -DskipTests package` pass with UTF-8 output.

- [x] 1.2 Scaffold the Vue 3 + Vite + TypeScript frontend, router, test runner, lint/type-check scripts, and minimal shell. `[Req-ID]: REQ-WEB-001`
  - Scope: `frontend/package*.json`, `frontend/src/**`, `frontend/vite.config.*`, frontend test/lint/type configuration.
  - Depends: none.
  - Verify: `npm --prefix frontend test -- --run`, `npm --prefix frontend run typecheck`, `npm --prefix frontend run lint`, and `npm --prefix frontend run build` pass.

- [x] 1.3 Add the application-owned MySQL 8 configuration, Flyway baseline, secret-free environment example, and controlled artifact root. `[Req-ID]: REQ-TSK-006, REQ-EXP-005`
  - Scope: `compose.yaml`, backend configuration profiles, `db/migration/**`, `.env.example`, `.gitignore`.
  - Depends: 1.1.
  - Verify: a focused Testcontainers migration test passes against MySQL 8 and proves no KnowledgeEngineeringEngine database configuration is reused.

- [x] 1.4 Map the PC UI v1.0.0 semantic foundations into project tokens and document project-configurable values. `[Req-ID]: REQ-WEB-007`
  - Scope: frontend token/style files and `docs/ui-decisions.md`; no page feature logic.
  - Depends: 1.2.
  - Verify: token-focused unit/static checks pass and the shell is visually checked at 1440x820 plus one narrower and one wider PC content width.

## 2. One-feature tracer bullet

- [x] 2.1 Define the initial one-feature generation contract and domain values through RED tests. `[Req-ID]: REQ-KAG-004, REQ-ANA-002, REQ-ANA-003, REQ-ANA-005`
  - Scope: initial `testcase` and `featureaudit` values. The superseded structured result contract was removed by 6.1; the active contract is fixed Markdown.
  - Depends: 1.1.
  - Verify: the early tracer tests established deterministic result validation; 6.1 replaces its obsolete structured-result checks with fixed Markdown parser tests.

- [x] 2.2 Implement immutable scope snapshots and one reproducible Few-shot bundle through RED tests. `[Req-ID]: REQ-SCP-001, REQ-SCP-002, REQ-FEW-001, REQ-FEW-002, REQ-FEW-003`
  - Scope: `scope` and `fewshot` modules; `RequirementScope`, `ExampleScope`, hashes, AUTO/NONE, configured Good/Bad type and enabled-document filtering.
  - Depends: 2.1.
  - Verify: focused tests reject out-of-scope evidence, disabled or wrong-type examples, retry scope drift, and example business-fact contamination.

- [x] 2.3 Implement `KnowledgeAgentPort` and its WebClient/WireMock adapter for one isolated strict-scope SSE invocation. `[Req-ID]: REQ-KAG-001, REQ-KAG-002, REQ-KAG-003, REQ-KAG-004, REQ-KAG-005`
  - Scope: `knowledgeagent` port, HTTP/SSE adapter, private transport DTOs, WireMock fixtures for agent list, session creation, events, and failures.
  - Depends: 2.1, 2.2.
  - Verify: adapter contract tests cover success, terminal error, clean EOF without complete, timeout, malformed JSON, large event data, and bounded transient retry.

- [x] 2.4 Implement the minimal deterministic `WorkbookExporter` through Apache POI RED tests. `[Req-ID]: REQ-EXP-001, REQ-EXP-002, REQ-EXP-003, REQ-EXP-004, REQ-EXP-005`
  - Scope: `export` module and temporary-filesystem test seam; exactly two business worksheets, formula safety, readback, hash, opaque artifact ID.
  - Depends: 2.1, 2.2.
  - Verify: focused exporter tests reopen the workbook and assert sheet/header contract, one source row, text cell types for all four dangerous prefixes, hash, and path containment.

- [x] 2.5 Implement one durable single-feature task use case from creation to validated artifact. `[Req-ID]: REQ-TSK-001, REQ-TSK-002, REQ-TSK-007, REQ-KAG-002, REQ-EXP-001`
  - Scope: `GenerationWorkflow`, initial MySQL task/batch/attempt repositories, create/detail REST endpoints; no multi-task scheduler yet.
  - Depends: 1.3, 2.1, 2.2, 2.3, 2.4.
  - Verify: a backend integration test creates a task, invokes the fake agent once, persists accepted results, exports once, and exposes truthful detail; a validation failure produces no ready artifact.

- [x] 2.6 Implement the minimal PC task form and detail/download path for the tracer bullet. `[Req-ID]: REQ-WEB-001, REQ-WEB-003, REQ-WEB-005, REQ-WEB-006, REQ-WEB-007`
  - Scope: frontend task API client, create page, detail page, validated download action; reuse project tokens.
  - Depends: 1.4, 2.5.
  - Verify: focused frontend tests cover duplicate submit, loading/error state, keyboard focus, terminal artifact availability, and absence of filesystem paths.

- [x] 2.7 Prove the complete mocked one-feature RED→GREEN slice before broadening scope. `[Req-ID]: REQ-TSK-001, REQ-KAG-003, REQ-SCP-003, REQ-ANA-002, REQ-ANA-005, REQ-EXP-005`
  - Scope: one backend acceptance fixture and one scoped browser flow; do not add new features in this task.
  - Depends: 2.5, 2.6.
  - Verify: create → persist → isolated session → explicit SSE complete → scope/contract validation → candidate issue → Excel readback → browser download passes; injected out-of-scope evidence fails closed.

## 3. Durable shared execution

- [x] 3.1 Complete the persisted task/batch state machines and shared list/detail queries. `[Req-ID]: REQ-TSK-002, REQ-TSK-007`
  - Scope: `task` domain/repositories/use cases and paginated REST query DTOs.
  - Depends: 2.7.
  - Verify: focused domain and repository tests cover every allowed/forbidden transition, `PARTIAL`, progress calculation, pagination, and stable failure summaries.

- [x] 3.2 Enforce five active task slots with durable overflow queuing in MySQL 8. `[Req-ID]: REQ-TSK-003, REQ-TSK-008`
  - Scope: `TaskExecutionQueue`, transactional claim query, fixed executor configuration, bounded batch reads.
  - Depends: 3.1.
  - Verify: a deterministic MySQL concurrency test proves five claims execute, a sixth remains queued, and the sixth becomes claimable only after a slot is durably released.

- [x] 3.3 Add cooperative shared cancellation at safe checkpoints. `[Req-ID]: REQ-TSK-004`
  - Scope: cancellation request/use case, worker checkpoint behavior, REST mutation; no deletion.
  - Depends: 3.2.
  - Verify: integration tests cancel queued and running tasks, preserve accepted evidence, stop future claims, and reach `CANCELLED` without duplicate actions.

- [x] 3.4 Add failed-batch-only retry, bounded attempts, idempotency, lease expiry, and restart recovery. `[Req-ID]: REQ-TSK-005, REQ-TSK-006`
  - Scope: retry/recovery use cases, attempt records, idempotency constraint, startup recovery.
  - Depends: 3.2.
  - Verify: MySQL tests simulate partial success, duplicate requests, expired claims, and restart; only retryable failed batches rerun and successful results remain singletons.

- [x] 3.5 Harden server-only credentials, safe configuration, logs, and browser DTOs. `[Req-ID]: REQ-KAG-006, REQ-SCP-004`
  - Scope: backend configuration binding, redaction, authorized option resolver, frontend-safe DTOs.
  - Depends: 2.3, 3.1.
  - Verify: focused tests reject arbitrary ungranted IDs and prove API keys, model credentials, tenant secrets, external-subject secrets, internal URLs, and filesystem paths are absent from responses and captured logs.

## 4. Feature audit, ALL mode, and candidate issues

- [x] 4.1 Implement bounded cross-audit and `ALL` feature discovery without loading or prompting the full unbounded set at once. `[Req-ID]: REQ-ANA-001, REQ-TSK-008`
  - Scope: `featureaudit` module, feature pages, canonical feature IDs, batch planner.
  - Depends: 3.2, 3.5.
  - Verify: focused tests discover function-list and requirement-only features, retain omissions/conflicts, page large inputs, and create bounded deterministic batches.

- [x] 4.2 Complete Markdown row, scope-boundary, and ALL-completeness validation. `[Req-ID]: REQ-SCP-003, REQ-ANA-002, REQ-ANA-003, REQ-ANA-004, REQ-ANA-007`
  - Scope: fixed Markdown row validation and scope boundary checks; correction-call scheduling remains out of scope.
  - Depends: 2.2, 4.1.
  - Verify: focused tests reject malformed rows, invalid feature discovery, scope leaks, and example-fact contamination; no structured case-pair or basis field remains in the active Markdown contract.

- [x] 4.3 Implement candidate-only Markdown audit rows with cross-batch deduplication safeguards. `[Req-ID]: REQ-ANA-005, REQ-ANA-006`
  - Scope: four-column audit rows and MySQL persistence; no approval or confirm/reject endpoint.
  - Depends: 4.1, 4.2.
  - Verify: focused tests preserve row order and prevent replay duplication without adding review lifecycle state.

- [x] 4.4 Integrate ALL completeness and candidate reports into workflow and workbook generation. `[Req-ID]: REQ-TSK-007, REQ-ANA-007, REQ-EXP-001, REQ-EXP-003, REQ-EXP-006`
  - Scope: `GenerationWorkflow` orchestration and exporter input assembly; no UI approval feature.
  - Depends: 3.4, 4.2, 4.3.
  - Verify: workflow tests cover full success, permanent batch failure, cancellation, missing feature result, source deduplication, and retained artifact metadata with truthful terminal states.

## 5. Shared PC Web workflow

- [x] 5.1 Build the shared task list with filter/reset, pagination, progress text, and distinct initial-empty/no-results/error states. `[Req-ID]: REQ-WEB-002, REQ-WEB-006, REQ-WEB-007`
  - Scope: task list page and focused API/store/composable code.
  - Depends: 1.4, 3.1.
  - Verify: component tests cover stale/failed requests, pagination reset, missing values, keyboard operation, and non-color-only status.

- [x] 5.2 Complete task detail for frozen material summary, batch progress, audit findings, cases, recovery, and export. `[Req-ID]: REQ-WEB-003, REQ-WEB-006, REQ-WEB-007`
  - Scope: task detail page and local regions; it presents business rows only, not evidence ledgers or Few-shot protocol fields.
  - Depends: 4.4.
  - Verify: component tests cover running/partial/failed/completed states, local-region error, long/truncated content with full access, and missing data displayed as `-` rather than zero.

- [x] 5.3 Add shared cancel and failed-batch retry confirmations and final artifact download. `[Req-ID]: REQ-WEB-004, REQ-WEB-005, REQ-TSK-004, REQ-TSK-005`
  - Scope: action controls, confirmation overlays, mutation states, download client.
  - Depends: 3.3, 3.4, 5.2.
  - Verify: tests cover eligibility, explicit confirmation, duplicate-action prevention, failure context retention, focus restoration, and disabled download in every non-ready state.

- [x] 5.4 Run the PC UI implementation checklist over the complete minimal workflow and correct confirmed violations. `[Req-ID]: REQ-WEB-006, REQ-WEB-007`
  - Scope: existing frontend only; no new business capability.
  - Depends: 5.1, 5.2, 5.3.
  - Verify: record evidence for keyboard-only flow, focus return, reduced motion, slow/error/empty/repeated requests, long content, 1440x820, and relevant narrower/wider PC widths.

## 6. Simplified Markdown contract and business-friendly UX realignment

- [x] 6.1 Replace the model-result JSON contracts with small Markdown parsers through RED tests. `[Req-ID]: REQ-KAG-004, REQ-ANA-001, REQ-ANA-002, REQ-ANA-003, REQ-ANA-005, REQ-EXP-007`
  - Scope: one `功能点清单` parser for ALL discovery plus the approved two-table generation parser; exact headings/headers, escaped pipes, `<br>`, empty findings, required feature/test-case rows, malformed/fenced/JSON/image rejection.
  - Depends: existing 2.3 adapter baseline.
  - Verify: focused tests first fail for old FEATURE_AUDIT/CASE_GENERATION JSON and malformed Markdown, then pass only for the simple feature-list or approved two-table contract.

- [x] 6.2 Persist accepted Markdown rows per batch and make retry accumulation idempotent. `[Req-ID]: REQ-TSK-005, REQ-TSK-007, REQ-TSK-009, REQ-ANA-006`
  - Scope: Flyway migration, task/batch repository operations, atomic accepted-row replacement, ordered reads, raw completed Markdown diagnostic snapshot.
  - Depends: 6.1 and existing durable queue/retry baseline.
  - Verify: MySQL-focused tests prove three accepted batches accumulate in order, retry adds one copy, accepted batch replay is ignored/rejected, and partial rows survive a failed batch.

- [x] 6.3 Simplify the KEE invocation and workflow to request/accept Markdown while preserving strict scope and SSE completion. `[Req-ID]: REQ-KAG-002, REQ-KAG-003, REQ-KAG-004, REQ-SCP-001, REQ-SCP-004, REQ-FEW-002, REQ-FEW-003`
  - Scope: `KnowledgeAgentPort`, WebClient adapter, prompt builder, `GenerationWorkflow`, server-side ALL discovery before batch creation, and removal of FEATURE_AUDIT/CASE_GENERATION JSON-only mapping/validation from the active path; no new KEE core behavior.
  - Depends: 6.1, 6.2.
  - Verify: WireMock/workflow tests prove exact document whitelist, optional project omission, default AUTO examples, explicit SSE complete, terminal error/EOF failure, malformed Markdown failure, and no duplicate accepted rows; source/import scan proves superseded case-generation JSON schemas, mappers, validators, fixtures, and tests are deleted rather than bypassed.

- [x] 6.4 Generate exactly two accumulated Excel worksheets through Apache POI RED tests. `[Req-ID]: REQ-EXP-001, REQ-EXP-002, REQ-EXP-003, REQ-EXP-004, REQ-EXP-005, REQ-EXP-007`
  - Scope: exporter request/model, `ApachePoiWorkbookExporter`, artifact readback; no image retrieval or embedding.
  - Depends: 6.2.
  - Verify: focused tests reopen the workbook, assert exact sheet order/headers, cross-batch row order, line breaks/wrapping, formula safety, partial accepted rows, hash/path containment, and absence of extra sheets/images.

- [x] 6.5 Simplify the creation, list, and detail UI with business wording and recovery states. `[Req-ID]: REQ-WEB-001, REQ-WEB-002, REQ-WEB-003, REQ-WEB-004, REQ-WEB-005, REQ-WEB-006, REQ-WEB-007, REQ-WEB-008`
  - Scope: task API DTOs, HomeView, TaskListView, TaskDetailView and styles/tests; default ALL, natural-language specified feature, read-only single scope, AUTO default, NONE advanced, optional supplemental note, accepted-row preview, shared cancel/retry/download.
  - Depends: 6.2, 6.4.
  - Verify: component tests cover loading/ready/empty/error, one/multiple/no scope, duplicate submit, failure input retention and focus, keyboard Tab/Enter/dialog return, partial download wording, no UUID/JSON/project/Few-shot enums, and no image option.

- [x] 6.6 Run local integration and PC browser acceptance for the simplified flow. `[Req-ID]: REQ-TSK-009, REQ-EXP-002, REQ-WEB-007, REQ-WEB-008`
  - Scope: changed backend/frontend modules and local browser flow only; no live model call yet.
  - Depends: 6.3, 6.4, 6.5.
  - Verify: backend focused regression, frontend unit/type/lint/build, and browser acceptance at 1440x820, 1024x768, and 1920x1080 cover create ALL, specified feature, progress, partial/failure retry, cumulative preview, and Excel download.

- [x] 6.7 Surgically restore KEE changes that existed only for the superseded testcase JSON protocol. `[Req-ID]: REQ-KAG-007`
  - Scope: confirmed testcase-specific hunks in `D:\workspace\KnowledgeEngineeringEngine`; no whole-file checkout, no reset/clean, no database/configuration/prompt mutation, and no unrelated retrieval-readiness rollback.
  - Depends: 6.3.
  - Verify: before/after hunk inventory is recorded; the JSON-only helper/call/tests are absent, the dedicated test file is back at `HEAD`, `gofmt` and `git diff --check` pass. Focused Go compilation remains blocked before and after the restoration by the pre-existing `internal/utils/inject.go` `pg_query.Parse/Deparse` error, so no Go-test pass is claimed.

## 7. Live integration and bounded acceptance

- [x] 7.1 Incorporate the delegated KnowledgeEngineeringEngine contract without copying secrets or changing its core repository. `[Req-ID]: REQ-KAG-001, REQ-FEW-001, REQ-SCP-004`
  - Scope: non-secret application configuration and contract fixtures only.
  - Depends: 6.6, 6.7 and verified output from task `019ff1ab-f48a-7cd2-a349-a1d66e27d31f`.
  - Verify: configuration resolves exactly one API agent, one department example library, authorized strategic-operations options, and expected external-subject/SSE behavior; otherwise leave this task pending.

- [x] 7.2 Run one live single-feature smoke against strategic-operations V1.0 admission materials. `[Req-ID]: REQ-KAG-003, REQ-SCP-001, REQ-SCP-002, REQ-EXP-005`
  - Scope: deployed/local application plus current KnowledgeEngineeringEngine API; no source mutation in the sibling repository.
  - Depends: 7.1.
  - Verify: capture non-secret request scope, explicit SSE terminal, accepted Markdown, task state, and two-Sheet workbook readback; any scope drift or missing completion fails the smoke.

- [x] 7.3 Run one `ALL` acceptance over the available work-order plan and function-list subset in KB `abd55572-af4b-44e9-820b-3a8a5e645664`, V1.0 admission materials. `[Req-ID]: REQ-ANA-001, REQ-ANA-007, REQ-FEW-003`
  - Scope: one bounded real-model acceptance after all deterministic gates; do not reparse unrelated documents.
  - Depends: 7.2.
  - Verify: record discovered/completed/failed feature counts, accumulated finding/case row counts, scope leakage, malformed-row count, task recovery state, formula safety, and two-Sheet workbook openability. Subjective quality remains observational until an accountable gold-set owner exists.

## 8. Change-focused verification and handoff

- [x] 8.1 Run changed-module backend tests, selected MySQL 8 concurrency/recovery tests, frontend unit/type/lint/build checks, and the scoped browser workflow. `[Req-ID]: REQ-TSK-006, REQ-WEB-007`
  - Scope: tests and build gates directly covering changed modules; no unrelated workspace regression.
  - Depends: 7.3.
  - Verify: record each command and result separately, keeping build/static gates distinct from functional regression.

- [x] 8.2 Verify requirement traceability and strict OpenSpec validity, then document bounded regression scope and known exclusions. `[Req-ID]: REQ-TSK-001, REQ-WEB-001`
  - Scope: active change artifacts, production/test Req-ID markers, traceability report, review evidence.
  - Depends: 8.1.
  - Verify: every active requirement has production and test evidence where applicable; `openspec validate testcase-agent-web-mvp --type change --strict --no-interactive` passes.

- [x] 8.3 Prepare the completion handoff and leave Comet archive pending explicit user approval.
  - Scope: evidence summary and remaining external/product decisions only; no commit, push, deploy, or archive.
  - Depends: 8.2.
  - Verify: handoff distinguishes completed, pending, risks, live-verification evidence, and the later login/approval/knowledge-ingestion changes.

## 9. Prompt-quality follow-up

- [x] 9.1 Absorb the approved Dify functional-test design guidance into the configured KEE API agent prompt. `[Req-ID]: REQ-KAG-008`
  - Scope: update only the existing API agent's `system_prompt` configuration; preserve the agent identity, model, tools, selected knowledge base, Markdown discovery contract, Java adapter/parser, KEE core code, and all server-side credentials.
  - Depends: 8.3.
  - Verify: record a secret-free before/after prompt hash and configuration snapshot, reload and read back the saved prompt, assert the exact two-table headings and positive/negative/evidence-bound rules, prove old JSON and approval-lifecycle wording are absent, and run one bounded live single-feature smoke through the existing Java Web chain without adding Java validation.
  - Evidence: prompt readback is 2,159 characters with SHA-256 `48d377659ee79630a8f4e7da35da384f216ec56ef201c02a96f5aaf836e60f68`; required markers are present, forbidden markers are absent, and the non-prompt configuration hash is unchanged. Live task `f999b685-21d4-4e18-a074-d2b5d7b7043e` completed with one audit row, two named testcase rows, and an artifact. Without Java semantic validation, the observed model output did not fully follow the requested minimum negative-scenario count or step/result-count correspondence; this remains a documented prompt-only limitation.
