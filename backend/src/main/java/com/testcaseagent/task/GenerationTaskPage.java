package com.testcaseagent.task;

import java.util.List;

/**
 * Stable offset page for the shared task list.
 *
 * [Req-ID]: REQ-TSK-002
 */
public record GenerationTaskPage(
        List<GenerationTaskListItem> items,
        int page,
        int size,
        long totalItems) {
}
