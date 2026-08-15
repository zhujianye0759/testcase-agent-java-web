## ADDED Requirements

### Requirement: [REQ-EXP-001] Export only validated terminal data
The system SHALL generate an Excel artifact only from persisted rows belonging to accepted batches whose scope, SSE terminal state, and Markdown structure passed deterministic validation. A `PARTIAL` task MAY expose an artifact containing only accepted batches when the UI labels it as partial.

#### Scenario: Task validation fails
- **WHEN** a task has a scope, evidence, contract, or completeness error
- **THEN** no downloadable workbook is marked ready

### Requirement: [REQ-EXP-002] Produce exactly two business worksheets
The workbook SHALL contain exactly two worksheets in this order: `需求与功能清单审查发现` with columns `序号 | 对象/功能点 | 问题分类 | 证据对照`, and `测试用例` with columns `用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容`.

#### Scenario: Workbook is generated
- **WHEN** a task reaches export eligibility
- **THEN** both worksheets and all ten required headers are present and no technical/audit metadata worksheet is added

### Requirement: [REQ-EXP-003] Accumulate rows across accepted batches
The system SHALL regenerate the workbook from all accepted database rows ordered by batch and row sequence; it SHALL NOT incrementally edit an existing `.xlsx` file.

#### Scenario: A task completes three generation batches
- **WHEN** all three batches are accepted
- **THEN** the two worksheets contain the ordered union of all accepted rows exactly once

### Requirement: [REQ-EXP-004] Prevent spreadsheet formula injection
The system SHALL write untrusted text beginning with `=`, `+`, `-`, or `@` as plain text rather than an executable formula.

#### Scenario: Requirement text begins with equals sign
- **WHEN** a document or model field starts with `=`
- **THEN** Apache POI reads the exported cell as a text cell and not a formula cell

### Requirement: [REQ-EXP-005] Verify and identify the artifact
The system SHALL reopen the generated workbook with Apache POI, validate its required structure and safety invariants, calculate a hash, and store only an application-controlled artifact identifier and path.

#### Scenario: Workbook cannot be reopened
- **WHEN** post-generation readback fails or required structure is absent
- **THEN** the artifact is rejected and is not downloadable

### Requirement: [REQ-EXP-006] Retain Phase 1 artifacts without automatic deletion
The system SHALL retain successful Phase 1 Excel artifacts until an explicit later retention policy or user-authorized removal is implemented.

#### Scenario: Artifact ages without a retention policy
- **WHEN** an artifact remains stored beyond any elapsed duration
- **THEN** the application does not delete it automatically

### Requirement: [REQ-EXP-007] Defer image embedding
The Phase 1 exporter SHALL write `对应需求内容` as wrapped text only and SHALL NOT fetch or embed Markdown images, external URLs, or KEE resource binaries.

#### Scenario: Agent returns a Markdown image reference
- **WHEN** `对应需求内容` contains Markdown image syntax
- **THEN** the batch is rejected with a recoverable contract error rather than silently downloading or embedding the image
