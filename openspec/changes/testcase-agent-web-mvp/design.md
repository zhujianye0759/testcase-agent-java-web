## Context

This is a new repository. The existing AI, retrieval, document parsing, Wiki, PageIndex, and evidence capabilities live in `D:\workspace\KnowledgeEngineeringEngine`; its current working tree is independently owned and dirty, so this change treats it as an external service. A separate Codex task is preparing the API-specific test-case agent, department example library, and bounded service credential.

The application serves about 15 internal users without Phase 1 authentication. Viewing, cancellation, retry, and download are shared operations. Knowledge scope remains a hard security boundary even though user-level authorization is deferred. The first development slice uses one feature; after deterministic gates pass, the same MVP adds `ALL` mode and performs one real acceptance run against strategic-operations knowledge base `abd55572-af4b-44e9-820b-3a8a5e645664`, V1.0 admission materials.

The repository now has a CodeGraph index. Design, implementation, review, and affected-test selection use CodeGraph first and fall back to source reads only for documents, configuration, or explicit index gaps.

## Goals / Non-Goals

**Goals:**

- Deliver one coherent Web flow from a business-friendly task form through durable execution, fixed Markdown validation, batch-safe accumulation, and a two-Sheet Excel download.
- Keep at most five tasks executing while additional tasks remain durably queued.
- Make interruption, retry, partial failure, missing SSE terminal state, scope violations, and unsafe Excel content observable and testable.
- Preserve reproducibility through immutable scope, prompt, Schema, Few-shot, attempt, and artifact snapshots.
- Apply the PC UI v1.0.0 semantic tokens, page templates, explicit states, keyboard behavior, and accessibility checks.

**Non-Goals:**

- Login, roles, SSO, project membership, per-user authorization, approval workflow, or template-management UI.
- Spring AI, LangChain4j, direct model APIs, RAG, vector storage, OCR, document parsing, or self-developed agent logic.
- Adding new KnowledgeEngineeringEngine core behavior, automatically ingesting generated Excel back into a knowledge base, or adding artifact retention cleanup. Surgical removal of the earlier testcase-JSON-only KEE changes is in scope and must preserve unrelated worktree changes.
- Embedding requirement screenshots or other images in Excel. Image handling is deferred to a separate change after a scoped KEE resource contract is confirmed.
- Claiming broad model quality from repeated full-model tests. The change uses deterministic replay plus one bounded real acceptance run.

## Decisions

### 1. Modular monolith with feature packages

Use a Spring Boot modular monolith and Vue SPA. Core feature modules expose use-case interfaces and depend on ports; MySQL, KnowledgeEngineeringEngine HTTP/SSE, filesystem, and Apache POI adapters depend inward.

Alternative considered: microservices or a separate queue service. Rejected for the first 15-user release because it adds distributed failure modes without proving a need. Module boundaries and persistent leases keep later worker extraction possible without building it now.

The initial deep modules and their interfaces are:

| Module | Interface callers learn | Complexity hidden by the implementation | Must not access |
|---|---|---|---|
| `GenerationWorkflow` | create, inspect, cancel, retry a task | state machine, batch creation, idempotency, cancellation checkpoints, validation/export ordering | HTTP/SSE DTOs, controller state, filesystem paths |
| `TaskExecutionQueue` | claim eligible work and report an attempt outcome | five-slot admission, MySQL claims/leases, recovery, retry classification, duplicate-success prevention | Vue/browser concepts, agent prompt construction |
| `KnowledgeAgentPort` | resolve an agent and invoke one isolated batch | credentials, headers, WebClient, sessions, SSE framing/terminal semantics, upstream error mapping | MySQL entities, Excel, Web DTOs |
| `ScopePolicy` | freeze allowed selections and validate evidence ownership | grant intersection, canonical coordinates, snapshot hashing, retry reuse, fail-closed explanations | model generation decisions, workbook formatting |
| `FewShotModule` | load one configured example bundle for a generation context | enabled Good/Bad whitelist validation, AUTO/NONE and preview text loading | formal evidence registration, example approval mutations |
| `MarkdownResultParser` | parse one completed batch into the two approved row types | exact headings/headers, escaped pipes, `<br>` line breaks, malformed-row rejection | retries, persistence, Excel formatting |
| `BatchResultAccumulator` | accept or replace one batch result idempotently | task/batch ordering, atomic replacement, duplicate prevention, partial progress | HTTP/SSE framing, workbook drawing APIs |
| `WorkbookExporter` | export one fully validated task result | worksheets, styles, source merging, formula safety, readback, hash and controlled storage | validation bypass, arbitrary browser paths, knowledge ingestion |

