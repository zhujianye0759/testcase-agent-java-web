package com.testcaseagent.task;

/**
 * Bounded reader query for the three potentially large V2 task-detail collections.
 *
 * <p>The same page size keeps the public API small while the independent page indexes let a reader advance
 * feedback, test points and testcases without loading any full task-owned collection.</p>
 *
 * [Req-ID]: REQ-TGV2-009
 */
public record StructuredDetailQuery(int feedbackPage, int testPointPage, int testcasePage, int size) {

    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 20;
    /** One test-point result may legally approach 4 MiB, so only one is assembled in a response at a time. */
    public static final int TEST_POINT_PAGE_SIZE = 1;

    public StructuredDetailQuery {
        if (feedbackPage < 0 || testPointPage < 0 || testcasePage < 0) {
            throw new IllegalArgumentException("Structured detail page indexes must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("Structured detail page size must be between 1 and " + MAX_SIZE);
        }
    }

    public static StructuredDetailQuery defaults() {
        return new StructuredDetailQuery(0, 0, 0, DEFAULT_SIZE);
    }

    /** Backward-compatible server-side constructor for callers that do not yet select an inner testcase page. */
    public StructuredDetailQuery(int feedbackPage, int testPointPage, int size) {
        this(feedbackPage, testPointPage, 0, size);
    }

    long feedbackOffset() {
        return Math.multiplyExact((long) feedbackPage, size);
    }

    long testPointOffset() {
        return Math.multiplyExact((long) testPointPage, TEST_POINT_PAGE_SIZE);
    }

    int testPointSize() {
        return TEST_POINT_PAGE_SIZE;
    }

    long testcaseOffset() {
        return Math.multiplyExact((long) testcasePage, TEST_POINT_PAGE_SIZE);
    }

    int testcaseSize() {
        return TEST_POINT_PAGE_SIZE;
    }
}
