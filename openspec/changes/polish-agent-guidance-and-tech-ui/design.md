## Context

The MVP already implements strict server-side scope selection, Markdown generation, durable task operations, and deterministic two-sheet Excel export. Its KEE prompt now follows the simplified Markdown contract, but observed output quality is uneven in step/result pairing, generic negative coverage, observable wording, executable test data, view-operation granularity, and evidence-bounded audit language. The frontend is usable but visually generic and does not yet express a cohesive testing-intelligence product experience.

This change crosses an external runtime configuration boundary and the Vue presentation layer. It must preserve the established Java backend contracts and must not revive the superseded JSON model contract, add Java model-output validation that the user deferred, or change KEE core source.

## Goals / Non-Goals

**Goals:**

- Improve the KEE agent's Markdown quality through prompt and method-example guidance only.
- Upgrade the complete PC workflow to a polished, technology-oriented visual system with strong hierarchy and accessible state handling.
- Preserve current business wording, APIs, scope security, durable task behavior, Markdown parsing, and Excel output.
- Provide reproducible tests and three-viewport browser evidence for main-thread acceptance.

**Non-Goals:**

- KEE Go source, retrieval, agent engine, or API protocol changes.
- New Java validators for semantic model quality, new retries for model wording, or a new output format.
- Login, roles, SSO, approval workflows, example lifecycle management, images in Excel, mobile UI, or dark theme.
- New frontend UI libraries, icon packages, chart packages, or decorative image-generation dependencies.

## Decisions

### 1. Treat prompt and examples as configuration, not application code

The existing KEE API agent prompt will be updated through the supported agent configuration API or UI. Good/Bad documents will be updated or replaced through supported knowledge document operations and revalidated for whitelist membership, example-library ownership, completed parsing, enabled status, and declared quality kind.

Alternative considered: enforce the seven rules in KEE Go or Java validators. Rejected because the user explicitly deferred validation and these improvements are authoring guidance, not a new wire or security contract.

### 2. Preserve fixed Markdown and two-sheet Excel contracts

The agent prompt will continue to return exactly the two required Markdown tables. The Java parser, persisted rows, and exporter remain unchanged unless an actual regression is found during verification.

Alternative considered: return structured JSON. Rejected because the simplified Markdown contract is the approved baseline and model JSON variability was the reason for the simplification.

### 3. Use a restrained “intelligence operations” visual direction

The visual direction will use a light PC enterprise base, deep navy/blue emphasis, cyan as a controlled intelligence accent, subtle grid/glow decoration, strong information typography, and compact status instrumentation. Decorative effects may support hierarchy but must not reduce contrast, obscure content, or turn the product into a dark-theme dashboard.

All exact values are project-configurable unless already source-confirmed by PC UI Guidelines. Existing semantic tokens will be extended only where the same role is reused across the shell and pages.

Alternative considered: install a component framework or adopt a full dark theme. Rejected to avoid dependency expansion, broad rewrites, and conflict with the PC UI v1.0.0 light semantic foundation.

### 4. Keep the application shell and page responsibilities shallow

`App.vue` owns identity and primary navigation. `HomeView`, `TaskListView`, and `TaskDetailView` own their existing business flows. Shared CSS provides semantic layout/component classes; no speculative component abstraction will be introduced unless the same behavior is already repeated and testable.

### 5. Test behavior before screenshot polish

Vue unit tests remain the contract for wording, states, requests, recovery, and keyboard semantics. The browser script will then capture representative pages at 1024×768, 1440×820, and 1920×1080 for main-thread visual review. Reduced-motion and visible-focus checks are mandatory; screenshots alone are not acceptance.

## Risks / Trade-offs

- **Prompt-only rules may still be violated by the model** → Verify with focused live generation and record any residual variability without silently adding deferred validators.
- **Updating example files can create new document IDs** → Update only server-side example whitelist configuration after parse/index readiness is proven; never broaden to the whole example library.
- **Technology styling can become decorative clutter** → Keep one primary action per region, use semantic tokens, constrain glow/motion to nonessential layers, and verify zoom/reflow and reduced motion.
- **Large CSS edits can regress existing states** → Require current unit tests to stay green, add state-focused tests before styling, and review diffs page by page.
- **External KEE configuration rollback is not a Git reset** → Capture the previous prompt and document identifiers securely, compare non-prompt agent configuration hashes, and retain an explicit restore path without writing secrets to the repository or logs.

## Migration Plan

1. Preserve the Git baseline commit and capture the current non-secret KEE prompt/config fingerprints.
2. Update and verify the KEE prompt rules; update example documents only through supported APIs/UI and verify parse/enable/quality conditions.
3. Implement frontend tests and visual changes without backend-contract changes.
4. Run frontend unit, type, lint, build, backend regression, OpenSpec validation, and browser acceptance.
5. Run one bounded live generation to inspect prompt adherence and AUTO example use without treating prompt variability as a scope failure.
6. If KEE configuration verification fails, restore the previous prompt and examples; if UI gates fail, revert only the UI change commit while retaining the MVP baseline.

## Open Questions

None blocking. Exact project-configurable visual token values will be documented in `docs/ui-decisions.md` and accepted through rendered browser evidence rather than inferred from screenshots.

## Verification Record

On 2026-08-15, after the refreshed KEE image was healthy, a live `ALL` task discovered and froze 11 feature points, completed all 11 generation batches, accumulated 11 audit rows and 22 test-case rows, and exported the required two-sheet workbook. The workbook passed structural validation with no formula errors, placeholder leakage, or clipped `###` cells.

The strengthened prompt consistently produced the fixed two-table Markdown shape and two cases per feature. Residual model variability remains in semantic wording: isolated rows still use alternatives such as “或”, conditional instructions such as “若界面无此类控件则跳过”, or generic network/permission expectations not stated by formal evidence. Per the approved boundary, this is recorded as prompt variability and does not introduce a Java semantic validator, KEE-core validator, or output-protocol change.
