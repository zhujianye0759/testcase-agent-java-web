package com.testcaseagent.export;

import java.util.List;

/** One already-validated structured testcase business projection. [Req-ID]: REQ-SGD-003, REQ-SGD-004 */
public record StructuredTestCaseRow(
        String sourceId, String title, String functionName, Status status, List<String> preconditions,
        List<StructuredTestStep> steps, List<String> requirementSummaries, List<String> missingInformation,
        boolean validated) {

    /** Formal coverage status displayed exactly as stored by Java. */
    public enum Status { FORMAL, PENDING_CONFIRMATION }

    /** Copies nested collections before export sorting and rendering. */
    public StructuredTestCaseRow {
        if (preconditions == null || steps == null || requirementSummaries == null || missingInformation == null) {
            throw new IllegalArgumentException("Structured testcase collections must not be null");
        }
        preconditions = List.copyOf(preconditions);
        steps = List.copyOf(steps);
        requirementSummaries = List.copyOf(requirementSummaries);
        missingInformation = List.copyOf(missingInformation);
    }
}
