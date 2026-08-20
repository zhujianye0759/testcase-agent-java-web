package com.testcaseagent.structuredgeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.testcaseagent.validation.FeatureReconciliationValidator;
import com.testcaseagent.validation.RequirementMaterialReviewValidator;
import com.testcaseagent.validation.StructuredEvidence;
import com.testcaseagent.validation.StructuredKeyType;
import com.testcaseagent.validation.StructuredValidationRegistry;
import com.testcaseagent.web.TestCaseAgentApplication;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** MySQL acceptance tests for atomic structured result persistence. [Req-ID]: REQ-STG-001, REQ-STG-006 */
@Testcontainers
@SpringBootTest(classes = TestCaseAgentApplication.class)
class StructuredGenerationAcceptanceStoreIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("structured_generation").withUsername("testcase_agent").withPassword("test-only-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl); registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword); registry.add("app.artifacts.root", () -> "./var/test-artifacts");
    }

    @org.springframework.beans.factory.annotation.Autowired JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired PlatformTransactionManager transactionManager;
    private StructuredGenerationAcceptanceStore store;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM structured_reference_binding"); jdbc.update("DELETE FROM structured_test_case_step");
        jdbc.update("DELETE FROM structured_test_case"); jdbc.update("DELETE FROM structured_test_point");
        jdbc.update("DELETE FROM structured_feature_reconciliation"); jdbc.update("DELETE FROM structured_review_finding");
        jdbc.update("DELETE FROM structured_requirement_fact"); jdbc.update("DELETE FROM structured_generation_attempt");
        jdbc.update("DELETE FROM structured_generation_work_item"); jdbc.update("DELETE FROM generation_task");
        jdbc.update("INSERT INTO generation_task (id, task_mode, status, request_snapshot) VALUES ('task-1', 'FEATURE', 'QUEUED', JSON_OBJECT())");
        store = new StructuredGenerationAcceptanceStore(jdbc, new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    /** [Req-ID]: REQ-STG-001, REQ-STG-006 */
    @Test
    void validatesBeforeAtomicPersistenceAndAcceptsTheSameResultIdempotently() {
        String workId = store.register(reviewWork("a".repeat(64)));
        StructuredGenerationAcceptanceStore.WorkClaim claim = store.claimNext("task-1", "worker-1").orElseThrow();
        RequirementMaterialReviewValidator validator = new RequirementMaterialReviewValidator();
        RequirementMaterialReviewValidator.WorkItem item = reviewItem();
        RequirementMaterialReviewValidator.Result result = new RequirementMaterialReviewValidator.Result(List.of(fact("fact-1")), List.of());

        store.acceptReview(claim, validator, item, result);
        store.acceptReview(claim, validator, item, result);

        assertThat(workId).isEqualTo(claim.workItemId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_requirement_fact", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_work_item WHERE id = ?", String.class, workId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT source_label FROM structured_generation_work_item WHERE id = ?", String.class, workId)).isEqualTo("requirements slice 33-64");
        RequirementMaterialReviewValidator.Result changed = new RequirementMaterialReviewValidator.Result(List.of(fact("fact-1", "changed function")), List.of());
        assertThatThrownBy(() -> store.acceptReview(claim, validator, item, changed)).isInstanceOf(IllegalStateException.class);
    }

    /** [Req-ID]: REQ-STG-001, REQ-STG-003, REQ-STG-006 */
    @Test
    void rebuildsPublishedKeysIdempotentlyAndRestoresConfirmedFunctionMappingsAfterRestart() {
        StructuredValidationRegistry liveRegistry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true))
                .registerEvidence(new StructuredEvidence("evidence-2", "task-1", "material-1", false, false, true));
        store.register(reviewWork("1".repeat(64)));
        StructuredGenerationAcceptanceStore.WorkClaim review = store.claimNext("task-1", "worker-1").orElseThrow();
        store.acceptReview(review, new RequirementMaterialReviewValidator(),
                new RequirementMaterialReviewValidator.WorkItem(
                        liveRegistry, "material-1", "requirements_spec", List.of("evidence-1")),
                new RequirementMaterialReviewValidator.Result(List.of(fact("fact-confirmed", "事实显示名称")), List.of()));

        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "2".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 2, 2, "material-1", "功能清单切片",
                List.of("evidence-2"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim extraction = store.claimNext("task-1", "worker-1").orElseThrow();
        store.acceptFunctionListItems(extraction, liveRegistry, List.of(
                new StructuredGenerationAcceptanceStore.FunctionListItem(
                        "fli-confirmed", "订单/最终功能", "已确认功能", List.of("evidence-2"))));

        StructuredGenerationAcceptanceStore.AcceptedInputs accepted = store.acceptedInputs("task-1");
        accepted.facts().forEach(row -> liveRegistry.requireOrRegister(StructuredKeyType.REQUIREMENT_FACT, row.factKey()));
        accepted.functionItems().forEach(row -> liveRegistry.requireOrRegister(StructuredKeyType.FUNCTION_LIST_ITEM, row.itemKey()));
        StructuredValidationRegistry restartedRegistry = StructuredValidationRegistry.forTask("task-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true))
                .registerEvidence(new StructuredEvidence("evidence-2", "task-1", "material-1", false, false, true));
        accepted.facts().forEach(row -> restartedRegistry.requireOrRegister(StructuredKeyType.REQUIREMENT_FACT, row.factKey()));
        accepted.functionItems().forEach(row -> restartedRegistry.requireOrRegister(StructuredKeyType.FUNCTION_LIST_ITEM, row.itemKey()));

        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "3".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_RECONCILIATION", null, null, null, "核对",
                List.of("evidence-1", "evidence-2"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim reconciliation = store.claimNext("task-1", "worker-1").orElseThrow();
        FeatureReconciliationValidator.Result result = new FeatureReconciliationValidator.Result(List.of(
                new FeatureReconciliationValidator.Reconciliation("reconciliation-confirmed",
                        List.of("fli-confirmed"), List.of("fact-confirmed"),
                        FeatureReconciliationValidator.Classification.EXACT_MATCH,
                        List.of("evidence-1", "evidence-2"), "保持范围",
                        FeatureReconciliationValidator.ConfirmationStatus.CONFIRMED)));
        store.acceptReconciliation(reconciliation, new FeatureReconciliationValidator(),
                new FeatureReconciliationValidator.WorkItem(restartedRegistry, List.of("fli-confirmed"),
                        List.of("fact-confirmed"), List.of("evidence-1", "evidence-2")), result);

        StructuredGenerationAcceptanceStore restartedStore = new StructuredGenerationAcceptanceStore(
                jdbc, new TransactionTemplate(transactionManager), Clock.systemUTC());
        assertThat(restartedStore.acceptedConfirmedFunctions("task-1")).singleElement().satisfies(mapping -> {
            assertThat(mapping.reconciliationKey()).isEqualTo("reconciliation-confirmed");
            assertThat(mapping.functionItems()).extracting(StructuredGenerationAcceptanceStore.AcceptedFunctionItem::path)
                    .containsExactly("订单/最终功能");
            assertThat(mapping.facts()).extracting(StructuredGenerationAcceptanceStore.AcceptedFact::factKey)
                    .containsExactly("fact-confirmed");
        });
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void rollsBackAllBusinessRowsWhenOneDatabaseWriteFailsAndRetriesOnlyTheFailedWork() {
        String firstId = store.register(reviewWork("d".repeat(64)));
        StructuredGenerationAcceptanceStore.WorkClaim first = store.claimNext("task-1", "worker-1").orElseThrow();
        RequirementMaterialReviewValidator validator = new RequirementMaterialReviewValidator();
        String secondId = store.register(reviewWork("f".repeat(64)));
        StructuredGenerationAcceptanceStore.WorkClaim completedSecond = store.claimNext("task-1", "worker-2").orElseThrow();
        store.acceptReview(completedSecond, validator, reviewItem(),
                new RequirementMaterialReviewValidator.Result(List.of(fact("fact-second")), List.of()));
        RequirementMaterialReviewValidator.Result invalidForDatabase = new RequirementMaterialReviewValidator.Result(
                List.of(fact("fact-ok"), fact("x".repeat(129))), List.of());

        assertThatThrownBy(() -> store.acceptReview(first, validator, reviewItem(), invalidForDatabase))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_requirement_fact WHERE work_item_id = ?", Integer.class, firstId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_attempt WHERE id = ?", String.class, first.attemptId())).isEqualTo("RUNNING");

        store.fail(first, "model_execution_failed");
        StructuredGenerationAcceptanceStore.WorkClaim retried = store.claimNext("task-1", "worker-2").orElseThrow();
        assertThat(retried.workItemId()).isEqualTo(firstId);
        assertThat(retried.attemptNumber()).isEqualTo(2);
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_work_item WHERE id = ?", String.class, secondId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_requirement_fact WHERE work_item_id = ?", Integer.class, secondId)).isEqualTo(1);
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void concurrentClaimsAllowOnlyOneRunningAttemptForOneWorkIdentity() throws Exception {
        store.register(reviewWork("1".repeat(64)));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = workers.submit(() -> { start.await(); return store.claimNext("task-1", "worker-a").isPresent(); });
            Future<Boolean> second = workers.submit(() -> { start.await(); return store.claimNext("task-1", "worker-b").isPresent(); });
            start.countDown();
            assertThat(List.of(first.get(), second.get())).filteredOn(Boolean::booleanValue).hasSize(1);
        } finally {
            workers.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_attempt WHERE status = 'RUNNING'", Integer.class)).isEqualTo(1);
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void concurrentRegistrationReturnsOneStableWorkIdForTheSameIdentity() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = workers.submit(() -> { start.await(); return store.register(reviewWork("9".repeat(64))); });
            Future<String> second = workers.submit(() -> { start.await(); return store.register(reviewWork("9".repeat(64))); });
            start.countDown();
            assertThat(first.get()).isEqualTo(second.get());
        } finally {
            workers.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_work_item", Integer.class)).isEqualTo(1);
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void rejectsTheSameIdentityWhenTheFrozenOperationChangesWithoutMutatingTheStoredWork() {
        String identity = "8".repeat(64);
        StructuredGenerationAcceptanceStore.WorkRegistration extraction = new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", identity, "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT",
                1, 1, "material-1", "slice", List.of("evidence-1"), null, null);
        store.register(extraction);
        StructuredGenerationAcceptanceStore.WorkRegistration conflicting = new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", identity, "feature-scope-reconciliation", "FEATURE_SCOPE_RECONCILIATION",
                1, 1, "material-1", "slice", List.of("evidence-1"), null, null);

        assertThatThrownBy(() -> store.register(conflicting)).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT operation_name FROM structured_generation_work_item WHERE task_id = 'task-1' AND identity_key = ?",
                String.class, identity)).isEqualTo("FEATURE_SCOPE_EXTRACT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_work_item", Integer.class)).isEqualTo(1);
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void rejectsTheSameIdentityWhenTheFrozenEvidenceClosureChangesWithoutMutatingTheStoredWork() {
        String identity = "7".repeat(64);
        StructuredGenerationAcceptanceStore.WorkRegistration original = new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", identity, "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT",
                1, 1, "material-1", "slice", List.of("evidence-1"), null, null);
        store.register(original);
        StructuredGenerationAcceptanceStore.WorkRegistration conflicting = new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", identity, "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT",
                1, 1, "material-1", "slice", List.of("evidence-2"), null, null);

        assertThatThrownBy(() -> store.register(conflicting)).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT allowed_evidence_keys_json FROM structured_generation_work_item WHERE task_id = 'task-1' AND identity_key = ?",
                String.class, identity)).isEqualTo("[\"evidence-1\"]");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_work_item", Integer.class)).isEqualTo(1);
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void reclaimsAnExpiredLeaseAndRejectsTheStaleClaim() {
        StructuredGenerationAcceptanceStore.WorkClaim stale = claimReviewWork();
        jdbc.update("UPDATE structured_generation_work_item SET lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?", stale.workItemId());

        StructuredGenerationAcceptanceStore.WorkClaim replacement = store.claimNext("task-1", "worker-2").orElseThrow();

        assertThat(replacement.workItemId()).isEqualTo(stale.workItemId());
        assertThat(replacement.attemptNumber()).isEqualTo(2);
        assertThatThrownBy(() -> store.acceptReview(stale, new RequirementMaterialReviewValidator(), reviewItem(),
                new RequirementMaterialReviewValidator.Result(List.of(fact("fact-stale")), List.of())))
                .isInstanceOf(IllegalStateException.class);
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void doesNotClaimWorkAfterTheBoundedRetryLimit() {
        StructuredGenerationAcceptanceStore.WorkClaim first = claimReviewWork();
        store.fail(first, "model_execution_failed");
        StructuredGenerationAcceptanceStore.WorkClaim second = store.claimNext("task-1", "worker-2").orElseThrow();
        store.fail(second, "model_execution_failed");

        assertThat(store.claimNext("task-1", "worker-3")).isEmpty();
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void doesNotRetryStructuredOutputInvalidBecauseKeeAlreadyExhaustedItsRepairAttempt() {
        StructuredGenerationAcceptanceStore.WorkClaim claim = claimReviewWork();

        store.fail(claim, "structured_output_invalid");

        assertThat(store.claimNext("task-1", "worker-2")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?",
                Integer.class, claim.workItemId())).isEqualTo(1);
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void retriesOnlyTheRequestedTransientlyFailedWorkWithoutClaimingAnotherQueuedIdentity() {
        String requestedWork = store.register(reviewWork("a".repeat(64)));
        String otherWork = store.register(reviewWork("b".repeat(64)));
        StructuredGenerationAcceptanceStore.WorkClaim first = store.claimRegistered(
                "task-1", requestedWork, "worker-1").orElseThrow();
        store.fail(first, "model_unavailable");

        StructuredGenerationAcceptanceStore.WorkClaim retry = store.claimRegistered(
                "task-1", requestedWork, "worker-2").orElseThrow();

        assertThat(retry.workItemId()).isEqualTo(requestedWork);
        assertThat(retry.attemptNumber()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_work_item WHERE id = ?",
                String.class, otherWork)).isEqualTo("QUEUED");
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void rejectsUnknownFailureTypesWithoutChangingTheRunningClaim() {
        StructuredGenerationAcceptanceStore.WorkClaim claim = claimReviewWork();

        assertThatThrownBy(() -> store.fail(claim, "database password=secret"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_attempt WHERE id = ?",
                String.class, claim.attemptId())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT failure_type FROM structured_generation_attempt WHERE id = ?",
                String.class, claim.attemptId())).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_work_item WHERE id = ?",
                String.class, claim.workItemId())).isEqualTo("RUNNING");
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void rejectsValidatedContentFromAnotherTaskBeforeAnyPersistence() {
        StructuredGenerationAcceptanceStore.WorkClaim claim = claimReviewWork();
        StructuredValidationRegistry otherTaskRegistry = StructuredValidationRegistry.forTask("task-other")
                .register(StructuredKeyType.MATERIAL, "material-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-other", "material-1", false, false, true));
        RequirementMaterialReviewValidator.WorkItem foreign = new RequirementMaterialReviewValidator.WorkItem(
                otherTaskRegistry, "material-1", "requirements_spec", List.of("evidence-1"));

        assertThatThrownBy(() -> store.acceptReview(claim, new RequirementMaterialReviewValidator(), foreign,
                new RequirementMaterialReviewValidator.Result(List.of(fact("fact-1")), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_requirement_fact", Integer.class)).isZero();
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void updatesAndReadsProcessingAndCoverageAxesTogether() {
        StructuredGenerationAcceptanceStore.StructuredTaskState expected = new StructuredGenerationAcceptanceStore.StructuredTaskState(
                StructuredProcessingStatus.COMPLETED,
                StructuredCoverageStatus.UNABLE_TO_GENERATE);

        assertThat(store.updateTaskState("task-1", expected)).isEqualTo(expected);
        assertThat(store.findTaskState("task-1")).contains(expected);
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void persistsCancelledProcessingIndependentlyFromCoverage() {
        StructuredGenerationAcceptanceStore.StructuredTaskState expected = new StructuredGenerationAcceptanceStore.StructuredTaskState(
                StructuredProcessingStatus.CANCELLED, StructuredCoverageStatus.PENDING);

        store.updateTaskState("task-1", expected);

        assertThat(store.findTaskState("task-1")).contains(expected);
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void acceptsOnlyRegistryVerifiedJavaFunctionListItemsIdempotently() {
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "2".repeat(64),
                "java-function-list-import", "FEATURE_SCOPE_EXTRACT", null, null, "material-1", "slice", List.of("evidence-1"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim claim = store.claimNext("task-1", "worker-1").orElseThrow();
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true));
        List<StructuredGenerationAcceptanceStore.FunctionListItem> items = List.of(
                new StructuredGenerationAcceptanceStore.FunctionListItem("item-1", "模块/功能", "由 Java 任务预先确定", List.of("evidence-1")));

        store.acceptFunctionListItems(claim, registry, items);
        store.acceptFunctionListItems(claim, registry, items);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_function_list_item", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding", Integer.class)).isEqualTo(1);
        registry.require(StructuredKeyType.FUNCTION_LIST_ITEM, "item-1");
        assertThatThrownBy(() -> store.acceptFunctionListItems(claim, registry, List.of(
                new StructuredGenerationAcceptanceStore.FunctionListItem("item-1", "模块/功能", "changed", List.of("evidence-1")))))
                .isInstanceOf(IllegalStateException.class);
    }

    /** [Req-ID]: REQ-STG-003, REQ-STG-006 */
    @Test
    void mergesTheSameStableFunctionItemAcrossSlicesAndARegistryRestartIntoOneTaskRow() {
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "b".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 1, 1, "material-1", "slice 1",
                List.of("evidence-1"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim first = store.claimNext("task-1", "worker-1").orElseThrow();
        StructuredValidationRegistry firstRegistry = StructuredValidationRegistry.forTask("task-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true));
        store.acceptFunctionListItems(first, firstRegistry, List.of(
                new StructuredGenerationAcceptanceStore.FunctionListItem("fli-stable", "订单/提交", "提交订单", List.of("evidence-1"))));

        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "c".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 2, 2, "material-1", "slice 2",
                List.of("evidence-2"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim second = store.claimNext("task-1", "worker-2").orElseThrow();
        StructuredValidationRegistry rebuiltRegistry = StructuredValidationRegistry.forTask("task-1")
                .registerEvidence(new StructuredEvidence("evidence-2", "task-1", "material-1", false, false, true));
        store.acceptFunctionListItems(second, rebuiltRegistry, List.of(
                new StructuredGenerationAcceptanceStore.FunctionListItem("fli-stable", "订单/提交", "提交订单", List.of("evidence-2"))));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_function_list_item WHERE task_id = 'task-1' AND item_key = 'fli-stable'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding WHERE subject_type = 'FUNCTION_LIST_ITEM' "
                + "AND subject_key = 'fli-stable'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = 'task-1' AND status = 'COMPLETED'",
                Integer.class)).isEqualTo(2);
        rebuiltRegistry.require(StructuredKeyType.FUNCTION_LIST_ITEM, "fli-stable");
    }

    /** [Req-ID]: REQ-STG-003, REQ-STG-006 */
    @Test
    void atomicallyMergesConcurrentFirstWritesOfTheSameStableFunctionItem() throws Exception {
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "d".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 1, 1, "material-1", "slice 1",
                List.of("evidence-1"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim first = store.claimNext("task-1", "worker-1").orElseThrow();
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "e".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 2, 2, "material-1", "slice 2",
                List.of("evidence-2"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim second = store.claimNext("task-1", "worker-2").orElseThrow();
        StructuredValidationRegistry firstRegistry = StructuredValidationRegistry.forTask("task-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true));
        StructuredValidationRegistry secondRegistry = StructuredValidationRegistry.forTask("task-1")
                .registerEvidence(new StructuredEvidence("evidence-2", "task-1", "material-1", false, false, true));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstWrite = workers.submit(() -> {
                start.await();
                store.acceptFunctionListItems(first, firstRegistry, List.of(
                        new StructuredGenerationAcceptanceStore.FunctionListItem("fli-concurrent", "订单/提交", "提交订单", List.of("evidence-1"))));
                return null;
            });
            Future<?> secondWrite = workers.submit(() -> {
                start.await();
                store.acceptFunctionListItems(second, secondRegistry, List.of(
                        new StructuredGenerationAcceptanceStore.FunctionListItem("fli-concurrent", "订单/提交", "提交订单", List.of("evidence-2"))));
                return null;
            });
            start.countDown();
            firstWrite.get();
            secondWrite.get();
        } finally {
            workers.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_function_list_item WHERE task_id = 'task-1' "
                + "AND item_key = 'fli-concurrent'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding WHERE subject_type = 'FUNCTION_LIST_ITEM' "
                + "AND subject_key = 'fli-concurrent'", Integer.class)).isEqualTo(2);
    }

    /** [Req-ID]: REQ-STG-002, REQ-STG-006 */
    @Test
    void rejectsARepeatedTaskFactKeyWithDifferentBusinessTextAfterRegistryRestart() {
        StructuredGenerationAcceptanceStore.WorkClaim first = claimReviewWork();
        store.acceptReview(first, new RequirementMaterialReviewValidator(), reviewItem(),
                new RequirementMaterialReviewValidator.Result(List.of(fact("fact-reused", "提交订单")), List.of()));
        store.register(reviewWork("f".repeat(64)));
        StructuredGenerationAcceptanceStore.WorkClaim conflicting = store.claimNext("task-1", "worker-2").orElseThrow();

        assertThatThrownBy(() -> store.acceptReview(conflicting, new RequirementMaterialReviewValidator(), reviewItem(),
                new RequirementMaterialReviewValidator.Result(List.of(fact("fact-reused", "取消订单")), List.of())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_requirement_fact WHERE task_id = 'task-1' "
                + "AND fact_key = 'fact-reused'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT function_name FROM structured_requirement_fact WHERE task_id = 'task-1' "
                + "AND fact_key = 'fact-reused'", String.class)).isEqualTo("提交订单");
        assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_work_item WHERE id = ?",
                String.class, conflicting.workItemId())).isEqualTo("RUNNING");
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void rejectsFunctionListEvidenceOutsideTheClaimedMaterialWithoutPublishingKeys() {
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "3".repeat(64),
                "java-function-list-import", "FEATURE_SCOPE_EXTRACT", null, null, "material-1", "slice", List.of("allowed-evidence"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim claim = store.claimNext("task-1", "worker-1").orElseThrow();
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .registerEvidence(new StructuredEvidence("allowed-evidence", "task-1", "other-material", false, false, true));

        assertThatThrownBy(() -> store.acceptFunctionListItems(claim, registry, List.of(
                new StructuredGenerationAcceptanceStore.FunctionListItem("item-unpublished", "模块", "说明", List.of("allowed-evidence")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_function_list_item", Integer.class)).isZero();
        assertThatThrownBy(() -> registry.require(StructuredKeyType.FUNCTION_LIST_ITEM, "item-unpublished"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void rejectsFunctionListEvidenceOutsideTheClaimedSliceAndAcceptsAnEmptySlice() {
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "4".repeat(64),
                "java-function-list-import", "FEATURE_SCOPE_EXTRACT", null, null, "material-1", "slice", List.of("allowed-evidence"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim outsideSlice = store.claimNext("task-1", "worker-1").orElseThrow();
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .registerEvidence(new StructuredEvidence("outside-evidence", "task-1", "material-1", false, false, true));

        assertThatThrownBy(() -> store.acceptFunctionListItems(outsideSlice, registry, List.of(
                new StructuredGenerationAcceptanceStore.FunctionListItem("item-outside", "模块", "说明", List.of("outside-evidence")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_function_list_item", Integer.class)).isZero();

        store.fail(outsideSlice, "structured_output_invalid");
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "6".repeat(64),
                "java-function-list-import", "FEATURE_SCOPE_EXTRACT", null, null, "material-1", "empty slice",
                List.of("allowed-evidence"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim empty = store.claimNext("task-1", "worker-2").orElseThrow();
        store.acceptFunctionListItems(empty, StructuredValidationRegistry.forTask("task-1"), List.of());
        assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_work_item WHERE id = ?", String.class, empty.workItemId())).isEqualTo("COMPLETED");
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void doesNotPublishJavaFunctionKeysWhenTheDatabaseTransactionRollsBack() {
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "5".repeat(64),
                "java-function-list-import", "FEATURE_SCOPE_EXTRACT", null, null, "material-1", "slice", List.of("evidence-1"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim claim = store.claimNext("task-1", "worker-1").orElseThrow();
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true));
        String tooLongKey = "x".repeat(129);

        assertThatThrownBy(() -> store.acceptFunctionListItems(claim, registry, List.of(
                new StructuredGenerationAcceptanceStore.FunctionListItem("item-rolled-back", "模块", "说明", List.of("evidence-1")),
                new StructuredGenerationAcceptanceStore.FunctionListItem(tooLongKey, "模块", "说明", List.of("evidence-1")))))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_function_list_item", Integer.class)).isZero();
        assertThatThrownBy(() -> registry.require(StructuredKeyType.FUNCTION_LIST_ITEM, "item-rolled-back"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** [Req-ID]: REQ-STG-006 */
    @Test
    void keepsReferenceSubjectsSeparateWhenFactAndFindingReuseTheSameKey() {
        StructuredGenerationAcceptanceStore.WorkClaim claim = claimReviewWork();
        RequirementMaterialReviewValidator.Result result = new RequirementMaterialReviewValidator.Result(
                List.of(fact("shared-key")), List.of(finding("shared-key")));

        store.acceptReview(claim, new RequirementMaterialReviewValidator(), reviewItem(), result);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding WHERE subject_key = 'shared-key'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT subject_type) FROM structured_reference_binding WHERE subject_key = 'shared-key'", Integer.class)).isEqualTo(2);
    }

    private static StructuredGenerationAcceptanceStore.WorkRegistration reviewWork(String identityKey) {
        return new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", identityKey,
                "requirement-material-quality-review", 33, 64, "material-1", "requirements slice 33-64",
                List.of("evidence-1"), null, null);
    }
    private StructuredGenerationAcceptanceStore.WorkClaim claimReviewWork() {
        store.register(reviewWork("a".repeat(64)));
        return store.claimNext("task-1", "worker-1").orElseThrow();
    }
    private static RequirementMaterialReviewValidator.WorkItem reviewItem() {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true));
        return new RequirementMaterialReviewValidator.WorkItem(registry, "material-1", "requirements_spec", List.of("evidence-1"));
    }
    private static RequirementMaterialReviewValidator.RequirementFact fact(String key) {
        return fact(key, "功能");
    }
    private static RequirementMaterialReviewValidator.RequirementFact fact(String key, String function) {
        return new RequirementMaterialReviewValidator.RequirementFact(key, function, List.of("role"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of("evidence-1"));
    }
    private static RequirementMaterialReviewValidator.ReviewFinding finding(String key) {
        return new RequirementMaterialReviewValidator.ReviewFinding(key, "ambiguity", "description", List.of("evidence-1"),
                "impact", "project recommendation", "guideline recommendation", RequirementMaterialReviewValidator.HandlingLevel.IMPROVEMENT);
    }
}
