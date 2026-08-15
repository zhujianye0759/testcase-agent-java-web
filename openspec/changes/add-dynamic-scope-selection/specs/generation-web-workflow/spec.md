## MODIFIED Requirements

### Requirement: [REQ-WEB-001] Create a generation task from a business-friendly PC form
The Web application SHALL present `生成全部测试用例` as the default/recommended business choice and `指定功能生成` as the alternative. It SHALL let the user choose an eligible knowledge base, system, active version, and one or more admission-material types through business labels. It SHALL NOT display internal mode names, feature IDs, JSON, raw KEE identifiers, project coordinates, document identifiers, or manual example selection.

#### Scenario: Valid form is submitted
- **WHEN** an internal user submits a valid task form
- **THEN** duplicate submission is prevented, the selected opaque material leaves are sent, and the UI navigates to the created task detail

#### Scenario: One value exists at a selector level
- **WHEN** the current knowledge-base, system, version, or material level has exactly one eligible value
- **THEN** that value is automatically selected and displayed by business label without exposing its internal coordinate

#### Scenario: Multiple values exist at a selector level
- **WHEN** a level has multiple eligible values
- **THEN** the user can choose one by keyboard and changing an upper value clears any incompatible lower selection

#### Scenario: User specifies one feature
- **WHEN** the user selects `指定功能生成`
- **THEN** the form asks for a function name or natural-language description and does not ask for a function-point ID

#### Scenario: User uses default examples
- **WHEN** the user does not open or change advanced settings
- **THEN** the page submits AUTO and describes the behavior as `自动参考优质示例（推荐）`

## ADDED Requirements

### Requirement: [REQ-WEB-009] Provide recoverable cascading scope-selection states
The material-range region SHALL distinguish loading, ready, empty, and error states, retain unrelated form input during reload or failure, ignore stale catalog responses, and provide an explicit reload action. Submission SHALL remain disabled until the hierarchy and at least one material type are selected.

#### Scenario: Catalog reload fails
- **WHEN** the user reloads material ranges and KEE discovery fails
- **THEN** the page keeps the selected mode, feature description, example policy, and supplementary instructions while showing an actionable local error

#### Scenario: Stale request finishes last
- **WHEN** an earlier catalog request completes after a later reload request
- **THEN** its result is ignored and does not overwrite the current selector state

#### Scenario: No eligible material exists
- **WHEN** discovery succeeds but produces no eligible system/version/material leaf
- **THEN** the page explains that no ready authorized material is available, provides reload guidance, and prevents task creation
