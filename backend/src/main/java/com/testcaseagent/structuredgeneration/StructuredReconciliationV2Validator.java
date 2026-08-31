package com.testcaseagent.structuredgeneration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.identity.LengthPrefixedSha256;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationPageInput;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationPageResult;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationResult;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationV2Canonicalizer;
import com.testcaseagent.validation.ReaderFacingTextPolicy;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Independently verifies KEE-derived V2 identities before any page is staged or published.
 *
 * <p>Page validation proves the local owner and relation derivations. Run validation then proves
 * the global owner partition, source coverage, and exact-path closure across every staged page.
 * Overlapping relations remain valid; the separate source-terminal ledger is the one-row-per-source
 * completion proof.</p>
 *
 * [Req-ID]: REQ-FSC-008
 */
public final class StructuredReconciliationV2Validator {
    private final ObjectMapper objectMapper;

    /** Creates the validator with the production mapper used only for safe result hashing. */
    public StructuredReconciliationV2Validator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /** Validates one owner page and converts it to non-business staging data. */
    public StructuredGenerationAcceptanceStore.ReconciliationPageStage validatePage(
            StructuredReconciliationV2Planner.RunPlan plan,
            FeatureScopeReconciliationPageInput.OwnerWindow window,
            FeatureScopeReconciliationPageResult result) {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(result, "result must not be null");
        if (!plan.runKey().equals(result.runKey()) || !window.pageKey().equals(result.pageKey())
                || !window.ownerSourceRefs().equals(result.completedOwnerSourceRefs())) {
            throw new IllegalArgumentException("V2 page does not echo the frozen run and owner window");
        }

        CatalogIndex catalog = new CatalogIndex(plan.catalog());
        Set<String> relationKeys = new HashSet<>();
        List<StructuredGenerationAcceptanceStore.ReconciliationRelation> relations = new ArrayList<>();
        for (FeatureScopeReconciliationPageResult.Reconciliation relation : result.reconciliations()) {
            List<FeatureScopeReconciliationPageInput.SourceRef> refs = relation.sourceRefs();
            refs.forEach(catalog::requireSource);
            FeatureScopeReconciliationPageInput.SourceRef owner = refs.get(0);
            if (!window.ownerSourceRefs().contains(owner) || !owner.equals(relation.ownerSourceRef())) {
                throw new IllegalArgumentException("V2 relation owner is outside its current owner window");
            }
            String expectedKey = FeatureScopeReconciliationV2Canonicalizer.reconciliationKey(
                    plan.runKey(), relation.classification(), relation.confirmationStatus(), refs);
            if (!expectedKey.equals(relation.reconciliationKey()) || !relationKeys.add(expectedKey)) {
                throw new IllegalArgumentException("V2 relation identity is invalid or duplicated");
            }
            List<String> expectedEvidence = catalog.evidenceUnion(refs);
            if (!expectedEvidence.equals(relation.evidenceKeys())) {
                throw new IllegalArgumentException("V2 relation evidence does not equal the referenced-source union");
            }
            ReaderFacingTextPolicy.requireSafe(relation.scopeRecommendation(), "scopeRecommendation");
            relations.add(new StructuredGenerationAcceptanceStore.ReconciliationRelation(
                    expectedKey, storeRef(owner), relation.functionListItemKeys(), relation.requirementFactKeys(),
                    relation.classification().wireValue(), expectedEvidence, relation.scopeRecommendation(),
                    relation.confirmationStatus().wireValue()));
        }
        return new StructuredGenerationAcceptanceStore.ReconciliationPageStage(
                storeRun(plan), storeWindow(window), storeRefs(result.completedOwnerSourceRefs()),
                List.copyOf(relations), resultHash(result));
    }

