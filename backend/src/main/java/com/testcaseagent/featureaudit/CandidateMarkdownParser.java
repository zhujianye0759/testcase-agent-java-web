package com.testcaseagent.featureaudit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Strict, scan-stage-only parser for the fixed two-table reconciliation response. */
final class CandidateMarkdownParser {
    private static final String AUDIT_HEADING = "## 需求与功能清单审查发现";
    private static final List<String> AUDIT_HEADER = List.of("序号", "对象/功能点", "问题分类", "证据对照");
    private static final String TEST_CASE_HEADING = "## 测试用例";
    private static final List<String> TEST_CASE_HEADER = List.of("用例名称", "功能模块", "前提约束", "执行步骤", "预期结果", "对应需求内容");
    private static final Pattern RAW_HTML_TAG = Pattern.compile("(?i)<(?!br\\s*/?>)[a-z/][^>]*>");
    private static final Pattern COORDINATE_TOKEN = Pattern.compile("^(documentId|unitId)=([^;\\s=]+)$");

    List<AuditRow> parse(String markdown) {
        if (markdown == null || markdown.isBlank()) throw invalid("two Markdown tables");
        String trimmed = markdown.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || markdown.contains("```")) {
            throw invalid("Markdown tables instead of JSON or code fences");
        }
        if (RAW_HTML_TAG.matcher(markdown).find()) throw invalid("only <br> HTML in table cells");

        List<String> lines = Arrays.asList(markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        int index = requireHeading(lines, 0, AUDIT_HEADING);
        index = skipBlanks(lines, index);
        requireHeader(lines, index, AUDIT_HEADER);
        index = skipBlanks(lines, index + 1);
        requireSeparator(lines, index, AUDIT_HEADER.size());
        index++;

        List<AuditRow> rows = new ArrayList<>();
        while (index < lines.size() && !lines.get(index).isBlank() && !lines.get(index).equals(TEST_CASE_HEADING)) {
            List<String> cells = parseDataRow(lines.get(index), AUDIT_HEADER.size());
            rows.add(new AuditRow(positiveSequence(cells.get(0)), nonBlank(cells.get(1), "对象/功能点"),
                    nonBlank(cells.get(2), "问题分类"), nonBlank(cells.get(3), "证据对照"), rows.size() + 1));
            index++;
        }
        index = skipBlanks(lines, index);
        index = requireHeading(lines, index, TEST_CASE_HEADING);
        index = skipBlanks(lines, index);
        requireHeader(lines, index, TEST_CASE_HEADER);
        index = skipBlanks(lines, index + 1);
        requireSeparator(lines, index, TEST_CASE_HEADER.size());
        index++;
        index = skipBlanks(lines, index);
        if (index != lines.size()) throw invalid("zero test-case rows and no trailing content");
        return List.copyOf(rows);
    }

    private static int requireHeading(List<String> lines, int index, String expected) {
        if (index >= lines.size() || !lines.get(index).equals(expected)) throw invalid("heading " + expected);
        return index + 1;
    }

    private static void requireHeader(List<String> lines, int index, List<String> expected) {
        if (index >= lines.size() || !parseDataRow(lines.get(index), expected.size()).equals(expected)) {
            throw invalid("exact table header");
        }
    }

    private static void requireSeparator(List<String> lines, int index, int columns) {
        if (index >= lines.size()) throw invalid("table separator");
        List<String> cells = parseDataRow(lines.get(index), columns);
        if (!cells.stream().allMatch(cell -> cell.matches(":?-{3,}:?"))) throw invalid("table separator");
    }

    private static List<String> parseDataRow(String line, int columns) {
        if (line == null || !line.startsWith("|") || !line.endsWith("|")) throw invalid("Markdown table row");
        String[] rawCells = line.substring(1, line.length() - 1).split("\\|", -1);
        if (rawCells.length != columns) throw invalid("exact table column count");
        return Arrays.stream(rawCells).map(String::trim).toList();
    }

    private static int skipBlanks(List<String> lines, int index) {
        while (index < lines.size() && lines.get(index).isBlank()) index++;
        return index;
    }

    private static int positiveSequence(String value) {
        try {
            int sequence = Integer.parseInt(value);
            if (sequence <= 0) throw invalid("positive visible sequence");
            return sequence;
        } catch (NumberFormatException exception) {
            throw invalid("numeric visible sequence");
        }
    }

    private static String nonBlank(String value, String label) {
        if (value == null || value.isBlank()) throw invalid("non-blank " + label);
        return value;
    }

    static IllegalArgumentException invalid(String expectation) {
        return new IllegalArgumentException("Expected strict scan Markdown with " + expectation);
    }

    /** Requires exactly one semicolon-delimited token for each material identity coordinate. */
    static void requireExactEvidenceCoordinates(String evidenceText, MaterialInventoryUnit unit) {
        String documentId = null;
        String unitId = null;
        for (String segment : evidenceText.split(";", -1)) {
            String token = segment.trim();
            if (!(token.contains("documentId") || token.contains("unitId"))) continue;
            java.util.regex.Matcher matcher = COORDINATE_TOKEN.matcher(token);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Candidate evidence has duplicate or malformed coordinate tokens");
            }
            if ("documentId".equals(matcher.group(1))) {
                if (documentId != null) {
                    throw new IllegalArgumentException("Candidate evidence has duplicate or malformed coordinate tokens");
                }
                documentId = matcher.group(2);
            } else {
                if (unitId != null) {
                    throw new IllegalArgumentException("Candidate evidence has duplicate or malformed coordinate tokens");
                }
                unitId = matcher.group(2);
            }
        }
        if (!unit.documentId().equals(documentId) || !unit.unitId().equals(unitId)) {
            throw new IllegalArgumentException("Candidate evidence must bind the exact documentId and unitId");
        }
    }

    record AuditRow(int sequence, String featureText, String category, String evidenceText, int rowPosition) { }
}