These are behavioral modules rather than one-class-per-row wrappers. Controllers know only application use-case interfaces. JPA entities and WebClient event DTOs remain inside adapters and never become domain parameters.

Ports are introduced only at real seams:

- KnowledgeEngineeringEngine is remote but owned: production uses the HTTP/SSE adapter and tests use a deterministic fake/WireMock adapter.
- MySQL persistence has a repository port because workflow tests need a deterministic adapter while concurrency and locking tests use the real MySQL 8 adapter.
- The local artifact filesystem is locally substitutable through a temporary directory, so it remains an internal seam of `WorkbookExporter`; an S3/MinIO port is not introduced until a second production adapter is actually required.
- Apache POI is an implementation detail of `WorkbookExporter`, not a domain-wide interface.

The interface of each module includes its invariants and error outcomes, not only Java method signatures. Tests exercise observable behavior through these interfaces and do not depend on private helper classes or storage layout.

### 2. Java 17, MySQL 8, and Flyway

Use the installed Java 17 baseline, Maven Wrapper, an application-owned MySQL 8 database, and Flyway versioned migrations. Never reuse the KnowledgeEngineeringEngine PostgreSQL database.

Alternative considered: Java 21 and PostgreSQL from the handoff. Rejected because the user selected MySQL 8 and the current approved runtime is Java 17. Persistence behavior will be tested against an actual MySQL 8 container when transaction or locking semantics matter.

### 3. Durable database queue with five worker slots

Persist tasks, batches, attempts, lease owner/expiry, cancellation requests, and terminal states. A fixed worker pool of five claims eligible work transactionally; expired non-terminal claims become recoverable after restart. Overflow remains in `QUEUED`.

Alternative considered: in-memory `@Async`, Spring Batch, or a message broker. In-memory execution cannot recover; Spring Batch and a broker are deferred until real scale demonstrates a need. MySQL claim SQL and transaction behavior must be proved with concurrency tests rather than assumed from an in-memory database.

There is no product-level total duration or feature limit, but feature discovery and generation are paged and batched. Every external call has a configured timeout and retry budget; total task duration emerges from durable progress rather than one unbounded request.

### 4. Explicit task and batch state machines

Tasks use `QUEUED -> AUDITING -> GENERATING -> VALIDATING -> COMPLETED`, with truthful `PARTIAL`, `FAILED`, and `CANCELLED` terminal branches. Batches persist claim, attempt, failure classification, and accepted result state. Cancellation is cooperative at safe checkpoints. Retry creates a new attempt only for eligible failed batches.

Idempotency keys include task identity, mode, normalized feature set, requirement-scope hash, example-selection hash, prompt version, and Schema version. A successful key cannot create a second successful batch result.

### 5. KnowledgeAgentPort hides the external protocol

The core depends on a `KnowledgeAgentPort` that discovers the configured agent, creates an isolated session, invokes a batch with an explicit requirement document whitelist and optional server-selected examples, and returns the completed Markdown text. Its adapter owns API Key headers, URI construction, WebClient, SSE framing, maximum event size, timeout, and error mapping.

HTTP success or clean EOF is insufficient. The adapter must see the explicit terminal-complete event; terminal error, malformed event, missing completion, or timeout produces no accepted result. Only after completion does the workflow parse the accumulated answer as the fixed Markdown contract.

### 6. RequirementScope and ExampleScope are separate trust domains

Task creation resolves user selections through server-authorized options and freezes an immutable requirement scope. The API credential grants are an outer limit; the task snapshot is the narrower request limit. Evidence is accepted only if it belongs to both.

