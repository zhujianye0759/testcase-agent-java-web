package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Strict protocol V2 input for one owner window over one unchanged global reconciliation catalog.
 * Pages restrict output ownership only; every page still carries the complete catalog.
 *
 * [Req-ID]: REQ-FSC-008
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonPropertyOrder({"operation", "protocol_version", "run", "global_catalog", "owner_window"})
public record FeatureScopeReconciliationPageInput(
        String operation,
        @JsonProperty("protocol_version") String protocolVersion,
        Run run,
        @JsonProperty("global_catalog") GlobalCatalog globalCatalog,
        @JsonProperty("owner_window") OwnerWindow ownerWindow) {
    public static final String OPERATION = "reconcile_page";
    public static final String PROTOCOL_VERSION = "2";

    /** Creates the only supported V2 operation and protocol version. */
    public FeatureScopeReconciliationPageInput(Run run, GlobalCatalog globalCatalog, OwnerWindow ownerWindow) {
        this(OPERATION, PROTOCOL_VERSION, run, globalCatalog, ownerWindow);
    }

    public FeatureScopeReconciliationPageInput {
        if (!OPERATION.equals(operation)) throw new IllegalArgumentException("operation must be reconcile_page");
        if (!PROTOCOL_VERSION.equals(protocolVersion)) throw new IllegalArgumentException("protocolVersion must be 2");
        run = Objects.requireNonNull(run, "run must not be null");
        globalCatalog = Objects.requireNonNull(globalCatalog, "globalCatalog must not be null");
        ownerWindow = Objects.requireNonNull(ownerWindow, "ownerWindow must not be null");
        if (run.functionItemCount() != globalCatalog.functionListItems().size()
                || run.requirementFactCount() != globalCatalog.requirementFacts().size()) {
            throw new IllegalArgumentException("run counts must equal the global catalog");
        }
        if (!run.catalogSha256().equals(FeatureScopeReconciliationV2Canonicalizer.catalogSha256(globalCatalog))) {
            throw new IllegalArgumentException("catalogSha256 must equal the canonical global catalog digest");
        }
        Set<SourceRef> catalogRefs = new HashSet<>(globalCatalog.sourceRefs());
        if (!catalogRefs.containsAll(ownerWindow.ownerSourceRefs())) {
            throw new IllegalArgumentException("ownerSourceRefs must belong to the global catalog");
        }
        if (!ownerWindow.pageKey().equals(FeatureScopeReconciliationV2Canonicalizer.pageKey(
                run.runKey(), ownerWindow.ownerSourceRefs()))) {
            throw new IllegalArgumentException("pageKey must match the run and canonical owner refs");
        }
    }

    /** Immutable identity and declared source counts for one task/catalog run. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"run_key", "catalog_sha256", "function_item_count", "requirement_fact_count"})
    public record Run(@JsonProperty("run_key") String runKey,
            @JsonProperty("catalog_sha256") String catalogSha256,
            @JsonProperty("function_item_count") int functionItemCount,
            @JsonProperty("requirement_fact_count") int requirementFactCount) {
        public Run {
            runKey = StructuredSkillContract.key(runKey, "runKey");
            catalogSha256 = requireSha256(catalogSha256, "catalogSha256");
            if (functionItemCount < 1 || requirementFactCount < 0) {
                throw new IllegalArgumentException("run counts are invalid");
            }
        }
    }

    /** Complete comparison catalog repeated unchanged on every page. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"function_list_items", "requirement_facts"})
    public record GlobalCatalog(
            @JsonProperty("function_list_items") List<FunctionListItem> functionListItems,
            @JsonProperty("requirement_facts") List<RequirementFact> requirementFacts) {
        public GlobalCatalog {
            functionListItems = copyUnbounded(functionListItems, "functionListItems", true);
            requirementFacts = copyUnbounded(requirementFacts, "requirementFacts", false);
            requireStrictlyOrderedStrings(functionListItems.stream().map(FunctionListItem::itemKey).toList(),
                    "functionListItems");
            requireStrictlyOrderedStrings(requirementFacts.stream().map(RequirementFact::factKey).toList(),
                    "requirementFacts");
        }

        /** Returns every catalog source in the frozen type-then-key order. */
        public List<SourceRef> sourceRefs() {
            java.util.ArrayList<SourceRef> refs = new java.util.ArrayList<>(
                    functionListItems.size() + requirementFacts.size());
            functionListItems.forEach(item -> refs.add(new SourceRef(SourceType.FUNCTION_LIST_ITEM, item.itemKey())));
            requirementFacts.forEach(fact -> refs.add(new SourceRef(SourceType.REQUIREMENT_FACT, fact.factKey())));
            return List.copyOf(refs);
        }
    }

    /** One Java-owned extracted function-list source in the V2 catalog. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"item_key", "path", "description", "evidence_keys"})
    public record FunctionListItem(@JsonProperty("item_key") String itemKey, String path, String description,
            @JsonProperty("evidence_keys") List<String> evidenceKeys) {
        public FunctionListItem {
            itemKey = StructuredSkillContract.key(itemKey, "itemKey");
            path = StructuredSkillContract.text(path, "path");
            description = StructuredSkillContract.text(description, "description");
            evidenceKeys = orderedEvidence(evidenceKeys, "evidenceKeys");
        }
    }

    /** One accepted formal requirement-fact source in the V2 catalog. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"fact_key", "function", "evidence_keys"})
    public record RequirementFact(@JsonProperty("fact_key") String factKey, String function,
            @JsonProperty("evidence_keys") List<String> evidenceKeys) {
        public RequirementFact {
            factKey = StructuredSkillContract.key(factKey, "factKey");
            function = StructuredSkillContract.text(function, "function");
            evidenceKeys = orderedEvidence(evidenceKeys, "evidenceKeys");
        }
    }

    /** Stable page identity and its canonical, nonempty ownership partition. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"page_key", "owner_source_refs"})
    public record OwnerWindow(@JsonProperty("page_key") String pageKey,
            @JsonProperty("owner_source_refs") List<SourceRef> ownerSourceRefs) {
        public OwnerWindow {
            pageKey = requireSha256(pageKey, "pageKey");
            ownerSourceRefs = copyUnbounded(ownerSourceRefs, "ownerSourceRefs", true);
            requireStrictlyOrdered(ownerSourceRefs, "ownerSourceRefs");
        }
    }

    /** Canonical catalog source identity. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonPropertyOrder({"source_type", "source_key"})
    public record SourceRef(@JsonProperty("source_type") SourceType sourceType,
            @JsonProperty("source_key") String sourceKey) implements Comparable<SourceRef> {
        public SourceRef {
            sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
            sourceKey = StructuredSkillContract.key(sourceKey, "sourceKey");
        }

        @Override
        public int compareTo(SourceRef other) {
            int typeOrder = Integer.compare(sourceType.order(), other.sourceType.order());
            return typeOrder != 0 ? typeOrder
                    : FeatureScopeReconciliationV2Canonicalizer.utf8Order().compare(sourceKey, other.sourceKey);
        }
    }

    /** Frozen wire identities; declaration order is also the canonical type order. */
    public enum SourceType {
        FUNCTION_LIST_ITEM(0), REQUIREMENT_FACT(1);

        private final int order;

        SourceType(int order) { this.order = order; }

        int order() { return order; }

        @JsonValue
        public String wireValue() { return name().toLowerCase(Locale.ROOT); }

        @JsonCreator
        public static SourceType fromWire(String value) {
            if (value == null) throw new IllegalArgumentException("sourceType must not be null");
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    private static List<String> orderedEvidence(List<String> values, String name) {
        List<String> copy = StructuredSkillContract.unboundedKeyReferences(values, name, true);
        requireStrictlyOrderedStrings(copy, name);
        return copy;
    }

    private static <T> List<T> copyUnbounded(List<T> values, String name, boolean requireNonEmpty) {
        List<T> copy = List.copyOf(Objects.requireNonNull(values, name + " must not be null"));
        if (requireNonEmpty && copy.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        if (copy.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException(name + " must not contain null");
        return copy;
    }

    private static <T extends Comparable<? super T>> void requireStrictlyOrdered(List<T> values, String name) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).compareTo(values.get(index)) >= 0) {
                throw new IllegalArgumentException(name + " must be strictly ordered and unique");
            }
        }
    }

    private static void requireStrictlyOrderedStrings(List<String> values, String name) {
        Comparator<String> order = FeatureScopeReconciliationV2Canonicalizer.utf8Order();
        for (int index = 1; index < values.size(); index++) {
            if (order.compare(values.get(index - 1), values.get(index)) >= 0) {
                throw new IllegalArgumentException(name + " must be strictly ordered and unique");
            }
        }
    }

    static String requireSha256(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return value;
    }
}
