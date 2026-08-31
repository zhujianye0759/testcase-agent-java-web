package com.testcaseagent.featureaudit;

import com.testcaseagent.scope.ParsedMaterial;
import com.testcaseagent.scope.ParsedMaterialPage;
import com.testcaseagent.scope.ParsedMaterialSummary;
import com.testcaseagent.scope.ParsedMaterialUnit;
import com.testcaseagent.scope.ParsedUnitCatalogPort;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.task.CreateGenerationTaskRequest;
import com.testcaseagent.task.GenerationTaskRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Enumerates every frozen requirement document before atomically opening its durable audit work.
 *
 * <p>It deliberately consumes only {@link ParsedUnitCatalogPort}; preview text and model output cannot satisfy this
 * boundary. V1 publishes in one write. V2 may persist page-sized staging rows, but none become formal evidence until
 * the complete frozen scope passes the final publication transaction.</p>
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

    /**
     * Traverses V2 material with bounded pages, stages each page durably, then publishes the exact frozen inventory.
     *
     * <p>A remote or database failure can leave restartable staging rows, but never a complete evidence gate. A
     * restart scans from the first remote page; exact staged rows are idempotent and any source drift fails closed.</p>
     *
     * [Req-ID]: REQ-TGV2-003
     */
    public void traversePagedV2(String taskId, CreateGenerationTaskRequest request, boolean explicitlyReplaced) {
        if (!Objects.requireNonNull(request, "request must not be null").isV2()) {
            throw new IllegalArgumentException("Paged traversal requires a V2 task snapshot");
        }
        throwIfCancellationRequested(taskId);
        if (explicitlyReplaced) {
            repository.clearMaterialInventoryForExplicitReplacement(taskId);
        }
        for (RequirementDocumentCoordinate document : request.requirementScope().documents()) {
            throwIfCancellationRequested(taskId);
            String role = documentRole(document, request.requirementAdmissionTypeKeys());
            ParsedMaterialSummary summary = materialReader.scanAll(
                    request.requirementScope(), document.documentId(), ParsedUnitCatalogPort.DEFAULT_PAGE_SIZE,
                    page -> stagePage(taskId, document, role, page));
            if (!document.documentId().equals(summary.knowledgeId())) {
                throw new IllegalStateException("Parsed material knowledge id does not match the frozen document");
            }
        }
        throwIfCancellationRequested(taskId);
        repository.publishStagedMaterialInventory(taskId, request.requirementScope());
    }

    private void stagePage(String taskId, RequirementDocumentCoordinate document, String role,
            ParsedMaterialPage page) {
        throwIfCancellationRequested(taskId);
        if (!document.documentId().equals(page.knowledgeId())) {
            throw new IllegalStateException("Parsed material page does not match the frozen document");
        }
        List<MaterialInventoryUnit> units = page.units().stream()
                .map(unit -> inventoryUnit(document.documentId(), role, unit))
                .toList();
        repository.stageMaterialInventoryPage(taskId, new MaterialInventoryPage(
                document.documentId(), page.knowledgeId(), role, page.totalUnits(), page.complete(), units));
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
