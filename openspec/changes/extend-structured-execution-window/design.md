## Context

The Java knowledge-agent adapter applies one configurable `Duration` at its Reactor `block` boundary. Its current five-minute default cancels a legitimate non-streaming isolated-skill call long before the authorized 3000-second model window. Independently, structured work claims expire after five minutes; accept and fail correctly reject an expired claim, but no execution-time renewal exists.

The structured workflow is synchronous per work item, uses at most two attempts only for the existing transient model failures, and atomically verifies the exact task/work/attempt/owner before accepting rows. Existing failed tasks and parsed material are protected evidence and are outside this change.

## Goals / Non-Goals

**Goals:**

- Give Java a 3060-second knowledge-agent wait window so a 3000-second non-streaming model request retains response parsing margin.
- Renew only the exact active structured attempt while its owning coordinator is executing.
- Stop renewal on completion, failure, cancellation, or lease loss and preserve single-acceptance guarantees.
- Prove timeout and renewal behavior with deterministic configuration, clock, and concurrency tests rather than a real 3000-second sleep.

**Non-Goals:**

- No KEE, model, prompt, schema, validation, retry-count, task-result, parsed-unit, OCR, or PageIndex change.
- No replay, retry, cancellation, or replacement of an existing business task.
- No new business status or destructive schema migration.

## Decisions

1. **Reuse the existing knowledge-agent timeout property and set its default to 3060 seconds.** The established `TESTCASE_KNOWLEDGE_AGENT_TIMEOUT` binding already reaches the non-streaming `Mono.block` boundary. Reusing it is smaller and keeps deployment override behavior stable. The trade-off is that other KEE HTTP/SSE calls share the wider ceiling; their own terminal and validation rules remain unchanged.

2. **Retain the five-minute claim duration and add a one-minute heartbeat.** A renewable short lease recovers a genuinely dead worker sooner than a fixed lease longer than 3000 seconds. Extending a single fixed lease would delay crash recovery and would still place acceptance close to an arbitrary deadline.

3. **Renew by exact claim coordinates in one transaction.** Renewal requires the work item to remain RUNNING, the lease to remain unexpired, the owner and attempt ID to match, the attempt to remain RUNNING, and the task not to have a cancellation request. Renewal moves expiry five minutes from the current application clock. A stale owner cannot renew after another attempt takes over.

4. **Keep invocation and acceptance as separate coordinator stages.** The heartbeat covers the external call and the subsequent atomic accept, but the coordinator checks cancellation and heartbeat health after the response and before persistence. A lost or cancelled lease cannot be used to accept business rows.

5. **Use a shared daemon scheduled executor owned by Spring.** One bounded scheduler supports the five existing generation workers. Each heartbeat is closed in `finally`; closing cancels its future without interrupting database commit work.

## Risks / Trade-offs

- **[Shared HTTP timeout holds a worker longer during an upstream outage]** → Existing five-worker concurrency, bounded model retry count, explicit KEE failures, and task persistence remain unchanged; operators can override the established environment variable.
- **[Heartbeat and recovery race at lease expiry]** → Renewal never extends an already expired lease and includes the exact attempt ID; accept/fail recheck the same locked claim before mutation.
- **[Cancellation arrives during a non-streaming call]** → Heartbeat stops renewing when cancellation is visible, and the coordinator checks cancellation before acceptance. The HTTP call is not converted into a new retry mechanism.
- **[Scheduler or database renewal fails]** → The active lease is marked unhealthy and the returned model result is rejected before acceptance; no old claim is revived.
- **[Existing dirty diagnostic work is mixed accidentally]** → Files and hunks for this change are tracked separately; no reset, clean, archive, or unrelated rewrite is performed.

## Migration Plan

1. Deploy the same application JAR with the existing Flyway history unchanged.
2. Start the single 8082 instance with `TESTCASE_KNOWLEDGE_AGENT_TIMEOUT=3060s`; verify health and configuration without creating a task.
3. Roll back by restoring the previous JAR and timeout environment value. No data rollback is required because the change adds no schema or business rows.

## Open Questions

None. KEE must independently expose a 3000-second non-streaming model request window before a real task is authorized.
