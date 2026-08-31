package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.testcaseagent.identity.LengthPrefixedSha256;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact protocol V1 input for one auditable function-candidate window.
 *
 * <p>Target units own output. Context units are adjacent read-only material and cannot own an
 * outcome or evidence. The constructor rejects malformed windows before any KEE call.</p>
 *
 * [Req-ID]: REQ-AFCE-001, REQ-AFCE-008
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FunctionCandidateExtractionInput(
        String operation,
        @JsonProperty("protocol_version") String protocolVersion,
        @JsonProperty("window_key") String windowKey,
        @JsonProperty("material_key") String materialKey,
        @JsonProperty("source_label") String sourceLabel,
        List<Unit> units,
        @JsonProperty("context_units") List<Unit> contextUnits) {
    static final String OPERATION = "extract_function_candidates";
    static final String PROTOCOL_VERSION = "1";
    private static final String SHA256_PATTERN = "[0-9a-f]{64}";
    private static final String WINDOW_DOMAIN = "function-candidate-window-v1";

    /** Creates the only supported candidate protocol version. */
    public FunctionCandidateExtractionInput(String windowKey, String materialKey, String sourceLabel,
            List<Unit> units, List<Unit> contextUnits) {
        this(OPERATION, PROTOCOL_VERSION, windowKey, materialKey, sourceLabel, units, contextUnits);
    }

    /**
     * Builds a candidate window whose public key is also the durable work identity.
     * Keeping this derivation beside the wire DTO prevents planner, validator, and recovery code
     * from assigning different identities to the same frozen parsed-unit window.
     */
    public static FunctionCandidateExtractionInput forWindow(String taskId, String materialKey, String sourceLabel,
            List<Unit> units, List<Unit> contextUnits) {
        List<Unit> checkedUnits = List.copyOf(units);
        List<Unit> checkedContext = List.copyOf(contextUnits);
        return new FunctionCandidateExtractionInput(
                expectedWindowKey(taskId, materialKey, checkedUnits, checkedContext),
                materialKey, sourceLabel, checkedUnits, checkedContext);
    }

    /** Recomputes the frozen cross-language V1 identity without trusting an inbound window key. */
    public static String expectedWindowKey(String taskId, String materialKey,
            List<Unit> units, List<Unit> contextUnits) {
        String checkedTaskId = StructuredSkillContract.key(taskId, "taskId");
        String checkedMaterialKey = StructuredSkillContract.key(materialKey, "materialKey");
        List<Unit> checkedUnits = List.copyOf(units);
        List<Unit> checkedContext = List.copyOf(contextUnits);
        List<String> fields = new ArrayList<>();
        fields.add(WINDOW_DOMAIN);
        fields.add(checkedTaskId);
        fields.add(checkedMaterialKey);
        fields.add(Integer.toString(checkedUnits.size()));
        checkedUnits.stream().map(Unit::unitKey).forEach(fields::add);
        fields.add(Integer.toString(checkedContext.size()));
        checkedContext.stream().map(Unit::unitKey).forEach(fields::add);
        return HexFormat.of().formatHex(LengthPrefixedSha256.digest(fields.toArray(String[]::new)));
    }

    public FunctionCandidateExtractionInput {
        if (!OPERATION.equals(operation)) {
            throw new IllegalArgumentException("operation must be extract_function_candidates");
        }
        if (!PROTOCOL_VERSION.equals(protocolVersion)) {
            throw new IllegalArgumentException("protocolVersion must be 1");
        }
        windowKey = StructuredSkillContract.key(windowKey, "windowKey");
        if (!windowKey.matches(SHA256_PATTERN)) {
            throw new IllegalArgumentException("windowKey must be a lowercase SHA-256 value");
        }
        materialKey = StructuredSkillContract.key(materialKey, "materialKey");
        sourceLabel = StructuredSkillContract.text(sourceLabel, "sourceLabel");
        units = StructuredSkillContract.list(units, "units", 1, 32);
        contextUnits = StructuredSkillContract.list(contextUnits, "contextUnits", 0, 31);
        if (units.size() + contextUnits.size() > 32) {
            throw new IllegalArgumentException("units and contextUnits must contain at most 32 entries");
        }

        StructuredSkillContract.uniqueKeys(units.stream().map(Unit::unitKey).toList(), "unit");
        int expectedOrdinal = units.get(0).ordinal();
        for (Unit unit : units) {
            if (unit.ordinal() != expectedOrdinal++) {
                throw new IllegalArgumentException("unit ordinals must be continuous");
            }
        }

        Set<String> targetKeys = new HashSet<>(units.stream().map(Unit::unitKey).toList());
        Set<Integer> targetOrdinals = new HashSet<>(units.stream().map(Unit::ordinal).toList());
        Set<String> contextKeys = new HashSet<>();
        int previousContextOrdinal = 0;
        for (Unit context : contextUnits) {
            if (!contextKeys.add(context.unitKey())) {
                throw new IllegalArgumentException("context unit keys must be unique");
            }
            if (targetKeys.contains(context.unitKey()) || targetOrdinals.contains(context.ordinal())) {
                throw new IllegalArgumentException("context units must not overlap target units");
            }
            if (context.ordinal() <= previousContextOrdinal) {
                throw new IllegalArgumentException("context unit ordinals must be strictly increasing");
            }
            previousContextOrdinal = context.ordinal();
        }
    }

    /** One exact persisted parsed unit; global ordinals are never renumbered for a window. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Unit(@JsonProperty("unit_key") String unitKey, int ordinal, String content) {
        public Unit {
            unitKey = StructuredSkillContract.key(unitKey, "unitKey");
            if (ordinal < 1) {
                throw new IllegalArgumentException("ordinal must be at least one");
            }
            if (content == null || content.isEmpty()) {
                throw new IllegalArgumentException("content must not be empty");
            }
            if (content.getBytes(StandardCharsets.UTF_8).length > 65_536) {
                throw new IllegalArgumentException("content exceeds maximum UTF-8 bytes");
            }
        }
    }
}
