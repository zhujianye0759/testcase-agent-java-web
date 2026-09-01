package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import com.testcaseagent.web.TestCaseAgentApplication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.transaction.PlatformTransactionManager;

/** Real-MySQL proof that reviewed test points are part of the same immutable task scope. [Req-ID]: REQ-TGV2-016 */
@Testcontainers
@SpringBootTest(classes = TestCaseAgentApplication.class, properties = "app.knowledge-agent.enabled=false")
@Import(ApprovedTestPointPersistenceIntegrationTest.RepositoryDependencies.class)
class ApprovedTestPointPersistenceIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("approved_test_point_test")
            .withUsername("testcase_agent")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired GenerationTaskRepository repository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void persistsAndRestoresReviewedPointsInCallerOrderWithTheRequestSnapshot() {
        String taskId = UUID.randomUUID().toString();
        List<ApprovedFunctionScope.ApprovedTestPoint> points = List.of(
                point("point-second", "function-b", "缺少初始化状态"),
                point("point-first", "function-a", "缺少量化阈值"));
        CreateGenerationTaskRequest request = request(new ApprovedFunctionScope("scope-17",
                List.of(function("function-a"), function("function-b")), points));

        repository.createTask(taskId, request);

        assertThat(repository.approvedScopeVersion(taskId)).isEqualTo("scope-17");
        assertThat(repository.approvedTestPoints(taskId)).containsExactlyElementsOf(points);
        assertThat(repository.request(taskId).approvedFunctionScope().testPoints()).containsExactlyElementsOf(points);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM v2_approved_test_point WHERE task_id=?", Integer.class, taskId)).isEqualTo(2);
    }

    @Test
    void historicalTaskWithoutPointRowsReadsAnEmptyReviewedPointSet() {
        String taskId = UUID.randomUUID().toString();
        repository.createTask(taskId, request(new ApprovedFunctionScope("scope-old", List.of(function("function-a")))));

        assertThat(repository.approvedTestPoints(taskId)).isEmpty();
        assertThat(repository.request(taskId).approvedFunctionScope().testPoints()).isEmpty();
    }

    @Test
    void rejectsPersistedScopeVersionOrSequenceDriftBeforeTheCoordinatorCanCallKee() {
        String taskId = UUID.randomUUID().toString();
        CreateGenerationTaskRequest request = request(new ApprovedFunctionScope("scope-closed",
                List.of(function("function-a"), function("function-b")), List.of(
                        point("point-a", "function-a", "缺少阈值"),
                        point("point-b", "function-b", "缺少初始状态"))));
        repository.createTask(taskId, request);

        jdbcTemplate.update("""
                UPDATE v2_approved_test_point SET scope_version='scope-tampered'
                WHERE task_id=? AND test_point_key='point-a'
                """, taskId);
        assertThatThrownBy(() -> repository.approvedTestPoints(taskId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot coordinates");

        jdbcTemplate.update("""
                UPDATE v2_approved_test_point SET scope_version='scope-closed', stable_sequence=3
                WHERE task_id=? AND test_point_key='point-a'
                """, taskId);
        assertThatThrownBy(() -> repository.approvedTestPoints(taskId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot coordinates");
    }

    @Test
    void rollsBackTaskFunctionsAndPointsWhenTheSecondPointCannotBePersisted() {
        String taskId = UUID.randomUUID().toString();
        CreateGenerationTaskRequest request = request(new ApprovedFunctionScope("scope-rollback",
                List.of(function("function-a"), function("function-b")), List.of(
                        point("point-a", "function-a", "缺少阈值"),
                        point("point-b", "function-b", "缺少初始状态"))));
        jdbcTemplate.execute("""
                ALTER TABLE v2_approved_test_point
                ADD CONSTRAINT chk_test_reject_second
                CHECK (task_id <> '%s' OR stable_sequence <> 2)
                """.formatted(taskId));
        try {
            assertThatThrownBy(() -> repository.createTask(taskId, request))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE v2_approved_test_point DROP CHECK chk_test_reject_second
                    """);
        }

        assertThat(count("generation_task", taskId)).isZero();
        assertThat(count("v2_approved_function", taskId)).isZero();
        assertThat(count("v2_approved_test_point", taskId)).isZero();
    }

    @Test
    void concurrentCreationOfTheSameFrozenTaskHasOneWinnerAndNoDuplicateScopeRows() throws Exception {
        String taskId = UUID.randomUUID().toString();
        CreateGenerationTaskRequest request = request(new ApprovedFunctionScope("scope-concurrent",
                List.of(function("function-a"), function("function-b")), List.of(
                        point("point-a", "function-a", "缺少阈值"),
                        point("point-b", "function-b", "缺少初始状态"))));
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> create = () -> {
                start.await();
                try {
                    repository.createTask(taskId, request);
                    return true;
                } catch (DataAccessException duplicate) {
                    return false;
                }
            };
            Future<Boolean> first = executor.submit(create);
            Future<Boolean> second = executor.submit(create);

            assertThat(List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count())
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("generation_task", taskId)).isEqualTo(1);
        assertThat(count("v2_approved_function", taskId)).isEqualTo(2);
        assertThat(count("v2_approved_test_point", taskId)).isEqualTo(2);
        assertThat(repository.approvedTestPoints(taskId)).containsExactlyElementsOf(
                request.approvedFunctionScope().testPoints());
    }

    private int count(String table, String taskId) {
        String ownerColumn = "generation_task".equals(table) ? "id" : "task_id";
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + ownerColumn + "=?",
                Integer.class, taskId);
    }

    private static ApprovedFunctionScope.ApprovedFunction function(String key) {
        return new ApprovedFunctionScope.ApprovedFunction(key, "功能-" + key, "范围/" + key, "");
    }

    private static ApprovedFunctionScope.ApprovedTestPoint point(String key, String functionKey, String missing) {
        return new ApprovedFunctionScope.ApprovedTestPoint(key, functionKey,
                ApprovedFunctionScope.ApprovedTestPointType.NORMAL_BEHAVIOR,
                ApprovedFunctionScope.ApprovedTestPointSource.GENERAL_EXPERIENCE,
                ApprovedFunctionScope.ApprovedTestPointStatus.PENDING_CONFIRMATION,
                "验证尚待确认的执行条件", List.of(missing));
    }

    private static CreateGenerationTaskRequest request(ApprovedFunctionScope scope) {
        RequirementScope requirementScope = new RequirementScope("kb", "system", "version", "admission_material",
                "project", List.of(new RequirementDocumentCoordinate("requirements", "requirements_spec")));
        List<String> keys = scope.functions().stream().map(ApprovedFunctionScope.ApprovedFunction::functionKey).toList();
        Map<String, String> paths = scope.functions().stream().collect(java.util.stream.Collectors.toMap(
                ApprovedFunctionScope.ApprovedFunction::functionKey, ApprovedFunctionScope.ApprovedFunction::path,
                (left, right) -> left, java.util.LinkedHashMap::new));
        return new CreateGenerationTaskRequest(GenerationTaskMode.ALL, keys.get(0), keys, paths, FewShotPolicy.NONE,
                "2.0", "2.0", "agent", requirementScope, new ExampleScope("example-kb", List.of("example")),
                List.of("requirements_spec"), "生成", new GenerationContractVersions("2.0", "2.0", "2.0"), scope);
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
