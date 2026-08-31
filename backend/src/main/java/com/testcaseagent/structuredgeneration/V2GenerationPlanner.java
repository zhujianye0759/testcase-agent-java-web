package com.testcaseagent.structuredgeneration;

import com.testcaseagent.featureaudit.MaterialInventoryDocument;
import com.testcaseagent.featureaudit.MaterialInventoryUnit;
import com.testcaseagent.identity.LengthPrefixedSha256;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Input;
import com.testcaseagent.knowledgeagent.MaterialContentTypeKey;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Input;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Result.FactType;
import com.testcaseagent.knowledgeagent.StructuredSourceQuoteV2;
import com.testcaseagent.task.ApprovedFunctionScope;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Converts one frozen V2 scope into restart-stable material windows and dynamic test points.
 * The planner owns identities only; it never reads model output or invents business facts.
 *
 * [Req-ID]: REQ-TGV2-003, REQ-TGV2-005, REQ-TGV2-006, REQ-TGV2-008
 */
public final class V2GenerationPlanner {

    private static final String MISSING_FORMAL_FACT_DESCRIPTION = "根据已审核功能范围形成待确认测试设计";
    private static final String MISSING_FORMAL_FACT_INFORMATION = "正式需求材料中未提取到可引用的原子需求事实";
    private final StructuredMaterialSlicePlanner windowPlanner = new StructuredMaterialSlicePlanner();

    /** Plans every parsed unit exactly once as a target while retaining adjacent read-only context. */
    public List<FactWindow> factWindows(String taskId, ApprovedFunctionScope.ApprovedFunction function,
            MaterialInventoryDocument material) {
        require(taskId, "taskId");
        Objects.requireNonNull(function, "function must not be null");
        Objects.requireNonNull(material, "material must not be null");
        return windowPlanner.planWindows(material.units()).stream().map(window -> factWindow(
                taskId, function, descriptor(material), window, null, 0)).toList();
    }

    /** Plans one restart-stable fact window from a bounded persisted-unit neighborhood. */
    public FactWindow nextFactWindow(String taskId, ApprovedFunctionScope.ApprovedFunction function,
            MaterialDescriptor material, List<MaterialInventoryUnit> neighborhood, int targetStartOrdinal) {
        Objects.requireNonNull(material, "material must not be null");
        int remaining = material.lastOrdinal() - targetStartOrdinal + 1;
        StructuredMaterialSlicePlanner.PlannedWindow window = windowPlanner.planNextWindow(
                neighborhood, targetStartOrdinal, material.firstOrdinal(), remaining);
        return factWindow(taskId, function, material, window, null, 0);
    }

    /** Bisects one persisted oversized target and recomputes context from the same frozen inventory. */
    public List<FactWindow> bisectFactWindow(String taskId, ApprovedFunctionScope.ApprovedFunction function,
            MaterialInventoryDocument material,
            StructuredGenerationAcceptanceStore.MaterialWindowPlan parent) {
        Objects.requireNonNull(parent, "parent must not be null");
        StructuredMaterialSlicePlanner.PlannedWindow restored = windowPlanner.restoreWindow(material.units(),
                parent.targetEvidenceKeys(), parent.contextEvidenceKeys());
        return windowPlanner.bisect(material.units(), restored).stream().map(window -> factWindow(
                taskId, function, descriptor(material), window, parent.workItemId(), parent.splitDepth() + 1)).toList();
    }

    /** Bisects one bounded target using only its already frozen target and adjacent context neighborhood. */
    public List<FactWindow> bisectFactWindow(String taskId, ApprovedFunctionScope.ApprovedFunction function,
            MaterialDescriptor material, FactWindow parent, String parentWorkItemId) {
        List<MaterialInventoryUnit> neighborhood = java.util.stream.Stream.concat(
                        parent.targetUnits().stream(), parent.contextUnits().stream())
                .sorted(java.util.Comparator.comparingInt(MaterialInventoryUnit::ordinal)).toList();
        StructuredMaterialSlicePlanner.PlannedWindow restored = windowPlanner.restoreWindow(neighborhood,
                parent.targetUnits().stream().map(MaterialInventoryUnit::unitId).toList(),
                parent.contextUnits().stream().map(MaterialInventoryUnit::unitId).toList());
        return windowPlanner.bisect(neighborhood, restored).stream().map(window -> factWindow(
                taskId, function, material, window, parentWorkItemId,
                parent.registration().splitDepth() + 1)).toList();
    }

