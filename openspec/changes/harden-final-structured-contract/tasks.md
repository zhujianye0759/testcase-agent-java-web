## 1. Contract RED baseline

- [x] 1.1 Add watched-failure tests for one-document parsed-unit preservation and supplemental-material fact isolation.
- [x] 1.2 Add watched-failure tests for reconciliation evidence exact closure, complete input coverage, and insufficient-evidence confirmation.
- [x] 1.3 Add watched-failure tests for ordered testcase expected-results equality and mandatory frozen review finding identity.

## 2. Java acceptance implementation

- [x] 2.1 Preserve and assert immutable parsed-unit review-slice identity at the coordinator/validator seam.
- [x] 2.2 Strengthen reconciliation work-item metadata and validator exact closure rules.
- [x] 2.3 Strengthen testcase expected-results ordering and review-finding mandatory identity validation.
- [x] 2.4 Verify transactional acceptance and same-record detail/Excel projection do not admit rejected results.

## 3. Verification and release preparation

- [x] 3.1 Run focused validators, coordinator, and MySQL/Testcontainers acceptance regressions.
- [x] 3.2 Run affected backend/frontend regression, OpenSpec strict validation, build, diff, and sensitive-content checks. (The full backend run retains only the separately established legacy fixed-2N/JSON-whitespace failures; affected structured tests pass.)
- [ ] 3.3 Review the Java-only diff, commit locally, deploy the existing Java runtime, and run the final GET-only scope gate.
- [ ] 3.4 Create exactly one final structured ALL task only after all preceding gates pass; record API, database, page, and Excel evidence without retrying or creating another task.
