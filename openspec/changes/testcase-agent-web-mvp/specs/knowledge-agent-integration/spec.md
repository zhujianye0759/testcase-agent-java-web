## ADDED Requirements

### Requirement: [REQ-KAG-001] Resolve the configured API agent
The system SHALL resolve the configured API agent by name or capability at runtime and SHALL fail fast when no unique usable agent exists.

#### Scenario: Agent name is ambiguous
- **WHEN** agent discovery returns zero or multiple matching API agents
- **THEN** the task fails before session creation with a configuration error that exposes no credential

### Requirement: [REQ-KAG-002] Isolate agent sessions by batch
The system SHALL create an independent KnowledgeEngineeringEngine session for every executable batch and SHALL NOT reuse a conversation context across concurrent tasks.

#### Scenario: Two tasks run concurrently
- **WHEN** two tasks invoke the same configured agent
- **THEN** each batch uses a distinct session identifier and receives no conversational state from the other task

### Requirement: [REQ-KAG-003] Require an explicit SSE terminal outcome
The system SHALL treat an agent invocation as successful only after receiving the required explicit complete terminal event and SHALL parse only the answer content accumulated before that terminal event.

#### Scenario: Stream ends without completion
- **WHEN** HTTP EOF, timeout, or connection loss occurs before the explicit complete terminal event
- **THEN** the invocation fails and no result from that invocation is eligible for export

#### Scenario: Terminal error is received
- **WHEN** the SSE stream emits a terminal error event
- **THEN** the adapter records the mapped failure after delivering no successful domain result

### Requirement: [REQ-KAG-004] Validate the fixed Markdown response contract
The system SHALL parse the completed answer as two ordered Markdown tables with the exact approved headings and columns and SHALL reject raw JSON, fenced payloads, missing or renamed headings, column-count drift, malformed rows, or ambiguous prose inside a table. A blank-separated non-structural model note after the final fixed table MAY be ignored and SHALL NOT be persisted or exported; any later heading or table-shaped content remains invalid.

#### Scenario: Agent returns a malformed table
- **WHEN** the completed answer omits `预期结果` or returns a row with the wrong number of cells
- **THEN** deterministic validation fails and the batch is not marked successful

### Requirement: [REQ-KAG-005] Apply bounded dependency retries
The system SHALL apply bounded retries only to classified transient network, rate-limit, or upstream availability failures; contract, scope, and evidence failures MUST NOT be retried indefinitely.

#### Scenario: Upstream is temporarily unavailable
- **WHEN** the invocation receives a configured transient failure and retry budget remains
- **THEN** the system retries with bounded backoff and records each attempt

### Requirement: [REQ-KAG-006] Keep integration credentials server-side
The system SHALL keep API keys and external-subject configuration on the Java server and SHALL use only the required `chat` and `retrieve` capabilities and granted knowledge scopes.

#### Scenario: Browser loads task configuration
- **WHEN** the frontend requests selectable task configuration
- **THEN** the response contains safe display values and opaque application identifiers but no API key, model credential, tenant secret, or internal resource URL

### Requirement: [REQ-KAG-007] Remove superseded testcase JSON protocol code
The Java application SHALL remove production code, schemas, mappers, validators, tests, and configuration that exist only for the superseded testcase JSON result protocol. The KnowledgeEngineeringEngine repository SHALL be surgically restored only for changes introduced solely to support that protocol, without reverting unrelated dirty-worktree changes or generic scope isolation.

#### Scenario: Markdown path replaces JSON generation
- **WHEN** the fixed Markdown generation path is complete
- **THEN** no active or dead Java production path accepts `testcase-agent.response.v1` or maps it to `generation-result-1.0`, and KEE generic chat/retrieval behavior passes focused non-regression tests after the JSON-specific restoration

### Requirement: [REQ-KAG-008] Configure evidence-bound functional testcase design guidance
The configured KnowledgeEngineeringEngine API agent SHALL be instructed to design only the current feature's functional testcases from the authorized formal requirement materials, applying GB/T 25000.51 functional completeness, correctness, and appropriateness without inventing unstated business behavior. The prompt SHALL request one positive and one negative testcase, permit applicable general-experience exception scenarios only when the formal materials are incomplete and label them as `依据通用经验`, and preserve the existing audit-plus-testcase Markdown table contract. This is prompt guidance only and SHALL NOT add a new Java semantic validator in this change.

#### Scenario: Updated API agent prompt is loaded
- **WHEN** the configured agent prompt is read back after the update
- **THEN** it contains the evidence boundary, positive/negative design rules, step-to-result correspondence, `<br>` cell formatting, and the exact two approved Markdown table headings and columns, while the agent identity, model, tools, selected knowledge base, and Java response parser remain unchanged
