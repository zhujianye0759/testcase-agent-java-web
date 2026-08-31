package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact input for one bounded requirement-material review slice. The caller-owned material key
 * is an opaque correlation value, not a KnowledgeEngineeringEngine document identifier.
 *
 * [Req-ID]: REQ-SKI-003, REQ-SMS-003
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RequirementMaterialQualityReviewInput(
        @JsonProperty("material_key") String materialKey,
        @JsonProperty("content_type_key") MaterialContentTypeKey contentTypeKey,
        @JsonProperty("source_label") String sourceLabel,
        List<MaterialUnit> units,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("context_units") List<MaterialUnit> contextUnits) {

    /** Preserves the V1 wire shape when a caller has no read-only context. */
    public RequirementMaterialQualityReviewInput(String materialKey, MaterialContentTypeKey contentTypeKey,
            String sourceLabel, List<MaterialUnit> units) {
        this(materialKey, contentTypeKey, sourceLabel, units, List.of());
    }

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
        contextUnits = contextUnits == null ? List.of() : List.copyOf(contextUnits);
        if (units.size() + contextUnits.size() > 32) {
            throw new IllegalArgumentException("units and contextUnits must contain at most 32 entries");
        }
        Set<String> targetKeys = new HashSet<>(units.stream().map(MaterialUnit::unitKey).toList());
        int previousContextOrdinal = 0;
        Set<String> contextKeys = new HashSet<>();
        for (MaterialUnit context : contextUnits) {
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
