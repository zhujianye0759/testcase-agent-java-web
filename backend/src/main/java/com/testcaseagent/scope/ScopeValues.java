package com.testcaseagent.scope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

final class ScopeValues {

    private ScopeValues() {
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }

    static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static List<RequirementDocumentCoordinate> sortedDistinctDocuments(
            List<RequirementDocumentCoordinate> documents) {
        Objects.requireNonNull(documents, "documents must not be null");
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("documents must not be empty");
        }
        Set<String> ids = documents.stream().map(RequirementDocumentCoordinate::documentId).collect(Collectors.toSet());
        if (ids.size() != documents.size()) {
            throw new IllegalArgumentException("documents must not contain duplicates");
        }
        return documents.stream().sorted(java.util.Comparator.comparing(RequirementDocumentCoordinate::documentId)).toList();
    }

    static String lengthPrefixed(String value) {
        return value.length() + ":" + value;
    }

    static String nullableLengthPrefixed(String value) {
        return value == null ? "-1:" : lengthPrefixed(value);
    }

    static String sha256(String canonicalValue) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalValue.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
