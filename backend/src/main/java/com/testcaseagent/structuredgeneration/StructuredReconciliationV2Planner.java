package com.testcaseagent.structuredgeneration;

import com.testcaseagent.identity.LengthPrefixedSha256;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationPageInput;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationV2Canonicalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Freezes one globally visible reconciliation catalog and pages only relation ownership.
 *
 * <p>The 100-owner initial window is an output-risk bound, not a comparison partition: every
 * page still sends the same complete catalog. Only a {@code response_too_large} result may split
 * one window, and that split retains the unchanged run and catalog identities.</p>
 *
 * [Req-ID]: REQ-FSC-008
 */
public final class StructuredReconciliationV2Planner {
    static final int INITIAL_OWNER_WINDOW_SIZE = 100;

    /** Creates the deterministic planner; it has no mutable or serializer-dependent state. */
    public StructuredReconciliationV2Planner() { }

    /** Builds one immutable run from every durably accepted function item and formal fact. */
    public RunPlan plan(String taskId, StructuredGenerationAcceptanceStore.AcceptedInputs accepted) {
        requireText(taskId, "taskId");
        Objects.requireNonNull(accepted, "accepted must not be null");
        Comparator<String> keyOrder = FeatureScopeReconciliationV2Canonicalizer.utf8Order();
        List<FeatureScopeReconciliationPageInput.FunctionListItem> items = accepted.functionItems().stream()
                .map(item -> new FeatureScopeReconciliationPageInput.FunctionListItem(
                        item.itemKey(), item.path(), item.description(),
                        canonicalEvidence(item.evidenceKeys(), item.itemKey(), keyOrder)))
                .sorted(Comparator.comparing(FeatureScopeReconciliationPageInput.FunctionListItem::itemKey, keyOrder))
                .toList();
        List<FeatureScopeReconciliationPageInput.RequirementFact> facts = accepted.facts().stream()
                .map(fact -> new FeatureScopeReconciliationPageInput.RequirementFact(
                        fact.factKey(), fact.function(),
                        canonicalEvidence(fact.evidenceKeys(), fact.factKey(), keyOrder)))
                .sorted(Comparator.comparing(FeatureScopeReconciliationPageInput.RequirementFact::factKey, keyOrder))
                .toList();
        requireUnique(items.stream().map(FeatureScopeReconciliationPageInput.FunctionListItem::itemKey).toList(),
                "function item keys");
        requireUnique(facts.stream().map(FeatureScopeReconciliationPageInput.RequirementFact::factKey).toList(),
                "requirement fact keys");
        if (items.isEmpty()) throw new IllegalArgumentException("global catalog requires a function item");

        FeatureScopeReconciliationPageInput.GlobalCatalog catalog =
                new FeatureScopeReconciliationPageInput.GlobalCatalog(items, facts);
        String catalogSha256 = FeatureScopeReconciliationV2Canonicalizer.catalogSha256(catalog);
        String runKey = HexFormat.of().formatHex(LengthPrefixedSha256.digest(
                "reconciliation-v2-run", taskId, catalogSha256,
                Integer.toString(items.size()), Integer.toString(facts.size())));

        List<FeatureScopeReconciliationPageInput.SourceRef> owners = new ArrayList<>(items.size() + facts.size());
        items.forEach(item -> owners.add(new FeatureScopeReconciliationPageInput.SourceRef(
                FeatureScopeReconciliationPageInput.SourceType.FUNCTION_LIST_ITEM, item.itemKey())));
        facts.forEach(fact -> owners.add(new FeatureScopeReconciliationPageInput.SourceRef(
                FeatureScopeReconciliationPageInput.SourceType.REQUIREMENT_FACT, fact.factKey())));
        owners.sort(Comparator.naturalOrder());

        List<FeatureScopeReconciliationPageInput.OwnerWindow> windows = new ArrayList<>();
        for (int start = 0; start < owners.size(); start += INITIAL_OWNER_WINDOW_SIZE) {
            windows.add(ownerWindow(runKey,
                    owners.subList(start, Math.min(start + INITIAL_OWNER_WINDOW_SIZE, owners.size()))));
        }
        return new RunPlan(runKey, catalogSha256, catalog, List.copyOf(windows));
    }

