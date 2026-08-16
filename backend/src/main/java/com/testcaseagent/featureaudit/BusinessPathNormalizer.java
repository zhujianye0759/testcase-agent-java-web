package com.testcaseagent.featureaudit;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared stable business-path identity used by reconciliation and freeze gates. */
final class BusinessPathNormalizer {
    private BusinessPathNormalizer() { }

    static String normalize(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Parses and validates the literal business-path structure shared by page reconciliation and freezing.
     *
     * [Req-ID]: REQ-BFA-003, REQ-BFA-005, REQ-BFA-007
     */
    static List<String> parseAndValidate(FeatureReviewConclusionType type, String explanation) {
        if (type != FeatureReviewConclusionType.SPLIT) {
            if (explanation.contains("<br>")) {
                throw new IllegalArgumentException("Only SPLIT conclusions may contain multiple business paths");
            }
            return List.of(requiredPath(explanation));
        }
        String[] rawPaths = explanation.split("<br>", -1);
        if (rawPaths.length < 2) {
            throw new IllegalArgumentException("SPLIT conclusions require explicit <br> separated business paths");
        }
        List<String> paths = new ArrayList<>(rawPaths.length);
        Set<String> normalized = new LinkedHashSet<>();
        for (String rawPath : rawPaths) {
            String path = requiredPath(rawPath);
            if (!normalized.add(normalize(path))) {
                throw new IllegalArgumentException("SPLIT conclusions require distinct business paths");
            }
            paths.add(path);
        }
        return List.copyOf(paths);
    }

    private static String requiredPath(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Business path must not be blank");
        String path = value.strip();
        if (path.indexOf('<') >= 0 || path.indexOf('>') >= 0) {
            throw new IllegalArgumentException("Business paths must be plain text");
        }
        return path;
    }
}
