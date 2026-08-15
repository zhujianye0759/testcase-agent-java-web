package com.testcaseagent.markdown;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * [Req-ID]: REQ-ANA-001
 *
 * <p>Parses the deliberately small ALL-mode discovery contract before the application creates its
 * own feature identities. Strict validation prevents an ambiguous model response from changing batch
 * ownership.</p>
 */
public final class MarkdownFeatureListParser {
    private static final String HEADING = "## 功能点清单";
    private static final List<String> HEADER = List.of("序号", "功能点");

    /** Parses one complete feature list and returns its immutable, ordered discovery rows. */
    public List<MarkdownFeatureRow> parse(String markdown) {
        List<String> lines = MarkdownTableSupport.contractLines(markdown);
        int index = 0;
        MarkdownTableSupport.requireHeading(lines, index, HEADING);
        index = MarkdownTableSupport.skipBlankLines(lines, index + 1);
        MarkdownTableSupport.requireHeader(lines, index, HEADER);
        index = MarkdownTableSupport.skipBlankLines(lines, index + 1);
        MarkdownTableSupport.requireSeparator(lines, index, HEADER.size());
        index++;

        List<MarkdownFeatureRow> rows = new ArrayList<>();
        Set<Integer> sequences = new HashSet<>();
        while (index < lines.size() && !lines.get(index).isBlank()) {
            List<String> cells = MarkdownTableSupport.parseDataRow(lines.get(index), HEADER.size());
            int sequence = parsePositiveSequence(cells.get(0));
            if (!sequences.add(sequence)) {
                throw MarkdownTableSupport.invalid("a unique discovery sequence");
            }
            rows.add(new MarkdownFeatureRow(sequence, MarkdownTableSupport.requireNonBlank(cells.get(1), "功能点")));
            index++;
        }
        if (rows.isEmpty()) {
            throw MarkdownTableSupport.invalid("at least one feature discovery row");
        }
        MarkdownTableSupport.requireNonStructuralTrailingNotes(lines, index);
        return List.copyOf(rows);
    }

    private int parsePositiveSequence(String value) {
        try {
            int sequence = Integer.parseInt(value);
            if (sequence <= 0) {
                throw MarkdownTableSupport.invalid("a positive discovery sequence");
            }
            return sequence;
        } catch (NumberFormatException exception) {
            throw MarkdownTableSupport.invalid("a numeric discovery sequence");
        }
    }
}
