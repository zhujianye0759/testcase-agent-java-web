package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Exact input for one bounded requirement-material review slice. [Req-ID]: REQ-SKI-003 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RequirementMaterialQualityReviewInput(
        @JsonProperty("material_key") String materialKey,
        @JsonProperty("content_type_key") MaterialContentTypeKey contentTypeKey,
        @JsonProperty("source_label") String sourceLabel,
        List<MaterialUnit> units) {
    public RequirementMaterialQualityReviewInput {
        materialKey = StructuredSkillContract.key(materialKey, "materialKey");
        if (contentTypeKey == null) throw new IllegalArgumentException("contentTypeKey must not be null");
        sourceLabel = StructuredSkillContract.text(sourceLabel, "sourceLabel");
        units = StructuredSkillContract.list(units, "units", 1, 32);
        StructuredSkillContract.uniqueKeys(units.stream().map(MaterialUnit::unitKey).toList(), "unit");
        int expected = units.get(0).ordinal();
        if (expected < 1) throw new IllegalArgumentException("first unit ordinal must be at least one");
        for (MaterialUnit unit : units) {
            if (unit.ordinal() != expected++) throw new IllegalArgumentException("unit ordinals must be continuous");
        }
    }

    /** One preserved parsed-unit slice entry; its ordinal is global, not re-numbered per call. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MaterialUnit(@JsonProperty("unit_key") String unitKey, int ordinal, String content) {
        public MaterialUnit {
            unitKey = StructuredSkillContract.key(unitKey, "unitKey");
            if (ordinal < 1) throw new IllegalArgumentException("ordinal must be at least one");
            if (content == null || content.isEmpty()) throw new IllegalArgumentException("content must not be empty");
            if (content.getBytes(StandardCharsets.UTF_8).length > 65_536) throw new IllegalArgumentException("content exceeds maximum UTF-8 bytes");
        }
    }
}
