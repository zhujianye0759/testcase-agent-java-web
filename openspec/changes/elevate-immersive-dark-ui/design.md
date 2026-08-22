## Context

The application ships a light enterprise workspace inside a navy shell (REQ-UIX-001 through REQ-UIX-008). The user's explicit product requirement now is a visibly cooler, more immersive interface. The frontend has no component library; every visual surface is plain CSS mapped through `tokens.css`, and Vue unit tests pin DOM structure, wording, states, and recovery behavior.

## Goals / Non-Goals

- Goals: one coherent dark mission-control visual system across shell, creation, list, and detail; stronger primary-action focus; richer but non-semantic ambience; dark-aware focus, form controls, and scrollbars; full regression and multi-viewport evidence.
- Non-Goals: no business workflow, wording, API, or scope-selection change; no dashboard, health indicator, or invented operational data; no new runtime dependency; no mobile or light-theme variant work.

## Decisions

### 1. Dark theme as an explicit user requirement

The v1.0.0 PC package intentionally does not define a dark theme, and the previous change kept a light workspace. The user's explicit requirement for this project overrides that earlier project choice (authority order). The source-confirmed light palette stays in `tokens.css` untouched and remains the semantic base for status hues; new dark roles are added as separate project-configurable tokens rather than by inverting the confirmed palette.

### 2. Semantic dark roles instead of scattered literals

New token families in `tokens.css`:

- Shell/aurora: `--color-shell-abyss`, `--color-shell-deep`, `--color-aurora-cyan/violet/blue` for the non-interactive background.
- Glass surfaces: `--color-glass-raised/muted/inset`, `--color-glass-border(-strong)` for panels, cards, inputs.
- Ink: `--color-ink-primary/secondary/tertiary/placeholder` for text on dark surfaces.
- Accents and gradients: `--color-accent-cyan/blue/violet`, deep stops, `--gradient-action-primary`, `--gradient-text-heading`, `--gradient-edge-cyan`.
- Status-on-dark: `--color-success/warning/error-ink`, `-glass`, `-edge` so chips and alerts stay readable and never color-only.
- Glow shadows: `--shadow-glow-action`, `--shadow-glow-cyan`; existing `--shadow-base/middle/high` are retuned to black-based parameters because the previous navy-tinted shadows are invisible on dark surfaces.

### 3. Ambience is decorative, layered, and cheap

The shell background is a static grid plus a blurred aurora layer (`.app-shell__ambient`) animated with slow `transform` drift. All decorative layers are `aria-hidden`, `pointer-events: none`, carry no status meaning, sit below content z-order, and become static under `prefers-reduced-motion`. No blur is applied to live content panels that contain long scrolling tables; blur is reserved for the shell header, hero note, and dialog.

### 4. Dark-aware interaction states

`:root` sets `color-scheme: dark` so native radios, checkboxes, select popups, and scrollbars render dark; `accent-color` tints checked controls. Focus uses a cyan outline plus a soft outer glow so keyboard focus stays visible on every dark surface. Loading regions shimmer with a transform-based sweep; in-progress status chips pulse their dot. All motion uses the existing `--motion-fast/standard/complex` tokens, is removed under reduced motion, and never encodes state by itself.

### 5. Primary action hierarchy

Exactly one gradient primary action per action region: the creation submit uses `--gradient-action-primary` (deep cyan→blue stops chosen so white label contrast stays >= 4.5:1) with a restrained glow. Other buttons remain single-color primary or glass secondary; disabled becomes a dimmed glass treatment instead of the light-blue disabled fill that was designed for light surfaces.

### 6. Tests before styles

`tokens.spec.ts` gains watched assertions for the new dark tokens and the global-style hooks (`color-scheme: dark`, `backdrop-filter`, `accent-color`, aurora keyframes, reduced-motion guard) before the CSS is written. Existing view specs must pass unchanged, proving the DOM/wording contract survived the reskin. Browser acceptance captures the three required viewports plus focus and reduced-motion evidence with the existing mocked-API Playwright pattern.

### 7. Structure carries the tech feel, not panel chrome

Review feedback rejected both the flat reskin ("只换了颜色") and the first structural pass ("格子太多"): the interface must read as one calm dark canvas with deliberate structure, not nested boxes. The structural language is therefore:

