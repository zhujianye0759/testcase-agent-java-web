## Why

The generation page currently receives one server-configured scope option, so users cannot choose another eligible knowledge base, system version, or material type without changing application configuration and restarting the backend. The product now needs a safe, business-readable scope catalog discovered from KEE while preserving server-side authorization, document whitelists, and frozen task evidence boundaries.

## What Changes

- Discover eligible requirement knowledge bases and their system/version/material hierarchy through supported KEE read APIs using the server-side API key.
- Present a cascading business-label selector for knowledge base, system, version, and one or more admission-material types; never expose credentials or raw KB/version/document identifiers to the browser.
- Resolve the browser's opaque selection token against a fresh server-authorized catalog and freeze the matching ready document whitelist when a task is created.
- Keep the configured generation agent and independent Good/Bad example scope server-owned and unchanged by user selection.
- Add explicit loading, empty, local error, stale-response, keyboard, and retry behavior for each asynchronous selector level.
- Replace the single fixed requirement-scope option as the default creation path while retaining fail-closed rejection for missing, stale, unauthorized, empty, disabled, or unparsed selections.
- Treat each eligible requirement knowledge base as the tester-facing business-system choice, and remove the redundant separate system selector from the page while retaining its internal scope coordinate.

## Capabilities

### New Capabilities

- `dynamic-scope-catalog`: Server-side discovery, normalization, opaque selection, and fail-closed freezing of KEE-backed requirement scope choices.

### Modified Capabilities

- `generation-web-workflow`: Replace the single material-scope summary with a cascading business-readable scope selection workflow.
- `scoped-generation-contract`: Resolve and freeze each new task from a current authorized catalog selection instead of a statically configured requirement scope.
- `knowledge-agent-integration`: Add supported read-only KEE catalog calls alongside the existing agent, preview, session, and SSE calls.

## Impact

- Java backend: KEE catalog port/adapter DTOs, catalog service, task option/create APIs, dynamic scope resolver, configuration, and focused contract tests.
- Vue frontend: task API DTOs, creation-page cascading selector, accessibility/state handling, and unit/browser tests.
- KEE: read-only use of existing knowledge-base, scope-container, system-version, content-type, and knowledge-list APIs; no KEE source, database, prompt, agent, or deployment change.
- Security: the API key and internal coordinates remain server-only; the browser receives business labels plus an opaque server-derived selection token.
