## ADDED Requirements

### Requirement: [REQ-CAT-001] Discover only eligible server-authorized requirement material
The Java server SHALL discover scope choices through supported KEE read APIs using only the server-side API key. It SHALL include only document knowledge bases with a system container, active system versions, completed and enabled admission-material documents, and document scopes matching the catalog system and version.

#### Scenario: Knowledge base has no system container
- **WHEN** an API-key-readable knowledge base is department-public, unscoped, FAQ, or otherwise lacks a system container
- **THEN** it is absent from the requirement-scope catalog

#### Scenario: Document is not ready
- **WHEN** a document is disabled, unparsed, assigned to another version, or outside admission materials
- **THEN** it contributes no material option and cannot enter a frozen whitelist

### Requirement: [REQ-CAT-002] Expose an opaque business-readable hierarchy
The Java server SHALL return knowledge-base, system, version, category, and material-type business labels plus opaque application keys and document counts. It SHALL NOT return the KEE API key, raw knowledge-base/system/version/project/document identifiers, internal resource URLs, or example-document content.

#### Scenario: Browser loads the scope catalog
- **WHEN** catalog discovery succeeds
- **THEN** the response can render the complete business hierarchy without revealing any KEE coordinate UUID

### Requirement: [REQ-CAT-003] Page and cache catalog reads without partial publication
The catalog adapter SHALL follow KEE knowledge-base cursor pages and knowledge-document number pages, group results incrementally, and publish a new immutable snapshot only after every required read succeeds. The service SHALL use a project-configurable TTL and explicit refresh operation.

#### Scenario: Later catalog page fails
- **WHEN** KEE returns an error while a new snapshot is being assembled
- **THEN** the service reports a recoverable catalog error and does not publish the partial snapshot

#### Scenario: Cached snapshot remains fresh
- **WHEN** another page requests the catalog before TTL expiry without explicit refresh
- **THEN** the service returns the same immutable snapshot without repeating the KEE fan-out

### Requirement: [REQ-CAT-004] Revalidate opaque selections before task creation
The server SHALL accept only opaque material-leaf IDs from the current server catalog, require all selected leaves to share one knowledge base, system, version, and material category, refresh the selected KEE knowledge base, and require each opaque ID to match the current ready document whitelist before freezing a task.

#### Scenario: Browser mixes versions
- **WHEN** a task request selects material leaves from different versions or systems
- **THEN** task creation is rejected before persistence or agent invocation

#### Scenario: Catalog changed after page load
- **WHEN** a selected document was disabled, moved, deleted, or replaced after the browser loaded its option
- **THEN** task creation fails with a reload-material-range action and does not widen to remaining documents

#### Scenario: Valid material types are selected
- **WHEN** all selected leaves remain current and share one scope
- **THEN** the frozen scope contains the sorted union of exactly those material type keys and matching ready document IDs

### Requirement: [REQ-CAT-005] Keep generation agent and examples server-owned
User scope selection SHALL affect only `RequirementScope`. The configured API agent and independent Good/Bad `ExampleScope` SHALL remain server-owned, and the example knowledge base SHALL never appear as an eligible requirement source.

#### Scenario: User selects a requirement scope
- **WHEN** the task request resolves successfully
- **THEN** the request uses the configured agent and example whitelist without accepting any browser override
