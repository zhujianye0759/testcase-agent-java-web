package com.testcaseagent.task;

/**
 * One durable queue admission bound to a fixed execution slot.
 *
 * [Req-ID]: REQ-TSK-003, REQ-TSK-008
 */
public record TaskExecutionClaim(String taskId, int slotNumber) {
}
