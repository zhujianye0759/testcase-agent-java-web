package com.testcaseagent.structuredgeneration;

import com.testcaseagent.featureaudit.MaterialInventoryUnit;
import com.testcaseagent.knowledgeagent.MaterialContentTypeKey;
import com.testcaseagent.knowledgeagent.FunctionListExtractionInput;
import com.testcaseagent.knowledgeagent.FunctionCandidateExtractionInput;
import com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInput;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Creates bounded review inputs from an already complete parsed-material inventory.
 *
 * <p>The planner validates and retains input order. It never sorts or re-numbers a malformed inventory because doing
 * so would hide a broken global parsed-unit sequence.</p>
 *
 * [Req-ID]: REQ-SKI-001, REQ-SKI-003, REQ-STG-002
 */
public final class StructuredMaterialSlicePlanner {
    private static final int MIN_TARGET_UNITS = 8;
    private static final int IDEAL_TARGET_UNITS = 12;
    private static final int MAX_TARGET_UNITS = 16;
    private static final int CONTEXT_UNITS_PER_SIDE = 4;
    private static final Pattern HEADING = Pattern.compile(
            "^(?:#{1,6}\\s+|第[一二三四五六七八九十百千万0-9]+[章节部分]|[一二三四五六七八九十]+[、.]|[0-9]+(?:\\.[0-9]+)*[、.\\s])");
    private static final Pattern FUNCTION_PATH = Pattern.compile("^(?:功能路径|功能菜单|菜单路径|业务路径)\\s*[:：]");

    /** Plans review requests whose target ownership is separate from their adjacent read-only context. */
    public List<RequirementMaterialQualityReviewInput> plan(
            String materialKey,
            MaterialContentTypeKey contentTypeKey,
            String sourceLabel,
            List<MaterialInventoryUnit> units) {
        Objects.requireNonNull(contentTypeKey, "contentTypeKey must not be null");
        return planWindows(units).stream().map(window -> new RequirementMaterialQualityReviewInput(
                materialKey, contentTypeKey, sourceLabel,
                reviewUnits(window.targetUnits()), reviewUnits(window.contextUnits()))).toList();
    }

    /** Plans function-list target ownership with the same semantic and context rules as review. */
    public List<FunctionListExtractionInput> planExtraction(
            String materialKey, String sourceLabel, List<MaterialInventoryUnit> units) {
        return planWindows(units).stream().map(window -> extractionInput(
                materialKey, sourceLabel, window)).toList();
    }

    /** Plans protocol V1 candidate windows and derives their durable identities from the frozen task. */
    public List<FunctionCandidateExtractionInput> planCandidateExtraction(
            String taskId, String materialKey, String sourceLabel, List<MaterialInventoryUnit> units) {
        return planWindows(units).stream().map(window -> candidateExtractionInput(
                taskId, materialKey, sourceLabel, window)).toList();
    }

    /** Reconstructs the former 32-unit root partition only for durable pre-V17 work. */
    public List<RequirementMaterialQualityReviewInput> legacyReviewPlan(String materialKey,
            MaterialContentTypeKey contentTypeKey, String sourceLabel, List<MaterialInventoryUnit> units) {
        List<MaterialInventoryUnit> checked = checkedInventory(units);
        java.util.ArrayList<RequirementMaterialQualityReviewInput> result = new java.util.ArrayList<>();
        for (int offset = 0; offset < checked.size(); offset += 32) {
            List<MaterialInventoryUnit> target = checked.subList(offset, Math.min(offset + 32, checked.size()));
            result.add(new RequirementMaterialQualityReviewInput(
                    materialKey, contentTypeKey, sourceLabel, reviewUnits(target)));
        }
        return List.copyOf(result);
    }

    /** Reconstructs the former extraction partition so completed historical siblings remain stable. */
    public List<FunctionListExtractionInput> legacyExtractionPlan(
            String materialKey, String sourceLabel, List<MaterialInventoryUnit> units) {
        List<MaterialInventoryUnit> checked = checkedInventory(units);
        java.util.ArrayList<FunctionListExtractionInput> result = new java.util.ArrayList<>();
        for (int offset = 0; offset < checked.size(); offset += 32) {
            result.add(new FunctionListExtractionInput(materialKey, sourceLabel,
                    extractionUnits(checked.subList(offset, Math.min(offset + 32, checked.size())))));
        }
        return List.copyOf(result);
    }

    /** Builds one review DTO from an already validated planned window. */
    public RequirementMaterialQualityReviewInput reviewInput(String materialKey,
            MaterialContentTypeKey contentTypeKey, String sourceLabel, PlannedWindow window) {
        Objects.requireNonNull(window, "window must not be null");
        return new RequirementMaterialQualityReviewInput(materialKey, contentTypeKey, sourceLabel,
                reviewUnits(window.targetUnits()), reviewUnits(window.contextUnits()));
    }

