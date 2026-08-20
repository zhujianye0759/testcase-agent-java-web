package com.testcaseagent.structuredgeneration;

import com.testcaseagent.featureaudit.MaterialInventoryUnit;
import com.testcaseagent.knowledgeagent.MaterialContentTypeKey;
import com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInput;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Creates bounded review inputs from an already complete parsed-material inventory.
 *
 * <p>The planner validates and retains input order. It never sorts or re-numbers a malformed inventory because doing
 * so would hide a broken global parsed-unit sequence.</p>
 *
 * [Req-ID]: REQ-SKI-001, REQ-SKI-003, REQ-STG-002
 */
public final class StructuredMaterialSlicePlanner {
    private static final int MAX_UNITS_PER_SLICE = 32;

    /** Splits one complete material into 1..32-unit calls while preserving its global ordinal. */
    public List<RequirementMaterialQualityReviewInput> plan(
            String materialKey,
            MaterialContentTypeKey contentTypeKey,
            String sourceLabel,
            List<MaterialInventoryUnit> units) {
        Objects.requireNonNull(contentTypeKey, "contentTypeKey must not be null");
        List<MaterialInventoryUnit> checked = List.copyOf(Objects.requireNonNull(units, "units must not be null"));
        if (checked.isEmpty()) throw new IllegalArgumentException("A review material must contain parsed units");
        requireContinuousDistinctUnits(checked);

        List<RequirementMaterialQualityReviewInput> slices = new ArrayList<>();
        for (int offset = 0; offset < checked.size(); offset += MAX_UNITS_PER_SLICE) {
            List<RequirementMaterialQualityReviewInput.MaterialUnit> slice = checked.subList(
                            offset, Math.min(offset + MAX_UNITS_PER_SLICE, checked.size())).stream()
                    .map(unit -> new RequirementMaterialQualityReviewInput.MaterialUnit(
                            unit.unitId(), unit.ordinal(), unit.content()))
                    .toList();
            slices.add(new RequirementMaterialQualityReviewInput(
                    materialKey, contentTypeKey, sourceLabel, slice));
        }
        return List.copyOf(slices);
    }

    private static void requireContinuousDistinctUnits(List<MaterialInventoryUnit> units) {
        Set<String> unitKeys = new HashSet<>();
        int expectedOrdinal = units.get(0).ordinal();
        for (MaterialInventoryUnit unit : units) {
            MaterialInventoryUnit checked = Objects.requireNonNull(unit, "unit must not be null");
            if (checked.ordinal() != expectedOrdinal++) {
                throw new IllegalArgumentException("Material unit ordinals must remain globally continuous");
            }
            if (!unitKeys.add(checked.unitId())) {
                throw new IllegalArgumentException("Material unit keys must be unique");
            }
        }
    }
}
