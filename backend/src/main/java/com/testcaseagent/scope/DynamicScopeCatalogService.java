package com.testcaseagent.scope;

import com.testcaseagent.scope.KnowledgeScopeCatalogPort.KnowledgeBase;
import com.testcaseagent.scope.KnowledgeScopeCatalogPort.KnowledgeDocument;
import com.testcaseagent.scope.KnowledgeScopeCatalogPort.ScopeContainer;
import com.testcaseagent.scope.KnowledgeScopeCatalogPort.SystemVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Composes KEE read endpoints into one immutable, browser-safe task scope catalog.
 *
 * <p>A refresh is published only after every required response has been validated, so a partial
 * KEE outage can never widen or partially replace the last trusted snapshot.</p>
 *
 * [Req-ID]: REQ-CAT-001, REQ-CAT-002, REQ-CAT-003, REQ-FSC-006
 */
public final class DynamicScopeCatalogService {
    private static final String ADMISSION_MATERIAL = "admission_material";

    private final KnowledgeScopeCatalogPort port;
    private final Duration cacheTtl;
    private final Clock clock;
    private final Object refreshLock = new Object();
    private volatile ScopeCatalogSnapshot cached;

    public DynamicScopeCatalogService(KnowledgeScopeCatalogPort port, Duration cacheTtl, Clock clock) {
        this.port = Objects.requireNonNull(port, "port must not be null");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (cacheTtl.isZero() || cacheTtl.isNegative()) throw new IllegalArgumentException("cacheTtl must be positive");
    }

    /** Returns the trusted snapshot; explicit refresh never publishes an incomplete replacement. */
    public ScopeCatalogSnapshot catalog(boolean refresh) {
        ScopeCatalogSnapshot current = cached;
        if (!refresh && usable(current)) return current;
        synchronized (refreshLock) {
            current = cached;
            if (!refresh && usable(current)) return current;
            ScopeCatalogSnapshot replacement = loadAll();
            cached = replacement;
            return replacement;
        }
    }

