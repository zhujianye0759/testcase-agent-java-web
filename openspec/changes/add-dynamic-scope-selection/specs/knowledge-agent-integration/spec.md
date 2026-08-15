## MODIFIED Requirements

### Requirement: [REQ-KAG-006] Keep integration credentials and coordinates server-side
The system SHALL keep API keys, external-subject configuration, generation-agent identity, example scope, and raw KEE coordinates on the Java server. It SHALL use only the required configured API-key capabilities and grants. Browser catalog responses SHALL contain business labels, counts, and opaque application keys but no credential, KEE UUID, tenant secret, model secret, or internal resource URL.

#### Scenario: Browser loads task configuration
- **WHEN** the frontend requests selectable task configuration
- **THEN** the response contains enough business-readable hierarchy to select material without exposing any server credential or raw KEE coordinate

## ADDED Requirements

### Requirement: [REQ-KAG-009] Use supported KEE read contracts for dynamic scope discovery
The Java adapter SHALL discover requirement choices only through supported KEE API-key GET endpoints for knowledge-base selector options, scope containers, system versions, and paged knowledge documents. It SHALL validate success envelopes and required fields, ignore unknown response properties, follow pagination, and classify malformed or failed catalog responses as read failures without invoking write routes.

#### Scenario: KEE adds an unrelated response field
- **WHEN** a supported catalog response contains unknown JSON properties
- **THEN** discovery continues using the required known fields

#### Scenario: KEE returns an unsuccessful envelope
- **WHEN** a catalog endpoint returns an HTTP error, `success=false`, or misses required scope data
- **THEN** catalog discovery fails closed and publishes no partially authorized option