    /**
     * Validates all completed leaf pages together and creates the only publication accepted by the store.
     */
    public StructuredGenerationAcceptanceStore.ReconciliationRunPublication validateRun(
            StructuredReconciliationV2Planner.RunPlan plan,
            List<StructuredGenerationAcceptanceStore.ReconciliationPageStage> stages) {
        Objects.requireNonNull(plan, "plan must not be null");
        List<StructuredGenerationAcceptanceStore.ReconciliationPageStage> checked = List.copyOf(stages);
        if (checked.isEmpty()) throw new IllegalArgumentException("V2 run has no completed pages");

        List<StructuredGenerationAcceptanceStore.ReconciliationSourceRef> expectedSources =
                storeRefs(plan.catalog().sourceRefs());
        Set<StructuredGenerationAcceptanceStore.ReconciliationSourceRef> owners = new LinkedHashSet<>();
        List<String> pageKeys = new ArrayList<>();
        List<String> pageResultHashes = new ArrayList<>();
        Set<String> uniquePageKeys = new HashSet<>();
        Set<String> relationKeys = new HashSet<>();
        Set<StructuredGenerationAcceptanceStore.ReconciliationSourceRef> covered = new LinkedHashSet<>();
        List<StructuredGenerationAcceptanceStore.ReconciliationRelation> relations = new ArrayList<>();

        for (var stage : checked) {
            if (!storeRun(plan).equals(stage.run()) || !uniquePageKeys.add(stage.ownerWindow().pageKey())
                    || !stage.ownerWindow().ownerSourceRefs().equals(stage.completedOwnerSourceRefs())) {
                throw new IllegalArgumentException("V2 staged pages contain a mixed or duplicate run");
            }
            pageKeys.add(stage.ownerWindow().pageKey());
            pageResultHashes.add(stage.resultSha256());
            for (var owner : stage.ownerWindow().ownerSourceRefs()) {
                if (!owners.add(owner)) throw new IllegalArgumentException("V2 owner appears in more than one page");
            }
            for (var relation : stage.relations()) {
                if (!relationKeys.add(relation.reconciliationKey())) {
                    throw new IllegalArgumentException("V2 relation identity is duplicated across pages");
                }
                if (!stage.ownerWindow().ownerSourceRefs().contains(relation.ownerSourceRef())) {
                    throw new IllegalArgumentException("V2 staged relation is owned by another page");
                }
                relation.functionListItemKeys().forEach(key -> covered.add(new StructuredGenerationAcceptanceStore.ReconciliationSourceRef(
                        "function_list_item", key)));
                relation.requirementFactKeys().forEach(key -> covered.add(new StructuredGenerationAcceptanceStore.ReconciliationSourceRef(
                        "requirement_fact", key)));
                relations.add(relation);
            }
        }
        List<StructuredGenerationAcceptanceStore.ReconciliationSourceRef> canonicalOwners = owners.stream()
                .sorted(Comparator.comparingInt((StructuredGenerationAcceptanceStore.ReconciliationSourceRef ref) ->
                                "function_list_item".equals(ref.sourceType()) ? 0 : 1)
                        .thenComparing(StructuredGenerationAcceptanceStore.ReconciliationSourceRef::sourceKey,
                                FeatureScopeReconciliationV2Canonicalizer.utf8Order()))
                .toList();
        if (!canonicalOwners.equals(expectedSources)) {
            throw new IllegalArgumentException("V2 owner pages do not exactly partition the global catalog");
        }
        if (!covered.equals(new LinkedHashSet<>(expectedSources))) {
            throw new IllegalArgumentException("V2 relations do not cover every global catalog source");
        }
        validateExactPathClosures(plan.catalog(), relations);

        // Store readers return leaves in canonical first-owner order; retain that order for the
        // row-locked publication comparison instead of sorting opaque page hashes.
        List<String> expectedPageKeys = List.copyOf(pageKeys);
        relations.sort(Comparator.comparing(StructuredGenerationAcceptanceStore.ReconciliationRelation::reconciliationKey));
        List<String> identityParts = new ArrayList<>();
        identityParts.add("reconciliation-v2-publication");
        identityParts.add(plan.runKey());
        identityParts.add(plan.catalogSha256());
        for (int index = 0; index < expectedPageKeys.size(); index++) {
            identityParts.add(expectedPageKeys.get(index));
            // Include the validated page payload, not only relation identities. This keeps
            // idempotency sensitive to reader-facing recommendations and every echoed field.
            identityParts.add(pageResultHashes.get(index));
        }
        relations.forEach(relation -> identityParts.add(relation.reconciliationKey()));
        String acceptedHash = HexFormat.of().formatHex(LengthPrefixedSha256.digest(identityParts.toArray(String[]::new)));
        return new StructuredGenerationAcceptanceStore.ReconciliationRunPublication(
                storeRun(plan), expectedPageKeys, expectedSources,
                List.copyOf(relations), acceptedHash);
    }

