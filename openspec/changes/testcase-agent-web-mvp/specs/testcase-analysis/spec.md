## ADDED Requirements

### Requirement: [REQ-ANA-001] Cross-audit the function list and requirement materials
For `ALL`, the system SHALL first request one simple Markdown feature list with heading `功能点清单` and columns `序号 | 功能点`, derive internal batch identities from the frozen task and returned sequence, and then invoke one bounded generation batch per discovered feature. The browser SHALL NOT supply or display internal feature IDs.

#### Scenario: Requirement contains an omitted function
- **WHEN** in-scope evidence describes a function absent from the function list
- **THEN** the feature audit records a traceable candidate and does not silently omit it from `ALL` generation
- **THEN** the feature list includes that business-readable function and Java schedules it once using a server-owned internal identity

### Requirement: [REQ-ANA-002] Parse test cases from the approved Markdown table
The system SHALL accept a test-case row only when all six approved columns are present: case name, function module, preconditions, steps, expected results, and corresponding requirement content.

#### Scenario: Complete generation row
- **WHEN** a generation batch succeeds
- **THEN** it contains at least one complete test-case row associated with the batch's requested feature scope

### Requirement: [REQ-ANA-003] Keep steps and expected results aligned
The system SHALL convert Markdown `<br>` markers in steps, expected results, and requirement content into ordered Excel line breaks without interpreting model text as HTML.

#### Scenario: Expected result is missing
- **WHEN** the `执行步骤` or `预期结果` cell is empty
- **THEN** deterministic validation rejects that batch

### Requirement: [REQ-ANA-004] Keep requirement facts separate from reference examples
The prompt SHALL require `对应需求内容` and `证据对照` to quote or summarize only the frozen requirement documents and MUST NOT use example-library content as a business fact. The fixed two-table Markdown contract has no separate structured basis field.

#### Scenario: Requirement omits an applicable error case
- **WHEN** the agent considers a generic error scenario that is not supported by requirement evidence
- **THEN** it does not present that scenario as a requirement citation or an evidence-backed audit finding

### Requirement: [REQ-ANA-005] Persist audit findings only as candidates
The system SHALL persist the four audit-finding columns as agent-generated review findings without an approval, reviewer, retirement, or defect-confirmation state.

#### Scenario: Agent reports vague wording
- **WHEN** a valid result identifies an ambiguous requirement
- **THEN** the row is labelled as an audit finding and is not represented as a formally confirmed defect

### Requirement: [REQ-ANA-006] Preserve batch order without retry duplication
The system SHALL preserve accepted rows in batch sequence and row sequence. It SHALL use batch acceptance identity, not fuzzy text matching, to prevent retry duplication.

#### Scenario: Two different batches report similar text
- **WHEN** two distinct feature batches legitimately return similar findings or test cases
- **THEN** both rows remain, while replay of the same accepted batch adds no duplicate

### Requirement: [REQ-ANA-007] Complete all discovered features in ALL mode
The system SHALL keep an auditable relationship between server-discovered features and terminal batch outcomes and SHALL NOT mark an `ALL` task complete while any required feature lacks an accepted or permanently failed batch outcome.

#### Scenario: One discovered feature has no terminal result
- **WHEN** every scheduled batch ends but one discovered feature has neither an accepted result nor a recorded permanent failure
- **THEN** task validation fails instead of reporting `COMPLETED`
