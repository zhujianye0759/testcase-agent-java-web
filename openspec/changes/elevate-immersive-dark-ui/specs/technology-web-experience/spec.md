## ADDED Requirements

### Requirement: Immersive dark mission-control experience
The Web application SHALL present the generation workflow as one coherent dark mission-control experience: a deep-shell background with restrained non-interactive aurora ambience, frosted-glass work surfaces, one unmistakable gradient primary action per action region, and dark-aware focus, form-control, scrollbar, and overlay treatments. All values SHALL be mapped through semantic project tokens compatible with PC UI Guidelines v1.0.0. The change SHALL NOT alter business workflow, wording, DOM contracts, request payloads, or the distinction of loading, ready, empty, no-results, error, forbidden, and not-found states. `[Req-ID]: REQ-UIX-009`

#### Scenario: User moves through the dark workflow
- **WHEN** the user navigates among creation, task list, and task detail at a supported PC viewport
- **THEN** the pages share the dark shell, glass surfaces, and accent language; labels, values, focus, and error feedback remain readable and unobscured by decorative layers; and the primary action of each region is unambiguous

#### Scenario: Task is actively running
- **WHEN** a task shows an in-progress status such as queued, auditing, generating, validating, or running
- **THEN** the status presents a subtle motion cue in addition to its text label and color, so state never relies on color alone

#### Scenario: User prefers reduced motion
- **WHEN** the browser exposes `prefers-reduced-motion: reduce`
- **THEN** aurora drift, shimmer, pulse, and hover displacement are removed or made static while the shell, surfaces, status text, and all workflow actions remain understandable

#### Scenario: User operates controls with the keyboard
- **WHEN** the user tabs through navigation, form controls, chips, dialogs, and pagination
- **THEN** every focused control shows a clearly visible dark-aware focus treatment and native controls render consistently with the dark theme
