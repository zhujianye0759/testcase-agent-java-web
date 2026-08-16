package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.ApachePoiWorkbookExporter;
import com.testcaseagent.featureaudit.FeatureAuditResult;
import com.testcaseagent.featureaudit.FeatureAuditService;
import com.testcaseagent.featureaudit.FrozenFeatureService;
import com.testcaseagent.featureaudit.FrozenFeatureTarget;
import com.testcaseagent.featureaudit.RequirementMaterialTraversalService;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.knowledgeagent.FeatureReconciliationInvocation;
import com.testcaseagent.knowledgeagent.KnowledgeAgentInvocation;
import com.testcaseagent.knowledgeagent.KnowledgeAgentInvocationResult;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.scope.ParsedMaterial;
import com.testcaseagent.scope.ParsedMaterialUnit;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementMaterialReaderPort;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import com.testcaseagent.web.TestCaseAgentApplication;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * End-to-end deterministic golden fixture for the ALL audit gate. It deliberately uses the durable MySQL ledger,
 * the real traversal/audit/freeze/workflow services, and a local deterministic KEE-model substitute.
 *
 * [Req-ID]: REQ-BFA-001, REQ-BFA-002, REQ-BFA-003, REQ-BFA-004, REQ-BFA-005, REQ-BFA-007, REQ-CAG-004
 */
@Testcontainers
@SpringBootTest(classes = TestCaseAgentApplication.class, properties = "app.knowledge-agent.enabled=false")
@Import(AllCompletenessGoldenFixtureTest.RepositoryDependencies.class)
class AllCompletenessGoldenFixtureTest {

    private static final int SUCCESS_N = 32;
    private static final String FUNCTION_DOCUMENT = "function-list-document";
    private static final String REQUIREMENT_DOCUMENT = "requirement-document";
    private static final String FUNCTION_UNIT = "function-list-unit";
    private static final String REQUIREMENT_UNIT = "requirement-unit";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("all_completeness_golden_test")
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

    @Autowired
    PlatformTransactionManager transactionManager;

