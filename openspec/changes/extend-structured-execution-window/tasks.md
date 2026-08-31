## 1. Watched failures

- [x] 1.1 Add configuration tests proving the default and deployed knowledge-agent wait boundary resolve to 3060 seconds without a real long sleep. `[Req-ID]: REQ-SEW-001`
- [x] 1.2 Add MySQL lease tests for exact renewal, original-expiry protection, stale/cancelled claim rejection, and concurrent renewal/recovery single ownership. `[Req-ID]: REQ-SEW-002`
- [x] 1.3 Add coordinator heartbeat tests for success, invocation/validation failure, cancellation, lost lease, one acceptance, and unchanged attempt limits. `[Req-ID]: REQ-SEW-003`

## 2. Minimal implementation

- [x] 2.1 Set the existing Java knowledge-agent timeout default to 3060 seconds while preserving environment override semantics and safe error mapping. `[Req-ID]: REQ-SEW-001`
- [x] 2.2 Add exact-claim transactional lease renewal without a schema change or stale-claim revival. `[Req-ID]: REQ-SEW-002`
- [x] 2.3 Add the bounded scheduled heartbeat lifecycle and split invocation from acceptance so cancellation or lost ownership fails closed before persistence. `[Req-ID]: REQ-SEW-003`
- [x] 2.4 Wire the production scheduler/heartbeat into the only structured ALL coordinator and retain existing retry, cancellation, recovery, and legacy task paths. `[Req-ID]: REQ-SEW-002, REQ-SEW-003`

## 3. Verification and deployment

- [x] 3.1 Run focused configuration, heartbeat, coordinator, and MySQL/Testcontainers tests plus affected retry/cancellation regressions. `[Req-ID]: REQ-SEW-001, REQ-SEW-002, REQ-SEW-003`
- [x] 3.2 Run related backend regression/build, OpenSpec strict validation, diff check, and sensitive-content review; perform a Java-only code review. `[Req-ID]: REQ-SEW-001, REQ-SEW-002, REQ-SEW-003`
- [x] 3.3 Replace the single 8082 Java instance with a 3060-second effective knowledge-agent wait, verify health/runtime configuration and read-only preflight, and create no business task. `[Req-ID]: REQ-SEW-001, REQ-SEW-003`

## 4. Transient database heartbeat recovery

- [x] 4.1 Add a watched failure proving one scheduled renewal exception does not permanently lose an otherwise unexpired exact lease. `[Req-ID]: REQ-SEW-003`
- [x] 4.2 Keep scheduled renewal retryable after transient exceptions while retaining explicit-false and final pre-acceptance fail-closed behavior. `[Req-ID]: REQ-SEW-003`
- [x] 4.3 Run focused heartbeat/coordinator/MySQL recovery regressions, strict OpenSpec and diff/security gates, deploy the single 8082 service, and recover only the existing authorized task. `[Req-ID]: REQ-SEW-002, REQ-SEW-003`
