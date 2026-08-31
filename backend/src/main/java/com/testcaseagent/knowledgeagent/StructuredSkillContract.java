package com.testcaseagent.knowledgeagent;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Shared, fail-closed bounds for the frozen structured Skill wire contract. [Req-ID]: REQ-SKI-003, REQ-SKI-004 */
final class StructuredSkillContract {
    static final int MAX_TEXT_BYTES = 16_384;
    static final int MAX_KEY_CHARS = 128;
    static final int MAX_KEY_REFERENCES = 100;

    private StructuredSkillContract() { }

    static String key(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must be non-blank");
        if (value.codePointCount(0, value.length()) > MAX_KEY_CHARS) {
            throw new IllegalArgumentException(name + " exceeds maximum Unicode characters");
        }
        return value;
    }

    static String text(String value, String name) { return text(value, name, MAX_TEXT_BYTES); }

    static String text(String value, String name, int maxBytes) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must be non-blank");
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new IllegalArgumentException(name + " exceeds maximum UTF-8 bytes");
        return value;
    }

    static String optionalText(String value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(name + " exceeds maximum UTF-8 bytes");
        }
        return value;
    }

    /**
     * Preserves KEE persisted parsed-unit content byte-for-byte instead of applying the ordinary
     * 16 KiB reader-text limit. The complete V2 request budget remains the transport guardrail.
     */
    static String parsedUnitContent(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must be non-blank");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new IllegalArgumentException(name + " must contain valid Unicode");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(name + " must contain valid Unicode");
            }
        }
        return value;
    }

    static <T> List<T> list(List<T> value, String name, int min, int max) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.size() < min || value.size() > max) throw new IllegalArgumentException(name + " has an invalid size");
        if (value.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException(name + " must not contain null");
        return List.copyOf(value);
    }

    static List<String> keyReferences(List<String> value, String name) {
        List<String> copy = list(value, name, 0, MAX_KEY_REFERENCES);
        copy.forEach(item -> key(item, name + " item"));
        if (new HashSet<>(copy).size() != copy.size()) throw new IllegalArgumentException(name + " must be unique");
        return copy;
    }

    /**
     * Validates V2 reconciliation references without inheriting the V1 100-key transport cap.
     * The V2 owner window, relation, and catalog are bounded by their dedicated byte budgets instead.
     */
    static List<String> unboundedKeyReferences(List<String> value, String name, boolean requireNonEmpty) {
        List<String> copy = List.copyOf(Objects.requireNonNull(value, name + " must not be null"));
        if (requireNonEmpty && copy.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        if (copy.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException(name + " must not contain null");
        copy.forEach(item -> key(item, name + " item"));
        if (new HashSet<>(copy).size() != copy.size()) throw new IllegalArgumentException(name + " must be unique");
        return copy;
    }

    static List<String> texts(List<String> value, String name) {
        List<String> copy = list(value, name, 0, MAX_KEY_REFERENCES);
        copy.forEach(item -> text(item, name + " item"));
        return copy;
    }

    static void uniqueKeys(List<String> values, String name) {
        if (new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException(name + " keys must be unique");
    }

    static void exactFields(Set<String> actual, Set<String> expected, String name) {
        if (!actual.equals(expected)) throw new StructuredSkillExecutionException(StructuredSkillErrorType.STRUCTURED_OUTPUT_INVALID, false);
    }
}