    /** Re-reads one selected KB so task creation rejects options that became stale after rendering. */
    public ScopeCatalogSnapshot revalidateKnowledgeBase(String knowledgeBaseId) {
        KnowledgeBase knowledgeBase = port.listKnowledgeBases().stream()
                .filter(candidate -> candidate.id().equals(knowledgeBaseId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选知识范围已不可用，请刷新后重新选择"));
        return build(List.of(knowledgeBase));
    }

    private boolean usable(ScopeCatalogSnapshot snapshot) {
        return snapshot != null && snapshot.loadedAt().plus(cacheTtl).isAfter(clock.instant());
    }

    private ScopeCatalogSnapshot loadAll() {
        return build(port.listKnowledgeBases());
    }

    private ScopeCatalogSnapshot build(List<KnowledgeBase> knowledgeBases) {
        Map<String, ScopeSelection> selections = new LinkedHashMap<>();
        List<ScopeCatalogView.KnowledgeBaseOption> safeKnowledgeBases = new ArrayList<>();
        knowledgeBases.stream().filter(kb -> "document".equals(kb.type()))
                .sorted(Comparator.comparing(KnowledgeBase::name).thenComparing(KnowledgeBase::id))
                .forEach(kb -> buildKnowledgeBase(kb, selections).ifPresent(safeKnowledgeBases::add));
        return new ScopeCatalogSnapshot(new ScopeCatalogView(safeKnowledgeBases), selections, clock.instant());
    }

    private Optional<ScopeCatalogView.KnowledgeBaseOption> buildKnowledgeBase(
            KnowledgeBase knowledgeBase, Map<String, ScopeSelection> selections) {
        Optional<ScopeContainer> containerValue = port.getScopeContainer(knowledgeBase.id());
        if (containerValue.isEmpty() || !"system".equals(containerValue.get().containerType())) return Optional.empty();
        ScopeContainer container = containerValue.get();
        if (blank(container.systemId())) return Optional.empty();

        List<KnowledgeDocument> documents = port.listDocuments(knowledgeBase.id());
        List<ScopeCatalogView.VersionOption> versions = port.listSystemVersions(knowledgeBase.id()).stream()
                .filter(SystemVersion::active)
                .filter(SystemVersion::current)
                .filter(version -> container.systemId().equals(version.systemId()))
                .sorted(Comparator.comparing(SystemVersion::displayName).thenComparing(SystemVersion::id))
                .map(version -> buildVersion(knowledgeBase, container, version, documents, selections))
                .filter(option -> !option.materialTypes().isEmpty()).toList();
        if (versions.isEmpty()) return Optional.empty();

        String systemKey = opaque("system-", knowledgeBase.id(), container.systemId());
        ScopeCatalogView.SystemOption system = new ScopeCatalogView.SystemOption(systemKey,
                blank(container.systemName()) ? knowledgeBase.name() : container.systemName(), versions);
        return Optional.of(new ScopeCatalogView.KnowledgeBaseOption(
                opaque("kb-", knowledgeBase.id()), knowledgeBase.name(), List.of(system)));
    }

    private ScopeCatalogView.VersionOption buildVersion(
            KnowledgeBase knowledgeBase,
            ScopeContainer container,
            SystemVersion version,
            List<KnowledgeDocument> documents,
            Map<String, ScopeSelection> selections) {
        Map<TypeCoordinate, List<KnowledgeDocument>> byType = documents.stream()
                .filter(document -> eligible(document, knowledgeBase.id(), container.systemId(), version.id()))
                .collect(Collectors.groupingBy(document -> new TypeCoordinate(document.scope().projectId(),
                        document.scope().contentTypeKey(), label(document.scope())), LinkedHashMap::new, Collectors.toList()));

        List<ScopeCatalogView.MaterialTypeOption> materialTypes = byType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(TypeCoordinate::label).thenComparing(TypeCoordinate::key)))
                .map(entry -> {
                    List<String> documentIds = entry.getValue().stream().map(KnowledgeDocument::id).distinct().sorted().toList();
                    Map<String, String> hashes = entry.getValue().stream().collect(Collectors.toMap(KnowledgeDocument::id,
                            KnowledgeDocument::fileSha256, (left, right) -> left, LinkedHashMap::new));
                    String selectionId = opaque("scope-", knowledgeBase.id(), container.systemId(), version.id(),
                            entry.getKey().projectId(), ADMISSION_MATERIAL, entry.getKey().key(), String.join(",", documentIds));
                    ScopeSelection selection = new ScopeSelection(selectionId, knowledgeBase.id(), container.systemId(),
                            version.id(), entry.getKey().projectId(), ADMISSION_MATERIAL, entry.getKey().key(), documentIds, hashes);
                    if (selections.put(selectionId, selection) != null) {
                        throw new ScopeCatalogUnavailableException("Duplicate opaque scope selection");
                    }
                    List<ScopeCatalogView.DocumentOption> documentOptions = entry.getValue().stream()
                            .sorted(Comparator.comparing(KnowledgeDocument::fileName).thenComparing(KnowledgeDocument::id))
                            .map(document -> {
                                String documentSelectionId = opaque("scope-document-", knowledgeBase.id(), container.systemId(),
                                        version.id(), entry.getKey().projectId(), ADMISSION_MATERIAL, entry.getKey().key(), document.id());
                                ScopeSelection documentSelection = new ScopeSelection(documentSelectionId, knowledgeBase.id(),
                                        container.systemId(), version.id(), entry.getKey().projectId(), ADMISSION_MATERIAL,
                                        entry.getKey().key(), List.of(document.id()), Map.of(document.id(), document.fileSha256()));
                                if (selections.put(documentSelectionId, documentSelection) != null) {
                                    throw new ScopeCatalogUnavailableException("Duplicate opaque document scope selection");
                                }
                                return new ScopeCatalogView.DocumentOption(documentSelectionId, document.fileName());
                            }).toList();
                    return new ScopeCatalogView.MaterialTypeOption(selectionId, entry.getKey().label(), documentIds.size(), documentOptions);
                }).toList();
        return new ScopeCatalogView.VersionOption(
                opaque("version-", knowledgeBase.id(), container.systemId(), version.id()), version.displayName(), materialTypes);
    }

    private static boolean eligible(KnowledgeDocument document, String knowledgeBaseId, String systemId, String versionId) {
        return knowledgeBaseId.equals(document.knowledgeBaseId())
                && "completed".equals(document.parseStatus())
                && "enabled".equals(document.enableStatus())
                && document.scope() != null
                && systemId.equals(document.scope().systemId())
                && versionId.equals(document.scope().versionId())
                && !blank(document.scope().projectId())
                && !blank(document.fileSha256())
                && ADMISSION_MATERIAL.equals(document.scope().contentCategory())
                && !blank(document.scope().contentTypeKey());
    }

    private static String label(KnowledgeScopeCatalogPort.DocumentScope scope) {
        String candidate = scope.contentTypeLabel();
        return blank(candidate) || scope.contentTypeKey().equals(candidate.trim()) ? "未命名材料类型" : candidate.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String opaque(String prefix, String... coordinates) {
        try {
            String canonical = String.join("\n", coordinates);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record TypeCoordinate(String projectId, String key, String label) { }
}
