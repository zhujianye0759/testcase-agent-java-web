package com.testcaseagent.markdown;

import java.util.ArrayList;
import java.util.List;

/**
 * [Req-ID]: REQ-KAG-004, REQ-ANA-002, REQ-ANA-003, REQ-ANA-005, REQ-EXP-007
 *
 * <p>Accepts only the approved ordered audit and test-case tables. It fails closed so later persistence
 * and workbook export never need to infer table ownership or handle model-provided images.</p>
 */
public final class MarkdownGenerationResultParser {
    private static final String AUDIT_HEADING = "## 需求与功能清单审查发现";
    private static final List<String> AUDIT_HEADER = List.of("序号", "对象/功能点", "问题分类", "证据对照");
    private static final String TEST_CASE_HEADING = "## 测试用例";
    private static final List<String> TEST_CASE_HEADER = List.of("用例名称", "功能模块", "前提约束", "执行步骤", "预期结果", "对应需求内容");

    /** Parses one completed response into immutable audit and test-case rows. */
    public MarkdownGenerationResult parse(String markdown) {
        List<String> lines = MarkdownTableSupport.contractLines(markdown);
        int index = 0;
        MarkdownTableSupport.requireHeading(lines, index, AUDIT_HEADING);
        index = MarkdownTableSupport.skipBlankLines(lines, index + 1);
        MarkdownTableSupport.requireHeader(lines, index, AUDIT_HEADER);
        index = MarkdownTableSupport.skipBlankLines(lines, index + 1);
        MarkdownTableSupport.requireSeparator(lines, index, AUDIT_HEADER.size());

        index++;
        List<MarkdownAuditRow> auditRows = new ArrayList<>();
        while (index < lines.size() && !lines.get(index).isBlank() && !lines.get(index).equals(TEST_CASE_HEADING)) {
            List<String> cells = MarkdownTableSupport.parseDataRow(lines.get(index), AUDIT_HEADER.size());
            auditRows.add(new MarkdownAuditRow(
                    parsePositiveSequence(cells.get(0), "audit"),
                    MarkdownTableSupport.requireNonBlank(cells.get(1), "对象/功能点"),
                    MarkdownTableSupport.requireNonBlank(cells.get(2), "问题分类"),
                    MarkdownTableSupport.requireNonBlank(cells.get(3), "证据对照")));
            index++;
        }

        index = MarkdownTableSupport.skipBlankLines(lines, index);
        MarkdownTableSupport.requireHeading(lines, index, TEST_CASE_HEADING);
        index = MarkdownTableSupport.skipBlankLines(lines, index + 1);
        MarkdownTableSupport.requireHeader(lines, index, TEST_CASE_HEADER);
        index = MarkdownTableSupport.skipBlankLines(lines, index + 1);
        MarkdownTableSupport.requireSeparator(lines, index, TEST_CASE_HEADER.size());
        index++;

        List<MarkdownTestCaseRow> testCaseRows = new ArrayList<>();
        while (index < lines.size() && !lines.get(index).isBlank()) {
            List<String> cells = MarkdownTableSupport.parseDataRow(lines.get(index++), TEST_CASE_HEADER.size());
            testCaseRows.add(new MarkdownTestCaseRow(
                    MarkdownTableSupport.requireNonBlank(cells.get(0), "用例名称"),
                    MarkdownTableSupport.requireNonBlank(cells.get(1), "功能模块"),
                    MarkdownTableSupport.requireNonBlank(cells.get(2), "前提约束"),
                    MarkdownTableSupport.requireNonBlank(cells.get(3), "执行步骤"),
                    MarkdownTableSupport.requireNonBlank(cells.get(4), "预期结果"),
                    MarkdownTableSupport.requireNonBlank(cells.get(5), "对应需求内容")));
        }
        if (testCaseRows.isEmpty()) {
            throw MarkdownTableSupport.invalid("at least one test-case row");
        }
        MarkdownTableSupport.requireNonStructuralTrailingNotes(lines, index);
        return new MarkdownGenerationResult(markdown, auditRows, testCaseRows);
    }

    private int parsePositiveSequence(String value, String rowKind) {
        try {
            int sequence = Integer.parseInt(value);
            if (sequence <= 0) {
                throw MarkdownTableSupport.invalid("a positive " + rowKind + " sequence");
            }
            return sequence;
        } catch (NumberFormatException exception) {
            throw MarkdownTableSupport.invalid("a numeric " + rowKind + " sequence");
        }
    }
}
