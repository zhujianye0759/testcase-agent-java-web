package com.testcaseagent.validation;

import com.testcaseagent.identity.LengthPrefixedSha256;
import com.testcaseagent.knowledgeagent.FunctionListExtractionResult;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates model-extracted function-list rows and assigns Java-owned stable identities.
 *
 * <p>The model contract intentionally has no {@code item_key}. This validator first closes every evidence reference
 * against the current material slice, then derives a task-local key from normalized business text. It does not mutate
 * the validation registry; callers register keys only after atomic persistence succeeds.</p>
 *
 * [Req-ID]: REQ-STG-001, REQ-STG-003
 */
public final class FunctionListExtractionValidator {

    /** Validates a complete extraction response and maps it to persistence-ready rows. */
    public List<ValidatedItem> validate(WorkItem workItem, Result result) {
        WorkItem item = Objects.requireNonNull(workItem, "workItem must not be null");
        Result checked = Objects.requireNonNull(result, "result must not be null");
        List<ModelItem> rows = requiredList(checked.functionListItems(), "functionListItems");
        if (rows.size() > 200) throw new IllegalArgumentException("Function-list extraction must contain 0..200 rows");

        List<ValidatedItem> validated = new ArrayList<>(rows.size());
        for (ModelItem value : rows) {
            ModelItem row = Objects.requireNonNull(value, "function-list item must not be null");
            String path = displayText(row.path(), "path");
            String description = displayText(row.description(), "description");
            String targetQuote = required(row.targetQuote(), "targetQuote");
            if (targetQuote.codePointCount(0, targetQuote.length())
                    > FunctionListExtractionResult.MAX_TARGET_QUOTE_CODE_POINTS) {
                throw new IllegalArgumentException("targetQuote exceeds maximum Unicode characters");
            }
            List<String> evidenceKeys = requiredList(row.evidenceKeys(), "evidenceKeys");
            if (evidenceKeys.isEmpty() || evidenceKeys.size() > 100) {
                throw new IllegalArgumentException("Function-list evidenceKeys must contain 1..100 rows");
            }
            Set<String> distinct = new LinkedHashSet<>();
            for (String evidenceKey : evidenceKeys) {
                String key = required(evidenceKey, "evidenceKey");
                if (!distinct.add(key) || !item.allowedEvidenceKeys().contains(key)) {
                    throw new IllegalArgumentException("Function-list evidence is duplicate or outside the current material slice");
                }
                item.registry().requireEvidence(key, item.materialKey());
            }
            String normalizedQuote = directEvidenceText(targetQuote);
            boolean quoteBound = distinct.stream().map(item.targetEvidenceTexts()::get)
                    .filter(Objects::nonNull)
                    .map(FunctionListExtractionValidator::directEvidenceText)
                    .anyMatch(evidenceText -> evidenceText.contains(normalizedQuote));
            if (!quoteBound) {
                throw new IllegalArgumentException(
                        "Function-list targetQuote must be a continuous excerpt of one referenced target unit");
            }
            String normalizedLeaf = directEvidenceText(finalPathLeaf(path));
            if (!normalizedQuote.contains(normalizedLeaf)) {
                throw new IllegalArgumentException(
                        "Function-list targetQuote must contain the final path leaf");
            }
            validated.add(new ValidatedItem(stableItemKey(item.registry().taskId(), path, description),
                    path, description, List.copyOf(distinct), List.of(targetQuote)));
        }
        return List.copyOf(validated);
    }

    /** Dedupe rows from multiple slices while preserving first-seen display text and deterministic evidence order. */
    public List<ValidatedItem> mergeSlices(List<ValidatedItem> items) {
        List<ValidatedItem> checked = requiredList(items, "items");
        Map<String, MergeState> merged = new LinkedHashMap<>();
        for (ValidatedItem value : checked) {
            ValidatedItem item = Objects.requireNonNull(value, "validated item must not be null");
            String key = required(item.itemKey(), "itemKey");
            String path = displayText(item.path(), "path");
            String description = displayText(item.description(), "description");
            MergeState state = merged.computeIfAbsent(key, ignored -> new MergeState(path, description));
            if (!canonical(state.path).equals(canonical(path))
                    || !canonical(state.description).equals(canonical(description))) {
                throw new IllegalArgumentException("Stable function-list key collision");
            }
            for (String evidenceKey : requiredList(item.evidenceKeys(), "evidenceKeys")) {
                if (!state.evidenceKeys.add(required(evidenceKey, "evidenceKey"))) {
                    continue;
                }
                if (state.evidenceKeys.size() > 100) {
                    throw new IllegalArgumentException("Merged function-list evidenceKeys exceed 100 rows");
                }
            }
            requiredList(item.targetQuotes(), "targetQuotes").stream()
                    .map(quote -> required(quote, "targetQuote"))
                    .forEach(state.targetQuotes::add);
        }
        return merged.entrySet().stream()
                .map(entry -> new ValidatedItem(entry.getKey(), entry.getValue().path, entry.getValue().description,
                        List.copyOf(entry.getValue().evidenceKeys), List.copyOf(entry.getValue().targetQuotes)))
                .toList();
    }

