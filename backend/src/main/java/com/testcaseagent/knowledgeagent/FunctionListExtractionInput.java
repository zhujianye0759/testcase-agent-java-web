package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exact extract-function-list input for one globally numbered material slice. [Req-ID]: REQ-SKI-003, REQ-STG-003 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FunctionListExtractionInput(
        String operation,
        @JsonProperty("material_key") String materialKey,
        @JsonProperty("source_label") String sourceLabel,
        List<Unit> units,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("context_units") List<Unit> contextUnits) {
    static final String OPERATION = "extract_function_list";

    /** Creates the only permitted feature-list extraction operation. */
    public FunctionListExtractionInput(String materialKey, String sourceLabel, List<Unit> units) {
        this(OPERATION, materialKey, sourceLabel, units, List.of());
    }

    /** Preserves the strict historical constructor while the context field is absent. */
    public FunctionListExtractionInput(String operation, String materialKey, String sourceLabel, List<Unit> units) {
        this(operation, materialKey, sourceLabel, units, List.of());
    }

    /** Creates an extraction request whose context is readable but cannot own output evidence. */
    public FunctionListExtractionInput(String materialKey, String sourceLabel, List<Unit> units,
            List<Unit> contextUnits) {
        this(OPERATION, materialKey, sourceLabel, units, contextUnits);
    }

    public FunctionListExtractionInput {
        if (!OPERATION.equals(operation)) throw new IllegalArgumentException("operation must be extract_function_list");
        materialKey = StructuredSkillContract.key(materialKey, "materialKey");
        sourceLabel = StructuredSkillContract.text(sourceLabel, "sourceLabel");
        units = StructuredSkillContract.list(units, "units", 1, 32);
        StructuredSkillContract.uniqueKeys(units.stream().map(Unit::unitKey).toList(), "unit");
        int expected = units.get(0).ordinal();
        if (expected < 1) throw new IllegalArgumentException("first unit ordinal must be at least one");
        for (Unit unit : units) if (unit.ordinal() != expected++) throw new IllegalArgumentException("unit ordinals must be continuous");
        contextUnits = contextUnits == null ? List.of() : List.copyOf(contextUnits);
        if (units.size() + contextUnits.size() > 32) {
            throw new IllegalArgumentException("units and contextUnits must contain at most 32 entries");
        }
        Set<String> targetKeys = new HashSet<>(units.stream().map(Unit::unitKey).toList());
        Set<String> contextKeys = new HashSet<>();
        int previousContextOrdinal = 0;
        for (Unit context : contextUnits) {
            if (!contextKeys.add(context.unitKey())) {
                throw new IllegalArgumentException("context unit keys must be unique");
            }
            if (targetKeys.contains(context.unitKey())
                    || units.stream().anyMatch(target -> target.ordinal() == context.ordinal())) {
                throw new IllegalArgumentException("context units must not overlap target units");
            }
            if (context.ordinal() <= previousContextOrdinal) {
                throw new IllegalArgumentException("context unit ordinals must be strictly increasing");
            }
            previousContextOrdinal = context.ordinal();
        }
    }

    /** One preserved parsed unit; ordinal remains global rather than being re-numbered by the extraction slice. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Unit(@JsonProperty("unit_key") String unitKey, int ordinal, String content) {
        public Unit {
            unitKey = StructuredSkillContract.key(unitKey, "unitKey");
            if (ordinal < 1) throw new IllegalArgumentException("ordinal must be at least one");
            if (content == null || content.isEmpty()) throw new IllegalArgumentException("content must not be empty");
            if (content.getBytes(StandardCharsets.UTF_8).length > 65_536) {
                throw new IllegalArgumentException("content exceeds maximum UTF-8 bytes");
            }
        }
    }
}
