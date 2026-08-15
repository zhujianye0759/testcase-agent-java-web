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
            repository.createTask(taskId, request("feature-" + index));
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
        repository.createTask(taskId, request("feature-recovery"));
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
        CreateGenerationTaskRequest frozenAll = new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "ff-1", List.of("ff-1"),
                java.util.Map.of("ff-1", "订单查询"), FewShotPolicy.NONE, "1.0", "1.0", "queue-agent",
                new RequirementScope("requirement-kb", "system-1", "version-1", "admission_material", null,
                        List.of(new RequirementDocumentCoordinate("function-doc", "function_list"))),
                new ExampleScope("example-kb", List.of("example-1")), List.of("function_list"), "恢复冻结功能");
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