    /** Returns the task-owned formal function identity shared by legacy and candidate extraction. */
    public static String stableItemKey(String taskId, String path, String description) {
        return "fli-" + HexFormat.of().formatHex(LengthPrefixedSha256.digest(
                required(taskId, "taskId"), canonical(path), canonical(description)));
    }

    private static String canonical(String value) {
        return Normalizer.normalize(required(value, "text"), Normalizer.Form.NFKC)
                .strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String displayText(String value, String field) {
        String normalized = Normalizer.normalize(required(value, field), Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
        return ReaderFacingTextPolicy.requireSafe(normalized, field);
    }

    /** Context may supply parent hierarchy, but the target-owned quote must prove the final function leaf. */
    private static String finalPathLeaf(String path) {
        String[] segments = Normalizer.normalize(required(path, "path"), Normalizer.Form.NFKC)
                .split("[/\\\\→>›»|]+");
        for (int index = segments.length - 1; index >= 0; index--) {
            if (!segments[index].isBlank()) return segments[index].strip();
        }
        throw new IllegalArgumentException("path must contain a non-blank final leaf");
    }

    /** Ignores layout whitespace only; punctuation and all other source characters remain evidence-bearing. */
    private static String directEvidenceText(String value) {
        String normalized = Normalizer.normalize(required(value, "text"), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)
                        && !Character.isSpaceChar(codePoint))
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    private static <T> List<T> requiredList(List<T> values, String field) {
        if (values == null) throw new IllegalArgumentException(field + " must not be null");
        return List.copyOf(values);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    /** Frozen material and slice evidence closure for one extraction invocation. */
    public record WorkItem(StructuredValidationRegistry registry, String materialKey,
            List<String> allowedEvidenceKeys, Map<String, String> targetEvidenceTexts) {
        public WorkItem {
            registry = Objects.requireNonNull(registry, "registry must not be null");
            required(materialKey, "materialKey");
            registry.require(StructuredKeyType.MATERIAL, materialKey);
            allowedEvidenceKeys = requiredList(allowedEvidenceKeys, "allowedEvidenceKeys");
            if (allowedEvidenceKeys.isEmpty()) throw new IllegalArgumentException("allowedEvidenceKeys must not be empty");
            Set<String> distinct = new LinkedHashSet<>();
            for (String evidenceKey : allowedEvidenceKeys) {
                String key = required(evidenceKey, "evidenceKey");
                if (!distinct.add(key)) throw new IllegalArgumentException("allowedEvidenceKeys must be unique");
                registry.requireEvidence(key, materialKey);
            }
            targetEvidenceTexts = Map.copyOf(Objects.requireNonNull(
                    targetEvidenceTexts, "targetEvidenceTexts must not be null"));
            if (!targetEvidenceTexts.keySet().equals(Set.copyOf(allowedEvidenceKeys))) {
                throw new IllegalArgumentException("Target evidence text must exactly match allowedEvidenceKeys");
            }
            targetEvidenceTexts.values().forEach(text -> required(text, "targetEvidenceText"));
        }
    }

    /** Exact model result content; it deliberately contains no item key. */
    public record Result(List<ModelItem> functionListItems) { }

    /** Exact model-produced row; Java assigns its identity only after validation. */
    public record ModelItem(String path, String description, List<String> evidenceKeys, String targetQuote) { }

    /** Java-validated row ready for atomic persistence. */
    public record ValidatedItem(String itemKey, String path, String description,
            List<String> evidenceKeys, List<String> targetQuotes) { }

    private static final class MergeState {
        private final String path;
        private final String description;
        private final Set<String> evidenceKeys = new LinkedHashSet<>();
        private final Set<String> targetQuotes = new LinkedHashSet<>();

        private MergeState(String path, String description) {
            this.path = path;
            this.description = description;
        }
    }
}
