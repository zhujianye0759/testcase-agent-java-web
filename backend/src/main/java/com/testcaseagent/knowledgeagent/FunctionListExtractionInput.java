package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Exact extract-function-list input for one globally numbered material slice. [Req-ID]: REQ-SKI-003, REQ-STG-003 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FunctionListExtractionInput(
        String operation,
        @JsonProperty("material_key") String materialKey,
        @JsonProperty("source_label") String sourceLabel,
        List<Unit> units) {
    static final String OPERATION = "extract_function_list";

    /** Creates the only permitted feature-list extraction operation. */
    public FunctionListExtractionInput(String materialKey, String sourceLabel, List<Unit> units) {
        this(OPERATION, materialKey, sourceLabel, units);
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
