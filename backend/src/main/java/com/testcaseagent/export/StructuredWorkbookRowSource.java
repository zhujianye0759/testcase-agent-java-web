package com.testcaseagent.export;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Supplies validated workbook rows without requiring the caller to materialize a task-sized list.
 *
 * <p>Production implementations must emit each collection in strictly increasing {@code sourceId} order and must
 * emit exactly the declared number of rows. That order lets the exporter detect duplicates with constant memory;
 * the exporter also checks the counts and keeps only its fixed SXSSF row window in memory.</p>
 *
 * [Req-ID]: REQ-TGV2-009
 */
public interface StructuredWorkbookRowSource {
    /** Returns the owning task identity; it is never written into a workbook cell. */
    String taskId();

    /** Returns the frozen number of first-sheet rows. */
    long reviewRowCount();

    /** Returns the frozen number of second-sheet formal testcase rows. */
    long testCaseRowCount();

    /** Emits first-sheet rows once in deterministic display order. */
    void forEachReview(Consumer<StructuredReviewRow> consumer);

    /** Emits second-sheet rows once in deterministic display order. */
    void forEachTestCase(Consumer<StructuredTestCaseRow> consumer);

    /** Adapts the historical in-memory request without changing its public contract. */
    static StructuredWorkbookRowSource from(StructuredWorkbookExportRequest request) {
        Objects.requireNonNull(request, "Structured export request is required");
        return new StructuredWorkbookRowSource() {
            @Override public String taskId() { return request.taskId(); }
            @Override public long reviewRowCount() { return request.reviewRows().size(); }
            @Override public long testCaseRowCount() { return request.testCaseRows().size(); }
            @Override public void forEachReview(Consumer<StructuredReviewRow> consumer) {
                request.reviewRows().forEach(consumer);
            }
            @Override public void forEachTestCase(Consumer<StructuredTestCaseRow> consumer) {
                request.testCaseRows().forEach(consumer);
            }
        };
    }
}
