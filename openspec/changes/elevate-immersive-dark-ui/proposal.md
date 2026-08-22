## Why

User feedback on the shipped control-deck interface is that the pages still read as plain and dated. The product is an intelligent test-design platform, and its PC interface should feel like one: immersive, precise, and alive, without sacrificing the established readability, accessibility, and business workflow guarantees. This change elevates the visual system into a coherent dark mission-control experience while touching no business behavior.

## What Changes

- Re-express the application shell and all three workflow pages as an immersive dark "mission control" experience: deep-space shell background, layered aurora ambience, frosted-glass work surfaces, gradient primary action, and glowing state accents, all mapped through semantic project tokens.
- Upgrade interaction feedback: visible dark-aware focus glow, hover lift and border brightening on actionable surfaces, shimmering loading regions, and a pulsing indicator for in-progress task statuses; status meaning never relies on color or motion alone.
- Render native form controls, scrollbars, selection, and dialog backdrops consistently with the dark theme via `color-scheme` and semantic tokens.
- Extend `tokens.css` with dark-theme semantic roles (shell, glass, ink, aurora, accent, glow) as recorded project-configurable values; the source-confirmed PC UI palette, spacing, typography, radius, and shell dimensions remain unchanged.
- Preserve every business behavior, DOM contract, accessible name, Chinese wording, REST contract, and page state machine; all decorative layers stay non-semantic and non-interactive, and reduced-motion removes ambient and decorative movement.
- Extend token/style regression tests and run browser visual acceptance at 1024×768, 1440×820, and 1920×1080 including keyboard focus and reduced-motion evidence.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `technology-web-experience`: adds the immersive dark visual requirement REQ-UIX-009 on top of the existing shell, page, state, and accessibility requirements.

## Impact

- Java Web repository frontend only: `tokens.css`, `global.css`, scoped view styles, token/style regression tests, a Playwright visual-acceptance script, and `docs/ui-decisions.md`.
- No Vue template structure, wording, request payload, API contract, backend code, database migration, or new frontend dependency.
- Supersedes the earlier "light enterprise workspace" presentation decision for these pages; the rejection of fake dashboards, health indicators, and invented operational data still stands.
