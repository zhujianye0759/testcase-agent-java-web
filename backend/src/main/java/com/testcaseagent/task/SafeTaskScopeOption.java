package com.testcaseagent.task;

/**
 * Browser DTO intentionally limited to an application-owned opaque ID and label.
 *
 * [Req-ID]: REQ-KAG-006, REQ-SCP-004
 */
public record SafeTaskScopeOption(String id, String label) {
}
