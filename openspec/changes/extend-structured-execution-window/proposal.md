## Why

Structured isolated-skill calls may legitimately require up to 3000 seconds, but Java currently stops waiting after 300 seconds and gives each claimed work item only a five-minute lease. A valid long-running response can therefore be cancelled by the caller or rejected after return because its lease expired.

## What Changes

- Extend the configurable Java knowledge-agent wait window beyond 3000 seconds with explicit response-processing margin.
- Keep a structured work claim active while its owning worker is executing the external call, without allowing stale owners or other workers to accept the same work.
- Stop lease renewal promptly when execution completes, fails, or is cancelled; retain the existing bounded retry and business-validation rules.
- Add deterministic timeout and concurrency tests without waiting for a real 3000-second call.

## Capabilities

### New Capabilities
- `structured-execution-window`: Defines the caller timeout and renewable work-lease behavior required for one safe long-running structured Skill invocation.

### Modified Capabilities

None.

## Impact

- Java knowledge-agent timeout configuration and `WebClientKnowledgeAgentAdapter` blocking boundary.
- Structured work claim, renewal, acceptance, failure, cancellation, and concurrent recovery behavior in MySQL.
- Structured ALL coordinator execution lifecycle and deployment configuration for the existing 8082 instance.
- No KEE code, KEE contract, model retry count, business validator, task data, parsed material, or existing failed task is changed.
