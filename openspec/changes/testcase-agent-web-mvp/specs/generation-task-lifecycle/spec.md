## ADDED Requirements

### Requirement: [REQ-TSK-001] Create a durable generation task
The system SHALL persist a generation task, immutable scope snapshot, requested generation mode, example policy, Markdown contract version, and prompt version before returning a task identifier.

#### Scenario: Task creation succeeds
- **WHEN** an internal user submits a valid task request
- **THEN** the system returns a non-guessable task identifier and persists the task in `QUEUED` state

### Requirement: [REQ-TSK-002] Query shared task state
The system SHALL expose a shared task list and task detail containing status, measurable progress, batches, failure summary, accepted audit/test-case row counts, validation outcome, and export state.

#### Scenario: Any internal user views a task
- **WHEN** an internal user opens the task list or a known task detail URL
- **THEN** the system returns the current shared task information without requiring login

### Requirement: [REQ-TSK-003] Limit active execution and durably queue overflow
The system SHALL execute no more than five generation tasks concurrently and SHALL keep additional tasks in a durable queue without starting their batches.

#### Scenario: Sixth task waits
- **WHEN** five tasks are actively executing and a sixth valid task is created
- **THEN** the sixth task remains `QUEUED` until an execution slot is released

### Requirement: [REQ-TSK-004] Cancel a shared task
The system SHALL allow any internal user to request cancellation, persist that intent, and stop future work at safe batch checkpoints without erasing completed evidence.

#### Scenario: Running task is cancelled
- **WHEN** a user cancels a running task
- **THEN** workers stop claiming new batches and the task reaches `CANCELLED` after in-flight work reaches a safe checkpoint

### Requirement: [REQ-TSK-005] Retry only failed batches
The system SHALL allow any internal user to retry failed batches while preserving successful batch results and preventing duplicate successful outputs.

#### Scenario: Partial task is retried
- **WHEN** a user retries a `PARTIAL` task
- **THEN** only retryable failed batches are re-queued and successful batches are not invoked again

#### Scenario: A retried batch succeeds
- **WHEN** a previously failed batch is accepted by its later retry attempt
- **THEN** the completed task and accepted batch do not display the historical failure summary, while the attempt history remains durable

### Requirement: [REQ-TSK-006] Recover after service restart
The system SHALL persist task claims and leases in MySQL 8 and SHALL recover expired claims after restart without duplicating a previously successful batch.

#### Scenario: Worker stops during a batch
- **WHEN** the service restarts after a worker lease expires before the batch reaches a durable success state
- **THEN** the batch becomes claimable again while completed batches remain unchanged

### Requirement: [REQ-TSK-007] Preserve truthful terminal states
The system SHALL distinguish `COMPLETED`, `PARTIAL`, `FAILED`, and `CANCELLED`; it MUST NOT represent partial output as complete.

#### Scenario: One batch fails permanently
- **WHEN** at least one required batch succeeds and another reaches a non-retryable failure
- **THEN** the task reaches `PARTIAL` and its detail identifies the failed batch and reason

### Requirement: [REQ-TSK-008] Bound internal work without imposing a product cap
The system SHALL process feature discovery, generation, validation, and export in bounded pages or batches even though Phase 1 defines no product-level feature-count or total-duration limit.

#### Scenario: Task contains many features
- **WHEN** an `ALL` task discovers more features than one generation batch can safely process
- **THEN** the system creates bounded batches and never loads or sends the full unbounded workload as one model request

### Requirement: [REQ-TSK-009] Accumulate accepted batch rows idempotently
The system SHALL persist each completed batch's parsed audit and test-case rows atomically, order them by batch and row sequence, and prevent a retry from duplicating an already accepted batch.

#### Scenario: A failed batch succeeds after retry
- **WHEN** one task already contains accepted rows and a previously failed batch is retried successfully
- **THEN** the new batch rows are added once after the earlier rows and the earlier accepted rows remain unchanged