    private static FactWindow factWindow(String taskId, ApprovedFunctionScope.ApprovedFunction function,
            MaterialDescriptor material, StructuredMaterialSlicePlanner.PlannedWindow window,
            String parentWorkItemId, int splitDepth) {
        List<String> targets = keys(window.targetUnits());
        List<String> context = keys(window.contextUnits());
        String windowKey = hash("requirement-fact-window-v2", taskId, function.functionKey(),
                material.documentId(), String.join("\n", targets), String.join("\n", context));
        RequirementFactExtractionV2Input input = new RequirementFactExtractionV2Input(
                function.functionKey(), function.name(), function.path(), function.description(),
                material.documentId(), contentType(material.documentRole()), windowKey, units(window.targetUnits()),
                units(window.contextUnits()));
        StructuredGenerationAcceptanceStore.WorkRegistration registration =
                new StructuredGenerationAcceptanceStore.WorkRegistration(taskId, windowKey,
                        "requirement-fact-extraction", "REQUIREMENT_FACT_EXTRACTION_V2",
                        window.targetUnits().get(0).ordinal(),
                        window.targetUnits().get(window.targetUnits().size() - 1).ordinal(),
                        material.documentId(), sourceLabel(material.documentRole()), targets,
                        function.functionKey(), null, material.documentId(), context,
                        parentWorkItemId, splitDepth);
        return new FactWindow(registration, input, window.targetUnits(), window.contextUnits());
    }

    /**
     * Derives one formal test point per atomic fact. A function with no fact still gets one explicit pending point,
     * so weak material lowers the result confidence instead of suppressing the entire artifact.
     */
    public List<TestPointPlan> testPoints(String taskId, ApprovedFunctionScope.ApprovedFunction function,
            List<PersistedFact> facts) {
        return testPoints(taskId, function, facts, true);
    }

    /** Adds an explicit pending point when one or more independent fact windows ended technically incomplete. */
    public List<TestPointPlan> testPoints(String taskId, ApprovedFunctionScope.ApprovedFunction function,
            List<PersistedFact> facts, boolean factExtractionComplete) {
        require(taskId, "taskId");
        Objects.requireNonNull(function, "function must not be null");
        List<PersistedFact> checked = List.copyOf(Objects.requireNonNull(facts, "facts must not be null"));
        if (checked.isEmpty()) {
            return List.of(missingFormalFactTestPoint(taskId, function));
        }
        java.util.ArrayList<TestPointPlan> points = new java.util.ArrayList<>(checked.stream()
                .map(fact -> testPoint(taskId, function, fact)).toList());
        if (!factExtractionComplete) {
            points.add(incompleteFactWindowsTestPoint(taskId, function));
        }
        return List.copyOf(points);
    }

    /** Derives one independently executable test-point work item from one atomic persisted fact. */
    public TestPointPlan testPoint(String taskId, ApprovedFunctionScope.ApprovedFunction function,
            PersistedFact fact) {
        require(taskId, "taskId");
        Objects.requireNonNull(function, "function must not be null");
        Objects.requireNonNull(fact, "fact must not be null");
        String pointKey = hash("test-point-v2", taskId, function.functionKey(), fact.factKey());
        FunctionalTestcaseDesignV2Input.TestPoint point = new FunctionalTestcaseDesignV2Input.TestPoint(
                pointKey, pointType(fact.factType()), FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT,
                fact.statement(), List.of());
        FunctionalTestcaseDesignV2Input.RequirementFact inputFact =
                new FunctionalTestcaseDesignV2Input.RequirementFact(fact.factKey(), fact.factType(),
                        fact.statement(), fact.sourceQuotes());
        return testPointPlan(taskId, function, point, List.of(inputFact));
    }