- **Unboxed sections**: top-level regions and form `fieldset`s carry no border, background, or corner ornaments. Sections separate through whitespace, a single hairline rule, and auto-numbered legends (`counter-reset: form-step` + `decimal-leading-zero` in cyan), so hierarchy comes from typography and rhythm instead of boxes. Boxes remain only where they mean something: selectable choices, the launch bar, and alerts.
- **Hero radar**: the creation hero carries one decorative radar (conic sweep, `aria-hidden`, stops under reduced motion) as the mission-control signature, replacing decoration-by-panel.
- **Two-column console form**: above the 1200px container threshold the creation form splits into a main column (mode, scope) and an execution rail (strategy, notes, launch actions) via a `display: contents` wrapper, so wide screens get a command-console layout without DOM order changes.
- **Sticky launch bar**: the launch actions sit in a `position: sticky` glass bar (`--layer-sticky-actions`) that stays reachable while the form scrolls; a scrolled viewport capture asserts it never escapes the viewport.
- **Custom controls with native semantics**: radios, checkboxes, and selects keep native elements and keyboard behavior but render through `appearance: none` dark faces (glowing radio dot, cyan check stroke, SVG chevron). The few-shot checkbox inside `details` shares the exact material-choice checkbox treatment so no control falls back to a generic full-width input face.
- **Pipeline stepper**: the detail stage flow renders as node dots on a connector hairline (complete = success-filled, current = pulsing cyan, pending = dim hollow), collapsing to a left rail under 760px.

### 8. Ambience is a sky, not a grid

Review feedback rejected the engineering-grid backdrop outright ("不喜欢格子"). The ambience is now a deep-sky metaphor: the shell background is a plain abyss gradient; `.app-shell::before` paints a two-scale starfield tile that twinkles through a slow opacity sweep; `.app-shell::after` is a meteor streak that crosses the upper sky for a short slice of a 16s cycle and stays invisible otherwise; heading bands replace their grid overlay with a single slow sheen sweep (`hero-sheen`). The radar, aurora, starfield, meteor, and sheen are all decorative, `pointer-events: none`, carry no status meaning, and stop under `prefers-reduced-motion` (the meteor also forces `opacity: 0`). New tokens `--color-star-bright/soft` and `--color-streak` keep the effect colors configurable.

### 9. Reference-grade polish (Linear / Aceternity patterns)

The "科技感" benchmark is top-tier developer products, not data-viz dashboards. Their transferable patterns, adapted to this codebase's CSS-only constraint:

- **Display-scale hero**: the creation title steps up to `--type-display-48` with a cyan drop-shadow glow; list/detail titles stay at 36px to preserve hierarchy.
- **Border beam**: the selected mode card gets a traveling 1px conic arc (`@property --beam-angle` + `beam-spin`, masked to the ring). The card's static gradient edge stays underneath, so browsers without `@property` degrade to the previous selected style.
- **Inner top glow**: cards (mode choices, detail summary, audit/case cards, sticky launch bar) add a faint radial top light plus a 1px inset highlight, the glass-shelf look used by Linear/Vercel panels.
- **Richer aurora**: a counter-drifting magenta/blue blob layer (`.app-shell__ambient::before`, `--color-aurora-magenta`) breaks the single-hue monotony; the creation band gains two static light shafts.
- **Living primary action**: the gradient submit/download button slowly shifts its gradient position (`action-flow`) and keeps the existing brightness/glow hover.
- All five are transform/opacity/gradient-position animations, stop under reduced motion, and carry no state meaning.

### 10. ui-ux-pro-max reasoning: AI-native violet brand, cyan interaction

The project-installed ui-ux-pro-max skill classifies this product as an **AI platform / developer tool** and recommends: AI-Native UI style, AI purple (`#7C3AED` family) as brand primary with cyan reserved for interactions, OLED-dark support, and staggered list reveals as the signature motion. Applied as a re-tone of the existing system rather than a rewrite:

- Brand moments move to violet: hero radar, form step counters, selected mode-card edge/inset/beam (`--gradient-edge-violet`, `--color-accent-violet-deep: #6D28D9`), and the primary action gradient (violet→blue).
- Interaction stays cyan: focus rings, links, info bars, chips, status pulses — matching the palette's "AI purple + cyan interactions" note.
- The heading gradient becomes white → violet mist → cyan mist; the aurora violet/magenta layers are strengthened so the sky carries the brand hue.
- Task list rows enter with a 380ms staggered rise (`row-enter` + per-row delays), the skill's recommended Stagger List pattern, clamped after the fifth row.
- Google Fonts import is deliberately skipped (offline internal deployment); the system font stack stays.

## Risks / Trade-offs

- **Backdrop blur performance on low-end PCs** → Blur only the header, hero note, and dialog; main panels use translucent solid fills.
- **Dark-theme contrast regressions** → Ink/status-on-dark tokens are chosen at >= 4.5:1 against their glass backgrounds; browser evidence at three viewports verifies.
- **Decorative motion distracting from tasks** → Ambience is slow, low-alpha, non-interactive, and fully removed under reduced motion; status motion is limited to a small dot pulse.
- **Scope creep into templates** → The reskin is CSS/token-only except scoped style blocks; any template change is limited to aria-hidden decorative nodes, and the existing view specs act as the guardrail.

## Migration Plan

Purely additive tokens plus a stylesheet rewrite; no data, API, or template-contract migration. Rollback is reverting the two stylesheets and token additions.

## Open Questions

None blocking. Final glow/alpha tuning is accepted through rendered browser evidence and recorded in `docs/ui-decisions.md`.