Few-shot search uses an independent department example-library grant. `AUTO` is the default and uses the configured Good/Bad document whitelist; `NONE` is available only in advanced settings as a comparison mode. Java verifies that each selected document belongs to the frozen example library, is parsed and enabled in KnowledgeEngineeringEngine, and declares the configured Good/Bad type. Manual example selection, approval, and retirement lifecycle are outside this product. Example content may influence writing method but never supplies a requirement fact.

### 7. Fixed Markdown result contract and idempotent accumulation

Each generation answer contains exactly two level-2 headings and tables in order:

1. `需求与功能清单审查发现`: `序号 | 对象/功能点 | 问题分类 | 证据对照`.
2. `测试用例`: `用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容`.

The parser accepts Markdown table rows and `<br>` line breaks, rejects missing/renamed headers, column-count drift, raw JSON, fenced payloads, and non-table prose that would make row ownership ambiguous. An empty findings table is valid; a successful generation batch must contain at least one test-case row.

`ALL` uses one separate, deliberately smaller Markdown discovery contract: heading `功能点清单`, columns `序号 | 功能点`. Java validates positive unique sequences and nonblank names, creates server-owned feature identities, persists the frozen list, and then generates one feature per batch. The earlier prompt-only JSON cursor, `scope_echo`, evidence ledger, and `feature_id` replay protocol are removed. If the discovery response exceeds the configured SSE/event bound or is malformed, the task fails visibly; Java does not guess missing functions.

Accepted rows are written atomically under `(task_id, batch_id, row_kind, row_sequence)`. A retry replaces the failed batch's rows in one transaction and cannot append a second copy of an already accepted batch. Export reads all accepted batches ordered by batch sequence then row sequence. A task with both accepted and permanently failed batches remains `PARTIAL` and may expose an Excel built only from accepted rows.

### 8. Audit findings remain candidate-only

The first Markdown table is an agent-generated review finding, not an approved defect. The Web detail and Excel label it as an audit finding and provide no confirm/reject workflow in Phase 1.

### 9. Deterministic two-Sheet Apache POI export

`WorkbookExporter` accepts only persisted accepted Markdown rows. It writes exactly the two required worksheets in contract order, converts `<br>` to Excel line breaks, enables wrapping, prevents formula injection, applies bounded styles, reopens the file with Apache POI, validates workbook names and headers, hashes the bytes, and only then marks an opaque artifact ID ready. Re-export always regenerates the whole workbook from database rows; it never mutates an existing `.xlsx` in place.

Artifacts live under an application-controlled root and are not automatically deleted in Phase 1. Browser responses never expose filesystem paths. Future ingestion into `所属系统 -> 版本 -> 测试过程材料 -> 功能测试` is a separate adapter and change.

### 9.1 Replace the old protocol; do not layer compatibility

The Markdown path replaces the testcase JSON generation path. After callers move, delete `generation-result-1.0` Schema handling, `testcase-agent.response.v1` case-generation mapping, structured case/evidence/few-shot usage validators, and tests/configuration used only by that path. Keep only independently required task, scope, queue, SSE terminal, example loading, and feature-discovery behavior. The deletion test is mandatory: an obsolete class retained only because an obsolete test imports it is deleted together with that test.

KEE restoration uses patch-level provenance, not whole-file checkout. First identify hunks introduced for JSON key scanning, strict JSON finalization/pass-through, alias restoration, unique-JSON extraction, and testcase-protocol routing. Revert only confirmed testcase-specific hunks, then run the pre-existing natural-language scope guard, retrieval routing, evidence finalization, PageIndex/RAG degradation, and agent package tests. If a hunk also fixes a generic security defect, keep it and document why it is not testcase-only.

### 11. Current structural impact and implementation ownership

CodeGraph identifies the active path as `GenerationTaskController -> AuthorizedTaskScopeResolver -> GenerationWorkflow -> KnowledgeAgentPort/WebClientKnowledgeAgentAdapter -> GenerationTaskRepository -> WorkbookExporter`, with Vue callers in `HomeView`, `TaskListView`, and `TaskDetailView`. The change is divided into non-overlapping ownership areas:

