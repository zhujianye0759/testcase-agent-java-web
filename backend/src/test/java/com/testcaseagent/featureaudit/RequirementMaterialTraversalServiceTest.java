package com.testcaseagent.featureaudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.scope.ParsedMaterial;
import com.testcaseagent.scope.ParsedMaterialPage;
import com.testcaseagent.scope.ParsedMaterialSummary;
import com.testcaseagent.scope.ParsedMaterialUnit;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementMaterialReaderPort;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.task.CreateGenerationTaskRequest;
import com.testcaseagent.task.ApprovedFunctionScope;
import com.testcaseagent.task.GenerationContractVersions;
import com.testcaseagent.task.GenerationTaskRepository;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Exercises the all-or-nothing material traversal seam before semantic audit work begins.
 *
 * [Req-ID]: REQ-SMR-002, REQ-SMR-003, REQ-BFA-001
 */
class RequirementMaterialTraversalServiceTest {

    /** [Req-ID]: REQ-TGV2-003 */
    @Test
    void v2StagesBoundedPagesAndPublishesOnlyAfterEveryFrozenDocumentCompletes() {
        RequirementMaterialReaderPort reader = mock(RequirementMaterialReaderPort.class);
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        CreateGenerationTaskRequest request = v2Request(List.of(
                new RequirementDocumentCoordinate("function-doc", "function_list"),
                new RequirementDocumentCoordinate("work-order-doc", "work_order_plan")),
                List.of("function_list", "work_order_plan"));
        when(reader.scanAll(eq(request.requirementScope()), eq("function-doc"), eq(50), any()))
                .thenAnswer(invocation -> emit(invocation.getArgument(3), "function-doc", List.of(
                        List.of(unit("function-1", 1)), List.of(unit("function-2", 2)))));
        when(reader.scanAll(eq(request.requirementScope()), eq("work-order-doc"), eq(50), any()))
                .thenAnswer(invocation -> emit(invocation.getArgument(3), "work-order-doc", List.of(
                        List.of(unit("work-1", 1)))));

        new RequirementMaterialTraversalService(reader, repository)
                .traversePagedV2("task-1", request, false);

        verify(reader, never()).readAll(any(), any());
        verify(repository, never()).replaceMaterialInventory(any(), any(), any(Boolean.class));
        ArgumentCaptor<MaterialInventoryPage> pages = ArgumentCaptor.forClass(MaterialInventoryPage.class);
        verify(repository, org.mockito.Mockito.times(3)).stageMaterialInventoryPage(eq("task-1"), pages.capture());
        assertThat(pages.getAllValues()).extracting(MaterialInventoryPage::documentRole)
                .containsExactly("FUNCTION_LIST", "FUNCTION_LIST", "WORK_ORDER_PLAN");
        verify(repository).publishStagedMaterialInventory("task-1", request.requirementScope());
    }