    /** Builds one extraction DTO from an already validated planned window. */
    public FunctionListExtractionInput extractionInput(
            String materialKey, String sourceLabel, PlannedWindow window) {
        Objects.requireNonNull(window, "window must not be null");
        return new FunctionListExtractionInput(materialKey, sourceLabel,
                extractionUnits(window.targetUnits()), extractionUnits(window.contextUnits()));
    }

    /** Builds one candidate DTO after a deterministic split has recomputed adjacent read-only context. */
    public FunctionCandidateExtractionInput candidateExtractionInput(
            String taskId, String materialKey, String sourceLabel, PlannedWindow window) {
        Objects.requireNonNull(window, "window must not be null");
        return FunctionCandidateExtractionInput.forWindow(taskId, materialKey, sourceLabel,
                candidateUnits(window.targetUnits()), candidateUnits(window.contextUnits()));
    }

    /**
     * Plans deterministic target windows. Eight to sixteen is the normal planning range, while a complete short
     * material remains one legal target instead of being padded or merged with invented content.
     * [Req-ID]: REQ-FTG-013
     */
    public List<PlannedWindow> planWindows(List<MaterialInventoryUnit> units) {
        List<MaterialInventoryUnit> checked = checkedInventory(units);
        java.util.ArrayList<PlannedWindow> windows = new java.util.ArrayList<>();
        int offset = 0;
        while (offset < checked.size()) {
            int targetSize = targetSize(checked, offset, checked.size() - offset);
            windows.add(window(checked, offset, offset + targetSize));
            offset += targetSize;
        }
        return List.copyOf(windows);
    }

    /**
     * Plans the next semantic window from a bounded database neighborhood. The supplied slice must contain the four
     * preceding context units when available and twenty units from the target cursor when available, which is enough
     * for the 16 target plus four following context contract. [Req-ID]: REQ-FTG-013, REQ-TGV2-003
     */
    public PlannedWindow planNextWindow(List<MaterialInventoryUnit> neighborhood, int targetStartOrdinal,
            int documentFirstOrdinal, int remainingTargetUnits) {
        List<MaterialInventoryUnit> checked = checkedInventory(neighborhood);
        if (remainingTargetUnits < 1 || targetStartOrdinal < documentFirstOrdinal) {
            throw new IllegalArgumentException("A positive in-document target cursor is required");
        }
        int targetOffset = targetStartOrdinal - checked.get(0).ordinal();
        int expectedFirst = Math.max(documentFirstOrdinal, targetStartOrdinal - CONTEXT_UNITS_PER_SIDE);
        int expectedLast = targetStartOrdinal + Math.min(remainingTargetUnits - 1,
                MAX_TARGET_UNITS + CONTEXT_UNITS_PER_SIDE - 1);
        if (checked.get(0).ordinal() != expectedFirst
                || checked.get(checked.size() - 1).ordinal() != expectedLast
                || targetOffset < 0 || targetOffset >= checked.size()) {
            throw new IllegalArgumentException("Bounded material neighborhood is incomplete");
        }
        int size = targetSize(checked, targetOffset, remainingTargetUnits);
        return window(checked, targetOffset, targetOffset + size);
    }

    /** Deterministically bisects only target ownership and recomputes context from the frozen inventory. */
    public List<PlannedWindow> bisect(List<MaterialInventoryUnit> units, PlannedWindow parent) {
        List<MaterialInventoryUnit> checked = checkedInventory(units);
        Objects.requireNonNull(parent, "parent must not be null");
        if (parent.targetUnits().size() < 2) {
            throw new IllegalArgumentException("A one-unit material window cannot be split");
        }
        int start = indexOfTarget(checked, parent.targetUnits());
        int middle = start + parent.targetUnits().size() / 2;
        return List.of(window(checked, start, middle),
                window(checked, middle, start + parent.targetUnits().size()));
    }

    /** Restores one persisted target/context plan from exact keys in the same frozen inventory. */
    public PlannedWindow restoreWindow(List<MaterialInventoryUnit> units,
            List<String> targetKeys, List<String> contextKeys) {
        List<MaterialInventoryUnit> checked = checkedInventory(units);
        List<String> checkedTargetKeys = List.copyOf(Objects.requireNonNull(targetKeys, "targetKeys must not be null"));
        List<String> checkedContextKeys = List.copyOf(Objects.requireNonNull(contextKeys, "contextKeys must not be null"));
        List<MaterialInventoryUnit> target = checked.stream()
                .filter(unit -> checkedTargetKeys.contains(unit.unitId())).toList();
        List<MaterialInventoryUnit> context = checked.stream()
                .filter(unit -> checkedContextKeys.contains(unit.unitId())).toList();
        if (!target.stream().map(MaterialInventoryUnit::unitId).toList().equals(checkedTargetKeys)
                || !context.stream().map(MaterialInventoryUnit::unitId).toList().equals(checkedContextKeys)) {
            throw new IllegalArgumentException("Persisted target or context keys do not match the frozen inventory");
        }
        indexOfTarget(checked, target);
        return new PlannedWindow(target, context);
    }