    @TempDir
    Path artifactDirectory;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM feature_review_conclusion_candidate");
        jdbcTemplate.update("DELETE FROM feature_review_conclusion");
        jdbcTemplate.update("DELETE FROM frozen_feature_target");
        jdbcTemplate.update("DELETE FROM feature_source_candidate");
        jdbcTemplate.update("DELETE FROM material_audit_duplicate_occurrence");
        jdbcTemplate.update("DELETE FROM material_audit_scan_outcome");
        jdbcTemplate.update("DELETE FROM material_audit_attempt");
        jdbcTemplate.update("DELETE FROM material_audit_work");
        jdbcTemplate.update("DELETE FROM material_inventory_unit");
        jdbcTemplate.update("DELETE FROM material_inventory_document");
        jdbcTemplate.update("DELETE FROM generation_attempt");
        jdbcTemplate.update("DELETE FROM generation_batch");
        jdbcTemplate.update("DELETE FROM generation_task");
    }

    @Test
    void blocksFreezeWhenTheFullyDisposedGoldenLedgerStillContainsAnUnresolvedConflict() {
        String taskId = "golden-conflict";
        CreateGenerationTaskRequest request = request();
        repository.createTask(taskId, request);
        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);
        GoldenWorkflow golden = workflow(FixtureDisposition.UNRESOLVED_CONFLICT);
        golden.traversal().traverse(taskId, request, false);
        assertThat(repository.featureAuditCounts(taskId).totalWork()).isEqualTo(3);
        assertCompleteAudit(taskId, golden.audit().audit(taskId, request));

        assertThatThrownBy(() -> golden.workflow().freezeAllFeatures(taskId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unresolved conflict");

        GenerationTaskRepository.FeatureAuditCounts counts = repository.featureAuditCounts(taskId);
        assertThat(repository.hasCompleteMaterialInventory(taskId, request.requirementScope())).isTrue();
        assertThat(repository.materialInventory(taskId)).hasSize(2);
        assertThat(counts.totalWork()).isEqualTo(3);
        assertThat(counts.completedWork()).isEqualTo(3);
        assertThat(counts.permanentlyFailedWork()).isZero();
        assertThat(counts.candidateCount()).isEqualTo(32);
        assertThat(counts.coveredCandidateCount()).isEqualTo(32);
        assertThat(counts.conclusionCount()).isEqualTo(31);
        assertThat(repository.frozenFeatureTargets(taskId)).isEmpty();
        assertThat(repository.taskStatus(taskId)).isEqualTo(GenerationTaskStatus.AUDITING);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generation_batch WHERE task_id = ?", Integer.class, taskId))
                .isZero();
    }

    @Test
    void completesTheMappedGoldenFixtureWithStableNAndExactlyTwoNRows() {
        SuccessfulRun first = runCompletableFixture("golden-complete-first");
        SuccessfulRun replay = runCompletableFixture("golden-complete-replay");

        assertThat(first.targets()).hasSize(SUCCESS_N);
        assertThat(first.targets()).allMatch(FrozenFeatureTarget::generationEligible);
        assertThat(first.targets()).extracting(FrozenFeatureTarget::stableFeatureId).isSorted();
        assertThat(first.targets()).extracting(FrozenFeatureTarget::stableSequence)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, SUCCESS_N).boxed().toList());
        assertThat(first.targets()).extracting(target -> List.of(target.stableFeatureId(), target.stableSequence(), target.featureName()))
                .containsExactlyElementsOf(replay.targets().stream()
                        .map(target -> List.of(target.stableFeatureId(), target.stableSequence(), target.featureName())).toList());
        assertThat(first.acceptedTestCaseRows()).isEqualTo(2 * SUCCESS_N);
        assertThat(replay.acceptedTestCaseRows()).isEqualTo(2 * SUCCESS_N);
    }

    private SuccessfulRun runCompletableFixture(String taskId) {
        CreateGenerationTaskRequest request = request();
        repository.createTask(taskId, request);
        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);
        GoldenWorkflow golden = workflow(FixtureDisposition.MAPPED);
        golden.traversal().traverse(taskId, request, false);
        assertThat(repository.featureAuditCounts(taskId).totalWork()).isEqualTo(3);
        assertCompleteAudit(taskId, golden.audit().audit(taskId, request));

        CreateGenerationTaskRequest frozen = golden.workflow().freezeAllFeatures(taskId, request);
        List<FrozenFeatureTarget> targets = repository.frozenFeatureTargets(taskId);
        golden.workflow().executeClaimed(new TaskExecutionClaim(taskId, 1));

        GenerationTaskRepository.FeatureAuditCounts counts = repository.featureAuditCounts(taskId);
        int acceptedRows = repository.acceptedMarkdownRows(taskId).testCaseRows().size();
        assertThat(frozen.featureIds()).hasSize(SUCCESS_N);
        assertThat(repository.hasCompleteMaterialInventory(taskId, request.requirementScope())).isTrue();
        assertThat(repository.materialInventory(taskId)).hasSize(2);
        assertThat(counts.totalWork()).isEqualTo(3);
        assertThat(counts.completedWork()).isEqualTo(3);
        assertThat(counts.permanentlyFailedWork()).isZero();
        assertThat(counts.candidateCount()).isEqualTo(32);
        assertThat(counts.coveredCandidateCount()).isEqualTo(32);
        assertThat(counts.conclusionCount()).isEqualTo(31);
        List<String> batchFailures = jdbcTemplate.query("""
                        SELECT failure_reason FROM generation_attempt a
                        JOIN generation_batch b ON b.id = a.batch_id
                        WHERE b.task_id = ? AND a.status = 'FAILED'
                        ORDER BY b.batch_sequence, a.attempt_number
                        """, (resultSet, ignored) -> resultSet.getString(1), taskId);
        assertThat(repository.taskStatus(taskId)).withFailMessage("batch failures: %s", batchFailures)
                .isEqualTo(GenerationTaskStatus.COMPLETED);
        assertThat(acceptedRows).isEqualTo(2 * SUCCESS_N);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generation_batch WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(SUCCESS_N);
        return new SuccessfulRun(targets, acceptedRows);
    }

    private void assertCompleteAudit(String taskId, FeatureAuditResult audit) {
        assertThat(audit.materialInventoryComplete()).isTrue();
        assertThat(audit.totalAuditWork()).isEqualTo(3);
        List<String> failures = jdbcTemplate.query("""
                        SELECT failure_summary FROM material_audit_attempt a
                        JOIN material_audit_work w ON w.id = a.work_id
                        WHERE w.task_id = ? AND a.status = 'FAILED'
                        ORDER BY w.id, a.attempt_number
                        """, (resultSet, ignored) -> resultSet.getString(1), taskId);
        assertThat(audit.completedAuditWork()).withFailMessage("audit failures: %s", failures).isEqualTo(3);
        assertThat(audit.permanentlyFailedAuditWork()).isZero();
        assertThat(audit.candidateCount()).isEqualTo(32);
        assertThat(audit.conclusionCount()).isEqualTo(31);
        assertThat(audit.complete()).isTrue();
    }

    private GoldenWorkflow workflow(FixtureDisposition disposition) {
        DeterministicGoldenAgent agent = new DeterministicGoldenAgent(disposition);
        RequirementMaterialReaderPort reader = new GoldenMaterialReader();
        RequirementMaterialTraversalService traversal = new RequirementMaterialTraversalService(reader, repository);
        FeatureAuditService audit = new FeatureAuditService(repository, agent);
        GenerationWorkflow workflow = new GenerationWorkflow(repository, agent, new ApachePoiWorkbookExporter(artifactDirectory), new ObjectMapper(),
                new TaskExecutionQueue(jdbcTemplate, transactionManager), Runnable::run,
                traversal, audit, new FrozenFeatureService(repository));
        return new GoldenWorkflow(workflow, traversal, audit);
    }

    private static CreateGenerationTaskRequest request() {
        return new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "all-golden", List.of(), Map.of(), FewShotPolicy.NONE,
                "markdown-1.0", "1.0", "golden-agent", scope(), new ExampleScope("example-kb", List.of("example-1")),
                List.of("function_list", "work_order_plan"), "生成全部功能 golden fixture");
    }

    private static RequirementScope scope() {
        return new RequirementScope("requirement-kb", "system", "version", "admission_material", "project",
                List.of(new RequirementDocumentCoordinate(FUNCTION_DOCUMENT, "function_list"),
                        new RequirementDocumentCoordinate(REQUIREMENT_DOCUMENT, "work_order_plan")));
    }

    private enum FixtureDisposition {
        UNRESOLVED_CONFLICT,
        MAPPED
    }

    private record SuccessfulRun(List<FrozenFeatureTarget> targets, int acceptedTestCaseRows) {
        private SuccessfulRun {
            targets = List.copyOf(targets);
        }
    }

    private record GoldenWorkflow(
            GenerationWorkflow workflow, RequirementMaterialTraversalService traversal, FeatureAuditService audit) { }

    private static final class GoldenMaterialReader implements RequirementMaterialReaderPort {
        @Override
        public ParsedMaterial readAll(RequirementScope scope, String knowledgeId, int requestedLimit) {
            if (FUNCTION_DOCUMENT.equals(knowledgeId)) {
                return new ParsedMaterial(FUNCTION_DOCUMENT, 1,
                        List.of(new ParsedMaterialUnit(FUNCTION_UNIT, 0, 1, "30 个功能清单条目", 0, 100)));
            }
            if (REQUIREMENT_DOCUMENT.equals(knowledgeId)) {
                return new ParsedMaterial(REQUIREMENT_DOCUMENT, 1,
                        List.of(new ParsedMaterialUnit(REQUIREMENT_UNIT, 0, 1, "两个需求候选功能", 0, 100)));
            }
            throw new IllegalArgumentException("Unexpected golden fixture material: " + knowledgeId);
        }
    }

    private static final class DeterministicGoldenAgent implements KnowledgeAgentPort {
        private static final Pattern FEATURE_PATH = Pattern.compile("仅生成当前功能路径：(.*?)。不得生成其他功能。");
        private final FixtureDisposition disposition;

        private DeterministicGoldenAgent(FixtureDisposition disposition) {
            this.disposition = disposition;
        }

        @Override
        public KnowledgeAgentInvocationResult reconcileFeatures(FeatureReconciliationInvocation invocation) {
            String prompt = invocation.prompt();
            if (prompt.contains("候选项：")) return result(reconciliation(prompt));
            if (prompt.contains("documentId=" + FUNCTION_DOCUMENT + "; unitId=" + FUNCTION_UNIT)) {
                return result(scan(functionRows(FUNCTION_DOCUMENT, FUNCTION_UNIT)));
            }
            if (prompt.contains("第一遍已接受对象/功能点：")) return result(scan(""));
            if (prompt.contains("documentId=" + REQUIREMENT_DOCUMENT + "; unitId=" + REQUIREMENT_UNIT)) {
                return result(scan(requirementRows()));
            }
            throw new IllegalArgumentException("Unexpected golden reconciliation prompt");
        }

        @Override
        public KnowledgeAgentInvocationResult invoke(KnowledgeAgentInvocation invocation) {
            Matcher matcher = FEATURE_PATH.matcher(invocation.prompt());
            if (!matcher.find()) throw new IllegalArgumentException("Golden batch did not identify a frozen feature path");
            String featurePath = matcher.group(1);
            String candidateIds = tokenAfter(invocation.prompt(), "candidateIds=");
            String leaf = featurePath.substring(featurePath.lastIndexOf('/') + 1).strip();
            return result("""
                    ## 需求与功能清单审查发现
                    | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                    | --- | --- | --- | --- |

                    ## 测试用例
                    | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                    | --- | --- | --- | --- | --- | --- |
                    | %s_正向 | %s | 已登录 | 1. 执行功能<br>2. 确认结果 | 1. 功能执行成功<br>2. 结果可见 | candidateIds=%s |
                    | %s_反向 | %s | 已登录 | 1. 输入异常条件<br>2. 提交 | 1. 拒绝异常条件<br>2. 保持原状态 | candidateIds=%s |
                    """.formatted(leaf, featurePath, candidateIds, leaf, featurePath, candidateIds));
        }

        private String reconciliation(String prompt) {
            List<Candidate> candidates = candidates(prompt);
            Map<String, List<Candidate>> byFeature = new LinkedHashMap<>();
            for (Candidate candidate : candidates) {
                byFeature.computeIfAbsent(candidate.feature(), ignored -> new ArrayList<>()).add(candidate);
            }
            StringBuilder rows = new StringBuilder();
            int sequence = 1;
            for (Candidate candidate : targetCandidates(prompt, candidates)) {
                Candidate anchor = byFeature.get(candidate.feature()).get(0);
                String feature;
                String category;
                if ("重复功能".equals(candidate.feature())) {
                    feature = "重复功能";
                    category = "重复";
                } else if ("组合功能".equals(candidate.feature())) {
                    feature = "组合功能正向<br>组合功能逆向";
                    category = "拆分";
                } else if ("证据不足功能".equals(candidate.feature()) && disposition == FixtureDisposition.UNRESOLVED_CONFLICT) {
                    feature = candidate.feature();
                    category = "证据不足";
                } else if ("冲突功能".equals(candidate.feature()) && disposition == FixtureDisposition.UNRESOLVED_CONFLICT) {
                    feature = candidate.feature();
                    category = "冲突";
                } else if ("功能01".equals(candidate.feature())) {
                    feature = candidate.feature();
                    category = "需求未覆盖该功能点";
                } else if (candidate.feature().startsWith("需求遗漏")) {
                    feature = candidate.feature();
                    category = "功能清单遗漏";
                } else {
                    feature = candidate.feature();
                    category = "匹配";
                }
                rows.append("| ").append(sequence++).append(" | ").append(feature).append(" | ").append(category)
                        .append(" | ").append(evidence(candidate, anchor)).append(" |\n");
            }
            return scan(rows.toString());
        }

        private static List<Candidate> candidates(String prompt) {
            List<Candidate> result = new ArrayList<>();
            for (String line : prompt.lines().toList()) {
                if (!line.startsWith("candidateId=") || !line.contains("; kind=")) continue;
                Map<String, String> tokens = new LinkedHashMap<>();
                for (String token : line.split("; ")) {
                    int separator = token.indexOf('=');
                    if (separator > 0) tokens.put(token.substring(0, separator), token.substring(separator + 1));
                }
                result.add(new Candidate(tokens.get("candidateId"), tokens.get("documentId"), tokens.get("unitId"),
                        tokens.get("featureText")));
            }
            if (result.size() != 32) throw new IllegalArgumentException("Golden reconciliation must receive 32 candidates");
            return result;
        }

        private static List<Candidate> targetCandidates(String prompt, List<Candidate> candidates) {
            int start = prompt.indexOf("本页目标候选：");
            if (start < 0) throw new IllegalArgumentException("Golden reconciliation must identify target candidates");
            List<String> targetIds = prompt.substring(start).lines().filter(line -> line.startsWith("candidateId="))
                    .map(line -> line.substring("candidateId=".length()).strip()).toList();
            List<Candidate> targets = candidates.stream().filter(candidate -> targetIds.contains(candidate.id())).toList();
            if (targets.size() != targetIds.size()) {
                throw new IllegalArgumentException("Golden reconciliation must bind every target candidate");
            }
            return targets;
        }

        private static String functionRows(String documentId, String unitId) {
            StringBuilder rows = new StringBuilder();
            for (int sequence = 1; sequence <= 30; sequence++) {
                String feature = switch (sequence) {
                    case 2, 3 -> "重复功能";
                    case 4 -> "组合功能";
                    case 5 -> "证据不足功能";
                    case 6 -> "冲突功能";
                    default -> "功能%02d".formatted(sequence);
                };
                rows.append("| ").append(sequence).append(" | ").append(feature).append(" | 功能项 | documentId=")
                        .append(documentId).append("; unitId=").append(unitId).append(" |\n");
            }
            return rows.toString();
        }

        private static String requirementRows() {
            return "| 1 | 需求遗漏一 | 功能项 | documentId=" + REQUIREMENT_DOCUMENT + "; unitId=" + REQUIREMENT_UNIT + " |\n"
                    + "| 2 | 需求遗漏二 | 功能项 | documentId=" + REQUIREMENT_DOCUMENT + "; unitId=" + REQUIREMENT_UNIT + " |\n";
        }

        private static String scan(String rows) {
            return """
                    ## 需求与功能清单审查发现
                    | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                    | --- | --- | --- | --- |
                    %s
                    ## 测试用例
                    | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                    | --- | --- | --- | --- | --- | --- |
                    """.formatted(rows);
        }

        private static String evidence(Candidate candidate, Candidate anchor) {
            return "candidateIds=" + candidate.id() + "; groupAnchorId=" + anchor.id() + "; documentId="
                    + candidate.documentId() + "; unitId=" + candidate.unitId();
        }

        private static String tokenAfter(String value, String token) {
            int start = value.indexOf(token);
            if (start < 0) throw new IllegalArgumentException("Golden generation prompt did not retain candidate IDs");
            int end = value.length();
            for (char delimiter : new char[] {'；', ';', '\n'}) {
                int found = value.indexOf(delimiter, start);
                if (found >= 0) end = Math.min(end, found);
            }
            return value.substring(start + token.length(), end).strip();
        }

        private static KnowledgeAgentInvocationResult result(String markdown) {
            return new KnowledgeAgentInvocationResult("golden-session", List.of(), markdown);
        }

        private record Candidate(String id, String documentId, String unitId, String feature) { }

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
