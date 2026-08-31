package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exact V2 input for one function and one admitted material window. [Req-ID]: REQ-TGV2-003 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RequirementFactExtractionV2Input(
        @JsonProperty("function_key") String functionKey,
        @JsonProperty("function_name") String functionName,
        @JsonProperty("function_path") String functionPath,
        @JsonProperty("function_description") String functionDescription,
        @JsonProperty("material_key") String materialKey,
        @JsonProperty("content_type_key") MaterialContentTypeKey contentTypeKey,
        @JsonProperty("window_key") String windowKey,
        List<MaterialUnit> units,
        @JsonProperty("context_units") List<MaterialUnit> contextUnits) {

    public RequirementFactExtractionV2Input {
        functionKey = StructuredSkillContract.key(functionKey, "functionKey");
        functionName = StructuredSkillContract.text(functionName, "functionName");
        functionPath = StructuredSkillContract.text(functionPath, "functionPath");
        functionDescription = StructuredSkillContract.optionalText(functionDescription, "functionDescription");
        materialKey = StructuredSkillContract.key(materialKey, "materialKey");
        if (contentTypeKey != MaterialContentTypeKey.REQUIREMENTS_SPEC
                && contentTypeKey != MaterialContentTypeKey.WORK_ORDER_PLAN) {
            throw new IllegalArgumentException("contentTypeKey must be a formal requirement material type");
        }
        windowKey = StructuredSkillContract.key(windowKey, "windowKey");
        units = StructuredSkillContract.list(units, "units", 1, 32);
        contextUnits = StructuredSkillContract.list(contextUnits, "contextUnits", 0, 32);
        requireContinuous(units);
        requireDistinctClosure(units, contextUnits);
    }

    private static void requireContinuous(List<MaterialUnit> values) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index).ordinal() != values.get(index - 1).ordinal() + 1) {
                throw new IllegalArgumentException("target unit ordinals must be continuous");
            }
        }
    }

    private static void requireDistinctClosure(List<MaterialUnit> targets, List<MaterialUnit> context) {
        Set<String> keys = new HashSet<>();
        Set<Integer> ordinals = new HashSet<>();
        for (MaterialUnit unit : java.util.stream.Stream.concat(targets.stream(), context.stream()).toList()) {
            if (!keys.add(unit.unitKey()) || !ordinals.add(unit.ordinal())) {
                throw new IllegalArgumentException("target and context units must not overlap or repeat");
            }
        }
    }

    /** Parsed-unit content is deliberately not subject to the ordinary 16 KiB text limit. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MaterialUnit(
            @JsonProperty("unit_key") String unitKey,
            int ordinal,
            String content) {
        public MaterialUnit {
            unitKey = StructuredSkillContract.key(unitKey, "unitKey");
            if (ordinal < 1) throw new IllegalArgumentException("ordinal must be positive");
            content = StructuredSkillContract.parsedUnitContent(content, "content");
        }
    }
}
