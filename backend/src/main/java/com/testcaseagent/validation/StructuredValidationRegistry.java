package com.testcaseagent.validation;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Holds the task-owned identities and resolved requirement evidence allowed in one structured workflow.
 *
 * <p>It deliberately stores neither model output nor example content. Callers may register accepted output identities
 * only after their enclosing transaction succeeds.</p>
 *
 * [Req-ID]: REQ-STG-001
 */
public final class StructuredValidationRegistry {
    private final String taskId;
    private final Map<StructuredKeyType, Map<String, String>> keys = new EnumMap<>(StructuredKeyType.class);
    private final Map<String, StructuredEvidence> evidence = new LinkedHashMap<>();

    private StructuredValidationRegistry(String taskId) {
        this.taskId = required(taskId, "taskId");
    }

    /** Starts an empty registry for one immutable task identity. */
    public static StructuredValidationRegistry forTask(String taskId) {
        return new StructuredValidationRegistry(taskId);
    }

    /** Registers a task-local key once; a key cannot change family or be reused. */
    public StructuredValidationRegistry register(StructuredKeyType type, String key) {
        Objects.requireNonNull(type, "type must not be null");
        String checkedKey = required(key, "key");
        if (keys.computeIfAbsent(type, ignored -> new LinkedHashMap<>()).putIfAbsent(checkedKey, checkedKey) != null) {
            throw new IllegalArgumentException("Structured key is already registered: " + checkedKey);
        }
        return this;
    }

    /** Registers a resolved evidence key once. Evidence from another task is rejected immediately. */
    public StructuredValidationRegistry registerEvidence(StructuredEvidence value) {
        StructuredEvidence checked = Objects.requireNonNull(value, "evidence must not be null");
        if (!taskId.equals(checked.taskId())) {
            throw new IllegalArgumentException("Evidence belongs to another task");
        }
        if (evidence.putIfAbsent(checked.evidenceKey(), checked) != null) {
            throw new IllegalArgumentException("Evidence key is already registered: " + checked.evidenceKey());
        }
        return this;
    }

    /** Requires an existing key of the exact expected family. */
    public void require(StructuredKeyType type, String key) {
        Objects.requireNonNull(type, "type must not be null");
        String checkedKey = required(key, "key");
        if (!keys.getOrDefault(type, Map.of()).containsKey(checkedKey)) {
            throw new IllegalArgumentException("Unknown or wrong structured key type: " + checkedKey);
        }
    }

    /** Requires usable formal evidence from the frozen task, regardless of its allowed material subset. */
    public void requireEvidence(String evidenceKey) {
        StructuredEvidence value = evidence.get(required(evidenceKey, "evidenceKey"));
        if (value == null || value.exampleScope() || value.retired() || !value.fullyTraversed()) {
            throw new IllegalArgumentException("Evidence is not usable within the frozen requirement scope");
        }
    }

    /** Requires usable evidence from exactly the current material work item. */
    public void requireEvidence(String evidenceKey, String materialKey) {
        requireEvidence(evidenceKey);
        StructuredEvidence value = evidence.get(evidenceKey);
        if (!required(materialKey, "materialKey").equals(value.materialKey())) {
            throw new IllegalArgumentException("Evidence belongs to another material work item");
        }
    }

    /** Returns the task identity for diagnostic-free ownership checks. */
    public String taskId() {
        return taskId;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