    /** [Req-ID]: REQ-TGV2-003 */
    @Test
    void v2NeverPublishesWhenALaterPageFailsAfterEarlierPagesWereStaged() {
        RequirementMaterialReaderPort reader = mock(RequirementMaterialReaderPort.class);
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        CreateGenerationTaskRequest request = v2Request(List.of(
                new RequirementDocumentCoordinate("work-order-doc", "work_order_plan")),
                List.of("work_order_plan"));
        when(reader.scanAll(eq(request.requirementScope()), eq("work-order-doc"), eq(50), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<ParsedMaterialPage> consumer = invocation.getArgument(3);
                    consumer.accept(new ParsedMaterialPage("work-order-doc", 2,
                            List.of(unit("work-1", 1)), false));
                    throw new IllegalStateException("later page failed closed");
                });

        assertThatThrownBy(() -> new RequirementMaterialTraversalService(reader, repository)
                .traversePagedV2("task-1", request, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed closed");

        verify(repository).stageMaterialInventoryPage(eq("task-1"), any());
        verify(repository, never()).publishStagedMaterialInventory(any(), any());
    }

    @Test
    void readsEveryFrozenDocumentThenPersistsRoleMappedCompleteInventory() {
        RequirementMaterialReaderPort reader = mock(RequirementMaterialReaderPort.class);
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        CreateGenerationTaskRequest request = request(List.of(
                new RequirementDocumentCoordinate("function-doc", "function_list"),
                new RequirementDocumentCoordinate("work-order-doc", "work_order_plan")),
                List.of("function_list", "work_order_plan"));
        when(reader.readAll(request.requirementScope(), "function-doc")).thenReturn(material("function-doc", "function-unit"));
        when(reader.readAll(request.requirementScope(), "work-order-doc")).thenReturn(material("work-order-doc", "work-unit"));

        RequirementMaterialTraversalService.TraversalResult result =
                new RequirementMaterialTraversalService(reader, repository).traverse("task-1", request, false);

        assertThat(result.documents()).extracting(MaterialInventoryDocument::documentRole)
                .containsExactly("FUNCTION_LIST", "WORK_ORDER_PLAN");
        ArgumentCaptor<List<MaterialInventoryDocument>> documents = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceMaterialInventory(eq("task-1"), documents.capture(), eq(false));
        assertThat(documents.getValue()).extracting(MaterialInventoryDocument::totalUnits).containsExactly(1, 1);
    }

    @Test
    void retainsPrototypeAndRequirementListAsDistinctSupplementaryMaterialRoles() {
        RequirementMaterialReaderPort reader = mock(RequirementMaterialReaderPort.class);
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        CreateGenerationTaskRequest request = request(List.of(
                new RequirementDocumentCoordinate("prototype-doc", "prototype"),
                new RequirementDocumentCoordinate("requirement-list-doc", "requirement_list")),
                List.of("prototype", "requirement_list"));
        when(reader.readAll(request.requirementScope(), "prototype-doc")).thenReturn(material("prototype-doc", "prototype-unit"));
        when(reader.readAll(request.requirementScope(), "requirement-list-doc"))
                .thenReturn(material("requirement-list-doc", "requirement-list-unit"));

        new RequirementMaterialTraversalService(reader, repository).traverse("task-1", request, false);

        ArgumentCaptor<List<MaterialInventoryDocument>> documents = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceMaterialInventory(eq("task-1"), documents.capture(), eq(false));
        assertThat(documents.getValue()).extracting(MaterialInventoryDocument::documentRole)
                .containsExactly("PROTOTYPE", "REQUIREMENT_LIST");
    }

    @Test
    void neverPersistsACompleteGateWhenALaterFrozenDocumentReadFails() {
        RequirementMaterialReaderPort reader = mock(RequirementMaterialReaderPort.class);
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        CreateGenerationTaskRequest request = request(List.of(
                new RequirementDocumentCoordinate("function-doc", "function_list"),
                new RequirementDocumentCoordinate("work-order-doc", "work_order_plan")),
                List.of("function_list", "work_order_plan"));
        when(reader.readAll(request.requirementScope(), "function-doc")).thenReturn(material("function-doc", "function-unit"));
        doThrow(new IllegalStateException("parsed units failed closed"))
                .when(reader).readAll(request.requirementScope(), "work-order-doc");

        assertThatThrownBy(() -> new RequirementMaterialTraversalService(reader, repository)
                .traverse("task-1", request, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed closed");

        verify(reader).readAll(request.requirementScope(), "function-doc");
        verify(reader).readAll(request.requirementScope(), "work-order-doc");
        verify(repository, never()).replaceMaterialInventory(any(), any(), any(Boolean.class));
    }

    @Test
    void stopsBeforeTheNextBoundedMaterialReadWhenCancellationIsRequested() {
        RequirementMaterialReaderPort reader = mock(RequirementMaterialReaderPort.class);
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        CreateGenerationTaskRequest request = request(List.of(
                new RequirementDocumentCoordinate("function-doc", "function_list"),
                new RequirementDocumentCoordinate("work-order-doc", "work_order_plan")),
                List.of("function_list", "work_order_plan"));
        when(repository.isCancellationRequested("task-1")).thenReturn(false, false, true);
        when(reader.readAll(request.requirementScope(), "function-doc")).thenReturn(material("function-doc", "function-unit"));

        assertThatThrownBy(() -> new RequirementMaterialTraversalService(reader, repository)
                .traverse("task-1", request, false))
                .isInstanceOf(CancellationException.class);

        verify(reader).readAll(request.requirementScope(), "function-doc");
        verify(repository, never()).replaceMaterialInventory(any(), any(), any(Boolean.class));
    }

    @Test
    void infersAnOldSnapshotRoleOnlyWhenItHasExactlyOneAdmissionType() {
        RequirementMaterialReaderPort reader = mock(RequirementMaterialReaderPort.class);
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        CreateGenerationTaskRequest request = request(List.of(new RequirementDocumentCoordinate("function-doc")),
                List.of("function_list"));
        when(reader.readAll(request.requirementScope(), "function-doc")).thenReturn(material("function-doc", "function-unit"));

        new RequirementMaterialTraversalService(reader, repository).traverse("task-1", request, false);

        ArgumentCaptor<List<MaterialInventoryDocument>> documents = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceMaterialInventory(eq("task-1"), documents.capture(), eq(false));
        assertThat(documents.getValue()).extracting(MaterialInventoryDocument::documentRole).containsExactly("FUNCTION_LIST");
    }

    @Test
    void rejectsAnOldSnapshotWhoseMultipleAdmissionTypesCannotIdentifyDocumentRoles() {
        RequirementMaterialReaderPort reader = mock(RequirementMaterialReaderPort.class);
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        CreateGenerationTaskRequest request = request(List.of(new RequirementDocumentCoordinate("legacy-doc")),
                List.of("function_list", "work_order_plan"));

        assertThatThrownBy(() -> new RequirementMaterialTraversalService(reader, repository)
                .traverse("task-1", request, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("document role");

        verifyNoInteractions(reader);
        verify(repository, never()).replaceMaterialInventory(any(), any(), any(Boolean.class));
    }

    @Test
    void explicitlyReplacedMaterialClearsTheOldGateBeforeReadingFromTheFirstPageAgain() {
        RequirementMaterialReaderPort reader = mock(RequirementMaterialReaderPort.class);
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        CreateGenerationTaskRequest request = request(List.of(new RequirementDocumentCoordinate("function-doc", "function_list")),
                List.of("function_list"));
        when(reader.readAll(request.requirementScope(), "function-doc")).thenReturn(material("function-doc", "function-unit"));

        new RequirementMaterialTraversalService(reader, repository).traverse("task-1", request, true);

        verify(repository).clearMaterialInventoryForExplicitReplacement("task-1");
        verify(repository).replaceMaterialInventory(eq("task-1"), any(), eq(false));
    }

    private static CreateGenerationTaskRequest request(
            List<RequirementDocumentCoordinate> documents, List<String> admissionTypes) {
        return new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "all-pending", List.of(), java.util.Map.of(),
                FewShotPolicy.NONE, "1.0", "1.0", "audit-agent",
                new RequirementScope("requirement-kb", "system-1", "version-1", "admission_material", null, documents),
                new ExampleScope("example-kb", List.of("example-1")), admissionTypes, "traverse material");
    }

    private static ParsedMaterial material(String documentId, String unitId) {
        return new ParsedMaterial(documentId, 1,
                List.of(new ParsedMaterialUnit(unitId, 0, 1, "text", 0, 4)));
    }

    private static CreateGenerationTaskRequest v2Request(
            List<RequirementDocumentCoordinate> documents, List<String> admissionTypes) {
        ApprovedFunctionScope approved = new ApprovedFunctionScope("scope-v2", List.of(
                new ApprovedFunctionScope.ApprovedFunction("function-a", "提交申请", "业务/提交申请", "")));
        RequirementScope scope = new RequirementScope(
                "requirement-kb", "system-1", "version-1", "admission_material", "project-1", documents);
        return new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "function-a", List.of("function-a"),
                Map.of("function-a", "业务/提交申请"), FewShotPolicy.NONE, "2.0", "2.0", "audit-agent", scope,
                new ExampleScope("example-kb", List.of("example-1")), admissionTypes, "traverse material",
                new GenerationContractVersions("2.0", "2.0", "2.0"), approved);
    }

    private static ParsedMaterialSummary emit(Object rawConsumer, String documentId,
            List<List<ParsedMaterialUnit>> pages) {
        @SuppressWarnings("unchecked")
        Consumer<ParsedMaterialPage> consumer = (Consumer<ParsedMaterialPage>) rawConsumer;
        int total = pages.stream().mapToInt(List::size).sum();
        for (int index = 0; index < pages.size(); index++) {
            consumer.accept(new ParsedMaterialPage(documentId, total, pages.get(index), index == pages.size() - 1));
        }
        return new ParsedMaterialSummary(documentId, total);
    }

    private static ParsedMaterialUnit unit(String unitId, int ordinal) {
        return new ParsedMaterialUnit(unitId, ordinal - 1, ordinal, "text-" + ordinal, ordinal - 1, ordinal);
    }
}
