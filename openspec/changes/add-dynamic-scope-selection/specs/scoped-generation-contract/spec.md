## MODIFIED Requirements

### Requirement: [REQ-SCP-001] Freeze an immutable requirement scope
The system SHALL resolve the current server-authorized opaque material selections and freeze their shared knowledge base, system, version, material category, optional project coordinate, selected material type keys, and exact matching document whitelist into an immutable `RequirementScope` snapshot used by every initial and retried batch. A missing project coordinate SHALL be normalized as no project constraint; it SHALL NOT broaden any other frozen coordinate or document whitelist.

#### Scenario: A task targets selected admission materials
- **WHEN** task creation revalidates one or more selected material types for one system version
- **THEN** every batch and retry uses the same frozen coordinates, type keys, document whitelist, and scope hash

#### Scenario: A task targets materials without a project coordinate
- **WHEN** the selected documents have no project coordinate
- **THEN** the snapshot omits that optional constraint while preserving the selected knowledge base, system, version, material category, type keys, and document whitelist

### Requirement: [REQ-SCP-004] Reject browser-supplied arbitrary or stale scope identifiers
The system SHALL expose server-derived opaque scope catalog keys and SHALL reject task requests containing raw KEE coordinates, unknown keys, stale keys, mixed-coordinate keys, or selections outside the service credential grants.

#### Scenario: Request contains a raw document ID
- **WHEN** a browser submits a document identifier or other KEE UUID in place of an opaque catalog key
- **THEN** task creation is rejected before persistence or KnowledgeEngineeringEngine invocation

#### Scenario: Request uses a previously valid stale key
- **WHEN** the current ready document whitelist no longer produces that opaque key
- **THEN** task creation is rejected and the browser is instructed to reload the material range
