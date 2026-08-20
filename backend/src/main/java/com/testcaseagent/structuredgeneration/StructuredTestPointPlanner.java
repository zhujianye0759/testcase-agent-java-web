package com.testcaseagent.structuredgeneration;

import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministically creates a non-fixed set of test points from accepted formal facts and explicit experience gaps.
 *
 * <p>Only a nonempty fact field creates its corresponding formal point. General-experience points must be supplied
 * as explicit gaps with missing information, so this planner never invents a missing requirement or promotes an
 * experience candidate into formal coverage.</p>
 *
 * [Req-ID]: REQ-STG-004, REQ-STG-005
 */
public final class StructuredTestPointPlanner {

    /** Creates stable request inputs in fact order and then explicit-gap order. */
    public List<FunctionalTestcaseDesignInput> plan(FunctionDefinition definition) {
        FunctionDefinition function = Objects.requireNonNull(definition, "definition must not be null");
        List<FunctionalTestcaseDesignInput> points = new ArrayList<>();
        for (FormalFact fact : function.formalFacts()) {
            points.add(formal(function, fact, FunctionalTestcaseDesignInput.TestPointType.NORMAL_BEHAVIOR,
                    function.functionName()));
            addIfPresent(points, function, fact, FunctionalTestcaseDesignInput.TestPointType.INPUT_VALIDATION, fact.inputs());
            addIfPresent(points, function, fact, FunctionalTestcaseDesignInput.TestPointType.BOUNDARY_VALUE, fact.businessRules());
            addIfPresent(points, function, fact, FunctionalTestcaseDesignInput.TestPointType.PERMISSION, fact.permissions());
            addIfPresent(points, function, fact, FunctionalTestcaseDesignInput.TestPointType.STATE_TRANSITION, fact.stateChanges());
            addIfPresent(points, function, fact, FunctionalTestcaseDesignInput.TestPointType.BUSINESS_EXCEPTION, fact.exceptionHandling());
            addIfPresent(points, function, fact, FunctionalTestcaseDesignInput.TestPointType.DEPENDENCY_FAILURE, fact.externalDependencies());
        }
        for (int index = 0; index < function.experienceGaps().size(); index++) {
            ExperienceGap gap = function.experienceGaps().get(index);
            String key = pointKey(function.functionKey(), "experience-" + (index + 1), gap.type());
            points.add(new FunctionalTestcaseDesignInput(function.functionKey(), function.functionName(),
                    new FunctionalTestcaseDesignInput.TestPoint(key, gap.type(), gap.description(), List.of(),
                            gap.evidenceKeys(), FunctionalTestcaseDesignInput.Basis.GENERAL_EXPERIENCE,
                            gap.missingInformation())));
        }
        return List.copyOf(points);
    }

    private static void addIfPresent(List<FunctionalTestcaseDesignInput> points, FunctionDefinition function,
            FormalFact fact, FunctionalTestcaseDesignInput.TestPointType type, List<String> values) {
        if (!values.isEmpty()) points.add(formal(function, fact, type, values.get(0)));
    }

    private static FunctionalTestcaseDesignInput formal(FunctionDefinition function, FormalFact fact,
            FunctionalTestcaseDesignInput.TestPointType type, String description) {
        return new FunctionalTestcaseDesignInput(function.functionKey(), function.functionName(),
                new FunctionalTestcaseDesignInput.TestPoint(pointKey(function.functionKey(), fact.factKey(), type),
                        type, description, List.of(fact.factKey()), fact.evidenceKeys(),
                        FunctionalTestcaseDesignInput.Basis.FORMAL_REQUIREMENT, List.of()));
    }

    private static String pointKey(String functionKey, String sourceKey,
            FunctionalTestcaseDesignInput.TestPointType type) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (functionKey + "\u0000" + sourceKey + "\u0000" + type.name()).getBytes(StandardCharsets.UTF_8));
            return "point-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    /** One frozen function and all accepted facts/gaps used to derive its points. */
    public record FunctionDefinition(
            String functionKey,
            String functionName,
            List<FormalFact> formalFacts,
            List<ExperienceGap> experienceGaps) {
        public FunctionDefinition {
            functionKey = required(functionKey, "functionKey");
            functionName = required(functionName, "functionName");
            formalFacts = copyDistinct(formalFacts, FormalFact::factKey, "formal fact");
            experienceGaps = List.copyOf(Objects.requireNonNull(experienceGaps, "experienceGaps must not be null"));
        }
    }

    /** Accepted formal fact fields that directly support formal test-point categories. */
    public record FormalFact(
            String factKey,
            String function,
            List<String> inputs,
            List<String> businessRules,
            List<String> permissions,
            List<String> stateChanges,
            List<String> exceptionHandling,
            List<String> externalDependencies,
            List<String> evidenceKeys) {
        public FormalFact {
            factKey = required(factKey, "factKey");
            function = required(function, "function");
            inputs = texts(inputs, "inputs", false);
            businessRules = texts(businessRules, "businessRules", false);
            permissions = texts(permissions, "permissions", false);
            stateChanges = texts(stateChanges, "stateChanges", false);
            exceptionHandling = texts(exceptionHandling, "exceptionHandling", false);
            externalDependencies = texts(externalDependencies, "externalDependencies", false);
            evidenceKeys = texts(evidenceKeys, "evidenceKeys", true);
        }
    }

    /** One explicitly identified non-formal gap; it can only create a pending-confirmation request. */
    public record ExperienceGap(
            FunctionalTestcaseDesignInput.TestPointType type,
            String description,
            List<String> evidenceKeys,
            List<String> missingInformation) {
        public ExperienceGap {
            type = Objects.requireNonNull(type, "type must not be null");
            description = required(description, "description");
            evidenceKeys = texts(evidenceKeys, "evidenceKeys", false);
            missingInformation = texts(missingInformation, "missingInformation", true);
        }
    }

    private static List<String> texts(List<String> values, String field, boolean nonempty) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
        if (nonempty && copy.isEmpty()) throw new IllegalArgumentException(field + " must not be empty");
        Set<String> distinct = new HashSet<>();
        for (String value : copy) {
            String checked = required(value, field + " item");
            if (!distinct.add(checked)) throw new IllegalArgumentException(field + " must be unique");
        }
        return copy;
    }

    private static <T> List<T> copyDistinct(
            List<T> values, java.util.function.Function<T, String> key, String field) {
        List<T> copy = List.copyOf(Objects.requireNonNull(values, field + "s must not be null"));
        Set<String> distinct = new HashSet<>();
        for (T value : copy) {
            T checked = Objects.requireNonNull(value, field + " must not be null");
            if (!distinct.add(key.apply(checked))) throw new IllegalArgumentException(field + " keys must be unique");
        }
        return copy;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