    /** Produces the explicit pending work item used when a function has no accepted formal fact. */
    public TestPointPlan missingFormalFactTestPoint(
            String taskId, ApprovedFunctionScope.ApprovedFunction function) {
        require(taskId, "taskId");
        Objects.requireNonNull(function, "function must not be null");
        String pointKey = missingFormalFactPointKey(taskId, function.functionKey());
        FunctionalTestcaseDesignV2Input.TestPoint point = new FunctionalTestcaseDesignV2Input.TestPoint(
                pointKey, FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                FunctionalTestcaseDesignV2Input.Basis.GENERAL_EXPERIENCE,
                MISSING_FORMAL_FACT_DESCRIPTION, List.of(MISSING_FORMAL_FACT_INFORMATION));
        return testPointPlan(taskId, function, point, List.of());
    }

    /** Reader-facing description of the exact no-fact fallback audited by explicit V2 recovery. */
    public static String missingFormalFactDescription() {
        return MISSING_FORMAL_FACT_DESCRIPTION;
    }

    /** Single missing-information value of the exact no-fact fallback audited by explicit V2 recovery. */
    public static String missingFormalFactInformation() {
        return MISSING_FORMAL_FACT_INFORMATION;
    }

    /**
     * Recomputes the stable identity of the no-fact fallback without using its reader-facing wording.
     * Explicit recovery uses this identity to retire only the exact fallback produced by this planner.
     * [Req-ID]: REQ-TGV2-011
     */
    public static String missingFormalFactPointKey(String taskId, String functionKey) {
        require(taskId, "taskId");
        require(functionKey, "functionKey");
        return hash("test-point-v2", taskId, functionKey, "missing-formal-fact");
    }

    /** Produces one extra pending point for a function whose fact-window traversal was technically incomplete. */
    public TestPointPlan incompleteFactWindowsTestPoint(
            String taskId, ApprovedFunctionScope.ApprovedFunction function) {
        require(taskId, "taskId");
        Objects.requireNonNull(function, "function must not be null");
        String pointKey = hash("test-point-v2", taskId, function.functionKey(), "incomplete-fact-windows");
        FunctionalTestcaseDesignV2Input.TestPoint point = new FunctionalTestcaseDesignV2Input.TestPoint(
                pointKey, FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                FunctionalTestcaseDesignV2Input.Basis.GENERAL_EXPERIENCE,
                "对未完成的需求事实范围形成待确认测试设计",
                List.of("部分正式需求材料窗口未完成事实提取"));
        return testPointPlan(taskId, function, point, List.of());
    }

    private static TestPointPlan testPointPlan(String taskId, ApprovedFunctionScope.ApprovedFunction function,
            FunctionalTestcaseDesignV2Input.TestPoint point,
            List<FunctionalTestcaseDesignV2Input.RequirementFact> facts) {
        FunctionalTestcaseDesignV2Input input = new FunctionalTestcaseDesignV2Input(function.functionKey(),
                function.name(), function.path(), function.description(), point, facts);
        String identity = hash("functional-testcase-design-v2", taskId, function.functionKey(),
                point.testPointKey());
        StructuredGenerationAcceptanceStore.WorkRegistration registration =
                new StructuredGenerationAcceptanceStore.WorkRegistration(taskId, identity,
                        "functional-testcase-design", "FUNCTIONAL_TESTCASE_DESIGN_V2", null, null,
                        null, null, List.of(), function.functionKey(), point.testPointKey(),
                        null, List.of(), null, 0);
        return new TestPointPlan(registration, input);
    }

