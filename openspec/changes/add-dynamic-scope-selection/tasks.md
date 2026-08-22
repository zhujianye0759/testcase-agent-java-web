## 1. KEE Catalog Contracts

- [x] 1.1 Add RED adapter tests for paged knowledge-base/document discovery, system containers, active versions, ready admission-material filtering, unknown fields, and failed envelopes under REQ-KAG-009 and REQ-CAT-001/003.
- [x] 1.2 Implement the read-only `KnowledgeScopeCatalogPort` and WebClient KEE adapter without changing existing generation/session/SSE behavior.

## 2. Dynamic Scope Resolution

- [x] 2.1 Add RED service tests for business hierarchy normalization, opaque identifiers, example-library exclusion, TTL cache/refresh, atomic failure, and no raw KEE-coordinate leakage under REQ-CAT-001 through REQ-CAT-005.
- [x] 2.2 Implement immutable catalog snapshots, deterministic opaque keys, paged grouping, and project-configurable catalog settings.
- [x] 2.3 Add RED task-resolution tests for valid multi-type union, raw/unknown/stale tokens, mixed coordinates, removed documents, and optional project normalization under REQ-SCP-001/004.
- [x] 2.4 Replace the fixed authorized-option resolver with selected-KB live revalidation and exact `RequirementScope` freezing while keeping agent/example configuration server-owned.
- [x] 2.5 Remove obsolete fixed requirement-scope configuration, classes, and tests after replacement coverage is green.

## 3. Business-Friendly Cascading UI

- [x] 3.1 Add RED frontend API/view tests for the new safe catalog DTO, knowledge-base/system/version/material cascading choices, auto-selection, upper-level reset, multi-type submission, loading/empty/error/reload, stale-response protection, keyboard focus, and input retention under REQ-WEB-001/009.
- [x] 3.2 Implement the cascading material-range region using existing semantic tokens and PC form/select/checkbox rules without exposing raw IDs or changing unrelated task controls.
- [x] 3.3 Update UI decisions and browser acceptance for 1024×768, 1440×820, and 1920×1080 including single/multiple choices, keyboard, reduced motion, and local failure recovery.

## 4. Verification and Live Handoff

- [x] 4.1 Pass focused and full backend tests, frontend unit/type/lint/build gates, strict OpenSpec validation, and secret scans.
- [x] 4.2 Restart only the Java Web backend and verify the live KEE catalog exposes business labels without UUIDs, accepts a selected strategic-operations scope, freezes the current ready document whitelist, and completes a real two-sheet Excel generation.
- [x] 4.3 Complete main-thread diff/impact review, confirm no KEE source or runtime mutation, inspect rendered evidence, and create the repository commit.
- [x] 4.4 Add RED frontend coverage and simplify the material-range form so the eligible requirement knowledge base is the single tester-facing `业务系统` selector, remove the redundant `知识库` and `系统` fields, preserve version/material reset and opaque selection submission, then perform browser acceptance at 1024×768, 1440×820, and 1920×1080. `[Req-ID]: REQ-WEB-010`
