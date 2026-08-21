package com.testcaseagent.validation;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Separates reader-facing business narration from internal structured identities and diagnostic placeholders.
 * Binding fields keep their machine keys; callers apply this policy only to text rendered to people.
 *
 * [Req-ID]: REQ-STG-001, REQ-SGD-001, REQ-SGD-003
 */
public final class ReaderFacingTextPolicy {
    private static final Pattern INTERNAL_KEY = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}_])(?<kind>fli|fact|finding|reconciliation|point|case)-[0-9a-f]{16,64}(?![0-9a-f])");
    private static final Pattern INTERNAL_PLACEHOLDER = Pattern.compile(
            "(?i)<(?:redacted|external-url|internal-path|internal-stack)>");

    private ReaderFacingTextPolicy() {
    }

    /** Fails closed when model-produced narration exposes an internal key or diagnostic placeholder. */
    public static String requireSafe(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (INTERNAL_KEY.matcher(value).find() || INTERNAL_PLACEHOLDER.matcher(value).find()) {
            throw new IllegalArgumentException(field + " contains an internal machine identity or placeholder");
        }
        return value;
    }

    /** Applies {@link #requireSafe(String, String)} to every reader-facing list item. */
    public static List<String> requireSafeItems(List<String> values, String field) {
        if (values == null) throw new IllegalArgumentException(field + " must not be null");
        for (String value : values) requireSafe(value, field);
        return List.copyOf(values);
    }

    /** Converts already-persisted legacy narration to reader-safe wording without exposing the original identity. */
    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        Matcher matcher = INTERNAL_KEY.matcher(value);
        StringBuffer safe = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(safe, Matcher.quoteReplacement(replacement(matcher.group("kind"))));
        }
        matcher.appendTail(safe);
        return safe.toString()
                .replace("<redacted>", "敏感信息已隐藏")
                .replace("<external-url>", "外部链接已隐藏")
                .replace("<internal-path>", "内部路径已隐藏")
                .replace("<internal-stack>", "内部诊断信息已隐藏");
    }

    private static String replacement(String kind) {
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "fli" -> "对应功能清单项";
            case "fact" -> "对应需求事实";
            case "finding" -> "对应审查发现";
            case "reconciliation" -> "对应核对记录";
            case "point" -> "对应测试点";
            case "case" -> "对应测试用例";
            default -> "内部标识已隐藏";
        };
    }
}
