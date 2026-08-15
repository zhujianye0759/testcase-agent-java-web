## ADDED Requirements

### Requirement: Step and expected-result alignment
The configured KEE API agent prompt SHALL require every generated test-case step to have exactly one same-numbered expected-result entry; multiple observable outcomes for one action SHALL remain within that one numbered expected-result entry. `[Req-ID]: REQ-GDQ-001`

#### Scenario: Multi-step case is generated
- **WHEN** the agent generates a test case containing multiple execution steps
- **THEN** the Markdown execution-step and expected-result cells contain the same sequence count and matching numbers

### Requirement: Concrete negative coverage
The configured KEE API agent prompt SHALL require exactly three applicable generic negative scenarios when the formal requirement contains no explicit anomaly, boundary, or invalid-input behavior, without inventing new business rules. `[Req-ID]: REQ-GDQ-002`

#### Scenario: Requirement contains no anomaly behavior
- **WHEN** a generated negative case has no formal anomaly behavior to cite
- **THEN** it contains exactly three applicable generic scenarios and labels their requirement basis as `依据通用经验`

### Requirement: Observable deterministic wording
The configured KEE API agent prompt SHALL reject vague outcomes such as “正常”、“正确”、“不报错”、“符合要求” and unresolved alternative wording such as “或”、“等”, unless the text immediately states a concrete observable result. `[Req-ID]: REQ-GDQ-003`

#### Scenario: Expected result is written
- **WHEN** the agent describes the result of an action
- **THEN** the result states an observable page, data, status, message, or navigation outcome using deterministic language

### Requirement: Executable test-data preconditions
The configured KEE API agent prompt SHALL require preconditions to identify the test identity, entry point, and data state needed to execute the case whenever those facts are supported by the requirement; unsupported values SHALL not be invented. `[Req-ID]: REQ-GDQ-004`

#### Scenario: Requirement supports a data precondition
- **WHEN** the requirement identifies prerequisite identity, data, or navigation context
- **THEN** the generated precondition records that context in executable terms

### Requirement: Granular view-operation steps
For view or query functions, the configured KEE API agent prompt SHALL split distinct supported user observations or operations into independently executable numbered steps instead of compressing them into one ambiguous action. `[Req-ID]: REQ-GDQ-005`

#### Scenario: View function exposes several supported observations
- **WHEN** the formal requirement lists multiple items that the user can view
- **THEN** the positive case expresses each supported observation as a separate step with a matching result

### Requirement: Evidence-bounded audit findings
The configured KEE API agent prompt SHALL describe an audit finding only to the level supported by formal requirement evidence and SHALL distinguish “未发现问题” from evidence insufficiency without presenting assumptions as defects. `[Req-ID]: REQ-GDQ-006`

#### Scenario: Evidence is insufficient for a defect conclusion
- **WHEN** the formal requirement does not support a concrete defect statement
- **THEN** the audit row records the bounded evidence condition and does not invent a business defect

### Requirement: Method-only Good and Bad examples
The configured AUTO Good/Bad example documents SHALL demonstrate the step/result, negative-scenario, observable-wording, test-data, view-operation, and evidence-boundary rules. They MUST remain whitelisted, parsed, enabled, correctly declared as GOOD or BAD, and MUST NOT be used as formal business evidence. `[Req-ID]: REQ-GDQ-007`

#### Scenario: AUTO examples are loaded
- **WHEN** the Java service loads the configured Good and Bad example documents
- **THEN** their content demonstrates the current guidance while requirement facts and citations remain sourced only from the frozen RequirementScope
