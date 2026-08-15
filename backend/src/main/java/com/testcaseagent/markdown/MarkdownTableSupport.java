package com.testcaseagent.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Internal strict Markdown table mechanics shared by the two public parser seams. */
final class MarkdownTableSupport {
    private static final Pattern BREAK_TAG = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern IMAGE_TAG = Pattern.compile("(?i)<img\\b");

    private MarkdownTableSupport() {
    }

    static List<String> contractLines(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw invalid("a nonblank table response");
        }
        if (markdown.contains("```") || markdown.strip().startsWith("{") || markdown.strip().startsWith("[")) {
            throw invalid("Markdown tables instead of JSON or a fenced payload");
        }
        if (markdown.contains("![") || IMAGE_TAG.matcher(markdown).find()) {
            throw invalid("text-only table cells without images");
        }

        List<String> lines = new ArrayList<>();
        for (String line : markdown.split("\\R", -1)) {
            lines.add(line.strip());
        }
        return lines;
    }

    static int skipBlankLines(List<String> lines, int index) {
        while (index < lines.size() && lines.get(index).isBlank()) {
            index++;
        }
        return index;
    }

    /**
     * Allows an explicitly blank-terminated final table to be followed only by ordinary model notes.
     * Structural Markdown after that boundary would make result ownership ambiguous and remains invalid.
     */
    static void requireNonStructuralTrailingNotes(List<String> lines, int index) {
        for (int noteIndex = skipBlankLines(lines, index); noteIndex < lines.size(); noteIndex++) {
            String line = lines.get(noteIndex);
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("#") || line.startsWith("|") || line.endsWith("|")
                    || line.startsWith("{") || line.startsWith("[")) {
                throw invalid("no heading or table content after the final table");
            }
        }
    }

    static void requireHeading(List<String> lines, int index, String expected) {
        if (index >= lines.size() || !lines.get(index).equals(expected)) {
            throw invalid("heading '" + expected + "' in the required order");
        }
    }

    static void requireHeader(List<String> lines, int index, List<String> expected) {
        if (index >= lines.size() || !parseRow(lines.get(index)).equals(expected)) {
            throw invalid("exact header '" + String.join(" | ", expected) + "'");
        }
    }

    static void requireSeparator(List<String> lines, int index, int columnCount) {
        if (index >= lines.size()) {
            throw invalid("a Markdown separator row");
        }
        List<String> cells = parseRow(lines.get(index));
        if (cells.size() != columnCount || cells.stream().anyMatch(cell -> !isSeparatorCell(cell))) {
            throw invalid("a valid " + columnCount + "-column Markdown separator row");
        }
    }

    static List<String> parseDataRow(String line, int columnCount) {
        List<String> cells = parseRow(line);
        if (cells.size() != columnCount) {
            throw invalid("a " + columnCount + "-column table row");
        }
        if (cells.stream().allMatch(MarkdownTableSupport::isSeparatorCell)) {
            throw invalid("a data row rather than a Markdown separator row");
        }
        return cells;
    }

    static String requireNonBlank(String value, String column) {
        if (value.isBlank()) {
            throw invalid("a nonblank '" + column + "' cell");
        }
        return value;
    }

    static MarkdownContractException invalid(String reason) {
        return new MarkdownContractException("expected " + reason);
    }

    private static List<String> parseRow(String line) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) {
            throw invalid("a pipe-delimited Markdown table row");
        }

        String contents = trimmed.substring(1, trimmed.length() - 1);
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        for (int index = 0; index < contents.length(); index++) {
            char character = contents.charAt(index);
            if (character == '\\' && index + 1 < contents.length() && contents.charAt(index + 1) == '|') {
                cell.append('|');
                index++;
            } else if (character == '|') {
                cells.add(normalizeCell(cell.toString()));
                cell.setLength(0);
            } else {
                cell.append(character);
            }
        }
        cells.add(normalizeCell(cell.toString()));
        return List.copyOf(cells);
    }

    private static String normalizeCell(String value) {
        return BREAK_TAG.matcher(value).replaceAll("\n").strip();
    }

    private static boolean isSeparatorCell(String cell) {
        int firstHyphen = cell.indexOf('-');
        int lastHyphen = cell.lastIndexOf('-');
        return firstHyphen >= 0
                && lastHyphen - firstHyphen + 1 >= 3
                && cell.substring(0, firstHyphen).matches(":?")
                && cell.substring(lastHyphen + 1).matches(":?")
                && cell.substring(firstHyphen, lastHyphen + 1).chars().allMatch(character -> character == '-');
    }
}
