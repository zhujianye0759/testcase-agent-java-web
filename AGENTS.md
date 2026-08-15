# AGENTS.md — Test Case Agent Java Web

## Project purpose

This repository contains the new Java Web application for shared test-case generation. It is an orchestration product, not a new AI or RAG platform.

The application calls `D:\workspace\KnowledgeEngineeringEngine` through its supported HTTP/SSE APIs. Do not copy the old Python prototype from `D:\workspace\testCaseAgent`, and do not modify either sibling repository unless the user explicitly authorizes a separate, bounded change.

## Current product boundary

- Phase 1 has no login, roles, SSO, membership, or permission-management UI.
- Internal users may share task viewing, cancellation, retry, and Excel download operations.
- Knowledge-base, system, version, project, material-type, document, evidence, and example scopes remain mandatory security boundaries.
- Requirement issues remain candidate findings in Phase 1. Do not present them as formally approved design-center defects.
- The Java application may only consume `APPROVED` and non-retired Good/Bad examples. Example approval and requirement-issue approval are deferred to a later authenticated phase.
- The existing KnowledgeEngineeringEngine agent is evaluated first. A self-developed AI agent requires a later, evidence-backed change.

## Technology baseline

- Backend: Java 17, Spring Boot 3.x, Maven Wrapper.
- Persistence: an application-owned MySQL 8 database with Flyway migrations. Never reuse the KnowledgeEngineeringEngine database.
- Frontend: Vue 3, Vite, TypeScript.
- Excel: Apache POI; model output must never be treated as an Excel binary.
- Tests: JUnit 5 plus focused contract/integration tests; use Testcontainers where an actual MySQL boundary must be proved.
- Do not add Spring AI, LangChain4j, a model SDK, vector database, RAG, OCR, or document parsers.

## Architecture and dependency direction

Use a modular monolith and package by feature. Core workflow code depends on ports; HTTP/SSE, MySQL, file storage, and Excel are adapters that depend on the core.

Keep these responsibilities separate:

- task: persistent task, batch, lease, cancellation, retry, recovery, and state transitions;
- knowledgeagent: KnowledgeEngineeringEngine API/SSE adapter;
- scope: immutable requirement/example scope snapshots and evidence ownership checks;
- featureaudit and requirementquality: feature discovery and candidate requirement issues;
- fewshot: approved example lookup, selection, and version snapshot;
- testcase and validation: structured cases, steps, JSON Schema, business invariants, and fail-closed evidence checks;
- export: deterministic Excel generation, source deduplication, hashing, and formula-injection prevention;
- web: REST DTOs and controllers; controllers must not call repositories or raw HTTP clients directly.

`RequirementScope` is the only source of formal requirement evidence. `ExampleScope` may influence method and structure but must never support a business fact, requirement issue, or evidence citation.

## Capacity and reliability

- Support 15 expected online users.
- Run at most 5 generation tasks concurrently; additional tasks remain durably queued.
- There is no product-level feature-count, total-duration, or retention limit in Phase 1. This does not permit unbounded in-memory work: process bounded batches, page large reads, apply per-call timeouts, and use bounded retries.
- Persist tasks, batches, leases/claims, attempts, terminal outcomes, scope hashes, prompt/Schema versions, selected examples, and artifact metadata in MySQL.
- A clean HTTP EOF is not a successful SSE terminal state. Require the explicit complete terminal contract; terminal errors, truncation, timeout, and missing completion fail closed.
- Partial success must remain `PARTIAL`, never `COMPLETED`.
- Do not automatically delete retained Excel artifacts in Phase 1. Future ingestion into `所属系统 -> 版本 -> 测试过程材料 -> 功能测试` is a separate change.

## PC frontend rules

Apply the `pc-ui-design-guidelines` v1.0.0 skill for frontend design, implementation, and review.

- Map its semantic tokens into this project rather than scattering literal values.
- Use the closest list, form, detail, and empty-state templates.
- Distinguish loading, ready, empty, no-results, error, forbidden, and not-found states.
- Prevent duplicate actions, retain useful context after failure, handle stale async responses, and support keyboard operation and visible focus.
- Record project-configurable values instead of measuring or inventing screenshot values.
- Verify at the 1440x820 design-check canvas and relevant narrower/wider PC content widths; do not treat that canvas as the only viewport.

## SDD, TDD, and traceability

- OpenSpec artifacts are the requirements and task source of truth.
- Every production change starts with a requirement ID defined in the active OpenSpec change.
- Use separate requirement IDs for separate operations. Do not combine create, query, cancel, retry, and download into one requirement.
- Mark Java production code and tests with `[Req-ID]: REQ-...` in JavaDoc or test descriptions. Mark Vue templates with `<!-- [Req-ID]: REQ-... -->`.
- Follow RED -> GREEN -> refactor. No production behavior without a watched-failure test when practical.
- Keep `openspec/changes/<change>/tasks.md` as the only implementation task list.

## Coding and verification discipline

- Read before writing, keep changes surgical, and do not introduce speculative abstractions.
- Public classes/interfaces/methods need useful JavaDoc. Explain why around SSE terminal handling, idempotency, scope snapshots, evidence validation, queue recovery, and Excel safety; avoid line-by-line narration.
- Separate build/static checks from functional regression. Run change-focused tests first.
- Do not claim runnable, complete, or secure behavior without the relevant test/build/runtime evidence.
- Do not commit, push, deploy, archive a Comet change, or initialize CodeGraph unless the user explicitly asks or approves the required gate.
