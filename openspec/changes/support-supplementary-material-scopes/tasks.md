## 1. Scope isolation evidence

- [x] 1.1 Add RED coverage that freezes the five selected material types/documents from one dynamic scope coordinate and preserves legacy range parsing. `[Req-ID]: REQ-SMS-001`
- [x] 1.2 Add RED coverage that requirement_list, like prototype, accepts findings but rejects independent formal facts. `[Req-ID]: REQ-SMS-001`

## 2. Reader-safe projection and acceptance tooling

- [x] 2.1 Add the two fixed Chinese material labels to the task-detail projection and make the frontend RED green. `[Req-ID]: REQ-SMS-001`
- [x] 2.2 Add a new ignored, read-only new-KB scope verification script; preserve old acceptance evidence unchanged. `[Req-ID]: REQ-SMS-002`

## 3. Verification

- [x] 3.1 Run focused backend and frontend tests, OpenSpec strict validation, and git diff check; do not create a business task. `[Req-ID]: REQ-SMS-001, REQ-SMS-002`

## 4. Per-material review authorization

- [x] 4.1 Add RED coverage proving review calls authorize only their current frozen document while the other structured calls retain their existing scope. `[Req-ID]: REQ-SMS-003`
- [x] 4.2 Derive a non-mutating single-document review scope from the frozen task snapshot and serialize it without coupling `material_key` to a document ID. `[Req-ID]: REQ-SMS-003`
- [x] 4.3 Run focused coordinator/scope/WireMock regression tests and strict OpenSpec validation. `[Req-ID]: REQ-SMS-003`
