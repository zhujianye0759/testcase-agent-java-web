package com.testcaseagent.knowledgeagent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Produces the exact compact JSON and identity bytes frozen by KEE protocol V2.
 *
 * <p>The small writer deliberately mirrors Go {@code encoding/json}: HTML-sensitive characters and
 * U+2028/U+2029 are escaped, object field order is fixed, and no trailing newline is added. Using a
 * generic application mapper here would make task identities depend on mapper configuration.</p>
 *
 * [Req-ID]: REQ-FSC-008
 */
public final class FeatureScopeReconciliationV2Canonicalizer {
    private static final Comparator<String> UTF8_ORDER = FeatureScopeReconciliationV2Canonicalizer::compareUtf8;

    private FeatureScopeReconciliationV2Canonicalizer() { }

    /** Returns the canonical compact catalog bytes used by {@code catalog_sha256}. */
    public static byte[] canonicalCatalogJson(FeatureScopeReconciliationPageInput.GlobalCatalog catalog) {
        StringBuilder json = new StringBuilder();
        json.append("{\"function_list_items\":[");
        appendItems(json, catalog.functionListItems());
        json.append("],\"requirement_facts\":[");
        appendFacts(json, catalog.requirementFacts());
        json.append("]}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Returns the lowercase SHA-256 digest of the compact canonical catalog JSON. */
    public static String catalogSha256(FeatureScopeReconciliationPageInput.GlobalCatalog catalog) {
        return sha256(canonicalCatalogJson(catalog));
    }

    /** Returns the canonical compact owner/reference array bytes. */
    public static byte[] canonicalSourceRefsJson(List<FeatureScopeReconciliationPageInput.SourceRef> refs) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < refs.size(); index++) {
            if (index > 0) json.append(',');
            FeatureScopeReconciliationPageInput.SourceRef ref = refs.get(index);
            json.append("{\"source_type\":");
            appendQuoted(json, ref.sourceType().wireValue());
            json.append(",\"source_key\":");
            appendQuoted(json, ref.sourceKey());
            json.append('}');
        }
        return json.append(']').toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Computes the frozen page identity without appending a newline after the owner JSON. */
    public static String pageKey(String runKey, List<FeatureScopeReconciliationPageInput.SourceRef> ownerRefs) {
        StructuredSkillContract.key(runKey, "runKey");
        byte[] prefix = ("reconcile-page-v2\n" + runKey + "\n").getBytes(StandardCharsets.UTF_8);
        return sha256(concat(prefix, canonicalSourceRefsJson(ownerRefs)));
    }

    /**
     * Computes the server-derived relation identity independently from its canonical source list.
     * The source list must already be in protocol order and contain no duplicate references.
     */
    public static String reconciliationKey(String runKey,
            FeatureScopeReconciliationResult.Classification classification,
            FeatureScopeReconciliationResult.ConfirmationStatus confirmationStatus,
            List<FeatureScopeReconciliationPageInput.SourceRef> referencedSources) {
        StructuredSkillContract.key(runKey, "runKey");
        if (classification == null || confirmationStatus == null) {
            throw new IllegalArgumentException("classification and confirmationStatus must not be null");
        }
        byte[] prefix = ("reconciliation-v2\n" + runKey + "\n" + classification.wireValue() + "\n"
                + confirmationStatus.wireValue() + "\n").getBytes(StandardCharsets.UTF_8);
        return sha256(concat(prefix, canonicalSourceRefsJson(referencedSources)));
    }

    /** Builds the canonical relation source list from the two public key arrays. */
    public static List<FeatureScopeReconciliationPageInput.SourceRef> relationSourceRefs(
            List<String> functionItemKeys, List<String> requirementFactKeys) {
        List<FeatureScopeReconciliationPageInput.SourceRef> refs = new ArrayList<>(
                functionItemKeys.size() + requirementFactKeys.size());
        functionItemKeys.forEach(key -> refs.add(new FeatureScopeReconciliationPageInput.SourceRef(
                FeatureScopeReconciliationPageInput.SourceType.FUNCTION_LIST_ITEM, key)));
        requirementFactKeys.forEach(key -> refs.add(new FeatureScopeReconciliationPageInput.SourceRef(
                FeatureScopeReconciliationPageInput.SourceType.REQUIREMENT_FACT, key)));
        return List.copyOf(refs);
    }

    /** Protocol ordering uses UTF-8 byte order, matching Go string ordering for source keys. */
    public static Comparator<String> utf8Order() {
        return UTF8_ORDER;
    }

    private static void appendItems(StringBuilder json,
            List<FeatureScopeReconciliationPageInput.FunctionListItem> items) {
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) json.append(',');
            FeatureScopeReconciliationPageInput.FunctionListItem item = items.get(index);
            json.append("{\"item_key\":");
            appendQuoted(json, item.itemKey());
            json.append(",\"path\":");
            appendQuoted(json, item.path());
            json.append(",\"description\":");
            appendQuoted(json, item.description());
            json.append(",\"evidence_keys\":");
            appendStrings(json, item.evidenceKeys());
            json.append('}');
        }
    }

    private static void appendFacts(StringBuilder json,
            List<FeatureScopeReconciliationPageInput.RequirementFact> facts) {
        for (int index = 0; index < facts.size(); index++) {
            if (index > 0) json.append(',');
            FeatureScopeReconciliationPageInput.RequirementFact fact = facts.get(index);
            json.append("{\"fact_key\":");
            appendQuoted(json, fact.factKey());
            json.append(",\"function\":");
            appendQuoted(json, fact.function());
            json.append(",\"evidence_keys\":");
            appendStrings(json, fact.evidenceKeys());
            json.append('}');
        }
    }

    private static void appendStrings(StringBuilder json, List<String> values) {
        json.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) json.append(',');
            appendQuoted(json, values.get(index));
        }
        json.append(']');
    }

    private static void appendQuoted(StringBuilder json, String value) {
        json.append('"');
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int width = Character.charCount(codePoint);
            if (width == 1 && Character.isSurrogate(value.charAt(offset))) {
                throw new IllegalArgumentException("canonical JSON value contains an unpaired surrogate");
            }
            offset += width;
            switch (codePoint) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case '<' -> json.append("\\u003c");
                case '>' -> json.append("\\u003e");
                case '&' -> json.append("\\u0026");
                case 0x2028 -> json.append("\\u2028");
                case 0x2029 -> json.append("\\u2029");
                default -> {
                    if (codePoint < 0x20) json.append("\\u%04x".formatted(codePoint));
                    else json.appendCodePoint(codePoint);
                }
            }
        }
        json.append('"');
    }

    private static int compareUtf8(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int shared = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < shared; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    private static byte[] concat(byte[] left, byte[] right) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(left.length + right.length);
        output.writeBytes(left);
        output.writeBytes(right);
        return output.toByteArray();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime must provide SHA-256", exception);
        }
    }
}