    private static FunctionalTestcaseDesignV2Input.TestPointType pointType(FactType type) {
        return switch (type) {
            case ROLE, PERMISSION -> FunctionalTestcaseDesignV2Input.TestPointType.PERMISSION;
            case INPUT -> FunctionalTestcaseDesignV2Input.TestPointType.INPUT_VALIDATION;
            case STATE_CHANGE -> FunctionalTestcaseDesignV2Input.TestPointType.STATE_TRANSITION;
            case EXCEPTION_HANDLING -> FunctionalTestcaseDesignV2Input.TestPointType.BUSINESS_EXCEPTION;
            case EXTERNAL_DEPENDENCY -> FunctionalTestcaseDesignV2Input.TestPointType.DEPENDENCY_FAILURE;
            case TRIGGER_CONDITION, BUSINESS_RULE, OUTPUT ->
                    FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR;
        };
    }

    private static MaterialContentTypeKey contentType(String role) {
        return switch (role) {
            case "REQUIREMENT" -> MaterialContentTypeKey.REQUIREMENTS_SPEC;
            case "WORK_ORDER_PLAN" -> MaterialContentTypeKey.WORK_ORDER_PLAN;
            default -> throw new IllegalArgumentException("V2 facts require a formal requirement material");
        };
    }

    private static String sourceLabel(String role) {
        return "WORK_ORDER_PLAN".equals(role) ? "工单方案" : "需求规格说明";
    }

    private static List<String> keys(List<MaterialInventoryUnit> units) {
        return units.stream().map(MaterialInventoryUnit::unitId).toList();
    }

    private static List<RequirementFactExtractionV2Input.MaterialUnit> units(
            List<MaterialInventoryUnit> units) {
        return units.stream().map(unit -> new RequirementFactExtractionV2Input.MaterialUnit(
                unit.unitId(), unit.ordinal(), unit.content())).toList();
    }

    private static String hash(String... fields) {
        return HexFormat.of().formatHex(LengthPrefixedSha256.digest(fields));
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    /** One exact material call and its matching durable work coordinates. */
    public record FactWindow(StructuredGenerationAcceptanceStore.WorkRegistration registration,
            RequirementFactExtractionV2Input input, List<MaterialInventoryUnit> targetUnits,
            List<MaterialInventoryUnit> contextUnits) {
        public FactWindow {
            targetUnits = List.copyOf(targetUnits);
            contextUnits = List.copyOf(contextUnits);
        }
    }

    /** One dynamic test-point call and its durable work coordinates. */
    public record TestPointPlan(StructuredGenerationAcceptanceStore.WorkRegistration registration,
            FunctionalTestcaseDesignV2Input input) { }

    /** Java-owned persisted fact supplied back to KEE only after exact quote validation. */
    public record PersistedFact(String factKey, FactType factType, String statement,
            List<StructuredSourceQuoteV2> sourceQuotes) {
        public PersistedFact {
            require(factKey, "factKey");
            Objects.requireNonNull(factType, "factType must not be null");
            require(statement, "statement");
            sourceQuotes = List.copyOf(sourceQuotes);
            if (sourceQuotes.isEmpty()) throw new IllegalArgumentException("sourceQuotes must not be empty");
        }
    }

    /** Lightweight material identity used while parsed units are fetched in bounded neighborhoods. */
    public record MaterialDescriptor(
            String documentId, String documentRole, int totalUnits, int firstOrdinal, int lastOrdinal) {
        public MaterialDescriptor {
            require(documentId, "documentId");
            require(documentRole, "documentRole");
            if (totalUnits < 1 || firstOrdinal < 1 || lastOrdinal - firstOrdinal + 1 != totalUnits) {
                throw new IllegalArgumentException("Material ordinals must describe one complete continuous inventory");
            }
        }
    }

    private static MaterialDescriptor descriptor(MaterialInventoryDocument material) {
        return new MaterialDescriptor(material.documentId(), material.documentRole(), material.totalUnits(),
                material.units().get(0).ordinal(), material.units().get(material.units().size() - 1).ordinal());
    }
}
