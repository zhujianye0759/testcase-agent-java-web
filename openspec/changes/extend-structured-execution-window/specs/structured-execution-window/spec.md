## ADDED Requirements

### Requirement: [REQ-SEW-001] Java preserves the authorized non-streaming model window
The Java knowledge-agent wait boundary SHALL exceed 3000 seconds and SHALL provide at least 60 seconds of response-processing margin. The deployed validation environment MUST resolve the existing knowledge-agent timeout property to exactly 3060 seconds. Timeout failure SHALL retain the existing safe structured model-execution error classification and MUST NOT increase the model retry count.

#### Scenario: Configured long-running structured call
- **WHEN** a structured isolated-skill invocation remains pending beyond the former 300-second boundary but returns within 3000 seconds
- **THEN** Java continues waiting under its 3060-second configured boundary and processes the response through the existing strict validator

#### Scenario: Timeout property remains configurable
- **WHEN** an operator supplies the established knowledge-agent timeout property
- **THEN** Java binds that positive duration without introducing a second incompatible timeout setting

### Requirement: [REQ-SEW-002] Active structured attempts renew their exact lease
While one coordinator executes a claimed structured work item, Java SHALL periodically renew the lease before expiry. Renewal MUST atomically match the task ID, work ID, attempt ID, owner, RUNNING work and attempt states, an unexpired lease, and a task without a cancellation request. An expired, completed, failed, cancelled, or replaced claim MUST NOT be renewed.

#### Scenario: Long call remains owned by one worker
- **WHEN** a structured call outlives its original five-minute lease and heartbeats continue successfully
- **THEN** another worker cannot reclaim the work, the original attempt remains the only running attempt, and the original worker may proceed to acceptance

#### Scenario: Stale owner cannot revive a claim
- **WHEN** a lease expires or another attempt has already taken ownership
- **THEN** renewal by the old claim fails without changing the new attempt or any business row

### Requirement: [REQ-SEW-003] Heartbeat lifecycle preserves terminal and cancellation safety
The coordinator SHALL stop heartbeat activity when invocation, validation, acceptance, failure, or cancellation leaves the work execution scope. After receiving a response and before accepting rows, it MUST confirm that cancellation has not been requested and the heartbeat still owns a healthy lease. Heartbeat failure or cancellation MUST NOT cause double acceptance, duplicate attempts, or a business-validation retry.

#### Scenario: Successful response is accepted under a live lease
- **WHEN** a long-running invocation returns after one or more successful renewals
- **THEN** the coordinator verifies the live lease, accepts once, and stops renewal after the work becomes terminal

#### Scenario: Failure stops renewal
- **WHEN** invocation or validation fails
- **THEN** renewal stops before the attempt is failed and the existing bounded failure classification applies once

#### Scenario: Cancellation stops renewal and acceptance
- **WHEN** cancellation becomes visible while the external call is pending
- **THEN** subsequent heartbeat ticks do not renew and the returned result is not accepted

#### Scenario: Concurrent renewal and recovery cannot both win
- **WHEN** lease renewal and another worker's expiry recovery race
- **THEN** exactly one current attempt remains authoritative and no result can be accepted twice

#### Scenario: Scheduled renewal survives a transient database outage
- **WHEN** one scheduled heartbeat renewal raises a transient database exception while the exact lease remains unexpired and a later renewal succeeds
- **THEN** the heartbeat continues renewing the same claim instead of permanently declaring ownership lost
- **AND** the final pre-acceptance health check remains fail-closed on an exception or an explicit renewal rejection
