package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.featureaudit.FeatureReviewConclusionType;
import com.testcaseagent.featureaudit.FrozenFeatureSource;
import com.testcaseagent.featureaudit.FrozenFeatureTarget;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import com.testcaseagent.web.TestCaseAgentApplication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Proves a durable ALL plan can only reproduce the retained eligible frozen feature sequence.
 *
 * [Req-ID]: REQ-CAG-001, REQ-BFA-005
 */
@Testcontainers
@SpringBootTest(classes = TestCaseAgentApplication.class, properties = "app.knowledge-agent.enabled=false")
@Import(FrozenBatchPlanningIntegrationTest.RepositoryDependencies.class)
class FrozenBatchPlanningIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("frozen_batch_plan_test")
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
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM generation_attempt");
        jdbcTemplate.update("DELETE FROM generation_batch");
        jdbcTemplate.update("DELETE FROM frozen_feature_target");
        jdbcTemplate.update("DELETE FROM generation_task");
    }

    @Test
    void createsAndReplaysOnlyTheRetainedEligibleFrozenFeaturesInStableOrder() {
        String taskId = createAuditingAllTask();
        List<FrozenFeatureTarget> retained = List.of(target("ff-first", 1, "订单查询", true),
                target("ff-skip", 2, "证据不足功能", false), target("ff-last", 3, "订单导出", true));
        persistFrozenTargets(taskId, retained);
        CreateGenerationTaskRequest frozenRequest = pendingRequest().withFrozenFeatures(retained);

        repository.planFrozenBatches(taskId, frozenRequest, retained, batches("first-attempt"));
        repository.planFrozenBatches(taskId, frozenRequest, retained, batches("replay-attempt"));

        assertThat(jdbcTemplate.query("SELECT feature_id FROM generation_batch WHERE task_id = ? ORDER BY batch_sequence",
                (resultSet, row) -> resultSet.getString(1), taskId)).containsExactly("ff-first", "ff-last");
        CreateGenerationTaskRequest stored = repository.request(taskId);
        assertThat(stored.featureIds()).containsExactly("ff-first", "ff-last");
        assertThat(stored.featurePaths()).containsExactly(Map.entry("ff-first", "订单查询"), Map.entry("ff-last", "订单导出"));
    }

    @Test
    void rejectsAPlanThatRenamesOrAddsToTheTaskOwnedFrozenTargets() {
        String taskId = createAuditingAllTask();
        List<FrozenFeatureTarget> retained = List.of(target("ff-first", 1, "订单查询", true));
        persistFrozenTargets(taskId, retained);
        CreateGenerationTaskRequest renamed = new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "ff-first", List.of("ff-first"),
                Map.of("ff-first", "订单查询-改名"), FewShotPolicy.NONE, "markdown-1.0", "1.0", "agent", scope(),
                new ExampleScope("example-kb", List.of("example-1")), List.of("function_list"), "生成全部功能");

        assertThatThrownBy(() -> repository.planFrozenBatches(taskId, renamed, retained, batches("bad")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("frozen");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generation_batch WHERE task_id = ?", Integer.class, taskId))
                .isZero();
    }

    private String createAuditingAllTask() {
        String taskId = UUID.randomUUID().toString();
        repository.createTask(taskId, pendingRequest());
        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);
        return taskId;
    }

    private void persistFrozenTargets(String taskId, List<FrozenFeatureTarget> targets) {
        for (FrozenFeatureTarget target : targets) {
            jdbcTemplate.update("""
                            INSERT INTO frozen_feature_target (
                                id, task_id, stable_feature_id, stable_sequence, feature_name, generation_eligible, source_summary)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """, UUID.randomUUID().toString(), taskId, target.stableFeatureId(), target.stableSequence(),
                    target.featureName(), target.generationEligible(),
                    "{\"conclusionId\":\"conclusion\",\"conclusionType\":\"MATCHED\",\"candidateIds\":[\"candidate\"],\"decisionReason\":\"fixture\"}");
        }
    }

    private static List<GenerationTaskRepository.PlannedBatch> batches(String suffix) {
        return List.of(new GenerationTaskRepository.PlannedBatch("batch-first-" + suffix, "attempt-first-" + suffix, "ff-first"),
                new GenerationTaskRepository.PlannedBatch("batch-last-" + suffix, "attempt-last-" + suffix, "ff-last"));
    }

    private static FrozenFeatureTarget target(String id, int sequence, String name, boolean eligible) {
        return new FrozenFeatureTarget(id, sequence, name, eligible,
                new FrozenFeatureSource("conclusion", FeatureReviewConclusionType.MATCHED, List.of("candidate"), "fixture"));
    }

    private static CreateGenerationTaskRequest pendingRequest() {
        return new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "all-pending", List.of(), Map.of(), FewShotPolicy.NONE,
                "markdown-1.0", "1.0", "agent", scope(), new ExampleScope("example-kb", List.of("example-1")),
                List.of("function_list"), "生成全部功能");
    }

    private static RequirementScope scope() {
        return new RequirementScope("requirement-kb", "system", "version", "admission_material", null,
                List.of(new RequirementDocumentCoordinate("function-document", "function_list")));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RepositoryDependencies {

        @Bean
        GenerationTaskRepository generationTaskRepository(
                JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
            return new GenerationTaskRepository(jdbcTemplate, objectMapper, transactionManager);
        }
    }
}