    /** Deterministically halves only the selected output-owner window. */
    public List<FeatureScopeReconciliationPageInput.OwnerWindow> bisect(
            RunPlan plan, FeatureScopeReconciliationPageInput.OwnerWindow parent) {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(parent, "parent must not be null");
        List<FeatureScopeReconciliationPageInput.SourceRef> owners = parent.ownerSourceRefs();
        if (owners.size() < 2) throw new IllegalArgumentException("a one-owner window cannot be bisected");
        int middle = owners.size() / 2;
        return List.of(
                ownerWindow(plan.runKey(), owners.subList(0, middle)),
                ownerWindow(plan.runKey(), owners.subList(middle, owners.size())));
    }

    private static FeatureScopeReconciliationPageInput.OwnerWindow ownerWindow(
            String runKey, List<FeatureScopeReconciliationPageInput.SourceRef> owners) {
        List<FeatureScopeReconciliationPageInput.SourceRef> canonical = List.copyOf(owners);
        if (canonical.isEmpty()) throw new IllegalArgumentException("owner window must not be empty");
        if (!canonical.equals(canonical.stream().sorted().toList())
                || new HashSet<>(canonical).size() != canonical.size()) {
            throw new IllegalArgumentException("owner window must be canonical and unique");
        }
        return new FeatureScopeReconciliationPageInput.OwnerWindow(
                FeatureScopeReconciliationV2Canonicalizer.pageKey(runKey, canonical), canonical);
    }

    private static List<String> canonicalEvidence(
            List<String> values, String sourceKey, Comparator<String> keyOrder) {
        Objects.requireNonNull(values, "evidenceKeys must not be null for " + sourceKey);
        List<String> checked = values.stream().map(value -> requireText(value, "evidenceKey")).toList();
        if (checked.isEmpty()) throw new IllegalArgumentException("evidenceKeys must not be empty for " + sourceKey);
        if (new LinkedHashSet<>(checked).size() != checked.size()) {
            throw new IllegalArgumentException("evidenceKeys must be unique for " + sourceKey);
        }
        return checked.stream().sorted(keyOrder).toList();
    }

    private static void requireUnique(List<String> values, String field) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " must be unique");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    /** Frozen task-level run and deterministic initial owner partition. */
    public record RunPlan(String runKey, String catalogSha256,
            FeatureScopeReconciliationPageInput.GlobalCatalog catalog,
            List<FeatureScopeReconciliationPageInput.OwnerWindow> ownerWindows) {
        public RunPlan {
            requireText(runKey, "runKey");
            requireText(catalogSha256, "catalogSha256");
            catalog = Objects.requireNonNull(catalog, "catalog must not be null");
            ownerWindows = List.copyOf(ownerWindows);
            if (ownerWindows.isEmpty()) throw new IllegalArgumentException("ownerWindows must not be empty");
        }

        /** Creates the exact V2 input for one initial or deterministically split owner page. */
        public FeatureScopeReconciliationPageInput input(
                FeatureScopeReconciliationPageInput.OwnerWindow ownerWindow) {
            String expectedPageKey = FeatureScopeReconciliationV2Canonicalizer.pageKey(
                    runKey, ownerWindow.ownerSourceRefs());
            if (!expectedPageKey.equals(ownerWindow.pageKey())) {
                throw new IllegalArgumentException("ownerWindow does not belong to this run");
            }
            return new FeatureScopeReconciliationPageInput(
                    new FeatureScopeReconciliationPageInput.Run(runKey, catalogSha256,
                            catalog.functionListItems().size(), catalog.requirementFacts().size()),
                    catalog, ownerWindow);
        }
    }
}
