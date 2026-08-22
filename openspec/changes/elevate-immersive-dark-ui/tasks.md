## 1. Requirements and Regression

- [x] 1.1 Extend `frontend/src/styles/tokens.spec.ts` with watched-failure assertions for the immersive dark tokens and global-style hooks of REQ-UIX-009, then run `cd frontend && npx vitest run src/styles/tokens.spec.ts` to watch the failure. `[Req-ID]: REQ-UIX-009`
- [x] 1.2 Add the dark semantic token families to `frontend/src/styles/tokens.css` (shell, aurora, glass, ink, accent/gradient, status-on-dark, glow) and retune the project-configurable shadow/tech tokens for dark surfaces, keeping every source-confirmed value unchanged. `[Req-ID]: REQ-UIX-009`

## 2. Immersive Dark Implementation

- [x] 2.1 Rewrite `frontend/src/styles/global.css` as the dark mission-control system (shell aurora, glass panels, gradient primary action, dark-aware inputs/focus/scrollbars, shimmering loading, pulsing in-progress status dots, reduced-motion removal) without renaming or removing any selector used by the views or tests. `[Req-ID]: REQ-UIX-009`
- [x] 2.2 Refresh the scoped stage-flow and detail styles in `frontend/src/views/TaskDetailView.vue` for the dark glass surfaces without template structure changes. `[Req-ID]: REQ-UIX-009`

## 3. Verification and Evidence

- [x] 3.1 Pass `npx vitest run`, `npm run typecheck`, `npm run lint`, and `npm run build` in `frontend/`.
- [x] 3.2 Add `frontend/scripts/dark_ui_acceptance.py` using the existing mocked-API Playwright pattern and capture 1024×768, 1440×820, and 1920×1080 evidence for creation, list, and detail, including keyboard-focus and reduced-motion captures. `[Req-ID]: REQ-UIX-009`
- [x] 3.3 Record the dark-theme token decisions in `docs/ui-decisions.md`, run `openspec validate elevate-immersive-dark-ui --strict`, and complete main-thread visual review of the captured evidence.

## 4. Structural Layout Pass

- [x] 4.1 Extend `tokens.spec.ts` and `HomeView.spec.ts` with watched-failure assertions for the structural anchors (hero radar presence/`aria-hidden`, `@keyframes radar-sweep`, step counters, sticky actions, custom controls), then run the specs to watch the failures. `[Req-ID]: REQ-UIX-009`
- [x] 4.2 Rebuild the creation layout in `frontend/src/styles/global.css` and `frontend/src/views/HomeView.vue`: unboxed numbered sections, decorative hero radar, two-column console form with execution rail above the 1200px container threshold, sticky launch bar (`--layer-sticky-actions`), and `appearance: none` dark radios/checkboxes/selects that share one control treatment. `[Req-ID]: REQ-UIX-009`
- [x] 4.3 Convert the detail stage flow in `frontend/src/views/TaskDetailView.vue` into a pipeline node stepper (dot nodes, connector hairline, state-filled dots, vertical rail under 760px) without template contract changes. `[Req-ID]: REQ-UIX-009`
- [x] 4.4 Remove the nested-panel chrome (fieldset borders/backgrounds, HUD corner ticks) after review feedback, and fix the `details` few-shot checkbox so it uses the shared custom control face instead of the generic full-width input rule. `[Req-ID]: REQ-UIX-009`
- [x] 4.5 Re-run `npx vitest run`, `npm run typecheck`, `npm run lint`, `npm run build`, extend `frontend/scripts/dark_ui_acceptance.py` with radar, reduced-motion sweep, and sticky-in-viewport assertions, re-capture all viewport evidence, and complete main-thread visual review including an expanded advanced-settings capture. `[Req-ID]: REQ-UIX-009`

## 5. Sky Ambience Pass

- [x] 5.1 Extend `tokens.spec.ts` with watched-failure assertions for the sky ambience (grid removal, `@keyframes star-twinkle`/`starfall`/`hero-sheen`, star tokens), then run the spec to watch the failure. `[Req-ID]: REQ-UIX-009`
- [x] 5.2 Remove the engineering-grid backdrop from `.app-shell` and the heading-band grid overlays in `frontend/src/styles/global.css`; add the twinkling two-scale starfield (`.app-shell::before`), the periodic meteor streak (`.app-shell::after`), and the heading sheen sweep, all static under `prefers-reduced-motion`. `[Req-ID]: REQ-UIX-009`
- [x] 5.3 Add `--color-star-bright/soft` and `--color-streak` to `frontend/src/styles/tokens.css`, re-run the full frontend verification and browser acceptance, and record the sky-ambience decision in `docs/ui-decisions.md`. `[Req-ID]: REQ-UIX-009`

## 6. Reference-Grade Polish Pass

- [x] 6.1 Extend `tokens.spec.ts` with watched-failure assertions for the polish anchors (`@property --beam-angle`, `@keyframes beam-spin`, display-48 hero type, `--color-aurora-magenta`), then run the spec to watch the failure. `[Req-ID]: REQ-UIX-009`
- [x] 6.2 Implement the Linear/Aceternity-grade treatments in `frontend/src/styles/global.css`: traveling border beam on the selected mode card, display-48 glowing hero title, inner top glow on cards and the sticky launch bar, counter-drifting magenta aurora layer, hero light shafts, and the flowing primary-action gradient, all with reduced-motion guards. `[Req-ID]: REQ-UIX-009`
- [x] 6.3 Re-run `npx vitest run`, `npm run lint`, `npm run build`, re-capture the browser acceptance evidence (including a zoomed selected-card capture proving the beam animates), and record the polish decisions in `docs/ui-decisions.md`. `[Req-ID]: REQ-UIX-009`

## 7. AI-Native Re-tone (ui-ux-pro-max)

- [x] 7.1 Install the ui-ux-pro-max skill into the project (`.claude/skills/ui-ux-pro-max`) and generate its design-system recommendation for the product's AI-platform/developer-tool positioning. `[Req-ID]: REQ-UIX-009`
- [x] 7.2 Extend `tokens.spec.ts` with watched-failure assertions for the re-tone anchors (violet-deep token, `--gradient-edge-violet`, violet action gradient, `row-enter` stagger), then run the spec to watch the failure. `[Req-ID]: REQ-UIX-009`
- [x] 7.3 Re-tone `frontend/src/styles/tokens.css` and `frontend/src/styles/global.css` to the AI-native violet-brand/cyan-interaction story (radar, step counters, selected card edge and beam, action gradient, heading gradient, aurora balance) and add the staggered task-row reveal, without touching backend code or template contracts. `[Req-ID]: REQ-UIX-009`
- [x] 7.4 Re-run `npx vitest run`, `npm run lint`, `npm run build`, and the browser acceptance suite at three viewports with main-thread visual review; record the re-tone decision in `docs/ui-decisions.md`. `[Req-ID]: REQ-UIX-009`
