package com.testcaseagent.featureaudit;

import com.testcaseagent.scope.ParsedMaterial;
import com.testcaseagent.scope.ParsedMaterialUnit;
import com.testcaseagent.scope.ParsedUnitCatalogPort;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.task.CreateGenerationTaskRequest;
import com.testcaseagent.task.GenerationTaskRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Enumerates every frozen requirement document before atomically opening its durable audit work.
 *
 * <p>It deliberately consumes only {@link ParsedUnitCatalogPort}; preview text and model output cannot
 * satisfy this completion boundary. A later document failure happens before any inventory is persisted.</p>
 *
 * [Req-ID]: REQ-SMR-002, REQ-SMR-003, REQ-BFA-001
 */
public final class RequirementMaterialTraversalService {

    private final ParsedUnitCatalogPort materialReader;
    private final GenerationTaskRepository repository;

    public RequirementMaterialTraversalService(
            ParsedUnitCatalogPort materialReader, GenerationTaskRepository repository) {
        this.materialReader = materialReader;
        this.repository = repository;
    }

    /**
     * Reads every document from its first page and persists a complete inventory only after all reads succeed.
     *
     * @param explicitlyReplaced true only when the caller has explicitly reported replacement and wants a restart
     * @return the complete inventory submitted to the durable repository gate
     */
    public TraversalResult traverse(String taskId, CreateGenerationTaskRequest request, boolean explicitlyReplaced) {
        throwIfCancellationRequested(taskId);
        if (explicitlyReplaced) {
            repository.clearMaterialInventoryForExplicitReplacement(taskId);
        }
        List<MaterialInventoryDocument> documents = new ArrayList<>();
        for (RequirementDocumentCoordinate document : request.requirementScope().documents()) {
            throwIfCancellationRequested(taskId);
            documents.add(readDocument(request, document));
        }
        throwIfCancellationRequested(taskId);
        repository.replaceMaterialInventory(taskId, documents, false);
        return new TraversalResult(documents);
    }

    private void throwIfCancellationRequested(String taskId) {
        if (repository.isCancellationRequested(taskId)) {
            throw new CancellationException("Cancellation requested between material reads");
        }
    }

    private MaterialInventoryDocument readDocument(
            CreateGenerationTaskRequest request, RequirementDocumentCoordinate document) {
        String role = documentRole(document, request.requirementAdmissionTypeKeys());
        ParsedMaterial material = materialReader.readAll(request.requirementScope(), document.documentId());
        if (!document.documentId().equals(material.knowledgeId())) {
            throw new IllegalStateException("Parsed material knowledge id does not match the frozen document");
        }
        List<MaterialInventoryUnit> units = material.units().stream()
                .map(unit -> inventoryUnit(document.documentId(), role, unit))
                .toList();
        return new MaterialInventoryDocument(document.documentId(), material.knowledgeId(), role,
                material.totalUnits(), true, units);
    }

    private static MaterialInventoryUnit inventoryUnit(String documentId, String role, ParsedMaterialUnit unit) {
        return new MaterialInventoryUnit(documentId, role, unit.unitId(), unit.chunkIndex(), unit.ordinal(),
                unit.content(), unit.startAt(), unit.endAt());
    }

    private static String documentRole(RequirementDocumentCoordinate document, List<String> admissionTypes) {
        String materialType = document.materialTypeKey();
        if (materialType == null) {
            if (admissionTypes.size() != 1) {
                throw new IllegalStateException("Cannot determine document role for a legacy task snapshot");
            }
            materialType = admissionTypes.get(0);
        }
        return switch (materialType) {
            case "function_list" -> "FUNCTION_LIST";
            case "work_order_plan" -> "WORK_ORDER_PLAN";
            case "requirements_spec" -> "REQUIREMENT";
            case "prototype" -> "PROTOTYPE";
            case "requirement_list" -> "REQUIREMENT_LIST";
            default -> throw new IllegalStateException("Unsupported requirement material type: " + materialType);
        };
    }

    /** Complete material inventory returned only after the repository gate accepts it. */
    public record TraversalResult(List<MaterialInventoryDocument> documents) {
        public TraversalResult {
            documents = List.copyOf(documents);
        }
    }
}
