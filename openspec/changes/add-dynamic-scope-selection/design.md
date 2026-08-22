## Context

The current Java application maps one browser-safe `scopeOptionId` to a fully static `TaskScopeOption`. This protects internal coordinates but makes the deployed system/version/material range effectively fixed. KEE already provides API-key-protected read endpoints for lightweight knowledge-base selection, scope containers, system versions, and paged knowledge documents. The Java service can compose those endpoints into a safe catalog without changing KEE.

Phase 1 still has no user login or per-user identity. “Authorized” therefore means readable by the configured server-side KEE API key and eligible under the Java product rules below. The browser must not receive the key, raw knowledge-base/version/document UUIDs, or a way to widen a server-resolved document set.

## Goals / Non-Goals

**Goals:**

- Let a tester choose an eligible knowledge base, system, active version, and one or more ready admission-material types using Chinese business labels.
- Discover choices from current KEE state and freeze a strict `RequirementScope` with the exact matching document whitelist at task creation.
- Keep the generation agent and independent Good/Bad example scope server-configured.
- Fail closed for stale, empty, disabled, unparsed, unauthorized, or mixed-coordinate selections.
- Preserve the existing task, batch, retry, Markdown, evidence, and two-sheet Excel contracts.

**Non-Goals:**

- KEE source, database, route, prompt, agent, or container changes.
- Login, per-user KEE identity delegation, permission-management UI, arbitrary document checkboxes, project selection, or displaying internal UUIDs.
- Selecting department-public example libraries as requirement sources, non-admission categories, FAQ knowledge bases, or unready documents.
- Persistent distributed catalog caching; Phase 1 runs one Java application instance.

## Decisions

### 1. Compose only supported read APIs in a separate catalog adapter

Add a `KnowledgeScopeCatalogPort` and a WebClient adapter using the existing server API base URL, API key, timeout, and dependencies. It will use:

- `GET /knowledge-bases/selector-options` with cursor pagination;
- `GET /knowledge-bases/{id}/scope-container`;
- `GET /knowledge-bases/{id}/system-versions`;
- `GET /knowledge-bases/{id}/knowledge?page=&page_size=`.

The adapter ignores unknown JSON properties, requires successful envelopes, follows pagination, and maps external failures to a catalog-specific exception. It will not call KEE management or write routes.

Alternative considered: add a new KEE aggregate endpoint. Rejected because the existing read contracts are sufficient and the user requires impact analysis before any KEE code change.

### 2. Build a cached server snapshot and expose only opaque hierarchy keys

`DynamicScopeCatalogService` will normalize ready KEE data into a single immutable snapshot. Eligible leaves require:

- document knowledge base and `container_type=system`;
- active version matching the container system;
- `content_category=admission_material`;
- nonblank material type key/name;
- `parse_status=completed` and `enable_status=enabled`;
- document scope matching the selected system and version.

Opaque keys are SHA-256-derived from canonical internal coordinates and the sorted document whitelist. The browser receives only opaque keys, business labels, and document counts. A short project-configurable TTL prevents every page load from fanning out to KEE; explicit reload invalidates the cache. A failed refresh keeps no newly built partial snapshot.

Alternative considered: return raw KEE identifiers and let the browser compose the selection. Rejected because it weakens the existing fail-closed boundary and exposes implementation coordinates.

### 3. Revalidate the selected leaves before freezing a task

Task creation sends one or more opaque material-leaf IDs. The service verifies they belong to one knowledge base, system, version, and material category, refreshes that selected knowledge base from KEE, recomputes current opaque IDs, and requires exact matches. It then unions and sorts the selected type keys and ready document IDs into the existing immutable `RequirementScope`.

If documents changed, a type disappeared, or the token is unknown, task creation returns an actionable stale-scope error and does not call the agent. Existing tasks and retries continue using their persisted frozen snapshot.

Alternative considered: trust the cached token until TTL expiry. Rejected because a document may be disabled or moved between catalog load and task submission.

### 4. Replace the fixed option model while preserving static agent/example ownership

Remove the fixed requirement-coordinate fields and the old `AuthorizedTaskScopeResolver`/`TaskScopeOption` mapping once their tests are replaced. Add server configuration for the generation agent, example knowledge base, Good/Bad example whitelist, catalog TTL, and page size. No requirement KB/system/version/document environment variables remain necessary.

The existing `ExampleScope` validation and independence rule remain unchanged. The dynamically chosen requirement knowledge base must differ from the configured example knowledge base.

### 5. Use one catalog response and local cascading controls

`GET /api/task-options` returns the immutable nested catalog. The Vue page treats each eligible requirement knowledge base as one tester-facing business system: it keeps three visible business layers, `业务系统` (the knowledge-base label), version, and material types. It does not render a second system selector. The backend retains the system coordinate that belongs to the selected knowledge base for scope validation and freezing. A single available value auto-selects; multiple values use native select/checkbox controls. Changing an upper level clears invalid lower selections. Loading, empty, and refresh failure affect only the material-range region; form input remains intact. Every request generation ignores stale responses.

The create payload changes from one `scopeOptionId` to `scopeSelectionIds`. This is an internal same-release API change; backend and frontend are deployed together.

## Risks / Trade-offs

- **Catalog fan-out can load KEE** → Use the lightweight selector endpoint, cursor/document pagination, immutable TTL cache, bounded per-call timeout, and refresh only on explicit user action or expiry.
- **A partial KEE outage could produce an incomplete catalog** → Build snapshots atomically and fail the refresh rather than publishing partial authorization state.
- **A document changes after page load** → Rebuild the selected KB catalog and compare opaque leaf IDs before task persistence.
- **Multiple Java instances would have independent caches** → Tokens are deterministic and revalidated from KEE, so correctness remains; cache efficiency is instance-local until a later distributed-cache requirement.
- **Phase 1 API-key scope is broader than a future user identity** → Label the UI as server-authorized material and defer per-user grants until authentication exists.
- **Very large document sets increase task snapshots** → Page reads and group incrementally; persist the exact whitelist as required rather than silently truncating it.

## Migration Plan

1. Add RED contract/domain tests for KEE catalog discovery, normalization, opaque output, stale selection, and mixed-coordinate rejection.
2. Implement the catalog port, adapter, service, dynamic resolver, and safe REST DTOs while retaining the current generation workflow.
3. Replace frontend task-option/create DTOs and implement the cascading selector with state/accessibility tests.
4. Remove fixed requirement-scope configuration and obsolete resolver classes/tests only after replacement coverage is green.
5. Run full backend/frontend/OpenSpec/browser gates, then restart the Java backend and verify live KEE catalog selection plus one generated Excel.
6. Roll back by reverting the Java commit and restoring the prior environment variables; no KEE rollback is required because KEE is read-only in this change.

## Open Questions

None blocking. Catalog TTL and page size remain project-configurable operational values and will be documented rather than inferred from UI examples.