    private static List<MaterialInventoryUnit> checkedInventory(List<MaterialInventoryUnit> units) {
        List<MaterialInventoryUnit> checked = List.copyOf(Objects.requireNonNull(units, "units must not be null"));
        if (checked.isEmpty()) throw new IllegalArgumentException("A material must contain parsed units");
        requireContinuousDistinctUnits(checked);
        return checked;
    }

    private static int targetSize(List<MaterialInventoryUnit> units, int offset, int remaining) {
        if (remaining <= MAX_TARGET_UNITS) return remaining;
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int size = MIN_TARGET_UNITS; size <= MAX_TARGET_UNITS; size++) {
            int tail = remaining - size;
            if (tail > 0 && tail < MIN_TARGET_UNITS) continue;
            if (!startsSemanticBoundary(units, offset + size)) continue;
            int distance = Math.abs(size - IDEAL_TARGET_UNITS);
            if (distance < bestDistance) {
                best = size;
                bestDistance = distance;
            }
        }
        if (best > 0) return best;
        int target = IDEAL_TARGET_UNITS;
        if (remaining - target < MIN_TARGET_UNITS) target = remaining - MIN_TARGET_UNITS;
        return Math.max(MIN_TARGET_UNITS, Math.min(MAX_TARGET_UNITS, target));
    }

    private static boolean startsSemanticBoundary(List<MaterialInventoryUnit> units, int index) {
        if (index >= units.size()) return true;
        String current = units.get(index).content().stripLeading();
        String previous = units.get(index - 1).content();
        boolean tableBoundary = current.startsWith("|") != previous.stripLeading().startsWith("|");
        return HEADING.matcher(current).find() || FUNCTION_PATH.matcher(current).find() || tableBoundary;
    }

    private static PlannedWindow window(List<MaterialInventoryUnit> units, int startInclusive, int endExclusive) {
        List<MaterialInventoryUnit> target = List.copyOf(units.subList(startInclusive, endExclusive));
        java.util.ArrayList<MaterialInventoryUnit> context = new java.util.ArrayList<>();
        context.addAll(units.subList(Math.max(0, startInclusive - CONTEXT_UNITS_PER_SIDE), startInclusive));
        context.addAll(units.subList(endExclusive,
                Math.min(units.size(), endExclusive + CONTEXT_UNITS_PER_SIDE)));
        return new PlannedWindow(target, context);
    }

    private static int indexOfTarget(List<MaterialInventoryUnit> inventory, List<MaterialInventoryUnit> target) {
        if (target.isEmpty()) throw new IllegalArgumentException("targetUnits must not be empty");
        int start = -1;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.get(i).unitId().equals(target.get(0).unitId())) {
                start = i;
                break;
            }
        }
        if (start < 0 || start + target.size() > inventory.size()
                || !inventory.subList(start, start + target.size()).equals(target)) {
            throw new IllegalArgumentException("Target units must be one exact continuous inventory window");
        }
        return start;
    }

    private static List<RequirementMaterialQualityReviewInput.MaterialUnit> reviewUnits(
            List<MaterialInventoryUnit> units) {
        return units.stream().map(unit -> new RequirementMaterialQualityReviewInput.MaterialUnit(
                unit.unitId(), unit.ordinal(), unit.content())).toList();
    }

    private static List<FunctionListExtractionInput.Unit> extractionUnits(List<MaterialInventoryUnit> units) {
        return units.stream().map(unit -> new FunctionListExtractionInput.Unit(
                unit.unitId(), unit.ordinal(), unit.content())).toList();
    }

    private static List<FunctionCandidateExtractionInput.Unit> candidateUnits(List<MaterialInventoryUnit> units) {
        return units.stream().map(unit -> new FunctionCandidateExtractionInput.Unit(
                unit.unitId(), unit.ordinal(), unit.content())).toList();
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

    /** Target units own output; context units are adjacent read-only material from the same frozen inventory. */
    public record PlannedWindow(List<MaterialInventoryUnit> targetUnits, List<MaterialInventoryUnit> contextUnits) {
        public PlannedWindow {
            targetUnits = List.copyOf(Objects.requireNonNull(targetUnits, "targetUnits must not be null"));
            contextUnits = List.copyOf(Objects.requireNonNull(contextUnits, "contextUnits must not be null"));
            if (targetUnits.isEmpty()) throw new IllegalArgumentException("targetUnits must not be empty");
            if (targetUnits.size() + contextUnits.size() > 32) {
                throw new IllegalArgumentException("targetUnits and contextUnits must contain at most 32 entries");
            }
        }
    }
}
