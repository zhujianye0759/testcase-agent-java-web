## ADDED Requirements

### Requirement: [REQ-WEB-001] Create a generation task from a business-friendly PC form
The Web application SHALL present `生成全部测试用例` as the default/recommended business choice and `指定功能生成` as the alternative. It SHALL NOT display internal mode names, feature IDs, JSON, UUIDs, project coordinates, or manual example selection.

#### Scenario: Valid form is submitted
- **WHEN** an internal user submits a valid task form
- **THEN** duplicate submission is prevented and the UI navigates to the created task detail

#### Scenario: One authorized scope is available
- **WHEN** the page loads exactly one server-authorized material scope
- **THEN** it is automatically selected and displayed as a read-only `系统 / 版本 / 材料范围` summary without internal identifiers

#### Scenario: User specifies one feature
- **WHEN** the user selects `指定功能生成`
- **THEN** the form asks for a function name or natural-language description and does not ask for a function-point ID

#### Scenario: User uses default examples
- **WHEN** the user does not open or change advanced settings
- **THEN** the page submits AUTO and describes the behavior as `自动参考优质示例（推荐）`

### Requirement: [REQ-WEB-002] Browse shared tasks
The Web application SHALL provide a shared list with task name, creation time, state, textual progress, failure summary, query/reset behavior, and pagination.

#### Scenario: Filter returns no task
- **WHEN** a valid filter has no matching task
- **THEN** the page shows `no-results` while preserving filters and reset action rather than showing the initial empty state

### Requirement: [REQ-WEB-003] Inspect task detail and progress
The Web application SHALL provide a business summary, frozen scope label, batch progress, audit-finding/test-case previews, failure recovery, and export state without exposing internal protocol fields.

#### Scenario: Task is still running
- **WHEN** task detail reports measurable completed and total work
- **THEN** the page shows determinate progress with textual state and retains cancel access

### Requirement: [REQ-WEB-004] Perform shared cancellation and retry
The Web application SHALL allow any internal user to cancel an eligible task or retry eligible failed batches after explicit confirmation.

#### Scenario: User retries a partial task
- **WHEN** the user confirms retry on a `PARTIAL` task
- **THEN** the UI disables duplicate action, preserves context on failure, and refreshes to the accepted queued state on success

### Requirement: [REQ-WEB-005] Download a safe accepted artifact
The Web application SHALL expose download only for a ready artifact generated from accepted batches and SHALL use an application artifact identifier rather than a filesystem path. A partial artifact SHALL be visibly labelled as containing completed batches only.

#### Scenario: Task has no valid export
- **WHEN** a task is queued, running, fully failed, cancelled, or blocked by validation
- **THEN** no enabled Excel download action is shown

### Requirement: [REQ-WEB-006] Implement explicit page and region states
The Web application SHALL distinguish `idle`, `loading`, `ready`, `empty`, `no-results`, `error`, `forbidden`, and `not-found`, replacing only the failed region when the rest of the page remains usable.

#### Scenario: Task table request fails
- **WHEN** the task list request fails after the page shell loads
- **THEN** the table region shows an actionable error state without discarding navigation and page context

### Requirement: [REQ-WEB-007] Meet PC keyboard and semantic-token acceptance
The Web application SHALL use mapped semantic tokens, keyboard-operable actions, visible focus, accessible names, non-color-only status, and reduced-motion behavior from the project PC guideline.

#### Scenario: User operates the task flow by keyboard
- **WHEN** the user creates, inspects, cancels, retries, and downloads through keyboard navigation
- **THEN** focus remains visible and returns predictably after dialogs or overlays close

### Requirement: [REQ-WEB-008] Preserve a simple recoverable creation experience
The primary action SHALL read `开始生成测试用例`, explain that execution continues in the background, preserve all useful input on failure, focus the error summary, and provide a recovery action when no authorized material scope is available.

#### Scenario: Creation request fails
- **WHEN** the server rejects or cannot create the task
- **THEN** the selected mode, function description, scope label, example setting, and optional `补充说明` remain unchanged and focus moves to the actionable error summary