    private static void validateExactPathClosures(
            FeatureScopeReconciliationPageInput.GlobalCatalog catalog,
            List<StructuredGenerationAcceptanceStore.ReconciliationRelation> relations) {
        Map<String, PathClosure> closures = new LinkedHashMap<>();
        catalog.functionListItems().forEach(item -> closures.computeIfAbsent(normalizedPath(item.path()), ignored ->
                new PathClosure()).items.add(item.itemKey()));
        catalog.requirementFacts().forEach(fact -> closures.computeIfAbsent(normalizedPath(fact.function()), ignored ->
                new PathClosure()).facts.add(fact.factKey()));
        for (PathClosure closure : closures.values()) {
            if (closure.items.isEmpty() || closure.facts.isEmpty()) continue;
            List<StructuredGenerationAcceptanceStore.ReconciliationRelation> touching = relations.stream()
                    .filter(relation -> relation.functionListItemKeys().stream().anyMatch(closure.items::contains)
                            || relation.requirementFactKeys().stream().anyMatch(closure.facts::contains))
                    .toList();
            if (touching.size() != 1) {
                throw new IllegalArgumentException("Normalized feature path must close in one V2 relation");
            }
            var relation = touching.get(0);
            if (!"exact_match".equals(relation.classification())
                    || !"confirmed".equals(relation.confirmationStatus())
                    || !new HashSet<>(relation.functionListItemKeys()).equals(closure.items)
                    || !new HashSet<>(relation.requirementFactKeys()).equals(closure.facts)) {
                throw new IllegalArgumentException("Normalized feature path must be one confirmed exact match");
            }
        }
    }

    private String resultHash(FeatureScopeReconciliationPageResult result) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(result)));
        } catch (NoSuchAlgorithmException | java.io.IOException exception) {
            throw new IllegalStateException("could not hash validated V2 result", exception);
        }
    }

    private static String normalizedPath(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        boolean separator = false;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                if (separator && result.length() > 0) result.append('/');
                result.appendCodePoint(codePoint);
                separator = false;
            } else if (result.length() > 0) {
                separator = true;
            }
        }
        return result.toString();
    }

    private static StructuredGenerationAcceptanceStore.ReconciliationSourceRef storeRef(
            FeatureScopeReconciliationPageInput.SourceRef ref) {
        return new StructuredGenerationAcceptanceStore.ReconciliationSourceRef(
                ref.sourceType().wireValue(), ref.sourceKey());
    }

    private static StructuredGenerationAcceptanceStore.ReconciliationRunIdentity storeRun(
            StructuredReconciliationV2Planner.RunPlan plan) {
        return new StructuredGenerationAcceptanceStore.ReconciliationRunIdentity(
                plan.runKey(), plan.catalogSha256(), plan.catalog().functionListItems().size(),
                plan.catalog().requirementFacts().size());
    }

    private static StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow storeWindow(
            FeatureScopeReconciliationPageInput.OwnerWindow window) {
        return new StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow(
                window.pageKey(), storeRefs(window.ownerSourceRefs()));
    }

    private static List<StructuredGenerationAcceptanceStore.ReconciliationSourceRef> storeRefs(
            List<FeatureScopeReconciliationPageInput.SourceRef> refs) {
        return refs.stream().map(StructuredReconciliationV2Validator::storeRef).toList();
    }

    private static final class CatalogIndex {
        private final Map<FeatureScopeReconciliationPageInput.SourceRef, List<String>> evidence = new LinkedHashMap<>();

        CatalogIndex(FeatureScopeReconciliationPageInput.GlobalCatalog catalog) {
            catalog.functionListItems().forEach(item -> evidence.put(new FeatureScopeReconciliationPageInput.SourceRef(
                    FeatureScopeReconciliationPageInput.SourceType.FUNCTION_LIST_ITEM, item.itemKey()), item.evidenceKeys()));
            catalog.requirementFacts().forEach(fact -> evidence.put(new FeatureScopeReconciliationPageInput.SourceRef(
                    FeatureScopeReconciliationPageInput.SourceType.REQUIREMENT_FACT, fact.factKey()), fact.evidenceKeys()));
        }

        void requireSource(FeatureScopeReconciliationPageInput.SourceRef ref) {
            if (!evidence.containsKey(ref)) throw new IllegalArgumentException("V2 relation references an unknown source");
        }

        List<String> evidenceUnion(List<FeatureScopeReconciliationPageInput.SourceRef> refs) {
            Comparator<String> order = FeatureScopeReconciliationV2Canonicalizer.utf8Order();
            return refs.stream().flatMap(ref -> evidence.get(ref).stream()).distinct().sorted(order).toList();
        }
    }

    private static final class PathClosure {
        private final Set<String> items = new LinkedHashSet<>();
        private final Set<String> facts = new LinkedHashSet<>();
    }

}
