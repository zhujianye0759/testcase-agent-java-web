package com.testcaseagent.featureaudit;

/**
 * Safe terminal observation for one final-reconciliation page that exhausted its fixed retry budget.
 *
 * <p>The original model/validator text is deliberately mapped before this object is created and is never retained as
 * a message, cause, field, or suppressed exception. This keeps the task snapshot safe while the separate controlled
 * diagnostic logger may retain the raw business context after credential redaction.</p>
 *
 * [Req-ID]: REQ-CWR-004
 */
public final class FinalReconciliationPageException extends RuntimeException {
    private final int pageNumber;
    private final int totalPages;
    private final Integer targetNumber;
    private final Integer targetCount;
    private final int attempts;
    private final Category category;
    private final boolean approvedIsolatedRecheckCategoriesOnly;

    private FinalReconciliationPageException(
            int pageNumber, int totalPages, Integer targetNumber, Integer targetCount, int attempts, Category category,
            boolean approvedIsolatedRecheckCategoriesOnly) {
        super(safeSummary(pageNumber, totalPages, targetNumber, targetCount, attempts, category), null, false, true);
        this.pageNumber = pageNumber;
        this.totalPages = totalPages;
        this.targetNumber = targetNumber;
        this.targetCount = targetCount;
        this.attempts = attempts;
        this.category = category;
        this.approvedIsolatedRecheckCategoriesOnly = approvedIsolatedRecheckCategoriesOnly;
    }

    /** Maps only known fixed validator messages into a safe, closed category. */
    public static FinalReconciliationPageException exhausted(
            int pageNumber, int totalPages, int attempts, String fixedContractFailure) {
        return exhausted(pageNumber, totalPages, attempts, fixedContractFailure,
                Category.isApprovedIsolatedRecheckCategory(Category.fromFixedContractFailure(fixedContractFailure)));
    }

    /** Creates a safe page observation and records whether every exhausted response used an approved isolated-recheck category. */
    public static FinalReconciliationPageException exhausted(
            int pageNumber, int totalPages, int attempts, String fixedContractFailure,
            boolean approvedIsolatedRecheckCategoriesOnly) {
        if (pageNumber < 1 || totalPages < pageNumber || attempts < 1) {
            throw new IllegalArgumentException("Invalid final reconciliation page observation");
        }
        return new FinalReconciliationPageException(
                pageNumber, totalPages, null, null, attempts, Category.fromFixedContractFailure(fixedContractFailure),
                approvedIsolatedRecheckCategoriesOnly);
    }

    /** Creates a safe observation for one failed single-target compensation call. */
    public static FinalReconciliationPageException singletonExhausted(
            int pageNumber, int totalPages, int targetNumber, int targetCount, int attempts, Category category) {
        if (pageNumber < 1 || totalPages < pageNumber || targetNumber < 1 || targetCount < targetNumber || attempts < 1) {
            throw new IllegalArgumentException("Invalid final reconciliation singleton observation");
        }
        return new FinalReconciliationPageException(
                pageNumber, totalPages, targetNumber, targetCount, attempts, category,
                Category.isApprovedIsolatedRecheckCategory(category));
    }

    public int pageNumber() { return pageNumber; }
    public int totalPages() { return totalPages; }
    public Integer targetNumber() { return targetNumber; }
    public Integer targetCount() { return targetCount; }
    public int attempts() { return attempts; }
    public Category category() { return category; }
    public boolean approvedIsolatedRecheckCategoriesOnly() { return approvedIsolatedRecheckCategoriesOnly; }
    public String safeSummary() { return getMessage(); }

    private static String safeSummary(
            int pageNumber, int totalPages, Integer targetNumber, Integer targetCount, int attempts, Category category) {
        String target = targetNumber == null ? "" : "第 " + targetNumber + "/" + targetCount + " 个目标";
        return "最终双向核对第 " + pageNumber + "/" + totalPages + " 个功能审核批次" + target + "连续 " + attempts + " 次未通过：" + category.label();
    }

    /** Closed, reader-safe categories for final-reconciliation contract failures. */
    public enum Category {
        STRICT_MARKDOWN("Markdown 格式不符合约定"),
        MISSING_TARGET("目标覆盖不完整"),
        REPRESENTATIVE_BINDING("代表项证据绑定不正确"),
        BUSINESS_PATH_STRUCTURE("业务路径结构不符合约定"),
        ANCHOR_CONFLICT("锚点关系不一致"),
        NORMALIZED_PATH_CONFLICT("同一业务路径结论不一致"),
        UNKNOWN_CONTRACT("固定合同未满足");

        private final String label;

        Category(String label) { this.label = label; }
        String label() { return label; }

        /** Limits isolated rechecks to the user-approved, fixed validation categories. */
        static boolean isApprovedIsolatedRecheckCategory(Category category) {
            return category == MISSING_TARGET || category == REPRESENTATIVE_BINDING || category == BUSINESS_PATH_STRUCTURE;
        }

        static Category fromFixedContractFailure(String failure) {
            if (failure == null) return UNKNOWN_CONTRACT;
            if (failure.startsWith("Expected strict final reconciliation Markdown with every retained candidateId exactly once")
                    || failure.startsWith("Expected strict final reconciliation Markdown with only retained candidateIds")
                    || failure.startsWith("Expected strict final reconciliation Markdown with candidateIds= token")
                    || failure.equals("Each page representative candidateId must have exactly one conclusion")
                    || failure.equals("Each page target candidateId must have exactly one conclusion")
                    || failure.equals("Each page conclusion must contain exactly one target candidateId")
                    || failure.equals("Every retained candidate requires exactly one page conclusion")) {
                return MISSING_TARGET;
            }
            if (failure.equals("Grouped representative evidence must contain one exact binding token")
                    || failure.equals("Grouped representative evidence must bind its exact documentId and unitId")
                    || failure.equals("Grouped representative evidence must bind its exact candidateId")
                    || failure.equals("Conclusion evidence must retain each candidate documentId and unitId")) {
                return REPRESENTATIVE_BINDING;
            }
            if (failure.equals("Only SPLIT conclusions may contain multiple business paths")
                    || failure.equals("SPLIT conclusions require explicit <br> separated business paths")
                    || failure.equals("Business path must not be blank")
                    || failure.equals("Business paths must be plain text")
                    || failure.equals("SPLIT conclusions require distinct business paths")
                    || failure.equals("Each grouped representative must retain its normalized business path")) {
                return BUSINESS_PATH_STRUCTURE;
            }
            if (failure.equals("Each normalized business path must retain one groupAnchorId and conclusion type")) {
                return NORMALIZED_PATH_CONFLICT;
            }
            if (failure.equals("Each groupAnchorId must reference a retained global candidate")
                    || failure.equals("Each groupAnchorId must not point after its target candidate")
                    || failure.equals("Each groupAnchorId must reference a self-anchored global candidate")
                    || failure.equals("Each conclusion must contain one valid groupAnchorId")
                    || failure.equals("Every anchored group must have one exact type and business path")
                    || failure.equals("Every anchored group must close on a global candidate")) {
                return ANCHOR_CONFLICT;
            }
            if (failure.startsWith("Expected strict final reconciliation Markdown with")) return STRICT_MARKDOWN;
            return UNKNOWN_CONTRACT;
        }
    }
}
