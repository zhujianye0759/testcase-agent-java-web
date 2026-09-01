package com.testcaseagent.task;

import com.testcaseagent.scope.DynamicScopeCatalogService;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.scope.ScopeCatalogSnapshot;
import com.testcaseagent.scope.ScopeSelection;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves opaque browser choices to a freshly revalidated, immutable formal requirement scope.
 *
 * [Req-ID]: REQ-CAT-004, REQ-CAT-005, REQ-SCP-001, REQ-SCP-004, REQ-TGV2-016
 */
public final class DynamicTaskScopeResolver {
    private final DynamicScopeCatalogService catalogService;
    private final TaskGenerationProfileProperties profile;

    public DynamicTaskScopeResolver(DynamicScopeCatalogService catalogService, TaskGenerationProfileProperties profile) {
        this.catalogService = catalogService;
        this.profile = profile;
    }

    public CreateGenerationTaskRequest resolve(CreateGenerationTaskCommand command) {
        GenerationContractVersions versions = command.contractVersions();
        if (versions == null || !versions.isV2() || command.approvedFunctionScope() == null) {
            throw new IllegalArgumentException("新任务必须提供版本 2.0 的已审核功能范围");
        }
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

        List<ApprovedFunctionScope.ApprovedFunction> approved = command.approvedFunctionScope().functions();
        List<String> approvedKeys = approved.stream().map(ApprovedFunctionScope.ApprovedFunction::functionKey).toList();
        Map<String, String> approvedPaths = approved.stream().collect(java.util.stream.Collectors.toMap(
                ApprovedFunctionScope.ApprovedFunction::functionKey,
                ApprovedFunctionScope.ApprovedFunction::path,
                (left, right) -> { throw new IllegalArgumentException("已审核功能范围包含重复键"); },
                java.util.LinkedHashMap::new));
        if (command.taskMode() == GenerationTaskMode.ALL) {
            return new CreateGenerationTaskRequest(command.taskMode(), approvedKeys.get(0), approvedKeys, approvedPaths,
                    command.fewShotPolicy(), command.schemaVersion(), command.promptVersion(), profile.requiredAgentId(),
                    requirementScope, profile.exampleScope(), admissionTypes,
                    command.prompt().isBlank() ? "请根据已审核功能范围生成测试用例。" : command.prompt(),
                    versions, command.approvedFunctionScope());
        }
        if (approved.size() != 1) {
            throw new IllegalArgumentException("单功能任务的已审核功能范围必须恰好包含一个功能");
        }
        String featureId = approvedKeys.get(0);
        String promptPrefix = command.prompt().isBlank() ? "" : command.prompt() + "\n";
        return new CreateGenerationTaskRequest(command.taskMode(), featureId, List.of(featureId),
                Map.of(featureId, approved.get(0).path()), command.fewShotPolicy(), command.schemaVersion(),
                command.promptVersion(), profile.requiredAgentId(), requirementScope, profile.exampleScope(), admissionTypes,
                promptPrefix + "目标功能描述：" + approved.get(0).description(),
                versions, command.approvedFunctionScope());
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

}
