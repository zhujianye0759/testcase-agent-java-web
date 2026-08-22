package com.testcaseagent.task;

import com.testcaseagent.scope.DynamicScopeCatalogService;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.scope.ScopeCatalogSnapshot;
import com.testcaseagent.scope.ScopeSelection;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves opaque browser choices to a freshly revalidated, immutable formal requirement scope.
 *
 * [Req-ID]: REQ-CAT-004, REQ-CAT-005, REQ-SCP-001, REQ-SCP-004
 */
public final class DynamicTaskScopeResolver {
    private final DynamicScopeCatalogService catalogService;
    private final TaskGenerationProfileProperties profile;

    public DynamicTaskScopeResolver(DynamicScopeCatalogService catalogService, TaskGenerationProfileProperties profile) {
        this.catalogService = catalogService;
        this.profile = profile;
    }

    public CreateGenerationTaskRequest resolve(CreateGenerationTaskCommand command) {
        ScopeCatalogSnapshot rendered = catalogService.catalog(false);
        List<ScopeSelection> selected = command.scopeSelectionIds().stream()
                .map(id -> selection(rendered, id)).toList();
        requireSameCoordinate(selected);

        ScopeCatalogSnapshot fresh = catalogService.revalidateKnowledgeBase(selected.get(0).knowledgeBaseId());
        List<ScopeSelection> revalidated = command.scopeSelectionIds().stream()
                .map(id -> selection(fresh, id)).toList();
        if (!selected.equals(revalidated)) {
            throw new IllegalArgumentException("所选知识范围已变化，请刷新后重新选择");
        }
        requireTaskPrerequisites(revalidated);

        ScopeSelection coordinate = revalidated.get(0);
        List<RequirementDocumentCoordinate> documents = revalidated.stream()
                .flatMap(value -> value.documentIds().stream()
                        .map(documentId -> new RequirementDocumentCoordinate(documentId, value.admissionTypeKey())))
                .sorted(java.util.Comparator.comparing(RequirementDocumentCoordinate::documentId))
                .toList();
        List<String> admissionTypes = revalidated.stream().map(ScopeSelection::admissionTypeKey)
                .distinct().sorted().toList();
        RequirementScope requirementScope = new RequirementScope(coordinate.knowledgeBaseId(), coordinate.systemId(),
                coordinate.versionId(), coordinate.materialCategory(), coordinate.projectId(),
                documents);

        if (command.taskMode() == GenerationTaskMode.ALL) {
            return new CreateGenerationTaskRequest(command.taskMode(), "all-pending", List.of(), Map.of(),
                    command.fewShotPolicy(), command.schemaVersion(), command.promptVersion(), profile.requiredAgentId(),
                    requirementScope, profile.exampleScope(), admissionTypes,
                    command.prompt().isBlank() ? "请发现全部功能。" : command.prompt());
        }
        String featureId = featureId(command.featureDescription());
        String promptPrefix = command.prompt().isBlank() ? "" : command.prompt() + "\n";
        return new CreateGenerationTaskRequest(command.taskMode(), featureId, List.of(featureId),
                Map.of(featureId, command.featureDescription()), command.fewShotPolicy(), command.schemaVersion(),
                command.promptVersion(), profile.requiredAgentId(), requirementScope, profile.exampleScope(), admissionTypes,
                promptPrefix + "目标功能描述：" + command.featureDescription());
    }

    private static ScopeSelection selection(ScopeCatalogSnapshot snapshot, String id) {
        ScopeSelection value = snapshot.selections().get(id);
        if (value == null) throw new IllegalArgumentException("所选知识范围已不可用，请刷新后重新选择");
        return value;
    }

    private static void requireSameCoordinate(List<ScopeSelection> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("请选择至少一种可用材料范围");
        ScopeSelection first = values.get(0);
        boolean mixed = values.stream().anyMatch(value -> !first.knowledgeBaseId().equals(value.knowledgeBaseId())
                || !first.systemId().equals(value.systemId()) || !first.versionId().equals(value.versionId())
                || !first.projectId().equals(value.projectId())
                || !first.materialCategory().equals(value.materialCategory()));
        if (mixed) throw new IllegalArgumentException("一次任务只能选择同一知识库、系统、版本和项目下的材料");
    }

    private static void requireTaskPrerequisites(List<ScopeSelection> values) {
        Set<String> materialTypes = values.stream().map(ScopeSelection::admissionTypeKey).collect(java.util.stream.Collectors.toSet());
        if (!materialTypes.contains("function_list")) {
            throw new IllegalArgumentException("任务必须包含功能清单材料");
        }
        if (!materialTypes.contains("requirements_spec") && !materialTypes.contains("work_order_plan")) {
            throw new IllegalArgumentException("任务必须包含需求规格说明书或工单方案作为正式需求材料");
        }
        Set<String> hashes = new HashSet<>();
        for (ScopeSelection value : values) {
            if (!value.documentSha256ById().keySet().equals(Set.copyOf(value.documentIds()))) {
                throw new IllegalArgumentException("材料文件哈希范围与文档范围不一致");
            }
            for (String hash : value.documentSha256ById().values()) {
                if (hash == null || hash.isBlank() || !hashes.add(hash)) {
                    throw new IllegalArgumentException("任务材料存在重复或缺失的文件 SHA-256");
                }
            }
        }
    }

    private static String featureId(String description) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(description.getBytes(StandardCharsets.UTF_8));
            return "feature-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
