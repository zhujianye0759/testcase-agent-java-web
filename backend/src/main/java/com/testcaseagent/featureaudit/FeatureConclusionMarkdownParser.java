package com.testcaseagent.featureaudit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strictly accepts the final two-table reconciliation response and proves every retained candidate is covered once.
 *
 * [Req-ID]: REQ-BFA-003, REQ-BFA-004
 */
final class FeatureConclusionMarkdownParser {
    private static final String CONCLUSION_HEADING = "## 需求与功能清单审查发现";
    private static final List<String> CONCLUSION_HEADER = List.of("序号", "对象/功能点", "问题分类", "证据对照");
    private static final String TEST_CASE_HEADING = "## 测试用例";
    private static final List<String> TEST_CASE_HEADER = List.of("用例名称", "功能模块", "前提约束", "执行步骤", "预期结果", "对应需求内容");
    private static final Pattern CANDIDATE_IDS = Pattern.compile("(?:^|;)\\s*candidateIds=([^;\\s]+)");
    private static final Map<String, FeatureReviewConclusionType> TYPES = Map.ofEntries(
            Map.entry("未发现问题", FeatureReviewConclusionType.MATCHED),
            Map.entry("匹配", FeatureReviewConclusionType.MATCHED),
            Map.entry("功能清单遗漏", FeatureReviewConclusionType.FUNCTION_LIST_MISSING),
            Map.entry("需求未覆盖该功能点", FeatureReviewConclusionType.REQUIREMENT_MISSING),
            Map.entry("冲突", FeatureReviewConclusionType.CONFLICT),
            Map.entry("拆分", FeatureReviewConclusionType.SPLIT),
            Map.entry("合并", FeatureReviewConclusionType.MERGE),
            Map.entry("重复", FeatureReviewConclusionType.DUPLICATE),
            Map.entry("证据不足", FeatureReviewConclusionType.INSUFFICIENT_EVIDENCE));

    List<FeatureReviewConclusion> parse(String markdown, Set<String> retainedCandidateIds) {
        if (markdown == null || markdown.isBlank()) throw invalid("two Markdown tables");
        if (retainedCandidateIds == null) throw new IllegalArgumentException("retainedCandidateIds must not be null");
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.contains("```") || normalized.trim().startsWith("{") || normalized.trim().startsWith("[")) {
            throw invalid("Markdown tables instead of JSON or code fences");
        }
        List<String> lines = Arrays.asList(normalized.split("\n", -1));
        int index = heading(lines, 0, CONCLUSION_HEADING);
        index = blanks(lines, index);
        header(lines, index, CONCLUSION_HEADER);
        index = blanks(lines, index + 1);
        separator(lines, index, CONCLUSION_HEADER.size());
        index++;

        List<FeatureReviewConclusion> conclusions = new ArrayList<>();
        while (index < lines.size() && !lines.get(index).isBlank() && !TEST_CASE_HEADING.equals(lines.get(index))) {
            List<String> cells = row(lines.get(index), CONCLUSION_HEADER.size());
            int sequence = positive(cells.get(0));
            FeatureReviewConclusionType type = TYPES.get(cells.get(2));
            if (type == null) throw invalid("known terminal conclusion type");
            String explanation = nonBlank(cells.get(1), "对象/功能点");
            String evidence = nonBlank(cells.get(3), "证据对照");
            List<String> candidateIds = candidateIds(evidence);
            conclusions.add(new FeatureReviewConclusion(conclusionId(sequence, type, explanation, evidence, candidateIds),
                    sequence, type, explanation, evidence, candidateIds));
            index++;
        }
        index = blanks(lines, index);
        index = heading(lines, index, TEST_CASE_HEADING);
        index = blanks(lines, index);
        header(lines, index, TEST_CASE_HEADER);
        index = blanks(lines, index + 1);
        separator(lines, index, TEST_CASE_HEADER.size());
        index = blanks(lines, index + 1);
        if (index != lines.size()) throw invalid("zero test-case rows and no trailing content");
        validateCoverage(conclusions, retainedCandidateIds);
        return List.copyOf(conclusions);
    }

    private static void validateCoverage(List<FeatureReviewConclusion> conclusions, Set<String> retained) {
        Set<Integer> sequences = new LinkedHashSet<>();
        Set<String> covered = new LinkedHashSet<>();
        for (FeatureReviewConclusion conclusion : conclusions) {
            if (!sequences.add(conclusion.sequence())) throw invalid("distinct conclusion sequences");
            for (String candidateId : conclusion.candidateIds()) {
                if (!retained.contains(candidateId)) throw invalid("only retained candidateIds");
                if (!covered.add(candidateId)) throw invalid("each candidateId exactly once");
            }
        }
        if (!covered.equals(retained)) throw invalid("every retained candidateId exactly once");
    }

    private static List<String> candidateIds(String evidence) {
        Matcher matcher = CANDIDATE_IDS.matcher(evidence);
        if (!matcher.find()) throw invalid("candidateIds= token in each conclusion evidence");
        String raw = matcher.group(1);
        if (matcher.find()) throw invalid("exactly one candidateIds= token in each conclusion evidence");
        List<String> ids = Arrays.asList(raw.split(",", -1));
        if (ids.isEmpty() || ids.stream().anyMatch(id -> id.isBlank()) || ids.stream().distinct().count() != ids.size()) {
            throw invalid("non-empty distinct candidateIds");
        }
        return List.copyOf(ids);
    }

    private static String conclusionId(int sequence, FeatureReviewConclusionType type, String explanation,
            String evidence, List<String> candidateIds) {
        try {
            String identity = sequence + "\u001f" + type + "\u001f" + explanation + "\u001f" + evidence + "\u001f"
                    + String.join(",", candidateIds);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static int heading(List<String> lines, int index, String expected) {
        if (index >= lines.size() || !expected.equals(lines.get(index))) throw invalid("heading " + expected);
        return index + 1;
    }

    private static void header(List<String> lines, int index, List<String> expected) {
        if (index >= lines.size() || !row(lines.get(index), expected.size()).equals(expected)) {
            throw invalid("exact table header");
        }
    }

    private static void separator(List<String> lines, int index, int columns) {
        if (index >= lines.size() || !row(lines.get(index), columns).stream().allMatch(cell -> cell.matches(":?-{3,}:?"))) {
            throw invalid("table separator");
        }
    }

    private static List<String> row(String line, int columns) {
        if (line == null || !line.startsWith("|") || !line.endsWith("|")) throw invalid("Markdown table row");
        String[] values = line.substring(1, line.length() - 1).split("\\|", -1);
        if (values.length != columns) throw invalid("exact table column count");
        return Arrays.stream(values).map(String::trim).toList();
    }

    private static int blanks(List<String> lines, int index) {
        while (index < lines.size() && lines.get(index).isBlank()) index++;
        return index;
    }

    private static int positive(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) throw invalid("positive conclusion sequence");
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid("numeric conclusion sequence");
        }
    }

    private static String nonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw invalid("non-blank " + field);
        return value;
    }

    private static IllegalArgumentException invalid(String expectation) {
        return new IllegalArgumentException("Expected strict final reconciliation Markdown with " + expectation);
    }
}
