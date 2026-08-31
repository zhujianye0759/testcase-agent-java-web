package com.testcaseagent.task;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Versioned, caller-supplied output of the independent admission review process.
 * It is an input snapshot, not a request for this application to rediscover or reconcile a function list.
 *
 * [Req-ID]: REQ-TGV2-001, REQ-TGV2-002
 */
public record ApprovedFunctionScope(String scopeVersion, List<ApprovedFunction> functions) {
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

    private static String required(String value, String field, int maxBytes) {
        String checked = Objects.requireNonNull(value, field + " must not be null").trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (checked.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " exceeds maximum UTF-8 bytes");
        }
        return checked;
    }
}
