package com.testcaseagent.task;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Versioned, caller-supplied output of the independent admission review process.
 * It is an input snapshot, not a request for this application to rediscover or reconcile a function list.
 *
 * [Req-ID]: REQ-TGV2-001, REQ-TGV2-002, REQ-TGV2-016
 */
public record ApprovedFunctionScope(
        String scopeVersion, List<ApprovedFunction> functions, List<ApprovedTestPoint> testPoints) {
    public ApprovedFunctionScope {
        scopeVersion = required(scopeVersion, "scopeVersion", 128);
        functions = List.copyOf(Objects.requireNonNull(functions, "functions must not be null"));
        if (functions.isEmpty()) throw new IllegalArgumentException("approved function scope must not be empty");
        HashSet<String> keys = new HashSet<>();
        for (ApprovedFunction function : functions) {
            if (!keys.add(function.functionKey())) {
                throw new IllegalArgumentException("approved function keys must be unique");
            }
        }
        testPoints = testPoints == null ? List.of() : List.copyOf(testPoints);
        HashSet<String> pointKeys = new HashSet<>();
        for (ApprovedTestPoint point : testPoints) {
            Objects.requireNonNull(point, "approved test point must not be null");
            if (!pointKeys.add(point.testPointKey())) {
                throw new IllegalArgumentException("approved test point keys must be unique");
            }
            if (!keys.contains(point.functionKey())) {
                throw new IllegalArgumentException("approved test point function must belong to the approved scope");
            }
        }
    }

    /** Source-compatible constructor for historical snapshots and callers that do not freeze reviewed points. */
    public ApprovedFunctionScope(String scopeVersion, List<ApprovedFunction> functions) {
        this(scopeVersion, functions, List.of());
    }

    /** One audited function whose identity and reader-facing wording are owned outside testcase generation. */
    public record ApprovedFunction(String functionKey, String name, String path, String description) {
        public ApprovedFunction {
            functionKey = required(functionKey, "functionKey", 128);
            name = required(name, "name", 16_384);
            path = required(path, "path", 16_384);
            description = description == null ? "" : description.trim();
            if (description.getBytes(StandardCharsets.UTF_8).length > 16_384) {
                throw new IllegalArgumentException("description exceeds maximum UTF-8 bytes");
            }
        }
    }

    /**
     * One caller-reviewed non-formal point. These coordinates are frozen input; generation may only retain or
     * downgrade the resulting outcome and must never replace the point with text inferred from a function description.
     */
    public record ApprovedTestPoint(
            String testPointKey,
            String functionKey,
            ApprovedTestPointType type,
            ApprovedTestPointSource source,
            ApprovedTestPointStatus status,
            String description,
            List<String> missingInformation) {
        public ApprovedTestPoint {
            testPointKey = required(testPointKey, "testPointKey", 128);
            functionKey = required(functionKey, "functionKey", 128);
            type = Objects.requireNonNull(type, "type must not be null");
            source = Objects.requireNonNull(source, "source must not be null");
            status = Objects.requireNonNull(status, "status must not be null");
            if (source != ApprovedTestPointSource.GENERAL_EXPERIENCE
                    || status != ApprovedTestPointStatus.PENDING_CONFIRMATION) {
                throw new IllegalArgumentException("approved test points must remain pending general-experience input");
            }
            description = required(description, "description", 16_384);
            missingInformation = List.copyOf(
                    Objects.requireNonNull(missingInformation, "missingInformation must not be null"));
            if (missingInformation.isEmpty() || missingInformation.size() > 100) {
                throw new IllegalArgumentException("missingInformation must contain 1..100 values");
            }
            HashSet<String> values = new HashSet<>();
            java.util.ArrayList<String> checked = new java.util.ArrayList<>();
            for (String value : missingInformation) {
                String normalized = required(value, "missingInformation", 16_384);
                if (!values.add(normalized)) {
                    throw new IllegalArgumentException("missingInformation values must be unique");
                }
                checked.add(normalized);
            }
            missingInformation = List.copyOf(checked);
        }
    }

    /** Supported reader-facing test intentions supplied by the independent admission review. */
    public enum ApprovedTestPointType {
        NORMAL_BEHAVIOR, INPUT_VALIDATION, BOUNDARY_VALUE, PERMISSION, STATE_TRANSITION,
        BUSINESS_EXCEPTION, DEPENDENCY_FAILURE
    }

    /** Non-formal provenance retained so reviewed points can never masquerade as requirement facts. */
    public enum ApprovedTestPointSource { GENERAL_EXPERIENCE }

    /** Review state retained by testcase generation; this workflow cannot promote it to formal coverage. */
    public enum ApprovedTestPointStatus { PENDING_CONFIRMATION }

    private static String required(String value, String field, int maxBytes) {
        String checked = Objects.requireNonNull(value, field + " must not be null").trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (checked.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " exceeds maximum UTF-8 bytes");
        }
        return checked;
    }
}
