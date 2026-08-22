## ADDED Requirements

### Requirement: Coherent technology visual system
The Web application SHALL present one restrained, modern technology-oriented visual system across its shell and three business pages. Color, typography, spacing, radius, shadow, layer, and motion values SHALL be mapped through semantic project tokens compatible with PC UI Guidelines v1.0.0 rather than scattered literal values. `[Req-ID]: REQ-UIX-001`

#### Scenario: User moves between workflow pages
- **WHEN** the user navigates among creation, task list, and task detail
- **THEN** the pages retain a coherent shell, hierarchy, component language, and clearly identifiable primary action

### Requirement: Business-oriented application shell
The application shell SHALL provide clear product identity, persistent primary navigation, current-route indication, service status context, keyboard operation, and visible focus without exposing implementation identifiers or infrastructure terminology. `[Req-ID]: REQ-UIX-002`

#### Scenario: Keyboard navigation through shell
- **WHEN** a user navigates the shell using Tab and Enter
- **THEN** every navigation action is reachable, visibly focused, correctly named, and activates the expected business page

### Requirement: Focused generation launch experience
The creation page SHALL make “生成全部测试用例” the recommended default, keep “指定功能生成” understandable through natural-language input, summarize the authorized system/version/material scope without UUIDs, present AUTO examples as the normal recommended behavior, keep NONE in advanced settings, prevent duplicate submission, retain input on failure, and focus an actionable error summary. `[Req-ID]: REQ-UIX-003`

#### Scenario: User starts an ALL task
- **WHEN** the single authorized scope is ready and the user activates “开始生成测试用例” without additional input
- **THEN** exactly one ALL create request is submitted and the interface explains that generation continues in the background

#### Scenario: Create request fails
- **WHEN** task creation returns an error
- **THEN** the selected mode and supplementary text remain present and keyboard focus moves to a business-readable recovery summary

### Requirement: Scannable shared task list
The task list SHALL prioritize task status, scope, progress, creation time, and permitted shared actions. It SHALL distinguish initial loading, ready, initial empty, filtered no-results, and request error while preserving filters and recovery actions. `[Req-ID]: REQ-UIX-004`

#### Scenario: Task query has no filtered result
- **WHEN** an active filter returns no tasks
- **THEN** the page preserves the filter context, identifies the no-results state, and offers a clear reset action

### Requirement: Decision-ready task detail
The task detail SHALL keep status, progress, frozen material summary, safe shared actions, audit findings, generated cases, batch recovery, and Excel download understandable without exposing internal UUIDs. Critical status and primary recovery/download actions SHALL remain discoverable. `[Req-ID]: REQ-UIX-005`

#### Scenario: Completed task is viewed
- **WHEN** a completed task detail is loaded
- **THEN** the user can identify completion, review accumulated findings and cases, and download the Excel artifact from a clearly labeled action

### Requirement: Complete state and accessibility behavior
The frontend SHALL distinguish loading, ready, empty, no-results, error, forbidden, and not-found states where applicable; local failures SHALL replace only the affected region. All actions SHALL support keyboard operation and visible focus, status SHALL not rely on color alone, duplicate writes SHALL be prevented, and reduced-motion preference SHALL remove nonessential movement. `[Req-ID]: REQ-UIX-006`

#### Scenario: Repeated or stale interaction occurs
- **WHEN** a user repeats a write action or a slower request resolves after a newer one
- **THEN** the interface prevents duplicate writes and does not replace current state with a stale response

### Requirement: Multi-viewport PC acceptance
The complete creation, list, and detail workflow SHALL remain usable at 1024×768, 1440×820, and 1920×1080 browser viewports without horizontal page overflow, clipped primary actions, hidden focus, or unreadably compressed essential content. `[Req-ID]: REQ-UIX-007`

#### Scenario: Workflow is exercised at supported PC viewports
- **WHEN** browser acceptance runs the ready and representative non-ready states at each required viewport
- **THEN** the workflow remains operable and visual evidence is captured for main-thread review

### Requirement: Distinctive but non-intrusive control-deck refinement
The application shell and core workflow surfaces SHALL use the same semantic visual roles to establish a distinctive control-deck character: deep shell contrast, restrained technical ambience, layered work surfaces, and an unambiguous primary action. Decorative effects SHALL be non-semantic, non-interactive, readable at supported PC widths, and removed or made static when reduced motion is preferred. `[Req-ID]: REQ-UIX-008`

#### Scenario: User works with the creation page
- **WHEN** the authorized scope is ready at a supported PC viewport
- **THEN** the user can distinguish the page context, task mode, material scope, and primary generation action without decorative content obscuring labels, input values, focus, or error feedback

#### Scenario: User prefers reduced motion
- **WHEN** the browser exposes `prefers-reduced-motion: reduce`
- **THEN** nonessential ambient movement and transform effects do not run while the shell and all workflow actions remain understandable
