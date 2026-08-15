## ADDED Requirements

### Requirement: [REQ-SCP-001] Freeze an immutable requirement scope
The system SHALL freeze the selected knowledge base, system, version, material category, optional project coordinate, and document whitelist into an immutable `RequirementScope` snapshot used by every initial and retried batch. A missing project coordinate SHALL be normalized as no project constraint; it SHALL NOT broaden any other frozen coordinate or document whitelist.

#### Scenario: A task targets strategic-operations admission materials
- **WHEN** a task is created for knowledge base `abd55572-af4b-44e9-820b-3a8a5e645664`, version V1.0, and admission materials
- **THEN** every batch and retry uses the same frozen coordinates and scope hash

#### Scenario: A task targets materials without a project coordinate
- **WHEN** the selected documents have no project coordinate
- **THEN** the snapshot omits that optional constraint while preserving the selected knowledge base, system, version, material category, and document whitelist

### Requirement: [REQ-SCP-002] Accept formal evidence only from RequirementScope
The system SHALL accept a business fact, requirement quotation, chapter, number, or rule only when its evidence resolves to the frozen `RequirementScope`. When the frozen scope has a project coordinate, evidence SHALL match it exactly; when it has none, evidence project metadata is optional and SHALL NOT be used to widen the other frozen coordinates.

#### Scenario: Evidence belongs to another version
- **WHEN** a candidate result references knowledge outside the task version or material scope
- **THEN** validation fails closed and no Excel artifact is produced from the invalid result

### Requirement: [REQ-FEW-001] Keep ExampleScope separate from RequirementScope
The system SHALL retrieve Few-shot candidates only from an independent department example-library scope and MUST NOT register example content as formal requirement evidence.

#### Scenario: Example contains a business value
- **WHEN** a selected example contains a role, threshold, name, or workflow absent from current requirement evidence
- **THEN** that value cannot appear as a supported requirement fact or requirement issue conclusion

### Requirement: [REQ-FEW-002] Select only configured enabled examples
The system SHALL select Few-shot examples only when the document ID is in the server-side Good/Bad whitelist, the document belongs to the frozen example knowledge base, KnowledgeEngineeringEngine reports `parse_status=completed` and `enable_status=enabled`, and the declared quality kind matches the configured Good/Bad type. No example approval or retirement lifecycle is part of this product.

#### Scenario: Disabled example matches semantically
- **WHEN** a configured example document is disabled in KnowledgeEngineeringEngine
- **THEN** the example is excluded from AUTO example loading

### Requirement: [REQ-FEW-003] Default to AUTO and keep NONE as an advanced comparison
The system SHALL default to automatic bounded selection from the server whitelist and SHALL support a no-example comparison mode only through advanced settings. Phase 1 SHALL NOT expose manual example selection.

#### Scenario: User selects NONE
- **WHEN** the task Few-shot policy is `NONE`
- **THEN** no example content is sent and the empty selection is recorded for reproducibility

#### Scenario: User accepts the default
- **WHEN** the task is created without changing advanced settings
- **THEN** the server validates and sends only configured parsed and enabled Good/Bad example documents

### Requirement: [REQ-SCP-003] Revalidate evidence before persistence and export
The system SHALL preserve the strict KEE request scope and document whitelist for every batch and retry. Requirement references written into Markdown SHALL be treated as display content, not as authority to widen the frozen scope.

#### Scenario: Retry request drifts from the frozen scope
- **WHEN** a retry attempts to use a different knowledge base, version, material type, or document whitelist
- **THEN** invocation is rejected before KEE is called

### Requirement: [REQ-SCP-004] Reject browser-supplied arbitrary scope identifiers
The system SHALL expose server-resolved selectable scope options and SHALL reject task requests containing identifiers outside those options or the service credential grants.

#### Scenario: Request contains an unlisted document ID
- **WHEN** a browser submits a document identifier not present in the server-authorized option set
- **THEN** task creation is rejected before any KnowledgeEngineeringEngine invocation
