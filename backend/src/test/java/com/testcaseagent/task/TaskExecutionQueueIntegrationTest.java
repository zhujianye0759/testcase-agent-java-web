package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import com.testcaseagent.web.TestCaseAgentApplication;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves durable five-slot task admission against MySQL 8.
 *
 * [Test-Ref]: TaskExecutionQueueIntegrationTest
 * [Req-ID]: REQ-TSK-003, REQ-TSK-005, REQ-TSK-006, REQ-TSK-008
 */
@Testcontainers
@SpringBootTest(classes = TestCaseAgentApplication.class, properties = "app.knowledge-agent.enabled=false")
@Import(TaskExecutionQueueIntegrationTest.QueueDependencies.class)
class TaskExecutionQueueIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("task_queue_test")
            .withUsername("testcase_agent")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    GenerationTaskRepository repository;

    @Autowired
    TaskExecutionQueue queue;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM v2_approved_function");
        jdbcTemplate.update("DELETE FROM structured_reference_binding");
        jdbcTemplate.update("DELETE FROM structured_generation_attempt");
        jdbcTemplate.update("DELETE FROM structured_generation_work_item");
        jdbcTemplate.update("DELETE FROM generation_attempt");
        jdbcTemplate.update("DELETE FROM generation_batch");
        jdbcTemplate.update("DELETE FROM generation_task");
        jdbcTemplate.update("UPDATE task_execution_slot SET task_id = NULL");
    }

    @AfterEach
    void releaseWorkers() {
        jdbcTemplate.update("UPDATE task_execution_slot SET task_id = NULL");
    }

    @Test
    void admitsOnlyFiveConcurrentTasksAndClaimsTheSixthOnlyAfterDurableRelease() throws Exception {
        List<String> taskIds = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            String taskId = UUID.randomUUID().toString();
            repository.createTask(taskId, GenerationWorkflowV2RoutingTest.request());
            taskIds.add(taskId);
        }

        CyclicBarrier start = new CyclicBarrier(6);
        ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            List<Future<Optional<TaskExecutionClaim>>> claims = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                claims.add(workers.submit(claimAfter(start)));
            }
            List<TaskExecutionClaim> admitted = claims.stream()
                    .map(this::resultOf)
                    .flatMap(Optional::stream)
                    .toList();

            assertThat(admitted).hasSize(5);
            assertThat(admitted).extracting(TaskExecutionClaim::taskId).doesNotHaveDuplicates();
            List<String> queuedTaskIds = taskIds.stream()
                    .filter(taskId -> admitted.stream().noneMatch(claim -> claim.taskId().equals(taskId)))
                    .toList();
            assertThat(queuedTaskIds).hasSize(1);
            String queuedTaskId = queuedTaskIds.get(0);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM generation_task WHERE status = 'AUDITING'", Integer.class)).isEqualTo(5);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM generation_task WHERE id = ?", String.class, queuedTaskId)).isEqualTo("QUEUED");

            TaskExecutionClaim releasedClaim = admitted.get(0);
            repository.transitionTask(releasedClaim.taskId(), GenerationTaskStatus.FAILED);
            queue.release(releasedClaim);

            Optional<TaskExecutionClaim> sixthClaim = queue.claimNext();
            assertThat(sixthClaim).isPresent();
            assertThat(sixthClaim.orElseThrow().taskId()).isEqualTo(queuedTaskId);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM task_execution_slot WHERE task_id IS NOT NULL", Integer.class)).isEqualTo(5);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void recoversAnExpiredClaimForAReplacementWorkerWithoutLosingTheAttempt() {
        String taskId = UUID.randomUUID().toString();
        String batchId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();
        repository.createTask(taskId, GenerationWorkflowV2RoutingTest.request());
        repository.createBatch(batchId, taskId, "feature-recovery");
        repository.createAttempt(attemptId, batchId);

        TaskExecutionClaim crashedClaim = queue.claimNext().orElseThrow();
        repository.transitionTask(taskId, GenerationTaskStatus.GENERATING);
        repository.startBatch(batchId, attemptId);
        jdbcTemplate.update("""
                        UPDATE generation_batch SET lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)
                        WHERE id = ?
                        """, batchId);

        queue.recoverAtStartup();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_task WHERE id = ?", String.class, taskId))
                .isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_batch WHERE id = ?", String.class, batchId))
                .isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_attempt WHERE id = ?", String.class, attemptId))
                .isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject("SELECT task_id FROM task_execution_slot WHERE slot_number = ?", String.class,
                crashedClaim.slotNumber())).isNull();
        assertThat(queue.claimNext()).hasValueSatisfying(replacement -> assertThat(replacement.taskId()).isEqualTo(taskId));
    }

    @Test
    void startupRecoveryRequeuesAnAuditingAllTaskWithPlannedButNeverRunningBatches() {
        String taskId = UUID.randomUUID().toString();
        String batchId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();
        CreateGenerationTaskRequest frozenAll = GenerationWorkflowV2RoutingTest.request();
        repository.createTask(taskId, frozenAll);
        repository.createBatch(batchId, taskId, "ff-1", 1);
        repository.createAttempt(attemptId, batchId);
        TaskExecutionClaim crashedClaim = queue.claimNext().orElseThrow();

        queue.recoverAtStartup();

        assertThat(repository.taskStatus(taskId)).isEqualTo(GenerationTaskStatus.QUEUED);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_batch WHERE id = ?", String.class, batchId))
                .isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_attempt WHERE id = ?", String.class, attemptId))
                .isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject("SELECT task_id FROM task_execution_slot WHERE slot_number = ?", String.class,
                crashedClaim.slotNumber())).isNull();
        assertThat(queue.claimNext()).hasValueSatisfying(replacement -> assertThat(replacement.taskId()).isEqualTo(taskId));
    }

    @Test
    void startupRecoveryRequeuesGeneratingAndValidatingStructuredTasksAndInvalidatesOnlyRunningAttempts() {
        String generatingTask = "structured-generating";
        String validatingTask = "structured-validating";
        repository.createTask(generatingTask, request("structured-g"));
        repository.createTask(validatingTask, request("structured-v"));
        jdbcTemplate.update("UPDATE generation_task SET workflow_version = '2.0' WHERE id IN (?, ?)",
                generatingTask, validatingTask);
        jdbcTemplate.update("""
                UPDATE generation_task SET status = 'GENERATING', structured_processing_status = 'RUNNING',
                    structured_coverage_status = 'PENDING' WHERE id = ?
                """, generatingTask);
        jdbcTemplate.update("""
                UPDATE generation_task SET status = 'VALIDATING', structured_processing_status = 'RUNNING',
                    structured_coverage_status = 'PENDING' WHERE id = ?
                """, validatingTask);
        jdbcTemplate.update("UPDATE task_execution_slot SET task_id = ? WHERE slot_number = 1", generatingTask);
        jdbcTemplate.update("UPDATE task_execution_slot SET task_id = ? WHERE slot_number = 2", validatingTask);
        insertStructuredRunningWork(generatingTask, "work-generating", "attempt-generating", "1".repeat(64));
        insertStructuredRunningWork(validatingTask, "work-validating", "attempt-validating", "2".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO structured_generation_work_item
                    (id, task_id, identity_key, skill_name, operation_name, status, allowed_evidence_keys_json,
                     accepted_result_sha256)
                VALUES ('work-completed', ?, ?, 'requirement-material-quality-review',
                    'REQUIREMENT_MATERIAL_REVIEW', 'COMPLETED', JSON_ARRAY(), ?)
                """, generatingTask, "3".repeat(64), "4".repeat(64));

        queue.recoverAtStartup();

        assertThat(jdbcTemplate.queryForList("""
                SELECT status FROM generation_task WHERE id IN (?, ?) ORDER BY id
                """, String.class, generatingTask, validatingTask)).containsOnly("QUEUED");
        assertThat(jdbcTemplate.queryForList("""
                SELECT structured_processing_status FROM generation_task WHERE id IN (?, ?) ORDER BY id
                """, String.class, generatingTask, validatingTask)).containsOnly("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_execution_slot WHERE task_id IN (?, ?)", Integer.class,
                generatingTask, validatingTask)).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT status FROM structured_generation_work_item WHERE id IN ('work-generating','work-validating')
                """, String.class)).containsOnly("FAILED");
        assertThat(jdbcTemplate.queryForList("""
                SELECT failure_type FROM structured_generation_attempt
                WHERE id IN ('attempt-generating','attempt-validating')
                """, String.class)).containsOnly("worker_interrupted");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id = 'work-completed'", String.class))
                .isEqualTo("COMPLETED");
        assertThat(queue.claimNext()).isPresent();
    }

    /** [Req-ID]: REQ-TGV2-008, REQ-TGV2-010 */
    @Test
    void startupRecoveryClosesFinalizationCrashWindowsWithoutMutatingHistoricalTasks() {
        String unpublishedV2 = "v2-unpublished-finalization";
        String terminalV2 = "v2-terminal-with-slot";
        String historicalV1 = "v1-read-only-with-slot";
        repository.createTask(unpublishedV2, GenerationWorkflowV2RoutingTest.request());
        repository.createTask(terminalV2, GenerationWorkflowV2RoutingTest.request());
        repository.createTask(historicalV1, request("historical-slot"));
        jdbcTemplate.update("""
                UPDATE generation_task
                SET status='VALIDATING', structured_processing_status='COMPLETED',
                    structured_coverage_status='COMPLETE', artifact_id=NULL, artifact_sha256=NULL, artifact_path=NULL
                WHERE id=?
                """, unpublishedV2);
        jdbcTemplate.update("""
                UPDATE generation_task
                SET status='COMPLETED', structured_processing_status='COMPLETED',
                    structured_coverage_status='COMPLETE', artifact_id='artifact',
                    artifact_sha256=?, artifact_path='artifact.xlsx'
                WHERE id=?
                """, "a".repeat(64), terminalV2);
        jdbcTemplate.update("UPDATE generation_task SET status='FAILED' WHERE id=?", historicalV1);
        jdbcTemplate.update("UPDATE task_execution_slot SET task_id=? WHERE slot_number=1", unpublishedV2);
        jdbcTemplate.update("UPDATE task_execution_slot SET task_id=? WHERE slot_number=2", terminalV2);
        jdbcTemplate.update("UPDATE task_execution_slot SET task_id=? WHERE slot_number=3", historicalV1);
        var historicalBefore = jdbcTemplate.queryForMap("SELECT * FROM generation_task WHERE id=?", historicalV1);

        queue.recoverAtStartup();

        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, structured_processing_status, structured_coverage_status, artifact_id
                FROM generation_task WHERE id=?
                """, unpublishedV2)).containsEntry("status", "QUEUED")
                .containsEntry("structured_processing_status", "PENDING")
                .containsEntry("structured_coverage_status", "PENDING")
                .containsEntry("artifact_id", null);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM generation_task WHERE id=?", historicalV1))
                .isEqualTo(historicalBefore);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_execution_slot WHERE task_id IN (?,?,?)
                """, Integer.class, unpublishedV2, terminalV2, historicalV1)).isZero();
        assertThat(repository.taskStatus(terminalV2)).isEqualTo(GenerationTaskStatus.COMPLETED);
    }

    @Test
    void everyHistoricalV1ShapeRemainsReadOnlyWhenTheQueueClaimsV2Work() {
        String historicalEmptyAll = "historical-empty-all";
        CreateGenerationTaskRequest emptyAll = new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "legacy",
                List.of(), java.util.Map.of(), FewShotPolicy.NONE, "1.0", "1.0", "queue-agent",
                new RequirementScope("requirement-kb", "system-1", "version-1", "admission_material", null,
                        List.of(new RequirementDocumentCoordinate("function-doc", "function_list"))),
                new ExampleScope("example-kb", List.of("example-1")), List.of("function_list"), "历史任务");
        repository.createTask(historicalEmptyAll, emptyAll);
        String historicalFeature = "historical-feature";
        repository.createTask(historicalFeature, request("legacy-feature"));
        String historicalPopulatedAll = "historical-populated-all";
        repository.createTask(historicalPopulatedAll, new CreateGenerationTaskRequest(
                GenerationTaskMode.ALL, "legacy-all", List.of("legacy-feature"),
                java.util.Map.of("legacy-feature", "旧功能"), FewShotPolicy.NONE, "1.0", "1.0", "queue-agent",
                emptyAll.requirementScope(), emptyAll.exampleScope(), List.of("function_list"), "历史任务"));
        String v2Task = "v2-runnable";
        repository.createTask(v2Task, GenerationWorkflowV2RoutingTest.request());

        queue.recoverAtStartup();
        Optional<TaskExecutionClaim> claim = queue.claimNext();

        assertThat(claim).hasValueSatisfying(value -> assertThat(value.taskId()).isEqualTo(v2Task));
        assertThat(List.of(historicalEmptyAll, historicalFeature, historicalPopulatedAll))
                .allSatisfy(taskId -> {
                    assertThat(repository.taskStatus(taskId)).isEqualTo(GenerationTaskStatus.QUEUED);
                    assertThat(jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM task_execution_slot WHERE task_id = ?", Integer.class, taskId))
                            .isZero();
                });
    }

    private void insertStructuredRunningWork(String taskId, String workId, String attemptId, String identity) {
        jdbcTemplate.update("""
                INSERT INTO structured_generation_work_item
                    (id, task_id, identity_key, skill_name, operation_name, status, allowed_evidence_keys_json,
                     lease_owner, lease_expires_at)
                VALUES (?, ?, ?, 'requirement-material-quality-review', 'REQUIREMENT_MATERIAL_REVIEW',
                    'RUNNING', JSON_ARRAY(), 'crashed-worker', DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 5 MINUTE))
                """, workId, taskId, identity);
        jdbcTemplate.update("""
                INSERT INTO structured_generation_attempt (id, work_item_id, attempt_number, status)
                VALUES (?, ?, 1, 'RUNNING')
                """, attemptId, workId);
    }

    private Callable<Optional<TaskExecutionClaim>> claimAfter(CyclicBarrier start) {
        return () -> {
            start.await();
            return queue.claimNext();
        };
    }

    private Optional<TaskExecutionClaim> resultOf(Future<Optional<TaskExecutionClaim>> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError("Concurrent task claim failed", exception);
        }
    }

    private static CreateGenerationTaskRequest request(String featureId) {
        return new CreateGenerationTaskRequest(
                GenerationTaskMode.FEATURE, featureId, FewShotPolicy.NONE, "1.0", "1.0", "queue-agent",
                new RequirementScope("requirement-kb", "system-1", "version-1", "admission_material", "project-1",
                        List.of(new RequirementDocumentCoordinate("document-" + featureId))),
                new ExampleScope("example-kb", List.of("example-1")), "requirements_spec", "queue test");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class QueueDependencies {

        @Bean
        GenerationTaskRepository generationTaskRepository(
                JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
            return new GenerationTaskRepository(jdbcTemplate, objectMapper, transactionManager);
        }

        @Bean
        TaskExecutionQueue taskExecutionQueue(
                JdbcTemplate jdbcTemplate,
                PlatformTransactionManager transactionManager) {
            return new TaskExecutionQueue(jdbcTemplate, transactionManager);
        }
    }
}