- Markdown contract module: new pure row records and parsers; deletes generation/feature-discovery JSON Schema validators, mappers, response DTOs, and their fixtures after callers move.
- Task persistence/workflow: Flyway V8 row tables, background ALL discovery, atomic batch acceptance/replacement, ordered aggregate/detail queries, partial artifact regeneration.
- Export adapter: accepts accumulated audit/test-case rows and emits exactly two sheets; it does not depend on model DTOs.
- Web: consumes business-safe task DTOs, previews the same two row types, and removes technical sections/identifiers from common views.
- KEE restoration: separate sibling-repository patch with its own hunk inventory and focused tests; it does not share files with Java implementation.

Dependency direction is `web/controller -> task workflow -> Markdown/parser and repository ports`; the KEE adapter and POI exporter depend inward on small interfaces. The parser must not access MySQL, HTTP, scope configuration, or POI. The exporter must not parse Markdown or call KEE. The frontend must not receive scope UUIDs, filesystem paths, raw prompts, raw Markdown, or API credentials.

### 10. Vue PC workflow and design-system mapping

Use Vue 3 + Vite + TypeScript. The initial UI has a task form, shared list, task detail, accepted-result preview, and validated download action. It maps the PC guideline's source-confirmed semantic palette, typography, spacing, radii, and 24/12-column layout into project tokens instead of scattering values.

The task form follows the form template and presents two business choices: `生成全部测试用例` (default/recommended) and `指定功能生成`. The latter accepts a function name or description, never an internal feature ID. A single authorized scope is auto-selected and shown as a read-only business summary; multiple scopes expose labels only. AUTO examples are described as `自动参考优质示例（推荐）`; NONE is inside advanced settings. `补充说明` is optional. The primary action is `开始生成测试用例` with background-execution guidance. Submission prevents duplicates, preserves input on failure, and focuses an error summary. The list and detail pages use business wording, retain cancel/retry/download, and never expose KB/version/project/document UUIDs or JSON.

## Risks / Trade-offs

- **No login means shared destructive operations** → Keep the scope explicit, require confirmation for cancel/retry, provide no delete operation, and defer accountable approval until authentication exists.
- **No product feature/time cap can create very large jobs** → Use durable bounded batches, paging, per-call timeouts, five task slots, visible progress, and cancellation; never treat “unlimited” as unbounded memory or one model request.
- **Indefinite artifact retention can exhaust disk** → Record artifact size and storage health now; add cleanup only through a later explicit retention change.
- **External agent or example-library preparation may be delayed** → Develop against versioned mocks and WireMock first; mark live E2E pending until the delegated task returns non-secret IDs and contract evidence.
- **Markdown is less rigid than JSON** → Keep the table grammar deliberately small, reject ambiguous structure, persist raw completed Markdown for diagnosis, and test malformed headings, separators, escaped pipes, multiline cells, duplicate retries, and partial batches.
- **MySQL claim semantics can be implemented incorrectly** → Use actual MySQL 8 Testcontainers tests for concurrency, lease expiry, cancellation, and duplicate-success prevention.
- **All-generation evaluation can be costly or flaky** → Run deterministic failure matrices first, one real baseline, targeted repair only when attribution is clear, and one final acceptance run when needed.

## Migration Plan

1. Initialize the independent repository, OpenSpec change, backend/frontend skeleton, MySQL 8 development service, and project configuration templates without secrets.
2. Apply Flyway migrations to a new empty application database; no existing data migration is required.
3. Run the one-feature WireMock RED→GREEN slice and MySQL recovery tests.
4. Inject delegated KnowledgeEngineeringEngine agent/example-library identifiers through environment configuration and run one real feature smoke test.
5. Add and verify `ALL` mode, then run the bounded strategic-operations V1.0 admission-material acceptance.

Rollback for the new application is to stop its services and preserve its isolated MySQL database and artifact root for diagnosis. Rollback never modifies KnowledgeEngineeringEngine data or source code.

## Open Questions

- Exact API agent name/ID, example-library ID, external-subject header mode, and configured Good/Bad seed example IDs remain pending from task `019ff1ab-f48a-7cd2-a349-a1d66e27d31f`.
- The initial prompt version and fixed Markdown contract version will be locked after the delegated API agent contract is returned; implementation uses explicit placeholders until then.
- Quality thresholds beyond hard safety/structure gates require a later accountable gold-set owner. This MVP can report observed full-scope results but cannot declare subjective model quality established without that owner.
