package com.testcaseagent.structuredgeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.validation.FeatureReconciliationValidator;
import com.testcaseagent.validation.FunctionalTestcaseResultValidator;
import com.testcaseagent.validation.FunctionCandidateExtractionValidator;
import com.testcaseagent.validation.RequirementMaterialReviewValidator;
import com.testcaseagent.knowledgeagent.FunctionCandidateExtractionResult;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Input;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result;
import com.testcaseagent.knowledgeagent.MaterialContentTypeKey;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Input;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Result;
import com.testcaseagent.knowledgeagent.StructuredSourceQuoteV2;
import com.testcaseagent.validation.RequirementFactV2Validator;
import com.testcaseagent.validation.FunctionalTestcaseV2Validator;
import com.testcaseagent.validation.StructuredEvidence;
import com.testcaseagent.validation.StructuredKeyType;
import com.testcaseagent.validation.StructuredValidationRegistry;
import com.testcaseagent.validation.StructuredValidationFailure;
import com.testcaseagent.validation.StructuredValidationException;
import com.testcaseagent.task.ApprovedFunctionScope;
import com.testcaseagent.task.GenerationTaskRepository;
import com.testcaseagent.task.GenerationTaskStatus;
import com.testcaseagent.web.TestCaseAgentApplication;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** MySQL acceptance tests for atomic structured result persistence. [Req-ID]: REQ-STG-001, REQ-STG-006, REQ-FTG-005 */
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
    @org.springframework.beans.factory.annotation.Autowired GenerationTaskRepository taskRepository;
    private StructuredGenerationAcceptanceStore store;

    @BeforeEach
    void reset() {
        jdbc.update("UPDATE task_execution_slot SET task_id = NULL");
        jdbc.update("DELETE FROM v2_work_publication");
        jdbc.update("DELETE FROM v2_generation_outcome");
        jdbc.update("DELETE FROM v2_testability_feedback_quote");
        jdbc.update("DELETE FROM v2_testability_feedback");
        jdbc.update("DELETE FROM v2_requirement_fact_quote");
        jdbc.update("DELETE FROM v2_requirement_fact");
        jdbc.update("DELETE FROM v2_approved_function");
        jdbc.update("DELETE FROM structured_reconciliation_source_terminal");
        jdbc.update("DELETE FROM structured_reconciliation_relation_stage_binding");
        jdbc.update("DELETE FROM structured_reconciliation_relation_stage");
        jdbc.update("DELETE FROM structured_reconciliation_page_stage");
        jdbc.update("DELETE FROM structured_reconciliation_run");
        jdbc.update("DELETE FROM structured_reference_binding"); jdbc.update("DELETE FROM structured_test_case_step");
        jdbc.update("DELETE FROM structured_test_case"); jdbc.update("DELETE FROM structured_test_point");
        jdbc.update("DELETE FROM structured_feature_reconciliation"); jdbc.update("DELETE FROM structured_review_finding");
        jdbc.update("DELETE FROM structured_requirement_fact"); jdbc.update("DELETE FROM structured_generation_attempt");
        // V17 lineage intentionally uses a restrictive self-FK. Test cleanup detaches only its own rows first;
        // production task retention and lineage integrity remain fail-closed.
        jdbc.update("UPDATE structured_generation_work_item SET parent_work_item_id = NULL WHERE parent_work_item_id IS NOT NULL");
        jdbc.update("DELETE FROM structured_generation_work_item");
        jdbc.update("DELETE FROM material_inventory_unit"); jdbc.update("DELETE FROM material_inventory_document");
        jdbc.update("DELETE FROM generation_attempt"); jdbc.update("DELETE FROM generation_batch");
        jdbc.update("DELETE FROM generation_task");
        jdbc.update("INSERT INTO generation_task (id, task_mode, status, request_snapshot) VALUES ('task-1', 'FEATURE', 'QUEUED', JSON_OBJECT())");
        jdbc.update("""
                INSERT INTO v2_approved_function
                (task_id, function_key, stable_sequence, scope_version, name_text, path_text, description_text)
                VALUES ('task-1', 'function-1', 1, 'scope-v2', '订单提交', '业务/订单提交', '')
                """);
        jdbc.update("""
                INSERT INTO material_inventory_document
                (task_id, document_id, knowledge_id, document_role, total_units, complete)
                VALUES ('task-1', 'document-1', 'knowledge-1', 'REQUIREMENT', 2, TRUE)
                """);
        jdbc.update("""
                INSERT INTO material_inventory_unit
                (task_id, document_id, unit_id, document_role, chunk_index, ordinal, content, start_at, end_at)
                VALUES
                ('task-1', 'document-1', 'evidence-1', 'REQUIREMENT', 0, 1,
                 '已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称', 0, 43),
                ('task-1', 'document-1', 'evidence-2', 'REQUIREMENT', 0, 2,
                 '订单提交后系统进入首页', 44, 56)
                """);
        store = new StructuredGenerationAcceptanceStore(jdbc, new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    /** [Req-ID]: REQ-TGV2-004, REQ-TGV2-008 */
    @Test
    void atomicallyMergesOneStableFactAcrossCompletedWindowsAndKeepsBothExactQuotes() {
        RequirementFactV2Validator validator = new RequirementFactV2Validator();
        String firstWindow = "1".repeat(64);
        var first = claimV2FactWindow(firstWindow, 1, "evidence-1");
        var firstInput = v2FactInput(firstWindow, "evidence-1", 1,
                "已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称");
        var firstResult = new RequirementFactExtractionV2Result("function-1", firstWindow, List.of(
                new RequirementFactExtractionV2Result.RequirementFact(
                        RequirementFactExtractionV2Result.FactType.OUTPUT, "系统    进入首页",
                        List.of(new StructuredSourceQuoteV2("evidence-1", "系统进入首页")))), List.of());

        store.acceptRequirementFactsV2(first, validator, firstInput, firstResult);

        String secondWindow = "2".repeat(64);
        var second = claimV2FactWindow(secondWindow, 2, "evidence-2");
        var secondInput = v2FactInput(secondWindow, "evidence-2", 2, "订单提交后系统进入首页");
        var secondResult = v2FactResult(secondWindow, "evidence-2", "系统进入首页");
        // The statement is deliberately the same semantic fact while the exact quotation belongs to another window.
        secondResult = new RequirementFactExtractionV2Result("function-1", secondWindow, List.of(
                new RequirementFactExtractionV2Result.RequirementFact(
                        RequirementFactExtractionV2Result.FactType.OUTPUT, "系统 进入首页",
                        List.of(new StructuredSourceQuoteV2("evidence-2", "系统进入首页")))), List.of());
        store.acceptRequirementFactsV2(second, validator, secondInput, secondResult);

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_requirement_fact", Integer.class)).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_requirement_fact_quote", Integer.class)).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_work_publication", Integer.class)).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_work_item WHERE status='COMPLETED'", Integer.class)).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_attempt WHERE status='COMPLETED'", Integer.class)).isEqualTo(2);
            softly.assertThat(store.acceptedRequirementFactsV2("task-1", "function-1"))
                    .singleElement().satisfies(fact -> {
                        assertThat(fact.factKey()).startsWith("fact-");
                        assertThat(fact.statement()).isEqualTo("系统 进入首页");
                        assertThat(fact.sourceQuotes()).hasSize(2);
                    });
        });
    }

    /** [Req-ID]: REQ-TGV2-004, REQ-TGV2-012 */
    @Test
    void preservesCaseInTheDeterministicReaderFacingFactWhileKeepingCaseInsensitiveIdentity() {
        jdbc.update("UPDATE material_inventory_document SET total_units=4 WHERE task_id='task-1' AND document_id='document-1'");
        jdbc.update("""
                INSERT INTO material_inventory_unit
                (task_id, document_id, unit_id, document_role, chunk_index, ordinal, content, start_at, end_at)
                VALUES
                ('task-1', 'document-1', 'evidence-3', 'REQUIREMENT', 0, 3, '状态“a”表示通过', 57, 65),
                ('task-1', 'document-1', 'evidence-4', 'REQUIREMENT', 0, 4, '状态“A”表示通过', 66, 74)
                """);
        RequirementFactV2Validator validator = new RequirementFactV2Validator();
        String lowerWindow = "5".repeat(64);
        String upperWindow = "6".repeat(64);

        store.acceptRequirementFactsV2(claimV2FactWindow(lowerWindow, 3, "evidence-3"), validator,
                v2FactInput(lowerWindow, "evidence-3", 3, "状态“a”表示通过"),
                new RequirementFactExtractionV2Result("function-1", lowerWindow, List.of(
                        new RequirementFactExtractionV2Result.RequirementFact(
                                RequirementFactExtractionV2Result.FactType.OUTPUT, "状态“a”表示通过",
                                List.of(new StructuredSourceQuoteV2("evidence-3", "状态“a”表示通过")))), List.of()));
        store.acceptRequirementFactsV2(claimV2FactWindow(upperWindow, 4, "evidence-4"), validator,
                v2FactInput(upperWindow, "evidence-4", 4, "状态“A”表示通过"),
                new RequirementFactExtractionV2Result("function-1", upperWindow, List.of(
                        new RequirementFactExtractionV2Result.RequirementFact(
                                RequirementFactExtractionV2Result.FactType.OUTPUT, "状态“A”表示通过",
                                List.of(new StructuredSourceQuoteV2("evidence-4", "状态“A”表示通过")))), List.of()));

        assertThat(jdbc.queryForList("SELECT statement_text FROM v2_requirement_fact"))
                .singleElement().extracting(row -> row.get("statement_text")).isEqualTo("状态“A”表示通过");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_requirement_fact_quote", Integer.class)).isEqualTo(2);
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void atomicallyAcceptsOneQuoteCompressionAndPublishesNothingFromAnUnsupportedBatch() {
        RequirementFactV2Validator validator = new RequirementFactV2Validator();
        String acceptedWindow = "3".repeat(64);
        var acceptedClaim = claimV2FactWindow(acceptedWindow, 1, "evidence-1");
        String acceptedSource = "该系统进入首页并显示当前用户名称";
        var acceptedInput = v2FactInput(acceptedWindow, "evidence-1", 1, acceptedSource);
        var acceptedResult = new RequirementFactExtractionV2Result("function-1", acceptedWindow, List.of(
                new RequirementFactExtractionV2Result.RequirementFact(
                        RequirementFactExtractionV2Result.FactType.OUTPUT,
                        "系统进入首页并显示当前用户名称",
                        List.of(new StructuredSourceQuoteV2("evidence-1", acceptedSource)))), List.of());

        store.acceptRequirementFactsV2(acceptedClaim, validator, acceptedInput, acceptedResult);

        String rejectedWindow = "4".repeat(64);
        var rejectedClaim = claimV2FactWindow(rejectedWindow, 2, "evidence-2");
        String rejectedSource = "订单提交后系统进入首页";
        var rejectedInput = v2FactInput(rejectedWindow, "evidence-2", 2, rejectedSource);
        var rejectedResult = new RequirementFactExtractionV2Result("function-1", rejectedWindow, List.of(
                new RequirementFactExtractionV2Result.RequirementFact(
                        RequirementFactExtractionV2Result.FactType.OUTPUT, "系统进入首页",
                        List.of(new StructuredSourceQuoteV2("evidence-2", "系统进入首页"))),
                new RequirementFactExtractionV2Result.RequirementFact(
                        RequirementFactExtractionV2Result.FactType.BUSINESS_RULE, "管理员可以提交订单",
                        List.of(new StructuredSourceQuoteV2("evidence-2", "订单提交")))), List.of());

        Throwable thrown = catchThrowable(() ->
                store.acceptRequirementFactsV2(rejectedClaim, validator, rejectedInput, rejectedResult));
        assertThat(thrown).isInstanceOfSatisfying(StructuredValidationException.class, failure -> {
            assertThat(failure.failure().code()).isEqualTo("FACT_DIRECT_EVIDENCE_UNSUPPORTED");
            store.fail(rejectedClaim, "business_validation_failed", failure.failure());
        });

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_requirement_fact", Integer.class))
                    .isEqualTo(1);
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_requirement_fact_quote", Integer.class))
                    .isEqualTo(1);
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_work_publication", Integer.class))
                    .isEqualTo(1);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM v2_requirement_fact WHERE first_work_item_id = ?
                    """, Integer.class, rejectedClaim.workItemId())).isZero();
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM v2_requirement_fact_quote quote_row
                    JOIN v2_requirement_fact fact
                      ON fact.task_id = quote_row.task_id AND fact.fact_key = quote_row.fact_key
                    WHERE fact.first_work_item_id = ?
                    """, Integer.class, rejectedClaim.workItemId())).isZero();
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM v2_work_publication WHERE work_item_id = ?
                    """, Integer.class, rejectedClaim.workItemId())).isZero();
            softly.assertThat(jdbc.queryForObject("""
                    SELECT status FROM structured_generation_work_item WHERE id = ?
                    """, String.class, rejectedClaim.workItemId())).isEqualTo("FAILED");
        });
    }

    /** [Req-ID]: REQ-TGV2-005, REQ-TGV2-007, REQ-TGV2-008 */
    @ParameterizedTest
    @CsvSource({
            "generated,FORMAL,1,0,true",
            "pending_only,PENDING_CONFIRMATION,0,1,false",
            "unable_to_generate,NONE,0,0,false"
    })
    void atomicallyPublishesEachV2OutcomeWithoutConflatingPendingAndFormalCoverage(
            String outcomeValue, String caseStatus, int formalCases, int pendingCases, boolean formalCoverage) {
        var input = v2TestcaseInput();
        String workId = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "9".repeat(64), "functional-testcase-design", "FUNCTIONAL_TESTCASE_DESIGN_V2",
                null, null, null, null, List.of(), "function-1", "point-1"));
        var claim = store.claimRegistered("task-1", workId, "v2-testcase-worker").orElseThrow();
        var outcome = FunctionalTestcaseDesignV2Result.GenerationOutcome.fromWire(outcomeValue);
        List<String> missing = outcome == FunctionalTestcaseDesignV2Result.GenerationOutcome.GENERATED
                ? List.of() : List.of("缺少正式业务依据");
        List<FunctionalTestcaseDesignV2Result.Testcase> testcases = "NONE".equals(caseStatus)
                ? List.of() : List.of(v2Testcase(
                        FunctionalTestcaseDesignV2Result.CaseStatus.valueOf(caseStatus), missing));

        store.acceptTestcasesV2(claim, new FunctionalTestcaseV2Validator(), input,
                new FunctionalTestcaseDesignV2Result("function-1", "point-1", outcome, missing, testcases));

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_generation_outcome", Integer.class))
                    .isEqualTo(1);
            softly.assertThat(jdbc.queryForObject("SELECT generation_outcome FROM v2_generation_outcome", String.class))
                    .isEqualTo(outcomeValue);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT formal_coverage_satisfied FROM v2_generation_outcome", Boolean.class))
                    .isEqualTo(formalCoverage);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_test_case WHERE case_status='FORMAL'", Integer.class))
                    .isEqualTo(formalCases);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_test_case WHERE case_status='PENDING_CONFIRMATION'", Integer.class))
                    .isEqualTo(pendingCases);
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_work_publication", Integer.class))
                    .isEqualTo(1);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id=?", String.class, workId))
                    .isEqualTo("COMPLETED");
        });
    }

    /** [Req-ID]: REQ-TGV2-011 */
    @Test
    void explicitlyRecoversAllZeroWriteAtomicityFailuresAndSupersedesOnlyMissingFactFallbacks() {
        V2AtomicityRecoveryFixture fixture = v2AtomicityRecoveryFixture();
        Map<String, Object> immutableAudit = v2AtomicityImmutableAuditSnapshot(fixture);

        var eligibility = taskRepository.structuredRetryEligibility("task-1");
        assertThat(eligibility.canRetry()).as("%s / %s", eligibility, jdbc.queryForMap("""
                SELECT status, structured_processing_status, structured_coverage_status,
                       workflow_version, input_version, artifact_version,
                       artifact_id, artifact_sha256, artifact_path, validation_error_code, validation_error_path
                FROM generation_task WHERE id='task-1'
                """)).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
            softly.assertThat(jdbc.queryForList("""
                    SELECT status FROM structured_generation_work_item
                    WHERE id IN (?, ?) ORDER BY id
                    """, String.class, fixture.factWorkIds().get(0), fixture.factWorkIds().get(1)))
                    .containsOnly("QUEUED");
            softly.assertThat(jdbc.queryForList("""
                    SELECT status FROM structured_generation_work_item
                    WHERE id IN (?, ?) ORDER BY id
                    """, String.class, fixture.fallbackWorkIds().get(0), fixture.fallbackWorkIds().get(1)))
                    .containsOnly("SUPERSEDED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id IN (?, ?)",
                    Integer.class, fixture.factWorkIds().get(0), fixture.factWorkIds().get(1))).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id IN (?, ?)",
                    Integer.class, fixture.fallbackWorkIds().get(0), fixture.fallbackWorkIds().get(1))).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_generation_outcome WHERE task_id='task-1'",
                    Integer.class)).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_work_publication WHERE task_id='task-1'",
                    Integer.class)).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM generation_task
                    WHERE id='task-1' AND artifact_id IS NULL AND artifact_sha256 IS NULL AND artifact_path IS NULL
                    """, Integer.class)).isEqualTo(1);
            softly.assertThat(store.v2AggregateState("task-1")).satisfies(aggregate -> {
                assertThat(aggregate.totalWork()).isEqualTo(2);
                assertThat(aggregate.pendingWork()).isEqualTo(2);
                assertThat(aggregate.testPointTotal()).isZero();
                assertThat(aggregate.unableOutcomeCount()).isZero();
            });
            softly.assertThat(taskRepository.structuredWorkbookRows("task-1")).satisfies(rows -> {
                assertThat(rows.reviewRowCount()).isZero();
                assertThat(rows.testCaseRowCount()).isZero();
            });
            softly.assertThat(v2AtomicityImmutableAuditSnapshot(fixture)).isEqualTo(immutableAudit);
        });

        StructuredGenerationAcceptanceStore.WorkClaim first = store.claimRegistered(
                "task-1", fixture.factWorkIds().get(0), "fact-recovery-worker").orElseThrow();
        StructuredGenerationAcceptanceStore.WorkClaim second = store.claimRegistered(
                "task-1", fixture.factWorkIds().get(1), "fact-recovery-worker-2").orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(first.attemptNumber()).isEqualTo(2);
            softly.assertThat(second.attemptNumber()).isEqualTo(2);
            softly.assertThat(first.operationName()).isEqualTo("REQUIREMENT_FACT_EXTRACTION_V2");
            softly.assertThat(store.claimRegistered(
                    "task-1", fixture.fallbackWorkIds().get(0), "fallback-must-not-run")).isEmpty();
        });

        List<ApprovedFunctionScope.ApprovedFunction> functions = List.of(
                new ApprovedFunctionScope.ApprovedFunction("function-1", "订单提交", "业务/订单提交", ""),
                new ApprovedFunctionScope.ApprovedFunction("function-2", "结果查询", "业务/结果查询", ""));
        for (int index = 0; index < functions.size(); index++) {
            ApprovedFunctionScope.ApprovedFunction function = functions.get(index);
            StructuredGenerationAcceptanceStore.WorkClaim claim = index == 0 ? first : second;
            String sourceContent = "已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称";
            String statement = "系统进入首页";
            RequirementFactExtractionV2Input input = new RequirementFactExtractionV2Input(
                    function.functionKey(), function.name(), function.path(), function.description(),
                    "document-1", MaterialContentTypeKey.REQUIREMENTS_SPEC, claim.identityKey(),
                    List.of(new RequirementFactExtractionV2Input.MaterialUnit("evidence-1", 1, sourceContent)), List.of());
            RequirementFactExtractionV2Result result = new RequirementFactExtractionV2Result(
                    function.functionKey(), claim.identityKey(), List.of(
                    new RequirementFactExtractionV2Result.RequirementFact(
                            RequirementFactExtractionV2Result.FactType.OUTPUT, statement,
                            List.of(new StructuredSourceQuoteV2("evidence-1", statement)))), List.of());
            store.acceptRequirementFactsV2(claim, new RequirementFactV2Validator(), input, result);

            V2GenerationPlanner.TestPointPlan replacement = new V2GenerationPlanner().testPoints(
                    "task-1", function,
                    store.acceptedRequirementFactsV2("task-1", function.functionKey())).get(0);
            String replacementWorkId = store.register(replacement.registration());
            assertThat(replacementWorkId).isNotEqualTo(fixture.fallbackWorkIds().get(index));
            assertThat(replacement.input().testPoint().basis())
                    .isEqualTo(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT);
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void explicitlyRecoversOnlyTheClosedZeroWriteDirectEvidenceRejectionGraph() {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        Map<String, Object> immutableAudit = v2AtomicityImmutableAuditSnapshot(fixture);

        var eligibility = taskRepository.structuredRetryEligibility("task-1");
        assertThat(eligibility.canRetry()).as(eligibility.unavailableReason()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE id IN (?, ?) AND status='QUEUED'
                    """, Integer.class, fixture.factWorkIds().get(0), fixture.factWorkIds().get(1))).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE id IN (?, ?) AND status='SUPERSEDED'
                    """, Integer.class, fixture.fallbackWorkIds().get(0), fixture.fallbackWorkIds().get(1))).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_attempt
                    WHERE work_item_id IN (?, ?) AND attempt_number=1 AND status='FAILED'
                    """, Integer.class, fixture.factWorkIds().get(0), fixture.factWorkIds().get(1))).isEqualTo(2);
            softly.assertThat(v2AtomicityImmutableAuditSnapshot(fixture)).isEqualTo(immutableAudit);
        });
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRecoveryNeverMutatesAnUnrelatedLegacyBatchBeforeItsV2ClosedWorldCheck() {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        jdbc.update("""
                INSERT INTO generation_batch (id, task_id, feature_id, batch_sequence, status)
                VALUES ('legacy-batch', 'task-1', 'legacy-feature', 1, 'FAILED')
                """);
        jdbc.update("""
                INSERT INTO generation_attempt
                (id, batch_id, attempt_number, status, failure_reason, retryable, completed_at)
                VALUES ('legacy-attempt', 'legacy-batch', 1, 'FAILED', 'safe fixture', TRUE, CURRENT_TIMESTAMP(6))
                """);

        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM generation_batch WHERE id='legacy-batch'", String.class)).isEqualTo("FAILED");
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE id IN (?, ?) AND status='QUEUED'
                    """, Integer.class, fixture.factWorkIds().get(0), fixture.factWorkIds().get(1))).isEqualTo(2);
        });
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRecoveryRejectsMixedDiagnosticsAndAnyFactRowOwnedByAFailedWindow() {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        String changedWork = fixture.factWorkIds().get(1);

        jdbc.update("""
                UPDATE structured_generation_work_item
                SET validation_error_code='FACT_ATOMICITY_INVALID'
                WHERE id=?
                """, changedWork);
        jdbc.update("""
                UPDATE structured_generation_attempt
                SET validation_error_code='FACT_ATOMICITY_INVALID'
                WHERE work_item_id=? AND attempt_number=1
                """, changedWork);
        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();

        jdbc.update("""
                UPDATE structured_generation_work_item
                SET validation_error_code='FACT_DIRECT_EVIDENCE_UNSUPPORTED'
                WHERE id=?
                """, changedWork);
        jdbc.update("""
                UPDATE structured_generation_attempt
                SET validation_error_code='FACT_DIRECT_EVIDENCE_UNSUPPORTED'
                WHERE work_item_id=? AND attempt_number=1
                """, changedWork);
        jdbc.update("""
                INSERT INTO v2_requirement_fact
                (task_id, fact_key, first_work_item_id, function_key, fact_type, statement_text)
                VALUES ('task-1', 'unexpected-fact', ?, 'function-1', 'output', '安全测试行')
                """, fixture.factWorkIds().get(0));

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRecoveryRejectsAPublicationOwnedByAFailedFactWindow() {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        jdbc.update("""
                INSERT INTO v2_work_publication
                (work_item_id, task_id, publication_type, input_sha256, result_sha256, published_at)
                VALUES (?, 'task-1', 'requirement_facts', ?, ?, CURRENT_TIMESTAMP(6))
                """, fixture.factWorkIds().get(0), "1".repeat(64), "2".repeat(64));

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRecoveryRejectsV2RowsOwnedByAFailedWindowEvenWhenTheirFunctionDrifts() {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        jdbc.update("""
                INSERT INTO v2_approved_function
                (task_id, function_key, stable_sequence, scope_version, name_text, path_text, description_text)
                VALUES ('task-1', 'function-unaffected', 3, 'scope-v2', '其他功能', '范围/其他功能', '')
                """);
        jdbc.update("""
                INSERT INTO v2_requirement_fact
                (task_id, fact_key, first_work_item_id, function_key, fact_type, statement_text)
                VALUES ('task-1', 'drifted-fact', ?, 'function-unaffected', 'output', '安全测试行')
                """, fixture.factWorkIds().get(0));

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRecoveryRejectsFallbackWithoutItsCompletedAttempt() {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        jdbc.update("DELETE FROM structured_generation_attempt WHERE work_item_id=?",
                fixture.fallbackWorkIds().get(0));

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRecoveryPreservesCompletedFunctionsAndRequeuesOnlyTheAffectedZeroWriteWindow() {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        String completedWork = fixture.factWorkIds().get(0);
        String failedWork = fixture.factWorkIds().get(1);
        String completedWindow = "a".repeat(64);
        var completedClaim = store.claimRegistered("task-1", completedWork, "completed-function-worker").orElseThrow();
        store.acceptRequirementFactsV2(completedClaim, new RequirementFactV2Validator(),
                v2FactInput(completedWindow, "evidence-1", 1,
                        "已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称"),
                v2FactResult(completedWindow, "evidence-1", "系统进入首页"));

        var failedClaim = store.claimRegistered("task-1", failedWork, "failed-function-worker").orElseThrow();
        store.fail(failedClaim, "business_validation_failed", StructuredValidationFailure.of(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                "$.requirement_facts[0].statement"));
        store.updateTaskState("task-1", new StructuredGenerationAcceptanceStore.StructuredTaskState(
                StructuredProcessingStatus.RUNNING, StructuredCoverageStatus.PENDING));
        V2GenerationPlanner.TestPointPlan affectedFallback = new V2GenerationPlanner().missingFormalFactTestPoint(
                "task-1", new ApprovedFunctionScope.ApprovedFunction(
                        "function-2", "结果查询", "业务/结果查询", ""));
        assertThat(store.registerMissingFactFallback(affectedFallback)).isEqualTo(fixture.fallbackWorkIds().get(1));
        jdbc.update("""
                UPDATE generation_task
                SET status='PARTIAL', structured_processing_status='FAILED', structured_coverage_status='PARTIAL',
                    result_snapshot=JSON_OBJECT('terminal', true), artifact_id='artifact-v2-partial',
                    artifact_sha256=?, artifact_path='fixture-v2-partial.xlsx'
                WHERE id='task-1'
                """, "e".repeat(64));
        Map<String, Object> completedSnapshot = Map.of(
                "work", jdbc.queryForList("""
                        SELECT status, accepted_result_sha256 FROM structured_generation_work_item WHERE id=?
                        """, completedWork),
                "facts", jdbc.queryForList("""
                        SELECT fact_key, statement_text, first_work_item_id FROM v2_requirement_fact
                        WHERE task_id='task-1' AND function_key='function-1'
                        """),
                "attempts", jdbc.queryForList("""
                        SELECT id, attempt_number, status, failure_type, completed_at FROM structured_generation_attempt
                        WHERE work_item_id=? ORDER BY attempt_number
                        """, completedWork));

        var eligibility = taskRepository.structuredRetryEligibility("task-1");
        assertThat(eligibility.canRetry()).as(eligibility.unavailableReason()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id=?", String.class, failedWork))
                    .isEqualTo("QUEUED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id=?", String.class,
                    fixture.fallbackWorkIds().get(1))).isEqualTo("SUPERSEDED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id=?", String.class,
                    fixture.fallbackWorkIds().get(0))).isEqualTo("SUPERSEDED");
            softly.assertThat(Map.of(
                    "work", jdbc.queryForList("""
                            SELECT status, accepted_result_sha256 FROM structured_generation_work_item WHERE id=?
                            """, completedWork),
                    "facts", jdbc.queryForList("""
                            SELECT fact_key, statement_text, first_work_item_id FROM v2_requirement_fact
                            WHERE task_id='task-1' AND function_key='function-1'
                            """),
                    "attempts", jdbc.queryForList("""
                            SELECT id, attempt_number, status, failure_type, completed_at FROM structured_generation_attempt
                            WHERE work_item_id=? ORDER BY attempt_number
                            """, completedWork))).isEqualTo(completedSnapshot);
        });
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRecoveryPreservesAnAffectedFunctionsCompletedEmptyFactWindow() {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        String completedWindow = "c".repeat(64);
        String completedWork = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", completedWindow, "requirement-fact-extraction", "REQUIREMENT_FACT_EXTRACTION_V2",
                2, 2, "document-1", "需求材料", List.of("evidence-2"), "function-1", null,
                "document-1", List.of(), null, 0));
        var completedClaim = store.claimRegistered("task-1", completedWork, "completed-empty-worker").orElseThrow();
        store.acceptRequirementFactsV2(completedClaim, new RequirementFactV2Validator(),
                v2FactInput(completedWindow, "evidence-2", 2, "订单提交后系统进入首页"),
                new RequirementFactExtractionV2Result("function-1", completedWindow, List.of(), List.of()));
        Map<String, Object> completedSnapshot = Map.of(
                "work", jdbc.queryForList("SELECT status, accepted_result_sha256 FROM structured_generation_work_item WHERE id=?",
                        completedWork),
                "publication", jdbc.queryForList("SELECT publication_type, result_sha256 FROM v2_work_publication WHERE work_item_id=?",
                        completedWork));

        var emptyWindowEligibility = taskRepository.structuredRetryEligibility("task-1");
        assertThat(emptyWindowEligibility.canRetry()).as(emptyWindowEligibility.unavailableReason()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_work_item WHERE id IN (?, ?) AND status='QUEUED'",
                    Integer.class, fixture.factWorkIds().get(0), fixture.factWorkIds().get(1))).isEqualTo(2);
            softly.assertThat(Map.of(
                    "work", jdbc.queryForList("SELECT status, accepted_result_sha256 FROM structured_generation_work_item WHERE id=?",
                            completedWork),
                    "publication", jdbc.queryForList("SELECT publication_type, result_sha256 FROM v2_work_publication WHERE work_item_id=?",
                            completedWork))).isEqualTo(completedSnapshot);
        });
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRecoveryCrossesFactUnitAndTestcaseWorkBatchBoundaries() {
        v2FactRecoveryFixture(StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        jdbc.update("UPDATE material_inventory_document SET total_units=259 WHERE task_id='task-1' AND document_id='document-1'");

        for (int ordinal = 3; ordinal <= 259; ordinal++) {
            String unitKey = "bounded-unit-" + ordinal;
            String content = "通用边界合成单元" + ordinal;
            jdbc.update("""
                    INSERT INTO material_inventory_unit
                    (task_id, document_id, unit_id, document_role, chunk_index, ordinal, content, start_at, end_at)
                    VALUES ('task-1', 'document-1', ?, 'REQUIREMENT', ?, ?, ?, 0, ?)
                    """, unitKey, ordinal - 1, ordinal, content, content.length());
        }
        for (int windowIndex = 0; windowIndex < 9; windowIndex++) {
            int ordinalStart = 3 + windowIndex * 32;
            int ordinalEnd = Math.min(ordinalStart + 31, 259);
            List<String> evidenceKeys = new java.util.ArrayList<>();
            List<RequirementFactExtractionV2Input.MaterialUnit> materialUnits = new java.util.ArrayList<>();
            for (int ordinal = ordinalStart; ordinal <= ordinalEnd; ordinal++) {
                String unitKey = "bounded-unit-" + ordinal;
                String content = "通用边界合成单元" + ordinal;
                evidenceKeys.add(unitKey);
                materialUnits.add(new RequirementFactExtractionV2Input.MaterialUnit(unitKey, ordinal, content));
            }
            String identity = sha256Text("bounded-fact-window-" + windowIndex);
            String workId = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                    "task-1", identity, "requirement-fact-extraction", "REQUIREMENT_FACT_EXTRACTION_V2",
                    ordinalStart, ordinalEnd, "document-1", "需求材料", evidenceKeys, "function-1", null,
                    "document-1", List.of(), null, 0));
            var claim = store.claimRegistered("task-1", workId, "bounded-fact-worker").orElseThrow();
            var input = new RequirementFactExtractionV2Input(
                    "function-1", "订单提交", "业务/订单提交", "", "document-1",
                    MaterialContentTypeKey.REQUIREMENTS_SPEC, identity,
                    materialUnits, List.of());
            store.acceptRequirementFactsV2(claim, new RequirementFactV2Validator(), input,
                    new RequirementFactExtractionV2Result("function-1", identity, List.of(), List.of()));
        }
        String tenthIdentity = sha256Text("bounded-fact-window-9");
        String tenthWorkId = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", tenthIdentity, "requirement-fact-extraction", "REQUIREMENT_FACT_EXTRACTION_V2",
                1, 1, "document-1", "需求材料", List.of("evidence-1"), "function-1", null,
                "document-1", List.of(), null, 0));
        var tenthClaim = store.claimRegistered("task-1", tenthWorkId, "second-fact-batch-worker").orElseThrow();
        store.acceptRequirementFactsV2(tenthClaim, new RequirementFactV2Validator(),
                v2FactInput(tenthIdentity, "evidence-1", 1,
                        "已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称"),
                new RequirementFactExtractionV2Result("function-1", tenthIdentity, List.of(), List.of()));

        V2GenerationPlanner planner = new V2GenerationPlanner();
        for (int sequence = 3; sequence <= 65; sequence++) {
            String functionKey = "bounded-function-" + sequence;
            String functionName = "通用功能" + sequence;
            jdbc.update("""
                    INSERT INTO v2_approved_function
                    (task_id, function_key, stable_sequence, scope_version, name_text, path_text, description_text)
                    VALUES ('task-1', ?, ?, 'scope-v2', ?, ?, '')
                    """, functionKey, sequence, functionName, "通用范围/" + functionName);
            var function = new ApprovedFunctionScope.ApprovedFunction(
                    functionKey, functionName, "通用范围/" + functionName, "");
            V2GenerationPlanner.TestPointPlan fallback = planner.missingFormalFactTestPoint("task-1", function);
            String workId = store.registerMissingFactFallback(fallback);
            var claim = store.claimRegistered("task-1", workId, "bounded-case-worker").orElseThrow();
            store.acceptTestcasesV2(claim, new FunctionalTestcaseV2Validator(), fallback.input(),
                    new FunctionalTestcaseDesignV2Result(functionKey,
                            fallback.input().testPoint().testPointKey(),
                            FunctionalTestcaseDesignV2Result.GenerationOutcome.UNABLE_TO_GENERATE,
                            List.of(V2GenerationPlanner.missingFormalFactInformation()), List.of()));
        }

        var eligibility = taskRepository.structuredRetryEligibility("task-1");
        assertSoftly(softly -> {
            softly.assertThat(eligibility.canRetry()).as(eligibility.unavailableReason()).isTrue();
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE task_id='task-1' AND operation_name='REQUIREMENT_FACT_EXTRACTION_V2'
                      AND status='COMPLETED'
                    """, Integer.class)).isEqualTo(10);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE task_id='task-1' AND operation_name='FUNCTIONAL_TESTCASE_DESIGN_V2'
                      AND status='COMPLETED'
                    """, Integer.class)).isEqualTo(65);
        });
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRecoveryRejectsAnAffectedFunctionsCompletedNonemptyFactWindow() {
        v2FactRecoveryFixture(StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        String completedWindow = "d".repeat(64);
        String completedWork = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", completedWindow, "requirement-fact-extraction", "REQUIREMENT_FACT_EXTRACTION_V2",
                2, 2, "document-1", "需求材料", List.of("evidence-2"), "function-1", null,
                "document-1", List.of(), null, 0));
        var completedClaim = store.claimRegistered("task-1", completedWork, "completed-nonempty-worker").orElseThrow();
        String source = "订单提交后系统进入首页";
        store.acceptRequirementFactsV2(completedClaim, new RequirementFactV2Validator(),
                v2FactInput(completedWindow, "evidence-2", 2, source),
                new RequirementFactExtractionV2Result("function-1", completedWindow, List.of(
                        new RequirementFactExtractionV2Result.RequirementFact(
                                RequirementFactExtractionV2Result.FactType.OUTPUT, source,
                                List.of(new StructuredSourceQuoteV2("evidence-2", source)))), List.of()));

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id=?", String.class, completedWork))
                .isEqualTo("COMPLETED");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @ParameterizedTest
    @ValueSource(strings = {"changed_input_hash", "missing_context_unit", "orphan_feedback_quote",
            "different_feedback_key_same_semantics", "changed_feedback_function", "changed_feedback_window",
            "extra_fact_quote", "null_fact_replay", "unknown_fact_replay_field",
            "feedback_affected_types_object", "completed_fact_null_hash"})
    void directEvidenceRecoveryRejectsAnIncompleteRetainedFactReplay(String mutation) {
        v2FactRecoveryFixture(StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        jdbc.update("""
                INSERT INTO v2_approved_function
                (task_id, function_key, stable_sequence, scope_version, name_text, path_text, description_text)
                VALUES ('task-1', 'function-3', 3, 'scope-v2', '独立功能', '范围/独立功能', '')
                """);
        String completedWindow = "5".repeat(64);
        String completedWork = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", completedWindow, "requirement-fact-extraction", "REQUIREMENT_FACT_EXTRACTION_V2",
                1, 1, "document-1", "需求材料", List.of("evidence-1"), "function-3", null,
                "document-1", List.of("evidence-2"), null, 0));
        var completedClaim = store.claimRegistered("task-1", completedWork, "completed-feedback-worker").orElseThrow();
        var input = new RequirementFactExtractionV2Input("function-3", "独立功能", "范围/独立功能", "",
                "document-1", MaterialContentTypeKey.REQUIREMENTS_SPEC, completedWindow,
                List.of(new RequirementFactExtractionV2Input.MaterialUnit(
                        "evidence-1", 1, "已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称")),
                List.of(new RequirementFactExtractionV2Input.MaterialUnit(
                        "evidence-2", 2, "订单提交后系统进入首页")));
        var result = new RequirementFactExtractionV2Result("function-3", completedWindow, List.of(
                new RequirementFactExtractionV2Result.RequirementFact(
                        RequirementFactExtractionV2Result.FactType.OUTPUT, "系统进入首页",
                        List.of(new StructuredSourceQuoteV2("evidence-1", "系统进入首页")))), List.of(
                new RequirementFactExtractionV2Result.TestabilityObservation(
                        RequirementFactExtractionV2Result.ObservationType.UNOBSERVABLE_RESULT,
                        "结果缺少可观察标准", List.of(RequirementFactExtractionV2Result.FactType.OUTPUT),
                        List.of(new StructuredSourceQuoteV2("evidence-1", "显示当前用户名称")))));
        store.acceptRequirementFactsV2(completedClaim, new RequirementFactV2Validator(), input, result);

        var baseline = taskRepository.structuredRetryEligibility("task-1");
        assertThat(baseline.canRetry()).as(baseline.unavailableReason()).isTrue();
        switch (mutation) {
            case "changed_input_hash" -> jdbc.update(
                    "UPDATE v2_work_publication SET input_sha256=? WHERE work_item_id=?",
                    "9".repeat(64), completedWork);
            case "missing_context_unit" -> jdbc.update(
                    "DELETE FROM material_inventory_unit WHERE task_id='task-1' AND unit_id='evidence-2'");
            case "orphan_feedback_quote" -> jdbc.update(
                    "DELETE FROM v2_testability_feedback_quote WHERE task_id='task-1'");
            case "different_feedback_key_same_semantics" -> {
                jdbc.update("""
                        INSERT INTO v2_testability_feedback
                        (task_id, feedback_key, work_item_id, function_key, window_key, observation_type,
                         description_text, affected_fact_types_json)
                        SELECT task_id, 'feedback-extra', work_item_id, function_key, window_key, observation_type,
                               description_text, affected_fact_types_json
                        FROM v2_testability_feedback WHERE task_id='task-1' AND work_item_id=?
                        """, completedWork);
                jdbc.update("""
                        INSERT INTO v2_testability_feedback_quote
                        (task_id, feedback_key, evidence_key, quote_sha256, quote_text)
                        SELECT task_id, 'feedback-extra', evidence_key, quote_sha256, quote_text
                        FROM v2_testability_feedback_quote WHERE task_id='task-1' AND feedback_key<>'feedback-extra'
                        """);
            }
            case "changed_feedback_function" -> jdbc.update(
                    "UPDATE v2_testability_feedback SET function_key='function-2' WHERE task_id='task-1' AND work_item_id=?",
                    completedWork);
            case "changed_feedback_window" -> jdbc.update(
                    "UPDATE v2_testability_feedback SET window_key=? WHERE task_id='task-1' AND work_item_id=?",
                    "6".repeat(64), completedWork);
            case "extra_fact_quote" -> jdbc.update("""
                    INSERT INTO v2_requirement_fact_quote
                    (task_id, fact_key, evidence_key, quote_sha256, quote_text)
                    SELECT task_id, fact_key, 'evidence-2', ?, '订单提交后系统进入首页'
                    FROM v2_requirement_fact WHERE task_id='task-1' AND first_work_item_id=? LIMIT 1
                    """, "8".repeat(64), completedWork);
            case "null_fact_replay" -> {
                String nullHash = sha256Json(null);
                jdbc.update("""
                        UPDATE v2_work_publication
                        SET validated_result_replay_json=CAST('null' AS JSON), result_sha256=?
                        WHERE work_item_id=?
                        """, nullHash, completedWork);
                jdbc.update("UPDATE structured_generation_work_item SET accepted_result_sha256=? WHERE id=?",
                        nullHash, completedWork);
            }
            case "unknown_fact_replay_field" -> jdbc.update("""
                    UPDATE v2_work_publication
                    SET validated_result_replay_json=JSON_SET(validated_result_replay_json, '$.untrusted', TRUE)
                    WHERE work_item_id=?
                    """, completedWork);
            case "feedback_affected_types_object" -> jdbc.update("""
                    UPDATE v2_testability_feedback SET affected_fact_types_json=JSON_OBJECT()
                    WHERE task_id='task-1' AND work_item_id=?
                    """, completedWork);
            case "completed_fact_null_hash" -> jdbc.update(
                    "UPDATE structured_generation_work_item SET accepted_result_sha256=NULL WHERE id=?",
                    completedWork);
            default -> throw new IllegalArgumentException("unknown retained fact mutation");
        }

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRecoveryRejectsCompletedTestcaseWorkWithoutItsAtomicProjection() {
        v2FactRecoveryFixture(StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        String unbacked = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "d".repeat(64), "functional-testcase-design", "FUNCTIONAL_TESTCASE_DESIGN_V2",
                null, null, null, null, List.of(), "function-1", "regular-point"));
        jdbc.update("UPDATE structured_generation_work_item SET status='COMPLETED', accepted_result_sha256=? WHERE id=?",
                "e".repeat(64), unbacked);

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRecoveryKeepsHistoricalFallbacksWithoutV23ReplayCompatible() {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        jdbc.update("UPDATE v2_work_publication SET validated_result_replay_json=NULL WHERE work_item_id IN (?, ?)",
                fixture.fallbackWorkIds().get(0), fixture.fallbackWorkIds().get(1));

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRecoveryRejectsHistoricalFallbackWhoseRebuiltResultHashWasReplaced() {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        String fallbackWorkId = fixture.fallbackWorkIds().get(0);
        String forgedHash = "4".repeat(64);
        jdbc.update("""
                UPDATE v2_work_publication
                SET validated_result_replay_json=NULL, result_sha256=?
                WHERE work_item_id=?
                """, forgedHash, fallbackWorkId);
        jdbc.update("UPDATE structured_generation_work_item SET accepted_result_sha256=? WHERE id=?",
                forgedHash, fallbackWorkId);

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRecoveryRejectsCompletedFallbackWithTwoSuccessfulAttempts() {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        jdbc.update("""
                INSERT INTO structured_generation_attempt
                (id, work_item_id, attempt_number, status, completed_at)
                VALUES (UUID(), ?, 2, 'COMPLETED', CURRENT_TIMESTAMP(6))
                """, fixture.fallbackWorkIds().get(0));

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @ParameterizedTest
    @ValueSource(strings = {"missing_step", "missing_case_binding", "changed_input_hash", "changed_priority",
            "extra_old_business_row", "null_testcase_replay", "null_point_missing",
            "testcase_preconditions_object", "testcase_inputs_null", "unknown_testcase_replay_field",
            "completed_testcase_null_hash"})
    void directEvidenceRecoveryRejectsAnIncompleteRetainedTestcaseProjection(String mutation) {
        v2FactRecoveryFixture(StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        jdbc.update("""
                INSERT INTO v2_approved_function
                (task_id, function_key, stable_sequence, scope_version, name_text, path_text, description_text)
                VALUES ('task-1', 'function-3', 3, 'scope-v2', '独立功能', '范围/独立功能', '')
                """);
        var function = new ApprovedFunctionScope.ApprovedFunction(
                "function-3", "独立功能", "范围/独立功能", "");
        String factWindow = "4".repeat(64);
        String factWorkId = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", factWindow, "requirement-fact-extraction", "REQUIREMENT_FACT_EXTRACTION_V2",
                2, 2, "document-1", "需求材料", List.of("evidence-2"), "function-3", null,
                "document-1", List.of(), null, 0));
        var factClaim = store.claimRegistered("task-1", factWorkId, "retained-fact-worker").orElseThrow();
        var factInput = new RequirementFactExtractionV2Input("function-3", "独立功能", "范围/独立功能", "",
                "document-1", MaterialContentTypeKey.REQUIREMENTS_SPEC, factWindow,
                List.of(new RequirementFactExtractionV2Input.MaterialUnit(
                        "evidence-2", 2, "订单提交后系统进入首页")), List.of());
        store.acceptRequirementFactsV2(factClaim, new RequirementFactV2Validator(), factInput,
                new RequirementFactExtractionV2Result("function-3", factWindow, List.of(
                        new RequirementFactExtractionV2Result.RequirementFact(
                                RequirementFactExtractionV2Result.FactType.BUSINESS_RULE, "订单提交后系统进入首页",
                                List.of(new StructuredSourceQuoteV2(
                                        "evidence-2", "订单提交后系统进入首页")))), List.of()));
        V2GenerationPlanner.TestPointPlan plan = new V2GenerationPlanner().testPoints(
                "task-1", function, store.acceptedRequirementFactsV2("task-1", "function-3")).get(0);
        var input = plan.input();
        String workId = store.register(plan.registration());
        var claim = store.claimRegistered("task-1", workId, "retained-testcase-worker").orElseThrow();
        var step = new FunctionalTestcaseDesignV2Result.Step(1, "订单提交", "订单提交",
                "实际结果符合预期", "", "记录结果");
        var testcase = new FunctionalTestcaseDesignV2Result.Testcase("订单提交", "订单提交",
                FunctionalTestcaseDesignV2Result.Priority.MEDIUM, List.of(),
                new FunctionalTestcaseDesignV2Result.Initialization(List.of(), List.of(), List.of(), List.of()),
                List.of(), List.of(step), List.of("订单提交"), "全部步骤符合预期", "任一步失败则不通过",
                List.of(), "记录结果", List.of(input.requirementFacts().get(0).factKey()), List.of("evidence-2"),
                FunctionalTestcaseDesignV2Result.CaseStatus.FORMAL, List.of());
        var secondStep = new FunctionalTestcaseDesignV2Result.Step(1, "进入首页", "进入首页",
                "实际结果符合预期", "", "记录结果");
        var secondTestcase = new FunctionalTestcaseDesignV2Result.Testcase("进入首页", "进入首页",
                FunctionalTestcaseDesignV2Result.Priority.HIGH, List.of(),
                new FunctionalTestcaseDesignV2Result.Initialization(List.of(), List.of(), List.of(), List.of()),
                List.of(), List.of(secondStep), List.of("进入首页"), "全部步骤符合预期", "任一步失败则不通过",
                List.of(), "记录结果", List.of(input.requirementFacts().get(0).factKey()), List.of("evidence-2"),
                FunctionalTestcaseDesignV2Result.CaseStatus.FORMAL, List.of());
        var validator = new FunctionalTestcaseV2Validator();
        List<FunctionalTestcaseDesignV2Result.Testcase> candidates = new java.util.ArrayList<>(
                List.of(testcase, secondTestcase));
        var candidateResult = new FunctionalTestcaseDesignV2Result("function-3", input.testPoint().testPointKey(),
                FunctionalTestcaseDesignV2Result.GenerationOutcome.GENERATED, List.of(), candidates);
        candidates = validator.validate(input, candidateResult).testcases().stream()
                .sorted(java.util.Comparator.comparing(FunctionalTestcaseV2Validator.AcceptedTestcase::caseKey).reversed())
                .map(FunctionalTestcaseV2Validator.AcceptedTestcase::testcase)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        store.acceptTestcasesV2(claim, new FunctionalTestcaseV2Validator(), input,
                new FunctionalTestcaseDesignV2Result("function-3", input.testPoint().testPointKey(),
                        FunctionalTestcaseDesignV2Result.GenerationOutcome.GENERATED, List.of(), candidates));

        var baseline = taskRepository.structuredRetryEligibility("task-1");
        assertThat(baseline.canRetry()).as(baseline.unavailableReason()).isTrue();

        switch (mutation) {
            case "missing_step" -> jdbc.update("DELETE FROM structured_test_case_step WHERE work_item_id=?", workId);
            case "missing_case_binding" -> jdbc.update("""
                    DELETE FROM structured_reference_binding
                    WHERE work_item_id=? AND subject_type='TEST_CASE' AND reference_type='EVIDENCE'
                    """, workId);
            case "changed_input_hash" -> jdbc.update(
                    "UPDATE v2_work_publication SET input_sha256=? WHERE work_item_id=?",
                    "9".repeat(64), workId);
            case "changed_priority" -> jdbc.update(
                    "UPDATE structured_test_case SET priority='HIGH' WHERE work_item_id=?", workId);
            case "extra_old_business_row" -> jdbc.update("""
                    INSERT INTO structured_review_finding
                    (work_item_id, task_id, finding_key, issue_type, description, test_design_impact,
                     current_project_recommendation, design_center_guideline_recommendation, handling_level)
                    VALUES (?, 'task-1', 'unexpected-old-row', '缺口', '说明', '影响', '建议', '指南', 'IMPROVEMENT')
                    """, workId);
            case "null_testcase_replay" -> {
                String nullHash = sha256Json(null);
                jdbc.update("""
                        UPDATE v2_work_publication
                        SET validated_result_replay_json=CAST('null' AS JSON), result_sha256=?
                        WHERE work_item_id=?
                        """, nullHash, workId);
                jdbc.update("UPDATE structured_generation_work_item SET accepted_result_sha256=? WHERE id=?",
                        nullHash, workId);
            }
            case "null_point_missing" -> jdbc.update("""
                    UPDATE structured_test_point SET missing_information_json=CAST('null' AS JSON)
                    WHERE work_item_id=?
                    """, workId);
            case "testcase_preconditions_object" -> jdbc.update("""
                    UPDATE structured_test_case SET preconditions_json=JSON_OBJECT()
                    WHERE work_item_id=?
                    """, workId);
            case "testcase_inputs_null" -> jdbc.update("""
                    UPDATE structured_test_case SET inputs_json=CAST('null' AS JSON)
                    WHERE work_item_id=?
                    """, workId);
            case "unknown_testcase_replay_field" -> jdbc.update("""
                    UPDATE v2_work_publication
                    SET validated_result_replay_json=JSON_SET(validated_result_replay_json, '$.untrusted', TRUE)
                    WHERE work_item_id=?
                    """, workId);
            case "completed_testcase_null_hash" -> jdbc.update(
                    "UPDATE structured_generation_work_item SET accepted_result_sha256=NULL WHERE id=?",
                    workId);
            default -> throw new IllegalArgumentException("unknown retained projection mutation");
        }

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-TGV2-011 */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "\"not-an-array\"", "[1]", "[true]", "[{}]"})
    void atomicityRecoveryFailsClosedForMalformedHistoricalEvidenceKeys(String malformedJson) {
        V2AtomicityRecoveryFixture fixture = v2AtomicityRecoveryFixture();
        jdbc.update("""
                UPDATE structured_generation_work_item
                SET allowed_evidence_keys_json=CAST(? AS JSON)
                WHERE id=?
                """, malformedJson, fixture.factWorkIds().get(0));

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void atomicityRecoveryFailsClosedWhenAFailedLeafReferencesNoFrozenInventoryUnit() {
        V2AtomicityRecoveryFixture fixture = v2AtomicityRecoveryFixture();
        jdbc.update("""
                UPDATE structured_generation_work_item
                SET allowed_evidence_keys_json=JSON_ARRAY('foreign-unit')
                WHERE id=?
                """, fixture.factWorkIds().get(0));

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void concurrentDirectEvidenceRecoveryHasOneWinner() throws Exception {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Integer> retry = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return taskRepository.retryFailedBatches("task-1");
            };
            Future<Integer> first = executor.submit(retry);
            Future<Integer> second = executor.submit(retry);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE id IN (?, ?) AND status='QUEUED'
                    """, Integer.class, fixture.factWorkIds().get(0), fixture.factWorkIds().get(1))).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRecoveryRollsBackWhenItsFallbackCannotBeSuperseded() {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        Map<String, Object> before = v2AtomicityFullSnapshot(fixture);
        jdbc.execute("ALTER TABLE structured_generation_work_item "
                + "ADD CONSTRAINT chk_test_no_direct_superseded CHECK (status <> 'SUPERSEDED')");
        try {
            assertThatThrownBy(() -> taskRepository.retryFailedBatches("task-1"))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE structured_generation_work_item DROP CHECK chk_test_no_direct_superseded");
        }

        assertThat(v2AtomicityFullSnapshot(fixture)).isEqualTo(before);
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @ParameterizedTest
    @ValueSource(strings = {"accepted_hash", "affected_formal_testcase", "blank_function"})
    void directEvidenceRecoveryRejectsAffectedNearStates(String mutation) {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        switch (mutation) {
            case "accepted_hash" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET accepted_result_sha256=? WHERE id=?
                    """, "9".repeat(64), fixture.factWorkIds().get(0));
            case "affected_formal_testcase" -> {
                String extra = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                        "task-1", "7".repeat(64), "functional-testcase-design", "FUNCTIONAL_TESTCASE_DESIGN_V2",
                        null, null, null, null, List.of(), "function-1", "regular-point"));
                jdbc.update("""
                        UPDATE structured_generation_work_item
                        SET status='COMPLETED', accepted_result_sha256=? WHERE id=?
                        """, "8".repeat(64), extra);
            }
            case "blank_function" -> jdbc.update(
                    "UPDATE structured_generation_work_item SET function_key='' WHERE id=?",
                    fixture.factWorkIds().get(0));
            default -> throw new IllegalArgumentException("unknown direct-evidence mutation");
        }

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-TGV2-011 */
    @Test
    void explicitlyRecoversTheSameClosedWorldFromAFailedTaskWithoutAnArtifact() {
        V2AtomicityRecoveryFixture fixture = v2AtomicityRecoveryFixture();
        jdbc.update("""
                UPDATE generation_task
                SET status='FAILED', artifact_id=NULL, artifact_sha256=NULL, artifact_path=NULL
                WHERE id='task-1'
                """);

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE id IN (?, ?) AND status='QUEUED'
                    """, Integer.class, fixture.factWorkIds().get(0), fixture.factWorkIds().get(1))).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE id IN (?, ?) AND status='SUPERSEDED'
                    """, Integer.class, fixture.fallbackWorkIds().get(0), fixture.fallbackWorkIds().get(1))).isEqualTo(2);
        });
    }

    /** [Req-ID]: REQ-TGV2-011 */
    @Test
    void recoversValidatedUnableOutcomeWhoseMissingInformationIsMoreSpecificThanThePlannerHint() {
        V2AtomicityRecoveryFixture fixture = v2AtomicityRecoveryFixture(List.of(
                V2GenerationPlanner.missingFormalFactInformation(),
                "缺少可用于形成正式用例的初始化条件"));

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE id IN (?, ?) AND status='QUEUED'
                    """, Integer.class, fixture.factWorkIds().get(0), fixture.factWorkIds().get(1))).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE id IN (?, ?) AND status='SUPERSEDED'
                    """, Integer.class, fixture.fallbackWorkIds().get(0), fixture.fallbackWorkIds().get(1))).isEqualTo(2);
        });
    }

    /** [Req-ID]: REQ-TGV2-011 */
    @Test
    void splitFactParentsRemainAuditOnlyWhileAllFailedLeavesAreRecovered() {
        V2AtomicityRecoveryFixture fixture = v2AtomicityRecoveryFixture();
        String parentId = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "d".repeat(64), "requirement-fact-extraction", "REQUIREMENT_FACT_EXTRACTION_V2",
                1, 2, "document-1", "需求材料", List.of("evidence-1", "evidence-2"),
                "function-1", null, "document-1", List.of(), null, 0));
        var parentClaim = store.claimRegistered("task-1", parentId, "split-parent-worker").orElseThrow();
        var left = new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "e".repeat(64), "requirement-fact-extraction", "REQUIREMENT_FACT_EXTRACTION_V2",
                1, 1, "document-1", "需求材料", List.of("evidence-1"),
                "function-1", null, "document-1", List.of(), parentId, 1);
        var right = new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "f".repeat(64), "requirement-fact-extraction", "REQUIREMENT_FACT_EXTRACTION_V2",
                2, 2, "document-1", "需求材料", List.of("evidence-2"),
                "function-1", null, "document-1", List.of(), parentId, 1);
        store.splitRequirementFactWork(parentClaim, left, right);
        String leftId = store.register(left);
        String rightId = store.register(right);
        for (String childId : List.of(leftId, rightId)) {
            var child = store.claimRegistered("task-1", childId, "split-child-worker").orElseThrow();
            store.fail(child, "business_validation_failed", StructuredValidationFailure.of(
                    StructuredValidationFailure.Code.FACT_ATOMICITY_INVALID,
                    "$.requirement_facts[0].statement"));
        }

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id=?", String.class, parentId))
                    .isEqualTo("SPLIT");
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE id IN (?, ?, ?, ?) AND status='QUEUED'
                    """, Integer.class, fixture.factWorkIds().get(0), fixture.factWorkIds().get(1),
                    leftId, rightId)).isEqualTo(4);
            softly.assertThat(store.v2AggregateState("task-1")).satisfies(aggregate -> {
                assertThat(aggregate.totalWork()).isEqualTo(4);
                assertThat(aggregate.pendingWork()).isEqualTo(4);
            });
        });

        List<RecoveredFactWork> recoveredLeaves = List.of(
                new RecoveredFactWork(fixture.factWorkIds().get(0), "a".repeat(64), "function-1", "evidence-1", 1),
                new RecoveredFactWork(fixture.factWorkIds().get(1), "b".repeat(64), "function-2", "evidence-1", 1),
                new RecoveredFactWork(leftId, "e".repeat(64), "function-1", "evidence-1", 1),
                new RecoveredFactWork(rightId, "f".repeat(64), "function-1", "evidence-2", 2));
        for (RecoveredFactWork leaf : recoveredLeaves) {
            var claim = store.claimRegistered("task-1", leaf.workId(), "recovered-leaf-worker").orElseThrow();
            String content = leaf.ordinal() == 1
                    ? "已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称"
                    : "订单提交后系统进入首页";
            RequirementFactExtractionV2Input input = new RequirementFactExtractionV2Input(
                    leaf.functionKey(), "功能", "业务/功能", "", "document-1",
                    MaterialContentTypeKey.REQUIREMENTS_SPEC, leaf.identityKey(),
                    List.of(new RequirementFactExtractionV2Input.MaterialUnit(
                            leaf.evidenceKey(), leaf.ordinal(), content)), List.of());
            RequirementFactExtractionV2Result result = new RequirementFactExtractionV2Result(
                    leaf.functionKey(), leaf.identityKey(), List.of(
                    new RequirementFactExtractionV2Result.RequirementFact(
                            RequirementFactExtractionV2Result.FactType.OUTPUT, "系统进入首页",
                            List.of(new StructuredSourceQuoteV2(leaf.evidenceKey(), "系统进入首页")))), List.of());
            store.acceptRequirementFactsV2(claim, new RequirementFactV2Validator(), input, result);
        }
        assertThat(store.v2AggregateState("task-1")).satisfies(aggregate -> {
            assertThat(aggregate.totalWork()).isEqualTo(4);
            assertThat(aggregate.completedWork()).isEqualTo(4);
            assertThat(aggregate.allWorkTerminal()).isTrue();
        });
    }

    /** [Req-ID]: REQ-TGV2-011 */
    @Test
    void exactSupersededMissingFactFallbackIsReactivatedWithoutAnotherKeeAttemptWhenFactsFailAgain() {
        V2AtomicityRecoveryFixture fixture = v2AtomicityRecoveryFixture();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
        for (String workId : fixture.factWorkIds()) {
            var claim = store.claimRegistered("task-1", workId, "fact-retry-worker").orElseThrow();
            store.fail(claim, "business_validation_failed", StructuredValidationFailure.of(
                    StructuredValidationFailure.Code.FACT_ATOMICITY_INVALID,
                    "$.requirement_facts[0].statement"));
        }
        List<Map<String, Object>> before = jdbc.queryForList("""
                SELECT work.id, work.accepted_result_sha256, point.test_point_type, point.basis,
                       point.description, point.missing_information_json,
                       outcome.generation_outcome, outcome.missing_information_json AS outcome_missing,
                       publication.input_sha256, publication.result_sha256, publication.published_at
                FROM structured_generation_work_item work
                JOIN structured_test_point point ON point.work_item_id=work.id
                JOIN v2_generation_outcome outcome ON outcome.work_item_id=work.id
                JOIN v2_work_publication publication ON publication.work_item_id=work.id
                WHERE work.id IN (?, ?) ORDER BY work.id
                """, fixture.fallbackWorkIds().get(0), fixture.fallbackWorkIds().get(1));
        V2GenerationPlanner planner = new V2GenerationPlanner();
        List<ApprovedFunctionScope.ApprovedFunction> functions = List.of(
                new ApprovedFunctionScope.ApprovedFunction("function-1", "订单提交", "业务/订单提交", ""),
                new ApprovedFunctionScope.ApprovedFunction("function-2", "结果查询", "业务/结果查询", ""));
        store.updateTaskState("task-1", new StructuredGenerationAcceptanceStore.StructuredTaskState(
                StructuredProcessingStatus.RUNNING, StructuredCoverageStatus.PENDING));

        for (int index = 0; index < functions.size(); index++) {
            V2GenerationPlanner.TestPointPlan fallback =
                    planner.missingFormalFactTestPoint("task-1", functions.get(index));
            String workId = store.registerMissingFactFallback(fallback);
            assertThat(workId).isEqualTo(fixture.fallbackWorkIds().get(index));
            assertThat(store.isCompleted(workId)).isTrue();
            assertThat(store.claimRegistered("task-1", workId, "must-not-call-kee")).isEmpty();
        }
        jdbc.update("""
                UPDATE generation_task
                SET status='PARTIAL', structured_processing_status='FAILED',
                    structured_coverage_status='UNABLE_TO_GENERATE',
                    result_snapshot=JSON_OBJECT('terminal', true), artifact_id='artifact-v2-second',
                    artifact_sha256=?, artifact_path='fixture-v2-second.xlsx'
                WHERE id='task-1'
                """, "e".repeat(64));

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_attempt
                    WHERE work_item_id IN (?, ?)
                    """, Integer.class, fixture.fallbackWorkIds().get(0), fixture.fallbackWorkIds().get(1))).isEqualTo(2);
            softly.assertThat(jdbc.queryForList("""
                    SELECT work.id, work.accepted_result_sha256, point.test_point_type, point.basis,
                           point.description, point.missing_information_json,
                           outcome.generation_outcome, outcome.missing_information_json AS outcome_missing,
                           publication.input_sha256, publication.result_sha256, publication.published_at
                    FROM structured_generation_work_item work
                    JOIN structured_test_point point ON point.work_item_id=work.id
                    JOIN v2_generation_outcome outcome ON outcome.work_item_id=work.id
                    JOIN v2_work_publication publication ON publication.work_item_id=work.id
                    WHERE work.id IN (?, ?) ORDER BY work.id
                    """, fixture.fallbackWorkIds().get(0), fixture.fallbackWorkIds().get(1))).isEqualTo(before);
            softly.assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        });
    }

    /** [Req-ID]: REQ-TGV2-011 */
    @Test
    void supersededFallbackCannotIgnoreItsPreviouslyValidatedV23Replay() {
        V2AtomicityRecoveryFixture fixture = v2AtomicityRecoveryFixture();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
        String fallbackWorkId = fixture.fallbackWorkIds().get(0);
        List<String> changedMissing = List.of(
                V2GenerationPlanner.missingFormalFactInformation(),
                "缺少可用于形成正式用例的初始化条件");
        String changedResultHash = sha256Json(new FunctionalTestcaseDesignV2Result(
                "function-1", V2GenerationPlanner.missingFormalFactPointKey("task-1", "function-1"),
                FunctionalTestcaseDesignV2Result.GenerationOutcome.UNABLE_TO_GENERATE,
                changedMissing, List.of()));
        jdbc.update("UPDATE v2_generation_outcome SET missing_information_json=JSON_ARRAY(?, ?) WHERE work_item_id=?",
                changedMissing.get(0), changedMissing.get(1), fallbackWorkId);
        jdbc.update("UPDATE v2_work_publication SET result_sha256=? WHERE work_item_id=?",
                changedResultHash, fallbackWorkId);
        jdbc.update("UPDATE structured_generation_work_item SET accepted_result_sha256=? WHERE id=?",
                changedResultHash, fallbackWorkId);
        store.updateTaskState("task-1", new StructuredGenerationAcceptanceStore.StructuredTaskState(
                StructuredProcessingStatus.RUNNING, StructuredCoverageStatus.PENDING));
        V2GenerationPlanner.TestPointPlan fallback = new V2GenerationPlanner().missingFormalFactTestPoint(
                "task-1", new ApprovedFunctionScope.ApprovedFunction(
                        "function-1", "订单提交", "业务/订单提交", ""));

        assertThatThrownBy(() -> store.registerMissingFactFallback(fallback))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Superseded no-fact fallback no longer matches its audited projection");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id=?", String.class, fallbackWorkId))
                .isEqualTo("SUPERSEDED");
    }

    /** [Req-ID]: REQ-TGV2-011 */
    @ParameterizedTest
    @ValueSource(strings = {"legacy_fact", "reconciliation_run", "cross_function_v2_fact"})
    void supersededFallbackWithUnexpectedBusinessRowsCannotBeReactivated(String mutation) {
        V2AtomicityRecoveryFixture fixture = v2AtomicityRecoveryFixture();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
        String fallbackWorkId = fixture.fallbackWorkIds().get(0);
        if ("legacy_fact".equals(mutation)) {
            jdbc.update("""
                    INSERT INTO structured_requirement_fact
                    (work_item_id, task_id, fact_key, function_name, roles_json, trigger_conditions_json,
                     inputs_json, business_rules_json, outputs_json, permissions_json, state_changes_json,
                     exception_handling_json, external_dependencies_json)
                    VALUES (?, 'task-1', 'unexpected-superseded-fact', '多余旧投影',
                            JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(),
                            JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY())
                    """, fallbackWorkId);
        } else if ("reconciliation_run".equals(mutation)) {
            jdbc.update("""
                    INSERT INTO structured_reconciliation_run
                    (work_item_id, task_id, run_key, catalog_sha256, function_item_count,
                     requirement_fact_count, catalog_source_refs_json, initial_page_keys_json, status)
                    VALUES (?, 'task-1', 'unexpected-superseded-run', ?, 0, 0,
                            JSON_ARRAY(), JSON_ARRAY(), 'STAGING')
                    """, fallbackWorkId, "2".repeat(64));
        } else {
            jdbc.update("""
                    INSERT INTO v2_requirement_fact
                    (task_id, fact_key, first_work_item_id, function_key, fact_type, statement_text)
                    VALUES ('task-1', 'unexpected-cross-function-fact', ?, 'function-2',
                            'business_rule', '多余 V2 事实')
                    """, fallbackWorkId);
        }
        store.updateTaskState("task-1", new StructuredGenerationAcceptanceStore.StructuredTaskState(
                StructuredProcessingStatus.RUNNING, StructuredCoverageStatus.PENDING));
        V2GenerationPlanner.TestPointPlan fallback = new V2GenerationPlanner().missingFormalFactTestPoint(
                "task-1", new ApprovedFunctionScope.ApprovedFunction(
                        "function-1", "订单提交", "业务/订单提交", ""));

        assertThatThrownBy(() -> store.registerMissingFactFallback(fallback))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Superseded no-fact fallback no longer matches its audited projection");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id=?", String.class, fallbackWorkId))
                .isEqualTo("SUPERSEDED");
    }

    /** [Req-ID]: REQ-TGV2-011 */
    @ParameterizedTest
    @ValueSource(strings = {
            "accepted_hash", "fact_publication", "active_lease", "wrong_code", "extra_unfinished", "missing_artifact",
             "extra_test_point", "extra_outcome", "extra_publication", "wrong_point_type",
             "wrong_outcome_missing", "wrong_input_hash", "coordinated_wrong_result_hash",
             "coordinated_empty_outcome_semantics", "tampered_fallback_replay", "null_fallback_replay",
             "wrong_function_name",
             "wrong_task_diagnostic",
            "extra_completed_work", "extra_superseded_work", "fallback_legacy_fact",
            "fallback_reconciliation_run", "split_without_children"
    })
    void rejectsNearMissesWithoutMutatingThePartialV2Task(String mutation) {
        V2AtomicityRecoveryFixture fixture = v2AtomicityRecoveryFixture();
        String firstFact = fixture.factWorkIds().get(0);
        switch (mutation) {
            case "accepted_hash" -> jdbc.update(
                    "UPDATE structured_generation_work_item SET accepted_result_sha256=? WHERE id=?",
                    "9".repeat(64), firstFact);
            case "fact_publication" -> jdbc.update("""
                    INSERT INTO v2_work_publication
                    (work_item_id, task_id, publication_type, input_sha256, result_sha256, published_at)
                    VALUES (?, 'task-1', 'requirement_facts', ?, ?, CURRENT_TIMESTAMP(6))
                    """, firstFact, "7".repeat(64), "8".repeat(64));
            case "active_lease" -> jdbc.update("""
                    UPDATE structured_generation_work_item
                    SET lease_owner='another-worker', lease_expires_at=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 5 MINUTE)
                    WHERE id=?
                    """, firstFact);
            case "wrong_code" -> {
                jdbc.update("""
                        UPDATE structured_generation_work_item
                        SET validation_error_code='FACT_QUOTE_NOT_GROUNDED'
                        WHERE id=?
                        """, firstFact);
                jdbc.update("""
                        UPDATE structured_generation_attempt
                        SET validation_error_code='FACT_QUOTE_NOT_GROUNDED'
                        WHERE work_item_id=?
                        """, firstFact);
            }
            case "extra_unfinished" -> store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                    "task-1", "e".repeat(64), "functional-testcase-design", "FUNCTIONAL_TESTCASE_DESIGN_V2",
                    null, null, null, null, List.of(), "function-1", "unexpected-point",
                    null, List.of(), null, 0));
            case "missing_artifact" -> jdbc.update(
                    "UPDATE generation_task SET artifact_sha256=NULL WHERE id='task-1'");
            case "extra_test_point" -> jdbc.update("""
                    INSERT INTO structured_test_point
                    (work_item_id, task_id, test_point_key, function_key, function_name, test_point_type,
                     basis, description, missing_information_json, formal_coverage_satisfied)
                    VALUES (?, 'task-1', 'unexpected-point', 'function-1', '订单提交', 'normal_behavior',
                            'general_experience', '多余投影', JSON_ARRAY('不允许'), FALSE)
                    """, fixture.fallbackWorkIds().get(0));
            case "extra_outcome" -> jdbc.update("""
                    INSERT INTO v2_generation_outcome
                    (work_item_id, task_id, test_point_key, function_key, generation_outcome,
                     missing_information_json, formal_coverage_satisfied)
                    VALUES (?, 'task-1', 'unexpected-outcome', 'function-1', 'unable_to_generate',
                            JSON_ARRAY('不允许'), FALSE)
                    """, firstFact);
            case "extra_publication" -> jdbc.update("""
                    INSERT INTO v2_work_publication
                    (work_item_id, task_id, publication_type, input_sha256, result_sha256, published_at)
                    VALUES (?, 'task-1', 'testcase_design', ?, ?, CURRENT_TIMESTAMP(6))
                    """, firstFact, "6".repeat(64), "7".repeat(64));
            case "wrong_point_type" -> jdbc.update("""
                    UPDATE structured_test_point SET test_point_type='input_validation'
                    WHERE work_item_id=?
                    """, fixture.fallbackWorkIds().get(0));
            case "wrong_outcome_missing" -> jdbc.update("""
                    UPDATE v2_generation_outcome SET missing_information_json=JSON_ARRAY('任意非空文字')
                    WHERE work_item_id=?
                    """, fixture.fallbackWorkIds().get(0));
            case "wrong_input_hash" -> jdbc.update("""
                    UPDATE v2_work_publication SET input_sha256=? WHERE work_item_id=?
                    """, "5".repeat(64), fixture.fallbackWorkIds().get(0));
            case "coordinated_wrong_result_hash" -> {
                jdbc.update("UPDATE structured_generation_work_item SET accepted_result_sha256=? WHERE id=?",
                        "4".repeat(64), fixture.fallbackWorkIds().get(0));
                jdbc.update("UPDATE v2_work_publication SET result_sha256=? WHERE work_item_id=?",
                        "4".repeat(64), fixture.fallbackWorkIds().get(0));
            }
            case "coordinated_empty_outcome_semantics" -> {
                String changedResultHash = sha256Json(new FunctionalTestcaseDesignV2Result(
                        "function-1", V2GenerationPlanner.missingFormalFactPointKey("task-1", "function-1"),
                        FunctionalTestcaseDesignV2Result.GenerationOutcome.UNABLE_TO_GENERATE,
                        List.of(), List.of()));
                jdbc.update("UPDATE v2_generation_outcome SET missing_information_json=JSON_ARRAY() WHERE work_item_id=?",
                        fixture.fallbackWorkIds().get(0));
                jdbc.update("UPDATE structured_generation_work_item SET accepted_result_sha256=? WHERE id=?",
                        changedResultHash, fixture.fallbackWorkIds().get(0));
                jdbc.update("UPDATE v2_work_publication SET result_sha256=? WHERE work_item_id=?",
                        changedResultHash, fixture.fallbackWorkIds().get(0));
            }
            case "tampered_fallback_replay" -> jdbc.update("""
                    UPDATE v2_work_publication SET validated_result_replay_json=JSON_OBJECT('unexpected', TRUE)
                    WHERE work_item_id=?
                    """, fixture.fallbackWorkIds().get(0));
            case "null_fallback_replay" -> {
                String nullHash = sha256Json(null);
                jdbc.update("""
                        UPDATE v2_work_publication
                        SET validated_result_replay_json=CAST('null' AS JSON), result_sha256=?
                        WHERE work_item_id=?
                        """, nullHash, fixture.fallbackWorkIds().get(0));
                jdbc.update("UPDATE structured_generation_work_item SET accepted_result_sha256=? WHERE id=?",
                        nullHash, fixture.fallbackWorkIds().get(0));
            }
            case "wrong_function_name" -> jdbc.update("""
                    UPDATE structured_test_point SET function_name='漂移名称' WHERE work_item_id=?
                    """, fixture.fallbackWorkIds().get(0));
            case "wrong_task_diagnostic" -> jdbc.update("""
                    UPDATE generation_task SET validation_error_code='UNEXPECTED_DIAGNOSTIC' WHERE id='task-1'
                    """);
            case "extra_completed_work" -> {
                String extra = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                        "task-1", "9".repeat(64), "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT",
                        1, 1, "document-1", "需求材料", List.of("evidence-1"), null, null,
                        "document-1", List.of(), null, 0));
                jdbc.update("UPDATE structured_generation_work_item SET status='COMPLETED', accepted_result_sha256=? WHERE id=?",
                        "3".repeat(64), extra);
            }
            case "extra_superseded_work" -> {
                String extra = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                        "task-1", "8".repeat(64), "functional-testcase-design", "FUNCTIONAL_TESTCASE_DESIGN_V2",
                        null, null, null, null, List.of(), "function-1", "unexpected-point",
                        null, List.of(), null, 0));
                jdbc.update("UPDATE structured_generation_work_item SET status='SUPERSEDED' WHERE id=?", extra);
            }
            case "fallback_legacy_fact" -> jdbc.update("""
                    INSERT INTO structured_requirement_fact
                    (work_item_id, task_id, fact_key, function_name, roles_json, trigger_conditions_json,
                     inputs_json, business_rules_json, outputs_json, permissions_json, state_changes_json,
                     exception_handling_json, external_dependencies_json)
                    VALUES (?, 'task-1', 'unexpected-fallback-fact', '多余旧投影',
                            JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(),
                            JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY())
                    """, fixture.fallbackWorkIds().get(0));
            case "fallback_reconciliation_run" -> jdbc.update("""
                    INSERT INTO structured_reconciliation_run
                    (work_item_id, task_id, run_key, catalog_sha256, function_item_count,
                     requirement_fact_count, catalog_source_refs_json, initial_page_keys_json, status)
                    VALUES (?, 'task-1', 'unexpected-fallback-run', ?, 0, 0,
                            JSON_ARRAY(), JSON_ARRAY(), 'STAGING')
                    """, fixture.fallbackWorkIds().get(0), "1".repeat(64));
            case "split_without_children" -> {
                String parent = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                        "task-1", "2".repeat(64), "requirement-fact-extraction", "REQUIREMENT_FACT_EXTRACTION_V2",
                        1, 2, "document-1", "需求材料", List.of("evidence-1", "evidence-2"),
                        "function-1", null, "document-1", List.of(), null, 0));
                jdbc.update("UPDATE structured_generation_work_item SET status='SPLIT' WHERE id=?", parent);
            }
            default -> throw new IllegalArgumentException("unknown fixture mutation");
        }
        List<Map<String, Object>> before = jdbc.queryForList("""
                SELECT id, status, accepted_result_sha256, lease_owner, validation_error_code
                FROM structured_generation_work_item WHERE task_id='task-1' ORDER BY id
                """);
        int attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_attempt", Integer.class);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
            softly.assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.PARTIAL);
            softly.assertThat(jdbc.queryForList("""
                    SELECT id, status, accepted_result_sha256, lease_owner, validation_error_code
                    FROM structured_generation_work_item WHERE task_id='task-1' ORDER BY id
                    """)).isEqualTo(before);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_attempt", Integer.class)).isEqualTo(attempts);
        });
    }

    /** [Req-ID]: REQ-TGV2-011 */
    @Test
    void concurrentAtomicityRecoveryHasOneWinner() throws Exception {
        V2AtomicityRecoveryFixture fixture = v2AtomicityRecoveryFixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Integer> retry = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return taskRepository.retryFailedBatches("task-1");
            };
            Future<Integer> first = executor.submit(retry);
            Future<Integer> second = executor.submit(retry);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE id IN (?, ?) AND status='QUEUED'
                    """, Integer.class, fixture.factWorkIds().get(0), fixture.factWorkIds().get(1))).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE id IN (?, ?) AND status='SUPERSEDED'
                    """, Integer.class, fixture.fallbackWorkIds().get(0), fixture.fallbackWorkIds().get(1))).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    /** [Req-ID]: REQ-TGV2-011 */
    @Test
    void rollsBackFactRequeueWhenFallbackSupersedeFails() {
        V2AtomicityRecoveryFixture fixture = v2AtomicityRecoveryFixture();
        Map<String, Object> before = v2AtomicityFullSnapshot(fixture);
        jdbc.execute("ALTER TABLE structured_generation_work_item "
                + "ADD CONSTRAINT chk_test_no_superseded CHECK (status <> 'SUPERSEDED')");
        try {
            assertThatThrownBy(() -> taskRepository.retryFailedBatches("task-1"))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE structured_generation_work_item DROP CHECK chk_test_no_superseded");
        }

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.PARTIAL);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE id IN (?, ?) AND status='FAILED'
                    """, Integer.class, fixture.factWorkIds().get(0), fixture.factWorkIds().get(1))).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE id IN (?, ?) AND status='COMPLETED'
                    """, Integer.class, fixture.fallbackWorkIds().get(0), fixture.fallbackWorkIds().get(1))).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_generation_outcome WHERE task_id='task-1'",
                    Integer.class)).isEqualTo(2);
            softly.assertThat(v2AtomicityFullSnapshot(fixture)).isEqualTo(before);
        });
    }

    /** [Req-ID]: REQ-TGV2-008 */
    @Test
    void v2TestcaseReplayIsIdempotentButAConflictingAcceptedHashIsRejectedWithoutExtraRows() {
        var input = v2TestcaseInput();
        String workId = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "8".repeat(64), "functional-testcase-design", "FUNCTIONAL_TESTCASE_DESIGN_V2",
                null, null, null, null, List.of(), "function-1", "point-1"));
        var claim = store.claimRegistered("task-1", workId, "v2-testcase-worker").orElseThrow();
        var testcase = v2Testcase(FunctionalTestcaseDesignV2Result.CaseStatus.FORMAL, List.of());
        var accepted = new FunctionalTestcaseDesignV2Result("function-1", "point-1",
                FunctionalTestcaseDesignV2Result.GenerationOutcome.GENERATED, List.of(), List.of(testcase));
        var conflictingCase = new FunctionalTestcaseDesignV2Result.Testcase(testcase.name(), testcase.title(),
                FunctionalTestcaseDesignV2Result.Priority.HIGH, testcase.preconditions(), testcase.initialization(), testcase.inputs(),
                testcase.steps(), testcase.expectedResults(), testcase.evaluationCriteria(),
                testcase.resultEvaluationCriteria(), testcase.terminationConditions(), testcase.resultCollection(),
                testcase.requirementFactKeys(), testcase.evidenceKeys(), testcase.caseStatus(),
                testcase.missingInformation());
        var conflicting = new FunctionalTestcaseDesignV2Result("function-1", "point-1",
                FunctionalTestcaseDesignV2Result.GenerationOutcome.GENERATED, List.of(), List.of(conflictingCase));

        store.acceptTestcasesV2(claim, new FunctionalTestcaseV2Validator(), input, accepted);
        store.acceptTestcasesV2(claim, new FunctionalTestcaseV2Validator(), input, accepted);
        assertThatThrownBy(() -> store.acceptTestcasesV2(
                claim, new FunctionalTestcaseV2Validator(), input, conflicting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different accepted result");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_test_case", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_generation_outcome", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v2_work_publication", Integer.class)).isEqualTo(1);
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void v2PagesRemainNonBusinessUntilOneAtomicRunPublication() {
        V2Fixture fixture = v2Fixture();
        store.initializeReconciliationRun(fixture.claim(), fixture.plan());

        store.stageReconciliationPage(fixture.claim(), fixture.itemPage());
        assertThat(store.pendingReconciliationPages(fixture.claim().workItemId(), fixture.run().runKey(), fixture.run().catalogSha256()))
                .containsExactly(fixture.factWindow());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_feature_reconciliation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding WHERE subject_type = 'RECONCILIATION'", Integer.class)).isZero();

        store.stageReconciliationPage(fixture.claim(), fixture.factPage());
        store.publishReconciliationRun(fixture.claim(), fixture.publication());

        assertSoftly(softly -> {
            softly.assertThat(store.hasCompletedReconciliationWork("task-1")).isTrue();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_feature_reconciliation", Integer.class)).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding WHERE subject_type = 'RECONCILIATION'", Integer.class)).isEqualTo(4);
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reconciliation_source_terminal", Integer.class)).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    fixture.claim().workItemId())).isEqualTo("COMPLETED");
            softly.assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_attempt WHERE id = ?", String.class,
                    fixture.claim().attemptId())).isEqualTo("COMPLETED");
        });
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void v2SamePageIsIdempotentButConflictingReplayFailsClosed() {
        V2Fixture fixture = v2Fixture();
        store.initializeReconciliationRun(fixture.claim(), fixture.plan());
        store.stageReconciliationPage(fixture.claim(), fixture.itemPage());
        store.stageReconciliationPage(fixture.claim(), fixture.itemPage());

        StructuredGenerationAcceptanceStore.ReconciliationPageStage conflict =
                new StructuredGenerationAcceptanceStore.ReconciliationPageStage(
                        fixture.run(), fixture.itemWindow(), fixture.itemWindow().ownerSourceRefs(),
                        fixture.itemPage().relations(), "9".repeat(64));

        assertThatThrownBy(() -> store.stageReconciliationPage(fixture.claim(), conflict))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reconciliation_page_stage", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reconciliation_relation_stage", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_feature_reconciliation", Integer.class)).isZero();
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void v2SplitPlanSurvivesRestartAndReturnsOnlyMissingLeafPage() {
        V2Fixture fixture = v2FixtureWithCombinedWindow();
        store.initializeReconciliationRun(fixture.claim(), fixture.plan());
        store.splitReconciliationPage(fixture.claim(), fixture.run(), fixture.plan().initialOwnerWindows().get(0).pageKey(),
                fixture.itemWindow(), fixture.factWindow());
        store.stageReconciliationPage(fixture.claim(), fixture.itemPage());

        StructuredGenerationAcceptanceStore restarted = new StructuredGenerationAcceptanceStore(
                jdbc, new TransactionTemplate(transactionManager), Clock.systemUTC());

        assertThat(restarted.pendingReconciliationPages(fixture.claim().workItemId(), fixture.run().runKey(), fixture.run().catalogSha256()))
                .containsExactly(fixture.factWindow());
        assertThat(restarted.completedReconciliationPageKeys(fixture.claim().workItemId(), fixture.run().runKey(), fixture.run().catalogSha256()))
                .containsExactly(fixture.itemWindow().pageKey());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_feature_reconciliation", Integer.class)).isZero();
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void v2PublishDatabaseConflictRollsBackRelationsBindingsTerminalsAndCompletion() {
        V2Fixture fixture = v2Fixture();
        store.initializeReconciliationRun(fixture.claim(), fixture.plan());
        store.stageReconciliationPage(fixture.claim(), fixture.itemPage());
        store.stageReconciliationPage(fixture.claim(), fixture.factPage());
        String legacyWork = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "f".repeat(64), "feature-scope-reconciliation", "FEATURE_SCOPE_RECONCILIATION",
                null, null, null, "legacy", List.of(), null, null));
        jdbc.update("UPDATE structured_generation_work_item SET status = 'COMPLETED', accepted_result_sha256 = ? WHERE id = ?",
                "e".repeat(64), legacyWork);
        jdbc.update("INSERT INTO structured_feature_reconciliation "
                + "(work_item_id, task_id, reconciliation_key, classification, scope_recommendation, confirmation_status) "
                + "VALUES (?, 'task-1', ?, 'EXACT_MATCH', 'legacy', 'CONFIRMED')",
                legacyWork, fixture.relation().reconciliationKey());

        assertThatThrownBy(() -> store.publishReconciliationRun(fixture.claim(), fixture.publication()))
                .isInstanceOf(IllegalStateException.class);

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_feature_reconciliation", Integer.class)).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding WHERE work_item_id = ?", Integer.class,
                    fixture.claim().workItemId())).isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reconciliation_source_terminal", Integer.class)).isZero();
            softly.assertThat(jdbc.queryForObject("SELECT status FROM structured_reconciliation_run WHERE work_item_id = ?", String.class,
                    fixture.claim().workItemId())).isEqualTo("STAGING");
            softly.assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    fixture.claim().workItemId())).isEqualTo("RUNNING");
        });
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void v2ConcurrentSamePageStagingProducesOneDurablePage() throws Exception {
        V2Fixture fixture = v2Fixture();
        store.initializeReconciliationRun(fixture.claim(), fixture.plan());
        StructuredGenerationAcceptanceStore secondStore = new StructuredGenerationAcceptanceStore(
                jdbc, new TransactionTemplate(transactionManager), Clock.systemUTC());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> first = executor.submit(() -> { start.await(); store.stageReconciliationPage(fixture.claim(), fixture.itemPage()); return null; });
            Future<Void> second = executor.submit(() -> { start.await(); secondStore.stageReconciliationPage(fixture.claim(), fixture.itemPage()); return null; });
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reconciliation_page_stage WHERE status = 'COMPLETED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reconciliation_relation_stage", Integer.class)).isEqualTo(1);
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void v2SourceOrderingUsesUnsignedUtf8BytesInsteadOfJavaUtf16Order() {
        var bmpPrivateUse = new StructuredGenerationAcceptanceStore.ReconciliationSourceRef(
                "requirement_fact", "\uE000");
        var supplementary = new StructuredGenerationAcceptanceStore.ReconciliationSourceRef(
                "requirement_fact", "\uD800\uDC00");

        assertThat(new StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow(
                "a".repeat(64), List.of(bmpPrivateUse, supplementary)).ownerSourceRefs())
                .containsExactly(bmpPrivateUse, supplementary);
        assertThatThrownBy(() -> new StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow(
                "a".repeat(64), List.of(supplementary, bmpPrivateUse)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonically ordered");
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void v2PageAndRelationHashesPersistGoCompatibleJsonEscapes() {
        String specialSourceKey = "item-<&-\u2028";
        var source = new StructuredGenerationAcceptanceStore.ReconciliationSourceRef(
                "function_list_item", specialSourceKey);
        var run = new StructuredGenerationAcceptanceStore.ReconciliationRunIdentity(
                sha256Text("run-v2-special-json"), sha256Text("catalog-v2-special-json"), 1, 0);
        // Go encoding/json escapes HTML-sensitive characters and U+2028. The frozen hashes use
        // those exact compact bytes, so a generic Jackson serialization must not redefine identity.
        String canonicalRefsJson = "[{\"source_type\":\"function_list_item\","
                + "\"source_key\":\"item-\\u003c\\u0026-\\u2028\"}]";
        String pageKey = sha256Text("reconcile-page-v2\n" + run.runKey() + "\n" + canonicalRefsJson);
        String relationKey = sha256Text("reconciliation-v2\n" + run.runKey()
                + "\nfunction_list_only\nconfirmed\n" + canonicalRefsJson);
        var window = new StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow(
                pageKey, List.of(source));
        var relation = new StructuredGenerationAcceptanceStore.ReconciliationRelation(
                relationKey, source, List.of(specialSourceKey), List.of(), "function_list_only",
                List.of("evidence-1"), "仅见功能清单", "confirmed");
        var plan = new StructuredGenerationAcceptanceStore.ReconciliationRunPlan(run, List.of(window));
        String workId = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "b".repeat(64), "feature-scope-reconciliation", "FEATURE_SCOPE_RECONCILIATION_V2",
                null, null, null, "special-json-v2", List.of(), null, null));
        var claim = store.claimRegistered("task-1", workId, "worker-special-json").orElseThrow();

        store.initializeReconciliationRun(claim, plan);
        store.stageReconciliationPage(claim, new StructuredGenerationAcceptanceStore.ReconciliationPageStage(
                run, window, List.of(source), List.of(relation), sha256Text("special-json-result")));

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("""
                            SELECT page_key FROM structured_reconciliation_page_stage
                            WHERE work_item_id = ?
                            """, String.class, workId))
                    .isEqualTo(pageKey);
            softly.assertThat(jdbc.queryForObject("""
                            SELECT reconciliation_key FROM structured_reconciliation_relation_stage
                            WHERE work_item_id = ?
                            """, String.class, workId))
                    .isEqualTo(relationKey);
        });
    }

    /** [Req-ID]: REQ-FSC-007, REQ-ESR-001, REQ-ESR-003 */
    @Test
    void diagnosedZeroWriteBusinessValidationFailureCanBeRequeuedAndClearsOnlyCurrentDiagnostics() {
        StructuredValidationFailure failure = StructuredValidationFailure.of(
                StructuredValidationFailure.Code.REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                "$.requirement_facts[0].business_rules[0]");
        ExplicitRetryFixture fixture = explicitRetryFixture("business_validation_failed", failure);

        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
            softly.assertThat(jdbc.queryForMap("""
                            SELECT status, validation_error_code, validation_error_path, validation_error_message
                            FROM structured_generation_work_item WHERE id = ?
                            """, fixture.failedWorkId()))
                    .containsEntry("status", "QUEUED")
                    .containsEntry("validation_error_code", null)
                    .containsEntry("validation_error_path", null)
                    .containsEntry("validation_error_message", null);
            softly.assertThat(jdbc.queryForMap("""
                            SELECT validation_error_code, validation_error_path, validation_error_message
                            FROM generation_task WHERE id = 'task-1'
                            """))
                    .containsEntry("validation_error_code", null)
                    .containsEntry("validation_error_path", null)
                    .containsEntry("validation_error_message", null);
            softly.assertThat(jdbc.queryForMap("""
                            SELECT failure_type, validation_error_code, validation_error_path, validation_error_message
                            FROM structured_generation_attempt
                            WHERE work_item_id = ? AND attempt_number = 1
                            """, fixture.failedWorkId()))
                    .containsEntry("failure_type", "business_validation_failed")
                    .containsEntry("validation_error_code", failure.code())
                    .containsEntry("validation_error_path", failure.path())
                    .containsEntry("validation_error_message", failure.message());
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_review_finding WHERE task_id = 'task-1'", Integer.class))
                    .isEqualTo(6);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_reference_binding binding
                    JOIN structured_generation_work_item work ON work.id = binding.work_item_id
                    WHERE work.task_id = 'task-1'
                    """, Integer.class)).isEqualTo(48);
        });
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();

    }

    /** [Req-ID]: REQ-ESR-001, REQ-ESR-003 */
    @Test
    void explicitRetryRequeuesOnlyTheZeroWriteStructuralFailureAndPreservesCompletedSlices() {
        ExplicitRetryFixture fixture = explicitRetryFixture("structured_output_invalid");

        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT structured_processing_status FROM generation_task WHERE id = 'task-1'", String.class))
                    .isEqualTo("PENDING");
            softly.assertThat(jdbc.queryForList(
                            "SELECT status FROM structured_generation_work_item WHERE id IN (?, ?) ORDER BY id",
                            String.class, fixture.completedWorkIds().get(0), fixture.completedWorkIds().get(1)))
                    .containsOnly("COMPLETED");
            softly.assertThat(jdbc.queryForList(
                            "SELECT accepted_result_sha256 FROM structured_generation_work_item WHERE id IN (?, ?)",
                            String.class, fixture.completedWorkIds().get(0), fixture.completedWorkIds().get(1)))
                    .containsExactlyInAnyOrder("1".repeat(64), "2".repeat(64));
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    fixture.failedWorkId())).isEqualTo("QUEUED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_review_finding WHERE task_id = 'task-1'", Integer.class)).isEqualTo(6);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_reference_binding binding
                    JOIN structured_generation_work_item work ON work.id = binding.work_item_id
                    WHERE work.task_id = 'task-1'
                    """, Integer.class)).isEqualTo(48);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class,
                    fixture.failedWorkId())).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT failure_type FROM structured_generation_attempt
                    WHERE work_item_id = ? AND attempt_number = 1
                    """, String.class, fixture.failedWorkId())).isEqualTo("structured_output_invalid");
        });

        StructuredGenerationAcceptanceStore.WorkClaim secondAttempt = store.claimRegistered(
                "task-1", fixture.failedWorkId(), "worker-2").orElseThrow();
        assertThat(secondAttempt.attemptNumber()).isEqualTo(2);
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-FTG-010, REQ-ESR-001 */
    @Test
    void explicitRetryIgnoresDurableSplitParentsAndRequeuesOnlyTheFailedLeaf() {
        ExplicitRetryFixture fixture = explicitRetryFixture("structured_output_invalid");
        String splitParent = store.register(reviewRegistration(
                "e".repeat(64), 1, 32, java.util.stream.IntStream.rangeClosed(1, 32)
                        .mapToObj(index -> "parent-unit-" + index).toList()));
        jdbc.update("UPDATE structured_generation_work_item SET status = 'SPLIT' WHERE id = ?", splitParent);

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_work_item WHERE id = ?",
                    String.class, splitParent)).isEqualTo("SPLIT");
            softly.assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_work_item WHERE id = ?",
                    String.class, fixture.failedWorkId())).isEqualTo("QUEUED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_review_finding WHERE task_id = 'task-1'", Integer.class)).isEqualTo(6);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_reference_binding binding
                    JOIN structured_generation_work_item work ON work.id = binding.work_item_id
                    WHERE work.task_id = 'task-1'
                    """, Integer.class)).isEqualTo(48);
        });
    }

    /** [Req-ID]: REQ-FTG-011, REQ-ESR-001, REQ-ESR-003 */
    @Test
    void explicitRetryRequeuesTheOnlyFailedSplitLeafWhileQueuedSiblingsRemain() {
        ExplicitRetryFixture fixture = explicitRetryFixture("structured_output_invalid");
        String splitParent = store.register(reviewRegistration(
                "e".repeat(64), 65, 68, List.of("unit-65", "unit-66", "unit-67", "unit-68")));
        jdbc.update("UPDATE structured_generation_work_item SET status = 'SPLIT' WHERE id = ?", splitParent);
        String queuedSibling = store.register(reviewRegistration(
                "f".repeat(64), 68, 68, List.of("unit-68")));

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?",
                    String.class, fixture.failedWorkId())).isEqualTo("QUEUED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?",
                    String.class, queuedSibling)).isEqualTo("QUEUED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?",
                    Integer.class, queuedSibling)).isZero();
            softly.assertThat(jdbc.queryForList(
                            "SELECT accepted_result_sha256 FROM structured_generation_work_item WHERE id IN (?, ?)",
                            String.class, fixture.completedWorkIds().get(0), fixture.completedWorkIds().get(1)))
                    .containsExactlyInAnyOrder("1".repeat(64), "2".repeat(64));
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_review_finding WHERE task_id = 'task-1'", Integer.class))
                    .isEqualTo(6);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_reference_binding binding
                    JOIN structured_generation_work_item work ON work.id = binding.work_item_id
                    WHERE work.task_id = 'task-1'
                    """, Integer.class)).isEqualTo(48);
        });
    }

    /** [Req-ID]: REQ-ESR-005 */
    @Test
    void explicitRetryAllowsOnlyTheZeroWriteSingleUnitReviewCapacityFailureWithQueuedSiblings() {
        ExplicitRetryFixture fixture = explicitRetryFixture("response_too_large");
        jdbc.update("""
                UPDATE structured_generation_work_item
                SET ordinal_start = 1, ordinal_end = 1,
                    allowed_evidence_keys_json = JSON_ARRAY('evidence-1')
                WHERE id = ?
                """, fixture.failedWorkId());
        String queuedSibling = store.register(reviewRegistration(
                "f".repeat(64), 2, 2, List.of("evidence-2")));

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?",
                    String.class, fixture.failedWorkId())).isEqualTo("QUEUED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?",
                    String.class, queuedSibling)).isEqualTo("QUEUED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?",
                    Integer.class, fixture.failedWorkId())).isEqualTo(1);
            softly.assertThat(jdbc.queryForList(
                            "SELECT accepted_result_sha256 FROM structured_generation_work_item WHERE id IN (?, ?)",
                            String.class, fixture.completedWorkIds().get(0), fixture.completedWorkIds().get(1)))
                    .containsExactlyInAnyOrder("1".repeat(64), "2".repeat(64));
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_review_finding WHERE task_id = 'task-1'", Integer.class))
                    .isEqualTo(6);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_reference_binding binding
                    JOIN structured_generation_work_item work ON work.id = binding.work_item_id
                    WHERE work.task_id = 'task-1'
                    """, Integer.class)).isEqualTo(48);
        });
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();

    }

    /** [Req-ID]: REQ-ESR-005 */
    @ParameterizedTest
    @ValueSource(strings = {"multi-unit", "multi-evidence", "wrong-skill", "wrong-operation",
            "missing-unit", "ordinal-mismatch", "cross-document-collision", "accepted-hash",
            "running-attempt", "multiple-failed", "execution-slot"})
    void explicitRetryRejectsCapacityFailureWithoutAnExclusivePersistedSingleReviewUnit(String mutation) {
        ExplicitRetryFixture fixture = explicitRetryFixture("response_too_large");
        jdbc.update("""
                UPDATE structured_generation_work_item
                SET ordinal_start = 1, ordinal_end = 1,
                    allowed_evidence_keys_json = JSON_ARRAY('evidence-1')
                WHERE id = ?
                """, fixture.failedWorkId());
        switch (mutation) {
            case "multi-unit" -> jdbc.update("""
                    UPDATE structured_generation_work_item
                    SET ordinal_end = 2, allowed_evidence_keys_json = JSON_ARRAY('evidence-1', 'evidence-2')
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "multi-evidence" -> jdbc.update("""
                    UPDATE structured_generation_work_item
                    SET allowed_evidence_keys_json = JSON_ARRAY('evidence-1', 'evidence-2')
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "wrong-skill" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET skill_name = 'functional-testcase-design'
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "wrong-operation" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET operation_name = 'FUNCTIONAL_TESTCASE_DESIGN'
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "missing-unit" -> jdbc.update("""
                    UPDATE structured_generation_work_item
                    SET allowed_evidence_keys_json = JSON_ARRAY('absent-evidence')
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "ordinal-mismatch" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET ordinal_start = 2, ordinal_end = 2
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "cross-document-collision" -> insertCollidingInventoryUnit();
            case "accepted-hash" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET accepted_result_sha256 = ? WHERE id = ?
                    """, "a".repeat(64), fixture.failedWorkId());
            case "running-attempt" -> jdbc.update("""
                    INSERT INTO structured_generation_attempt
                    (id, work_item_id, attempt_number, status)
                    VALUES (UUID(), ?, 2, 'RUNNING')
                    """, fixture.failedWorkId());
            case "multiple-failed" -> insertSecondFailedReviewLeaf();
            case "execution-slot" -> jdbc.update(
                    "UPDATE task_execution_slot SET task_id = 'task-1' WHERE slot_number = 1");
            default -> throw new IllegalArgumentException("Unknown mutation");
        }

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
        assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.FAILED);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                fixture.failedWorkId())).isEqualTo("FAILED");
    }

    /** [Req-ID]: REQ-ESR-005 */
    @ParameterizedTest
    @ValueSource(strings = {"structured_requirement_fact", "structured_review_finding",
            "structured_function_list_item", "structured_feature_reconciliation", "structured_test_point",
            "structured_test_case", "structured_test_case_step", "structured_reference_binding",
            "structured_function_source_outcome", "structured_function_candidate",
            "structured_function_outcome_candidate"})
    void explicitRetryRejectsCapacityFailureWhenAnyUnfinishedBusinessTableOwnsRows(String table) {
        ExplicitRetryFixture fixture = explicitRetryFixture("response_too_large");
        jdbc.update("""
                UPDATE structured_generation_work_item
                SET ordinal_start = 1, ordinal_end = 1,
                    allowed_evidence_keys_json = JSON_ARRAY('evidence-1')
                WHERE id = ?
                """, fixture.failedWorkId());
        insertPartialBusinessRow(table, fixture.failedWorkId());

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                fixture.failedWorkId())).isEqualTo("FAILED");
    }

    /** [Req-ID]: REQ-FTG-011, REQ-ESR-001 */
    @Test
    void explicitRetryRejectsAQueuedSplitSiblingThatAlreadyOwnsPartialRows() {
        ExplicitRetryFixture fixture = explicitRetryFixture("structured_output_invalid");
        String queuedSibling = store.register(reviewRegistration(
                "f".repeat(64), 68, 68, List.of("unit-68")));
        jdbc.update("""
                INSERT INTO structured_reference_binding
                (work_item_id, subject_key, subject_type, reference_type, reference_key)
                VALUES (?, 'partial-sibling', 'REVIEW_FINDING', 'EVIDENCE', 'unit-68')
                """, queuedSibling);

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.FAILED);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?",
                    String.class, fixture.failedWorkId())).isEqualTo("FAILED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?",
                    String.class, queuedSibling)).isEqualTo("QUEUED");
        });
    }

    /** [Req-ID]: REQ-ESR-001, REQ-ESR-002 */
    @ParameterizedTest
    @ValueSource(strings = {"scope_validation_failed", "forbidden"})
    void explicitRetryRejectsEveryFailureOutsideTheAuditedStructuralType(String failureType) {
        ExplicitRetryFixture fixture = explicitRetryFixture(failureType);

        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.FAILED);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    fixture.failedWorkId())).isEqualTo("FAILED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class,
                    fixture.failedWorkId())).isEqualTo(1);
        });
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-003, REQ-ESR-009 */
    @Test
    void exhaustedNetworkExtractionRetryPreservesCompletedSplitSiblingAndQueuesOnlyFailedChild() {
        SplitExtractionRetryFixture fixture = exhaustedNetworkExtractionFixture();
        String requestSnapshotBefore = jdbc.queryForObject(
                "SELECT request_snapshot FROM generation_task WHERE id = 'task-1'", String.class);
        Map<String, Object> parentWorkBefore = jdbc.queryForMap("""
                SELECT status, accepted_result_sha256, parent_work_item_id, split_depth, ordinal_start, ordinal_end
                FROM structured_generation_work_item WHERE id = ?
                """, fixture.parentWorkId());
        Map<String, Object> completedWorkBefore = jdbc.queryForMap("""
                SELECT status, accepted_result_sha256, parent_work_item_id, split_depth, ordinal_start, ordinal_end
                FROM structured_generation_work_item WHERE id = ?
                """, fixture.completedChildId());
        Map<String, Object> failedCoordinatesBefore = jdbc.queryForMap("""
                SELECT accepted_result_sha256, parent_work_item_id, split_depth, ordinal_start, ordinal_end,
                       material_key, material_document_id, allowed_evidence_keys_json
                FROM structured_generation_work_item WHERE id = ?
                """, fixture.failedChildId());
        List<Map<String, Object>> completedRowsBefore = jdbc.queryForList("""
                SELECT work_item_id, task_id, item_key, path_text, description, target_quotes_json
                FROM structured_function_list_item WHERE work_item_id = ? ORDER BY item_key
                """, fixture.completedChildId());
        List<Map<String, Object>> completedBindingsBefore = jdbc.queryForList("""
                SELECT subject_key, subject_type, reference_type, reference_key
                FROM structured_reference_binding WHERE work_item_id = ?
                ORDER BY subject_key, reference_type, reference_key
                """, fixture.completedChildId());
        List<Map<String, Object>> completedAttemptsBefore = jdbc.queryForList("""
                SELECT id, work_item_id, attempt_number, status, failure_type, created_at, completed_at,
                       validation_error_code, validation_error_path, validation_error_message
                FROM structured_generation_attempt WHERE work_item_id = ? ORDER BY attempt_number
                """, fixture.completedChildId());
        List<Map<String, Object>> failedAttemptsBefore = jdbc.queryForList("""
                SELECT id, work_item_id, attempt_number, status, failure_type, created_at, completed_at,
                       validation_error_code, validation_error_path, validation_error_message
                FROM structured_generation_attempt WHERE work_item_id = ? ORDER BY attempt_number
                """, fixture.failedChildId());
        Map<String, Object> inventoryDocumentBefore = jdbc.queryForMap("""
                SELECT task_id, document_id, knowledge_id, document_role, total_units, complete, created_at, updated_at
                FROM material_inventory_document
                WHERE task_id = 'task-1' AND document_id = 'function-list-1'
                """);
        List<Map<String, Object>> inventoryBefore = jdbc.queryForList("""
                SELECT task_id, document_id, unit_id, document_role, chunk_index, ordinal, content,
                       start_at, end_at, processing_status, created_at, updated_at
                FROM material_inventory_unit
                WHERE task_id = 'task-1' AND document_id = 'function-list-1'
                ORDER BY ordinal
                """);

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT request_snapshot FROM generation_task WHERE id = 'task-1'", String.class))
                    .isEqualTo(requestSnapshotBefore);
            softly.assertThat(jdbc.queryForMap("""
                            SELECT status, accepted_result_sha256, parent_work_item_id, split_depth,
                                   ordinal_start, ordinal_end
                            FROM structured_generation_work_item WHERE id = ?
                            """, fixture.parentWorkId())).isEqualTo(parentWorkBefore);
            softly.assertThat(jdbc.queryForMap("""
                            SELECT status, accepted_result_sha256
                            FROM structured_generation_work_item WHERE id = ?
                            """, fixture.completedChildId()))
                    .containsEntry("status", "COMPLETED")
                    .containsEntry("accepted_result_sha256", "8".repeat(64));
            softly.assertThat(jdbc.queryForMap("""
                            SELECT status, accepted_result_sha256, parent_work_item_id, split_depth,
                                   ordinal_start, ordinal_end
                            FROM structured_generation_work_item WHERE id = ?
                            """, fixture.completedChildId())).isEqualTo(completedWorkBefore);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?",
                    String.class, fixture.failedChildId())).isEqualTo("QUEUED");
            softly.assertThat(jdbc.queryForMap("""
                            SELECT accepted_result_sha256, parent_work_item_id, split_depth,
                                   ordinal_start, ordinal_end, material_key, material_document_id,
                                   allowed_evidence_keys_json
                            FROM structured_generation_work_item WHERE id = ?
                            """, fixture.failedChildId())).isEqualTo(failedCoordinatesBefore);
            softly.assertThat(jdbc.queryForObject("""
                            SELECT COUNT(*) FROM material_inventory_unit
                            WHERE task_id = 'task-1' AND document_id = 'function-list-1'
                            """, Integer.class)).isEqualTo(32);
            softly.assertThat(jdbc.queryForMap("""
                            SELECT task_id, document_id, knowledge_id, document_role, total_units, complete,
                                   created_at, updated_at
                            FROM material_inventory_document
                            WHERE task_id = 'task-1' AND document_id = 'function-list-1'
                            """)).isEqualTo(inventoryDocumentBefore);
            softly.assertThat(jdbc.queryForList("""
                            SELECT task_id, document_id, unit_id, document_role, chunk_index, ordinal, content,
                                   start_at, end_at, processing_status, created_at, updated_at
                            FROM material_inventory_unit
                            WHERE task_id = 'task-1' AND document_id = 'function-list-1'
                            ORDER BY ordinal
                            """)).isEqualTo(inventoryBefore);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_function_list_item WHERE work_item_id = ?",
                    Integer.class, fixture.completedChildId())).isEqualTo(54);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_reference_binding WHERE work_item_id = ?",
                    Integer.class, fixture.completedChildId())).isEqualTo(54);
            softly.assertThat(jdbc.queryForList("""
                            SELECT work_item_id, task_id, item_key, path_text, description, target_quotes_json
                            FROM structured_function_list_item WHERE work_item_id = ? ORDER BY item_key
                            """, fixture.completedChildId())).isEqualTo(completedRowsBefore);
            softly.assertThat(jdbc.queryForList("""
                            SELECT subject_key, subject_type, reference_type, reference_key
                            FROM structured_reference_binding WHERE work_item_id = ?
                            ORDER BY subject_key, reference_type, reference_key
                            """, fixture.completedChildId())).isEqualTo(completedBindingsBefore);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_function_list_item WHERE work_item_id = ?",
                    Integer.class, fixture.failedChildId())).isZero();
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_reference_binding WHERE work_item_id = ?",
                    Integer.class, fixture.failedChildId())).isZero();
            softly.assertThat(jdbc.queryForList("""
                            SELECT id, work_item_id, attempt_number, status, failure_type, created_at, completed_at,
                                   validation_error_code, validation_error_path, validation_error_message
                            FROM structured_generation_attempt
                            WHERE work_item_id = ? ORDER BY attempt_number
                            """, fixture.failedChildId())).isEqualTo(failedAttemptsBefore);
            softly.assertThat(jdbc.queryForList("""
                            SELECT id, work_item_id, attempt_number, status, failure_type, created_at, completed_at,
                                   validation_error_code, validation_error_path, validation_error_message
                            FROM structured_generation_attempt WHERE work_item_id = ? ORDER BY attempt_number
                            """, fixture.completedChildId())).isEqualTo(completedAttemptsBefore);
        });

        StructuredGenerationAcceptanceStore.WorkClaim thirdAttempt = store.claimRegistered(
                "task-1", fixture.failedChildId(), "explicit-retry-worker").orElseThrow();
        assertThat(thirdAttempt.attemptNumber()).isEqualTo(3);
        store.fail(thirdAttempt, "model_execution_failed");
        assertThat(store.claimRegistered(
                "task-1", fixture.failedChildId(), "automatic-worker-after-bound")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?",
                Integer.class, fixture.failedChildId())).isEqualTo(3);
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-ESR-009 */
    @Test
    void extractionModelFailureBeforeAutomaticBoundRemainsIneligible() {
        SplitExtractionRetryFixture fixture = exhaustedNetworkExtractionFixture();
        jdbc.update("DELETE FROM structured_generation_attempt WHERE work_item_id = ? AND attempt_number = 2",
                fixture.failedChildId());

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id = ?",
                String.class, fixture.failedChildId())).isEqualTo("FAILED");
    }

    /** [Req-ID]: REQ-ESR-009 */
    @Test
    void exhaustedModelFailureOutsideFunctionExtractionRemainsIneligible() {
        SplitExtractionRetryFixture fixture = exhaustedNetworkExtractionFixture();
        jdbc.update("""
                UPDATE structured_generation_work_item
                SET operation_name = 'FEATURE_SCOPE_RECONCILIATION_V2'
                WHERE id = ?
                """, fixture.failedChildId());

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-ESR-009 */
    @Test
    void exhaustedExtractionRejectsNullOrMixedHistoricalFailureTypes() {
        SplitExtractionRetryFixture fixture = exhaustedNetworkExtractionFixture();
        jdbc.update("""
                UPDATE structured_generation_attempt SET failure_type = NULL
                WHERE work_item_id = ? AND attempt_number = 1
                """, fixture.failedChildId());

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();

        jdbc.update("""
                UPDATE structured_generation_attempt SET failure_type = 'structured_output_invalid'
                WHERE work_item_id = ? AND attempt_number = 1
                """, fixture.failedChildId());
        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-009 */
    @Test
    void concurrentExhaustedNetworkExtractionRetriesHaveOneWinner() throws Exception {
        SplitExtractionRetryFixture fixture = exhaustedNetworkExtractionFixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> retry = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent retry start gate timed out");
                }
                return taskRepository.retryFailedBatches("task-1");
            };
            Future<Integer> first = executor.submit(retry);
            Future<Integer> second = executor.submit(retry);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id = ?",
                String.class, fixture.failedChildId())).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?",
                Integer.class, fixture.failedChildId())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_function_list_item WHERE work_item_id = ?",
                Integer.class, fixture.completedChildId())).isEqualTo(54);
    }

    /** [Req-ID]: REQ-ESR-001, REQ-ESR-009 */
    @Test
    void exhaustedNetworkExtractionRetryRejectsPartialFailedChild() {
        SplitExtractionRetryFixture fixture = exhaustedNetworkExtractionFixture();
        jdbc.update("""
                INSERT INTO structured_reference_binding
                (work_item_id, subject_key, subject_type, reference_type, reference_key)
                VALUES (?, 'partial-network-result', 'FUNCTION_LIST_ITEM', 'EVIDENCE', 'fn-unit-17')
                """, fixture.failedChildId());

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id = ?",
                String.class, fixture.failedChildId())).isEqualTo("FAILED");
    }

    /** [Req-ID]: REQ-ESR-001, REQ-ESR-004 */
    @ParameterizedTest
    @ValueSource(strings = {"missing-diagnostic", "missing-code", "unknown-code", "known-but-forbidden-code",
            "missing-path", "unsafe-path"})
    void explicitRetryRejectsBusinessValidationFailureWithoutAnAllowlistedSafeDiagnostic(String mutation) {
        StructuredValidationFailure failure = StructuredValidationFailure.of(
                StructuredValidationFailure.Code.REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                "$.requirement_facts[0].function");
        ExplicitRetryFixture fixture = explicitRetryFixture("business_validation_failed", failure);
        switch (mutation) {
            case "missing-diagnostic" -> jdbc.update("""
                    UPDATE structured_generation_attempt
                    SET validation_error_code = NULL, validation_error_path = NULL, validation_error_message = NULL
                    WHERE work_item_id = ? AND attempt_number = 1
                    """, fixture.failedWorkId());
            case "missing-code" -> jdbc.update("""
                    UPDATE structured_generation_attempt SET validation_error_code = ''
                    WHERE work_item_id = ? AND attempt_number = 1
                    """, fixture.failedWorkId());
            case "unknown-code" -> jdbc.update("""
                    UPDATE structured_generation_attempt SET validation_error_code = 'UNREVIEWED_VALIDATION_FAILURE'
                    WHERE work_item_id = ? AND attempt_number = 1
                    """, fixture.failedWorkId());
            case "known-but-forbidden-code" -> jdbc.update("""
                    UPDATE structured_generation_attempt SET validation_error_code = 'REVIEW_EVIDENCE_OUT_OF_SLICE'
                    WHERE work_item_id = ? AND attempt_number = 1
                    """, fixture.failedWorkId());
            case "missing-path" -> jdbc.update("""
                    UPDATE structured_generation_attempt SET validation_error_path = ''
                    WHERE work_item_id = ? AND attempt_number = 1
                    """, fixture.failedWorkId());
            case "unsafe-path" -> jdbc.update("""
                    UPDATE structured_generation_attempt SET validation_error_path = '$.requirement_facts[*].function'
                    WHERE work_item_id = ? AND attempt_number = 1
                    """, fixture.failedWorkId());
            default -> throw new IllegalArgumentException("Unknown mutation");
        }

        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.FAILED);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    fixture.failedWorkId())).isEqualTo("FAILED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class,
                    fixture.failedWorkId())).isEqualTo(1);
        });
    }

    /** [Req-ID]: REQ-ESR-001 */
    @Test
    void explicitRetryRejectsAFailedWorkThatAlreadyOwnsPartialRows() {
        StructuredValidationFailure failure = StructuredValidationFailure.of(
                StructuredValidationFailure.Code.REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                "$.requirement_facts[0].function");
        ExplicitRetryFixture fixture = explicitRetryFixture("business_validation_failed", failure);
        jdbc.update("""
                INSERT INTO structured_reference_binding
                (work_item_id, subject_key, subject_type, reference_type, reference_key)
                VALUES (?, 'partial-subject', 'REVIEW_FINDING', 'EVIDENCE', 'partial-evidence')
                """, fixture.failedWorkId());

        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();

        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                fixture.failedWorkId())).isEqualTo("FAILED");
    }

    /** [Req-ID]: REQ-ESR-002 */
    @Test
    void concurrentExplicitRetriesHaveOneWinnerAndDoNotPrecreateAnAttempt() throws Exception {
        StructuredValidationFailure failure = StructuredValidationFailure.of(
                StructuredValidationFailure.Code.REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                "$.requirement_facts[0].function");
        ExplicitRetryFixture fixture = explicitRetryFixture("business_validation_failed", failure);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> retry = () -> {
                ready.countDown();
                start.await();
                return taskRepository.retryFailedBatches("task-1");
            };
            Future<Integer> first = executor.submit(retry);
            Future<Integer> second = executor.submit(retry);
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class,
                fixture.failedWorkId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                fixture.failedWorkId())).isEqualTo("QUEUED");
    }

    /** [Req-ID]: REQ-ESR-007 */
    @Test
    void explicitRetryRecoversOnlyTheTaskForOneQueuedZeroWriteResidue() {
        ExplicitRetryFixture fixture = queuedRetryResidueFixture();
        jdbc.update("""
                UPDATE generation_task
                SET result_snapshot = JSON_OBJECT('preserved', TRUE),
                    artifact_id = 'artifact-preserved', artifact_sha256 = ?, artifact_path = 'preserved.xlsx'
                WHERE id = 'task-1'
                """, "a".repeat(64));
        Map<String, Object> workBefore = jdbc.queryForMap("""
                SELECT status, accepted_result_sha256, lease_owner, lease_expires_at,
                       validation_error_code, validation_error_path, validation_error_message
                FROM structured_generation_work_item WHERE id = ?
                """, fixture.failedWorkId());
        List<Map<String, Object>> attemptsBefore = jdbc.queryForList("""
                SELECT attempt_number, status, failure_type, validation_error_code, validation_error_path
                FROM structured_generation_attempt WHERE work_item_id = ? ORDER BY attempt_number
                """, fixture.failedWorkId());

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
            softly.assertThat(jdbc.queryForMap("""
                            SELECT structured_processing_status, structured_coverage_status, result_snapshot,
                                   artifact_id, artifact_sha256, artifact_path
                            FROM generation_task WHERE id = 'task-1'
                            """))
                    .containsEntry("structured_processing_status", "PENDING")
                    .containsEntry("structured_coverage_status", "PENDING")
                    .containsEntry("artifact_id", "artifact-preserved")
                    .containsEntry("artifact_sha256", "a".repeat(64))
                    .containsEntry("artifact_path", "preserved.xlsx");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT JSON_UNQUOTE(JSON_EXTRACT(result_snapshot, '$.preserved')) FROM generation_task WHERE id = 'task-1'",
                    String.class)).isEqualTo("true");
            softly.assertThat(jdbc.queryForMap("""
                    SELECT status, accepted_result_sha256, lease_owner, lease_expires_at,
                           validation_error_code, validation_error_path, validation_error_message
                    FROM structured_generation_work_item WHERE id = ?
                    """, fixture.failedWorkId())).isEqualTo(workBefore);
            softly.assertThat(jdbc.queryForList("""
                    SELECT attempt_number, status, failure_type, validation_error_code, validation_error_path
                    FROM structured_generation_attempt WHERE work_item_id = ? ORDER BY attempt_number
                    """, fixture.failedWorkId())).isEqualTo(attemptsBefore);
            softly.assertThat(jdbc.queryForList(
                            "SELECT accepted_result_sha256 FROM structured_generation_work_item WHERE id IN (?, ?)",
                            String.class, fixture.completedWorkIds().get(0), fixture.completedWorkIds().get(1)))
                    .containsExactlyInAnyOrder("1".repeat(64), "2".repeat(64));
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_review_finding WHERE task_id = 'task-1'", Integer.class))
                    .isEqualTo(6);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_reference_binding binding
                    JOIN structured_generation_work_item work ON work.id = binding.work_item_id
                    WHERE work.task_id = 'task-1'
                    """, Integer.class)).isEqualTo(48);
        });
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-007 */
    @Test
    void concurrentQueuedResidueRetriesHaveOneTaskOnlyWinner() throws Exception {
        ExplicitRetryFixture fixture = queuedRetryResidueFixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> retry = () -> {
                ready.countDown();
                start.await();
                return taskRepository.retryFailedBatches("task-1");
            };
            Future<Integer> first = executor.submit(retry);
            Future<Integer> second = executor.submit(retry);
            ready.await();
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                fixture.failedWorkId())).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class,
                fixture.failedWorkId())).isEqualTo(1);
    }

    /** [Req-ID]: REQ-ESR-003, REQ-ESR-010 */
    @Test
    void explicitRetryResumesOnlyTheTaskForOneExpiredRunningReconciliationClaim() {
        ExplicitRetryFixture fixture = expiredRunningReconciliationResidueFixture();
        Map<String, Object> workBefore = jdbc.queryForMap("""
                SELECT status, accepted_result_sha256, lease_owner, lease_expires_at
                FROM structured_generation_work_item WHERE id = ?
                """, fixture.failedWorkId());
        List<Map<String, Object>> attemptsBefore = jdbc.queryForList("""
                SELECT attempt_number, status, failure_type, completed_at
                FROM structured_generation_attempt WHERE work_item_id = ? ORDER BY attempt_number
                """, fixture.failedWorkId());
        List<Map<String, Object>> stagingBefore = jdbc.queryForList("""
                SELECT run.run_key, run.catalog_sha256, run.status AS run_status,
                       page.page_key, page.status AS page_status
                FROM structured_reconciliation_run run
                JOIN structured_reconciliation_page_stage page ON page.work_item_id = run.work_item_id
                WHERE run.work_item_id = ? ORDER BY page.page_key
                """, fixture.failedWorkId());

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
            softly.assertThat(jdbc.queryForMap("""
                    SELECT status, accepted_result_sha256, lease_owner, lease_expires_at
                    FROM structured_generation_work_item WHERE id = ?
                    """, fixture.failedWorkId())).isEqualTo(workBefore);
            softly.assertThat(jdbc.queryForList("""
                    SELECT attempt_number, status, failure_type, completed_at
                    FROM structured_generation_attempt WHERE work_item_id = ? ORDER BY attempt_number
                    """, fixture.failedWorkId())).isEqualTo(attemptsBefore);
            softly.assertThat(jdbc.queryForList("""
                    SELECT run.run_key, run.catalog_sha256, run.status AS run_status,
                           page.page_key, page.status AS page_status
                    FROM structured_reconciliation_run run
                    JOIN structured_reconciliation_page_stage page ON page.work_item_id = run.work_item_id
                    WHERE run.work_item_id = ? ORDER BY page.page_key
                    """, fixture.failedWorkId())).isEqualTo(stagingBefore);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT accepted_result_sha256 FROM structured_generation_work_item WHERE id = ?",
                    String.class, fixture.completedWorkIds().get(0))).isEqualTo("9".repeat(64));
        });
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();

        StructuredGenerationAcceptanceStore.WorkClaim recovered = store.claimRegistered(
                "task-1", fixture.failedWorkId(), "reconciliation-worker-2").orElseThrow();
        assertThat(recovered.attemptNumber()).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT attempt_number, status, failure_type
                FROM structured_generation_attempt WHERE work_item_id = ? ORDER BY attempt_number
                """, fixture.failedWorkId()))
                .extracting(row -> row.get("attempt_number"), row -> row.get("status"), row -> row.get("failure_type"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, "FAILED", "worker_interrupted"),
                        org.assertj.core.groups.Tuple.tuple(2L, "RUNNING", null));
    }

    /** [Req-ID]: REQ-ESR-010 */
    @ParameterizedTest
    @ValueSource(strings = {"unexpired", "missing-owner", "second-running-attempt", "accepted-hash",
            "partial-binding", "second-unfinished", "wrong-operation"})
    void expiredRunningReconciliationRecoveryRejectsEveryNearState(String mutation) {
        ExplicitRetryFixture fixture = expiredRunningReconciliationResidueFixture();
        switch (mutation) {
            case "unexpired" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET lease_expires_at = ? WHERE id = ?
                    """, java.sql.Timestamp.from(Instant.now().plus(Duration.ofMinutes(5))), fixture.failedWorkId());
            case "missing-owner" -> jdbc.update(
                    "UPDATE structured_generation_work_item SET lease_owner = NULL WHERE id = ?",
                    fixture.failedWorkId());
            case "second-running-attempt" -> jdbc.update("""
                    INSERT INTO structured_generation_attempt (id, work_item_id, attempt_number, status)
                    VALUES (UUID(), ?, 2, 'RUNNING')
                    """, fixture.failedWorkId());
            case "accepted-hash" -> jdbc.update(
                    "UPDATE structured_generation_work_item SET accepted_result_sha256 = ? WHERE id = ?",
                    "f".repeat(64), fixture.failedWorkId());
            case "partial-binding" -> jdbc.update("""
                    INSERT INTO structured_reference_binding
                    (work_item_id, subject_key, subject_type, reference_type, reference_key)
                    VALUES (?, 'partial-v2', 'RECONCILIATION', 'EVIDENCE', 'evidence-1')
                    """, fixture.failedWorkId());
            case "second-unfinished" -> store.register(reviewWork("e".repeat(64)));
            case "wrong-operation" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET operation_name = 'FEATURE_SCOPE_RECONCILIATION'
                    WHERE id = ?
                    """, fixture.failedWorkId());
            default -> throw new IllegalArgumentException("Unknown mutation");
        }

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
        assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.FAILED);
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-010 */
    @Test
    void concurrentExpiredRunningReconciliationRetriesHaveOneTaskOnlyWinner() throws Exception {
        ExplicitRetryFixture fixture = expiredRunningReconciliationResidueFixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> retry = () -> {
                ready.countDown();
                start.await();
                return taskRepository.retryFailedBatches("task-1");
            };
            Future<Integer> first = executor.submit(retry);
            Future<Integer> second = executor.submit(retry);
            ready.await();
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                fixture.failedWorkId())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class,
                fixture.failedWorkId())).isEqualTo(1);
    }

    /** [Req-ID]: REQ-ESR-010 */
    @Test
    void expiredRunningReconciliationRetryRollsBackWithItsOuterTransaction() {
        ExplicitRetryFixture fixture = expiredRunningReconciliationResidueFixture();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
            throw new IllegalStateException("force-test-rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.FAILED);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    fixture.failedWorkId())).isEqualTo("RUNNING");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class,
                    fixture.failedWorkId())).isEqualTo(1);
        });
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-003, REQ-ESR-011 */
    @Test
    void explicitRetryRebuildsOnlyTheInvalidCompletedReconciliationStagingRun() {
        InvalidReconciliationStagingFixture fixture = invalidCompletedReconciliationStagingFixture();
        List<Map<String, Object>> attemptsBefore = jdbc.queryForList("""
                SELECT attempt_number, status, failure_type, validation_error_code, validation_error_path
                FROM structured_generation_attempt WHERE work_item_id = ? ORDER BY attempt_number
                """, fixture.failedWorkId());
        Map<String, Integer> upstreamBefore = Map.of(
                "facts", countForTask("structured_requirement_fact"),
                "findings", countForTask("structured_review_finding"),
                "functions", countForTask("structured_function_list_item"),
                "bindings", jdbc.queryForObject("""
                        SELECT COUNT(*) FROM structured_reference_binding binding
                        JOIN structured_generation_work_item work ON work.id = binding.work_item_id
                        WHERE work.task_id = 'task-1'
                        """, Integer.class));

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    fixture.failedWorkId())).isEqualTo("QUEUED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_reconciliation_run WHERE work_item_id = ?", Integer.class,
                    fixture.failedWorkId())).isZero();
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_reconciliation_page_stage WHERE work_item_id = ?", Integer.class,
                    fixture.failedWorkId())).isZero();
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_reconciliation_relation_stage WHERE work_item_id = ?", Integer.class,
                    fixture.failedWorkId())).isZero();
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_reconciliation_relation_stage_binding WHERE work_item_id = ?
                    """, Integer.class, fixture.failedWorkId())).isZero();
            softly.assertThat(jdbc.queryForList("""
                    SELECT attempt_number, status, failure_type, validation_error_code, validation_error_path
                    FROM structured_generation_attempt WHERE work_item_id = ? ORDER BY attempt_number
                    """, fixture.failedWorkId())).isEqualTo(attemptsBefore);
            softly.assertThat(Map.of(
                    "facts", countForTask("structured_requirement_fact"),
                    "findings", countForTask("structured_review_finding"),
                    "functions", countForTask("structured_function_list_item"),
                    "bindings", jdbc.queryForObject("""
                            SELECT COUNT(*) FROM structured_reference_binding binding
                            JOIN structured_generation_work_item work ON work.id = binding.work_item_id
                            WHERE work.task_id = 'task-1'
                            """, Integer.class))).isEqualTo(upstreamBefore);
        });
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();

        StructuredGenerationAcceptanceStore.WorkClaim rebuiltClaim = store.claimRegistered(
                "task-1", fixture.failedWorkId(), "rebuilt-reconciliation-worker").orElseThrow();
        assertThat(rebuiltClaim.attemptNumber()).isEqualTo(2);
        store.initializeReconciliationRun(rebuiltClaim, fixture.plan());
        assertThat(store.pendingReconciliationPages(
                fixture.failedWorkId(), fixture.plan().run().runKey(), fixture.plan().run().catalogSha256()))
                .containsExactlyElementsOf(fixture.plan().initialOwnerWindows());
    }

    /** [Req-ID]: REQ-ESR-011 */
    @ParameterizedTest
    @ValueSource(strings = {"task-code", "task-path", "work-code", "work-path", "attempt-code",
            "attempt-path", "failure-type", "skill-name", "operation-name", "run-status", "run-hash",
            "zero-page", "second-run", "page-run-mismatch", "page-catalog-mismatch", "planned-page", "accepted-work",
            "lease", "running-attempt", "published-relation", "source-terminal", "downstream", "artifact",
            "artifact-hash-only", "artifact-path-only", "second-unfinished"})
    void invalidReconciliationStagingRecoveryRejectsEveryNearState(String mutation) {
        InvalidReconciliationStagingFixture fixture = invalidCompletedReconciliationStagingFixture();
        switch (mutation) {
            case "task-code" -> jdbc.update(
                    "UPDATE generation_task SET validation_error_code = 'REVIEW_FIELD_REQUIRED' WHERE id = 'task-1'");
            case "task-path" -> jdbc.update(
                    "UPDATE generation_task SET validation_error_path = '$.reconciliation_page' WHERE id = 'task-1'");
            case "work-code" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET validation_error_code = 'REVIEW_FIELD_REQUIRED'
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "work-path" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET validation_error_path = '$.reconciliation_page'
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "attempt-code" -> jdbc.update("""
                    UPDATE structured_generation_attempt SET validation_error_code = 'REVIEW_FIELD_REQUIRED'
                    WHERE work_item_id = ? AND attempt_number = 1
                    """, fixture.failedWorkId());
            case "attempt-path" -> jdbc.update("""
                    UPDATE structured_generation_attempt SET validation_error_path = '$.reconciliation_page'
                    WHERE work_item_id = ? AND attempt_number = 1
                    """, fixture.failedWorkId());
            case "failure-type" -> jdbc.update("""
                    UPDATE structured_generation_attempt SET failure_type = 'structured_output_invalid'
                    WHERE work_item_id = ? AND attempt_number = 1
                    """, fixture.failedWorkId());
            case "skill-name" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET skill_name = 'requirement-material-quality-review'
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "operation-name" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET operation_name = 'FEATURE_SCOPE_EXTRACT'
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "run-status" -> jdbc.update(
                    "UPDATE structured_reconciliation_run SET status = 'PUBLISHED' WHERE work_item_id = ?",
                    fixture.failedWorkId());
            case "run-hash" -> jdbc.update("""
                    UPDATE structured_reconciliation_run SET accepted_result_sha256 = ? WHERE work_item_id = ?
                    """, "6".repeat(64), fixture.failedWorkId());
            case "zero-page" -> jdbc.update(
                    "DELETE FROM structured_reconciliation_page_stage WHERE work_item_id = ?", fixture.failedWorkId());
            case "second-run" -> jdbc.update("""
                    INSERT INTO structured_reconciliation_run
                    (work_item_id, task_id, run_key, catalog_sha256, function_item_count, requirement_fact_count,
                     catalog_source_refs_json, initial_page_keys_json, status)
                    VALUES (?, 'task-1', ?, ?, 1, 0, JSON_ARRAY(), JSON_ARRAY(), 'STAGING')
                    """, fixture.completedWorkId(), sha256Text("second-run"), sha256Text("second-catalog"));
            case "page-run-mismatch" -> jdbc.update("""
                    UPDATE structured_reconciliation_page_stage SET run_key = ?
                    WHERE work_item_id = ? ORDER BY page_key LIMIT 1
                    """, sha256Text("mismatched-page-run"), fixture.failedWorkId());
            case "page-catalog-mismatch" -> jdbc.update("""
                    UPDATE structured_reconciliation_page_stage SET catalog_sha256 = ?
                    WHERE work_item_id = ? ORDER BY page_key LIMIT 1
                    """, sha256Text("mismatched-page-catalog"), fixture.failedWorkId());
            case "planned-page" -> jdbc.update("""
                    UPDATE structured_reconciliation_page_stage
                    SET status = 'PLANNED', completed_owner_source_refs_json = NULL,
                        result_sha256 = NULL, completed_at = NULL
                    WHERE work_item_id = ? ORDER BY page_key LIMIT 1
                    """, fixture.failedWorkId());
            case "accepted-work" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET accepted_result_sha256 = ? WHERE id = ?
                    """, "5".repeat(64), fixture.failedWorkId());
            case "lease" -> jdbc.update("""
                    UPDATE structured_generation_work_item
                    SET lease_owner = 'stale', lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 MINUTE)
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "running-attempt" -> jdbc.update("""
                    INSERT INTO structured_generation_attempt (id, work_item_id, attempt_number, status)
                    VALUES (UUID(), ?, 2, 'RUNNING')
                    """, fixture.failedWorkId());
            case "published-relation" -> jdbc.update("""
                    INSERT INTO structured_feature_reconciliation
                    (work_item_id, task_id, reconciliation_key, classification, scope_recommendation, confirmation_status)
                    VALUES (?, 'task-1', ?, 'EXACT_MATCH', '已发布', 'CONFIRMED')
                    """, fixture.failedWorkId(), "4".repeat(64));
            case "source-terminal" -> jdbc.update("""
                    INSERT INTO structured_reconciliation_source_terminal
                    (task_id, source_type, source_key, work_item_id, run_key)
                    VALUES ('task-1', 'function_list_item', 'terminal-item', ?, ?)
                    """, fixture.failedWorkId(), sha256Text("invalid-staging-run"));
            case "downstream" -> jdbc.update("""
                    INSERT INTO structured_test_point
                    (work_item_id, task_id, test_point_key, function_key, function_name, test_point_type,
                     basis, description, missing_information_json, formal_coverage_satisfied)
                    VALUES (?, 'task-1', 'point-near-state', 'function-near-state', '功能', 'NORMAL_BEHAVIOR',
                            'FORMAL_REQUIREMENT', '说明', JSON_ARRAY(), FALSE)
                    """, fixture.failedWorkId());
            case "artifact" -> jdbc.update(
                    "UPDATE generation_task SET artifact_id = UUID() WHERE id = 'task-1'");
            case "artifact-hash-only" -> jdbc.update(
                    "UPDATE generation_task SET artifact_sha256 = ? WHERE id = 'task-1'", "a".repeat(64));
            case "artifact-path-only" -> jdbc.update(
                    "UPDATE generation_task SET artifact_path = 'retained.xlsx' WHERE id = 'task-1'");
            case "second-unfinished" -> store.register(reviewWork("3".repeat(64)));
            default -> throw new IllegalArgumentException("Unknown mutation");
        }

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
        assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.FAILED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_reconciliation_page_stage WHERE work_item_id = ?", Integer.class,
                fixture.failedWorkId())).isEqualTo("zero-page".equals(mutation) ? 0 : 8);
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-011 */
    @Test
    void concurrentInvalidReconciliationStagingRetriesHaveOneWinner() throws Exception {
        InvalidReconciliationStagingFixture fixture = invalidCompletedReconciliationStagingFixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> retry = () -> {
                ready.countDown();
                start.await();
                return taskRepository.retryFailedBatches("task-1");
            };
            Future<Integer> first = executor.submit(retry);
            Future<Integer> second = executor.submit(retry);
            ready.await();
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                fixture.failedWorkId())).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class,
                fixture.failedWorkId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_reconciliation_run WHERE work_item_id = ?", Integer.class,
                fixture.failedWorkId())).isZero();
    }

    /** [Req-ID]: REQ-ESR-011 */
    @Test
    void invalidReconciliationStagingRebuildRollsBackWithItsOuterTransaction() {
        InvalidReconciliationStagingFixture fixture = invalidCompletedReconciliationStagingFixture();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
            throw new IllegalStateException("force-test-rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.FAILED);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    fixture.failedWorkId())).isEqualTo("FAILED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_reconciliation_run WHERE work_item_id = ?", Integer.class,
                    fixture.failedWorkId())).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_reconciliation_page_stage WHERE work_item_id = ?", Integer.class,
                    fixture.failedWorkId())).isEqualTo(8);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_reconciliation_relation_stage WHERE work_item_id = ?", Integer.class,
                    fixture.failedWorkId())).isEqualTo(8);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_reconciliation_relation_stage_binding WHERE work_item_id = ?
                    """, Integer.class, fixture.failedWorkId())).isEqualTo(16);
        });
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-003, REQ-ESR-012 */
    @Test
    void explicitRetryResumesOnlyPlannedPagesAfterAZeroWriteReconciliationModelFailure() {
        ReconciliationModelFailureFixture fixture = zeroWriteReconciliationModelFailureFixture();
        List<Map<String, Object>> runBefore = reconciliationRunSnapshot(fixture.failedWorkId());
        List<Map<String, Object>> pagesBefore = reconciliationPageSnapshot(fixture.failedWorkId());
        List<Map<String, Object>> relationsBefore = reconciliationRelationSnapshot(fixture.failedWorkId());
        List<Map<String, Object>> stageBindingsBefore = reconciliationStageBindingSnapshot(fixture.failedWorkId());
        List<Map<String, Object>> attemptsBefore = attemptSnapshot(fixture.failedWorkId());

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    fixture.failedWorkId())).isEqualTo("QUEUED");
            softly.assertThat(reconciliationRunSnapshot(fixture.failedWorkId())).isEqualTo(runBefore);
            softly.assertThat(reconciliationPageSnapshot(fixture.failedWorkId())).isEqualTo(pagesBefore);
            softly.assertThat(reconciliationRelationSnapshot(fixture.failedWorkId())).isEqualTo(relationsBefore);
            softly.assertThat(reconciliationStageBindingSnapshot(fixture.failedWorkId()))
                    .isEqualTo(stageBindingsBefore);
            softly.assertThat(attemptSnapshot(fixture.failedWorkId())).isEqualTo(attemptsBefore);
        });
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();

        StructuredGenerationAcceptanceStore.WorkClaim recovered = store.claimRegistered(
                "task-1", fixture.failedWorkId(), "reconciliation-model-recovery-worker").orElseThrow();
        assertThat(recovered.attemptNumber()).isEqualTo(2);
        assertThat(store.pendingReconciliationPages(
                fixture.failedWorkId(), fixture.plan().run().runKey(), fixture.plan().run().catalogSha256()))
                .containsExactlyElementsOf(fixture.plannedWindows());
        assertThat(attemptSnapshot(fixture.failedWorkId()))
                .extracting(row -> row.get("attempt_number"), row -> row.get("status"), row -> row.get("failure_type"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, "FAILED", "model_execution_failed"),
                        org.assertj.core.groups.Tuple.tuple(2L, "RUNNING", null));
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-003, REQ-ESR-013 */
    @Test
    void explicitRetryResumesOnlyPlannedPagesAfterAZeroWriteReconciliationStructuralFailure() {
        ReconciliationModelFailureFixture fixture = zeroWriteReconciliationStructuralFailureFixture();
        ReconciliationRetrySnapshot before = reconciliationRetrySnapshot(fixture.failedWorkId());
        List<Map<String, Object>> attemptsBefore = attemptSnapshot(fixture.failedWorkId());

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    fixture.failedWorkId())).isEqualTo("QUEUED");
            softly.assertThat(reconciliationRunSnapshot(fixture.failedWorkId())).isEqualTo(before.runs());
            softly.assertThat(reconciliationPageSnapshot(fixture.failedWorkId())).isEqualTo(before.pages());
            softly.assertThat(reconciliationRelationSnapshot(fixture.failedWorkId())).isEqualTo(before.relations());
            softly.assertThat(reconciliationStageBindingSnapshot(fixture.failedWorkId()))
                    .isEqualTo(before.stageBindings());
            softly.assertThat(attemptSnapshot(fixture.failedWorkId())).isEqualTo(attemptsBefore);
        });

        StructuredGenerationAcceptanceStore.WorkClaim recovered = store.claimRegistered(
                "task-1", fixture.failedWorkId(), "reconciliation-structural-recovery-worker").orElseThrow();
        assertThat(recovered.attemptNumber()).isEqualTo(2);
        assertThat(store.pendingReconciliationPages(
                fixture.failedWorkId(), fixture.plan().run().runKey(), fixture.plan().run().catalogSha256()))
                .containsExactlyElementsOf(fixture.plannedWindows());
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-013 */
    @ParameterizedTest
    @CsvSource({"5,1,1,false", "17,4,2,true", "31,7,3,false"})
    void reconciliationStructuralFailureRetryUsesProtocolStateRatherThanOneBusinessSample(
            int sourceCount, int ownerWindowSize, int completedPageCount, boolean emptyCompletedPage) {
        ReconciliationModelFailureFixture fixture = zeroWriteReconciliationFailureFixture(
                "structured_output_invalid", sourceCount, ownerWindowSize, completedPageCount);
        if (emptyCompletedPage) {
            String completedPage = jdbc.queryForObject("""
                    SELECT page_key FROM structured_reconciliation_page_stage
                    WHERE work_item_id = ? AND status = 'COMPLETED'
                    ORDER BY first_source_type, first_source_key LIMIT 1
                    """, String.class, fixture.failedWorkId());
            jdbc.update("""
                    DELETE FROM structured_reconciliation_relation_stage_binding
                    WHERE work_item_id = ? AND reconciliation_key IN (
                        SELECT reconciliation_key FROM structured_reconciliation_relation_stage
                        WHERE work_item_id = ? AND page_key = ?)
                    """, fixture.failedWorkId(), fixture.failedWorkId(), completedPage);
            jdbc.update("""
                    DELETE FROM structured_reconciliation_relation_stage
                    WHERE work_item_id = ? AND page_key = ?
                    """, fixture.failedWorkId(), completedPage);
        }
        ReconciliationRetrySnapshot before = reconciliationRetrySnapshot(fixture.failedWorkId());

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(reconciliationRunSnapshot(fixture.failedWorkId())).isEqualTo(before.runs());
            softly.assertThat(reconciliationPageSnapshot(fixture.failedWorkId())).isEqualTo(before.pages());
            softly.assertThat(reconciliationRelationSnapshot(fixture.failedWorkId())).isEqualTo(before.relations());
            softly.assertThat(reconciliationStageBindingSnapshot(fixture.failedWorkId()))
                    .isEqualTo(before.stageBindings());
        });
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-013 */
    @ParameterizedTest
    @ValueSource(strings = {"accepted-work", "lease", "running-attempt", "second-unfinished", "slot",
            "wrong-skill", "wrong-operation", "published-run", "run-hash", "no-planned", "planned-parent",
            "planned-page-stage", "split-page-stage", "partial-binding",
            "candidate-source-outcome", "candidate", "candidate-link", "published-relation", "source-terminal",
            "downstream", "artifact-id", "artifact-hash", "artifact-path"})
    void zeroWriteReconciliationStructuralFailureRetryRejectsEveryNearState(String mutation) {
        ReconciliationModelFailureFixture fixture = zeroWriteReconciliationStructuralFailureFixture();
        switch (mutation) {
            case "accepted-work" -> jdbc.update(
                    "UPDATE structured_generation_work_item SET accepted_result_sha256 = ? WHERE id = ?",
                    "f".repeat(64), fixture.failedWorkId());
            case "lease" -> jdbc.update("""
                    UPDATE structured_generation_work_item
                    SET lease_owner = 'stale', lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 MINUTE)
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "running-attempt" -> jdbc.update("""
                    INSERT INTO structured_generation_attempt (id, work_item_id, attempt_number, status)
                    VALUES (UUID(), ?, 2, 'RUNNING')
                    """, fixture.failedWorkId());
            case "second-unfinished" -> store.register(reviewWork("2".repeat(64)));
            case "slot" -> jdbc.update("UPDATE task_execution_slot SET task_id = 'task-1' WHERE slot_number = 1");
            case "wrong-skill" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET skill_name = 'requirement-material-quality-review'
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "wrong-operation" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET operation_name = 'FEATURE_SCOPE_EXTRACT' WHERE id = ?
                    """, fixture.failedWorkId());
            case "published-run" -> jdbc.update(
                    "UPDATE structured_reconciliation_run SET status = 'PUBLISHED' WHERE work_item_id = ?",
                    fixture.failedWorkId());
            case "run-hash" -> jdbc.update("""
                    UPDATE structured_reconciliation_run SET accepted_result_sha256 = ? WHERE work_item_id = ?
                    """, "e".repeat(64), fixture.failedWorkId());
            case "no-planned" -> jdbc.update("""
                    UPDATE structured_reconciliation_page_stage
                    SET status = 'COMPLETED', completed_owner_source_refs_json = owner_source_refs_json,
                        result_sha256 = ?, completed_at = CURRENT_TIMESTAMP(6)
                    WHERE work_item_id = ? AND status = 'PLANNED'
                    """, "d".repeat(64), fixture.failedWorkId());
            case "planned-parent" -> jdbc.update("""
                    INSERT INTO structured_reconciliation_page_stage
                    (work_item_id, page_key, run_key, catalog_sha256, parent_page_key, status,
                     first_source_type, first_source_key, owner_source_refs_json)
                    SELECT work_item_id, ?, run_key, catalog_sha256, page_key, 'PLANNED',
                           first_source_type, first_source_key, owner_source_refs_json
                    FROM structured_reconciliation_page_stage
                    WHERE work_item_id = ? AND status = 'PLANNED'
                    ORDER BY first_source_type, first_source_key LIMIT 1
                    """, "9".repeat(64), fixture.failedWorkId());
            case "planned-page-stage" -> insertReconciliationStageForPageStatus(
                    fixture.failedWorkId(), "PLANNED", "c".repeat(64));
            case "split-page-stage" -> {
                jdbc.update("""
                        UPDATE structured_reconciliation_page_stage SET status = 'SPLIT'
                        WHERE work_item_id = ? AND status = 'PLANNED'
                        ORDER BY first_source_type, first_source_key LIMIT 1
                        """, fixture.failedWorkId());
                insertReconciliationStageForPageStatus(fixture.failedWorkId(), "SPLIT", "c".repeat(64));
            }
            case "partial-binding" -> jdbc.update("""
                    INSERT INTO structured_reference_binding
                    (work_item_id, subject_key, subject_type, reference_type, reference_key)
                    VALUES (?, 'partial-v2', 'RECONCILIATION', 'EVIDENCE', 'evidence-1')
                    """, fixture.failedWorkId());
            case "candidate-source-outcome" -> insertCandidateSourceOutcome(fixture.failedWorkId());
            case "candidate" -> insertCandidate(fixture.failedWorkId());
            case "candidate-link" -> {
                insertCandidateSourceOutcome(fixture.failedWorkId());
                insertCandidate(fixture.failedWorkId());
                jdbc.update("""
                        INSERT INTO structured_function_outcome_candidate (work_item_id, unit_key, candidate_ref)
                        VALUES (?, 'partial-unit', ?)
                        """, fixture.failedWorkId(), "8".repeat(64));
            }
            case "published-relation" -> jdbc.update("""
                    INSERT INTO structured_feature_reconciliation
                    (work_item_id, task_id, reconciliation_key, classification, scope_recommendation, confirmation_status)
                    VALUES (?, 'task-1', ?, 'EXACT_MATCH', '不应存在', 'CONFIRMED')
                    """, fixture.failedWorkId(), "b".repeat(64));
            case "source-terminal" -> jdbc.update("""
                    INSERT INTO structured_reconciliation_source_terminal
                    (task_id, source_type, source_key, work_item_id, run_key)
                    VALUES ('task-1', 'function_list_item', 'terminal-item', ?, ?)
                    """, fixture.failedWorkId(), fixture.plan().run().runKey());
            case "downstream" -> jdbc.update("""
                    INSERT INTO structured_test_point
                    (work_item_id, task_id, test_point_key, function_key, function_name, test_point_type,
                     basis, description, missing_information_json, formal_coverage_satisfied)
                    VALUES (?, 'task-1', 'point-near-state', 'function-near-state', '功能', 'NORMAL_BEHAVIOR',
                            'FORMAL_REQUIREMENT', '说明', JSON_ARRAY(), FALSE)
                    """, fixture.failedWorkId());
            case "artifact-id" -> jdbc.update(
                    "UPDATE generation_task SET artifact_id = UUID() WHERE id = 'task-1'");
            case "artifact-hash" -> jdbc.update(
                    "UPDATE generation_task SET artifact_sha256 = ? WHERE id = 'task-1'", "a".repeat(64));
            case "artifact-path" -> jdbc.update(
                    "UPDATE generation_task SET artifact_path = 'retained.xlsx' WHERE id = 'task-1'");
            default -> throw new IllegalArgumentException("Unknown mutation");
        }
        ReconciliationRetrySnapshot before = reconciliationRetrySnapshot(fixture.failedWorkId());

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
        assertThat(reconciliationRetrySnapshot(fixture.failedWorkId())).isEqualTo(before);
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-013 */
    @ParameterizedTest
    @ValueSource(strings = {"completed-without-hash", "completed-without-owners", "planned-with-hash"})
    void reconciliationPageCompletionMetadataNearStatesAreRejectedByDatabaseConstraint(String mutation) {
        ReconciliationModelFailureFixture fixture = zeroWriteReconciliationStructuralFailureFixture();

        assertThatThrownBy(() -> {
            switch (mutation) {
                case "completed-without-hash" -> jdbc.update("""
                        UPDATE structured_reconciliation_page_stage SET result_sha256 = NULL
                        WHERE work_item_id = ? AND status = 'COMPLETED'
                        ORDER BY first_source_type, first_source_key LIMIT 1
                        """, fixture.failedWorkId());
                case "completed-without-owners" -> jdbc.update("""
                        UPDATE structured_reconciliation_page_stage SET completed_owner_source_refs_json = NULL
                        WHERE work_item_id = ? AND status = 'COMPLETED'
                        ORDER BY first_source_type, first_source_key LIMIT 1
                        """, fixture.failedWorkId());
                case "planned-with-hash" -> jdbc.update("""
                        UPDATE structured_reconciliation_page_stage SET result_sha256 = ?
                        WHERE work_item_id = ? AND status = 'PLANNED'
                        ORDER BY first_source_type, first_source_key LIMIT 1
                        """, "c".repeat(64), fixture.failedWorkId());
                default -> throw new IllegalArgumentException("Unknown mutation");
            }
        }).isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class)
                .hasMessageContaining("chk_structured_reconciliation_page_completion");
    }

    private void insertReconciliationStageForPageStatus(String workItemId, String status, String relationKey) {
        jdbc.update("""
                INSERT INTO structured_reconciliation_relation_stage
                (work_item_id, page_key, reconciliation_key, owner_source_type, owner_source_key,
                 classification, scope_recommendation, confirmation_status)
                SELECT work_item_id, page_key, ?, first_source_type, first_source_key,
                       'function_list_only', '不可保留的未完成页结果', 'confirmed'
                FROM structured_reconciliation_page_stage
                WHERE work_item_id = ? AND status = ?
                ORDER BY first_source_type, first_source_key LIMIT 1
                """, relationKey, workItemId, status);
    }

    /** [Req-ID]: REQ-ESR-012 */
    @ParameterizedTest
    @ValueSource(strings = {"accepted-work", "lease", "running-attempt", "second-unfinished", "wrong-skill",
            "wrong-operation", "business-validation", "published-run", "run-hash",
            "no-planned", "planned-parent", "planned-page-stage", "partial-binding", "candidate-source-outcome",
            "candidate", "candidate-link", "published-relation", "source-terminal", "downstream", "artifact-id",
            "artifact-hash", "artifact-path"})
    void zeroWriteReconciliationModelFailureRetryRejectsEveryNearState(String mutation) {
        ReconciliationModelFailureFixture fixture = zeroWriteReconciliationModelFailureFixture();
        switch (mutation) {
            case "accepted-work" -> jdbc.update(
                    "UPDATE structured_generation_work_item SET accepted_result_sha256 = ? WHERE id = ?",
                    "f".repeat(64), fixture.failedWorkId());
            case "lease" -> jdbc.update("""
                    UPDATE structured_generation_work_item
                    SET lease_owner = 'stale', lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 MINUTE)
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "running-attempt" -> jdbc.update("""
                    INSERT INTO structured_generation_attempt (id, work_item_id, attempt_number, status)
                    VALUES (UUID(), ?, 2, 'RUNNING')
                    """, fixture.failedWorkId());
            case "second-unfinished" -> store.register(reviewWork("2".repeat(64)));
            case "wrong-skill" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET skill_name = 'requirement-material-quality-review'
                    WHERE id = ?
                    """, fixture.failedWorkId());
            case "wrong-operation" -> jdbc.update("""
                    UPDATE structured_generation_work_item SET operation_name = 'FEATURE_SCOPE_EXTRACT' WHERE id = ?
                    """, fixture.failedWorkId());
            case "business-validation" -> jdbc.update("""
                    UPDATE structured_generation_attempt
                    SET failure_type = 'business_validation_failed',
                        validation_error_code = 'REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED',
                        validation_error_path = '$.requirement_facts[0].function',
                        validation_error_message = 'Requirement fact field is not directly supported by cited evidence'
                    WHERE work_item_id = ? AND attempt_number = 1
                    """, fixture.failedWorkId());
            case "published-run" -> jdbc.update(
                    "UPDATE structured_reconciliation_run SET status = 'PUBLISHED' WHERE work_item_id = ?",
                    fixture.failedWorkId());
            case "run-hash" -> jdbc.update("""
                    UPDATE structured_reconciliation_run SET accepted_result_sha256 = ? WHERE work_item_id = ?
                    """, "e".repeat(64), fixture.failedWorkId());
            case "no-planned" -> jdbc.update("""
                    UPDATE structured_reconciliation_page_stage
                    SET status = 'COMPLETED', completed_owner_source_refs_json = owner_source_refs_json,
                        result_sha256 = ?, completed_at = CURRENT_TIMESTAMP(6)
                    WHERE work_item_id = ? AND status = 'PLANNED'
                    """, "d".repeat(64), fixture.failedWorkId());
            case "planned-parent" -> jdbc.update("""
                    INSERT INTO structured_reconciliation_page_stage
                    (work_item_id, page_key, run_key, catalog_sha256, parent_page_key, status,
                     first_source_type, first_source_key, owner_source_refs_json)
                    SELECT work_item_id, ?, run_key, catalog_sha256, page_key, 'PLANNED',
                           first_source_type, first_source_key, owner_source_refs_json
                    FROM structured_reconciliation_page_stage
                    WHERE work_item_id = ? AND status = 'PLANNED'
                    ORDER BY first_source_type, first_source_key LIMIT 1
                    """, "9".repeat(64), fixture.failedWorkId());
            case "planned-page-stage" -> jdbc.update("""
                    INSERT INTO structured_reconciliation_relation_stage
                    (work_item_id, page_key, reconciliation_key, owner_source_type, owner_source_key,
                     classification, scope_recommendation, confirmation_status)
                    SELECT work_item_id, page_key, ?, first_source_type, first_source_key,
                           'function_list_only', '不可保留的未完成页结果', 'confirmed'
                    FROM structured_reconciliation_page_stage
                    WHERE work_item_id = ? AND status = 'PLANNED'
                    ORDER BY first_source_type, first_source_key LIMIT 1
                    """, "c".repeat(64), fixture.failedWorkId());
            case "partial-binding" -> jdbc.update("""
                    INSERT INTO structured_reference_binding
                    (work_item_id, subject_key, subject_type, reference_type, reference_key)
                    VALUES (?, 'partial-v2', 'RECONCILIATION', 'EVIDENCE', 'evidence-1')
                    """, fixture.failedWorkId());
            case "candidate-source-outcome" -> insertCandidateSourceOutcome(fixture.failedWorkId());
            case "candidate" -> insertCandidate(fixture.failedWorkId());
            case "candidate-link" -> {
                insertCandidateSourceOutcome(fixture.failedWorkId());
                insertCandidate(fixture.failedWorkId());
                jdbc.update("""
                        INSERT INTO structured_function_outcome_candidate (work_item_id, unit_key, candidate_ref)
                        VALUES (?, 'partial-unit', ?)
                        """, fixture.failedWorkId(), "8".repeat(64));
            }
            case "published-relation" -> jdbc.update("""
                    INSERT INTO structured_feature_reconciliation
                    (work_item_id, task_id, reconciliation_key, classification, scope_recommendation, confirmation_status)
                    VALUES (?, 'task-1', ?, 'EXACT_MATCH', '不应存在', 'CONFIRMED')
                    """, fixture.failedWorkId(), "b".repeat(64));
            case "source-terminal" -> jdbc.update("""
                    INSERT INTO structured_reconciliation_source_terminal
                    (task_id, source_type, source_key, work_item_id, run_key)
                    VALUES ('task-1', 'function_list_item', 'terminal-item', ?, ?)
                    """, fixture.failedWorkId(), fixture.plan().run().runKey());
            case "downstream" -> jdbc.update("""
                    INSERT INTO structured_test_point
                    (work_item_id, task_id, test_point_key, function_key, function_name, test_point_type,
                     basis, description, missing_information_json, formal_coverage_satisfied)
                    VALUES (?, 'task-1', 'point-near-state', 'function-near-state', '功能', 'NORMAL_BEHAVIOR',
                            'FORMAL_REQUIREMENT', '说明', JSON_ARRAY(), FALSE)
                    """, fixture.failedWorkId());
            case "artifact-id" -> jdbc.update(
                    "UPDATE generation_task SET artifact_id = UUID() WHERE id = 'task-1'");
            case "artifact-hash" -> jdbc.update(
                    "UPDATE generation_task SET artifact_sha256 = ? WHERE id = 'task-1'", "a".repeat(64));
            case "artifact-path" -> jdbc.update(
                    "UPDATE generation_task SET artifact_path = 'retained.xlsx' WHERE id = 'task-1'");
            default -> throw new IllegalArgumentException("Unknown mutation");
        }
        ReconciliationRetrySnapshot before = reconciliationRetrySnapshot(fixture.failedWorkId());

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
        assertThat(reconciliationRetrySnapshot(fixture.failedWorkId())).isEqualTo(before);
    }

    private void insertCandidateSourceOutcome(String workItemId) {
        jdbc.update("""
                INSERT INTO structured_function_source_outcome
                (work_item_id, task_id, unit_key, source_ordinal, kee_disposition,
                 java_final_decision, reason_code)
                VALUES (?, 'task-1', 'partial-unit', 1, 'UNRESOLVED', 'REJECTED', 'model_item_unusable')
                """, workItemId);
    }

    private void insertCandidate(String workItemId) {
        jdbc.update("""
                INSERT INTO structured_function_candidate
                (work_item_id, task_id, candidate_ref, path_text, description, target_quote,
                 recommended_status, java_final_decision, reason_code, missing_information_json)
                VALUES (?, 'task-1', ?, '待确认功能', '待确认说明', '待确认原文',
                        'PENDING_CONFIRMATION', 'REJECTED', 'insufficient_detail', JSON_ARRAY('待确认'))
                """, workItemId, "8".repeat(64));
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-012 */
    @Test
    void concurrentZeroWriteReconciliationModelFailureRetriesHaveOneWinner() throws Exception {
        ReconciliationModelFailureFixture fixture = zeroWriteReconciliationModelFailureFixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> retry = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent retry start gate timed out");
                }
                return taskRepository.retryFailedBatches("task-1");
            };
            Future<Integer> first = executor.submit(retry);
            Future<Integer> second = executor.submit(retry);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                fixture.failedWorkId())).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class,
                fixture.failedWorkId())).isEqualTo(1);
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-013 */
    @Test
    void concurrentZeroWriteReconciliationStructuralFailureRetriesHaveOneWinner() throws Exception {
        ReconciliationModelFailureFixture fixture = zeroWriteReconciliationStructuralFailureFixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> retry = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent retry start gate timed out");
                }
                return taskRepository.retryFailedBatches("task-1");
            };
            Future<Integer> first = executor.submit(retry);
            Future<Integer> second = executor.submit(retry);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                fixture.failedWorkId())).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class,
                fixture.failedWorkId())).isEqualTo(1);
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-012 */
    @Test
    void reconciliationRetrySeesBindingCommittedWhileWaitingForWorkLock() throws Exception {
        ReconciliationModelFailureFixture fixture = zeroWriteReconciliationModelFailureFixture();
        List<Map<String, Object>> taskBefore = generationTaskSnapshot("task-1");
        List<Map<String, Object>> worksBefore = structuredWorkSnapshot("task-1");
        List<Map<String, Object>> attemptsBefore = taskAttemptSnapshot("task-1");
        List<Map<String, Object>> runBefore = reconciliationRunSnapshot(fixture.failedWorkId());
        List<Map<String, Object>> pagesBefore = reconciliationPageSnapshot(fixture.failedWorkId());
        List<Map<String, Object>> relationsBefore = reconciliationRelationSnapshot(fixture.failedWorkId());
        List<Map<String, Object>> stageBindingsBefore = reconciliationStageBindingSnapshot(fixture.failedWorkId());
        CountDownLatch workLockAttempted = new CountDownLatch(1);
        JdbcTemplate observingJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if (sql.contains("FROM structured_generation_work_item work")
                        && sql.contains("WHERE work.task_id = ?")
                        && sql.contains("FOR UPDATE")) {
                    workLockAttempted.countDown();
                }
                return super.query(sql, rowMapper, args);
            }
        };
        GenerationTaskRepository observingRepository = new GenerationTaskRepository(
                observingJdbc, new ObjectMapper().findAndRegisterModules(), transactionManager);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            blocker.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (PreparedStatement lockWork = blocker.prepareStatement(
                    "SELECT id FROM structured_generation_work_item WHERE id = ? FOR UPDATE")) {
                lockWork.setString(1, fixture.failedWorkId());
                assertThat(lockWork.executeQuery().next()).isTrue();
            }

            Future<Integer> retry = executor.submit(() -> observingRepository.retryFailedBatches("task-1"));
            awaitTaskLockHeldByRetry("task-1");
            assertThat(workLockAttempted.await(10, TimeUnit.SECONDS)).isTrue();
            // The test-owned observer signals immediately before the work-lock SQL is executed. The blocker still
            // owns that row, so the incomplete Future proves the retry is waiting at this exact lock boundary.
            assertThat(retry.isDone()).isFalse();

            // The old lock order established a consistent snapshot before waiting here. The corrected order reaches
            // this work-row lock before its first consistent read, so the later current reads must see this insert.
            try (PreparedStatement insertBinding = blocker.prepareStatement("""
                    INSERT INTO structured_reference_binding
                    (work_item_id, subject_key, subject_type, reference_type, reference_key)
                    VALUES (?, 'concurrent-partial-v2', 'RECONCILIATION', 'EVIDENCE', 'evidence-1')
                    """)) {
                insertBinding.setString(1, fixture.failedWorkId());
                assertThat(insertBinding.executeUpdate()).isEqualTo(1);
            }
            blocker.commit();

            assertThat(retry.get(30, TimeUnit.SECONDS)).isZero();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertSoftly(softly -> {
            softly.assertThat(generationTaskSnapshot("task-1")).isEqualTo(taskBefore);
            softly.assertThat(structuredWorkSnapshot("task-1")).isEqualTo(worksBefore);
            softly.assertThat(taskAttemptSnapshot("task-1")).isEqualTo(attemptsBefore);
            softly.assertThat(reconciliationRunSnapshot(fixture.failedWorkId())).isEqualTo(runBefore);
            softly.assertThat(reconciliationPageSnapshot(fixture.failedWorkId())).isEqualTo(pagesBefore);
            softly.assertThat(reconciliationRelationSnapshot(fixture.failedWorkId())).isEqualTo(relationsBefore);
            softly.assertThat(reconciliationStageBindingSnapshot(fixture.failedWorkId()))
                    .isEqualTo(stageBindingsBefore);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_reference_binding
                    WHERE work_item_id = ? AND subject_key = 'concurrent-partial-v2'
                    """, Integer.class, fixture.failedWorkId())).isEqualTo(1);
        });
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void directEvidenceRetrySeesBindingCommittedWhileWaitingForWorkLock() throws Exception {
        V2AtomicityRecoveryFixture fixture = v2FactRecoveryFixture(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
        String failedWorkId = fixture.factWorkIds().get(0);
        List<Map<String, Object>> taskBefore = generationTaskSnapshot("task-1");
        List<Map<String, Object>> worksBefore = structuredWorkSnapshot("task-1");
        List<Map<String, Object>> attemptsBefore = taskAttemptSnapshot("task-1");
        CountDownLatch workLockAttempted = new CountDownLatch(1);
        JdbcTemplate observingJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if (sql.contains("FROM structured_generation_work_item work")
                        && sql.contains("WHERE work.task_id = ?")
                        && sql.contains("FOR UPDATE")) {
                    workLockAttempted.countDown();
                }
                return super.query(sql, rowMapper, args);
            }
        };
        GenerationTaskRepository observingRepository = new GenerationTaskRepository(
                observingJdbc, new ObjectMapper().findAndRegisterModules(), transactionManager);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            blocker.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (PreparedStatement lockWork = blocker.prepareStatement(
                    "SELECT id FROM structured_generation_work_item WHERE id = ? FOR UPDATE")) {
                lockWork.setString(1, failedWorkId);
                assertThat(lockWork.executeQuery().next()).isTrue();
            }

            Future<Integer> retry = executor.submit(() -> observingRepository.retryFailedBatches("task-1"));
            awaitTaskLockHeldByRetry("task-1");
            assertThat(workLockAttempted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(retry.isDone()).isFalse();

            try (PreparedStatement insertBinding = blocker.prepareStatement("""
                    INSERT INTO structured_reference_binding
                    (work_item_id, subject_key, subject_type, reference_type, reference_key)
                    VALUES (?, 'concurrent-partial-v2', 'TEST_POINT', 'EVIDENCE', 'evidence-1')
                    """)) {
                insertBinding.setString(1, failedWorkId);
                assertThat(insertBinding.executeUpdate()).isEqualTo(1);
            }
            blocker.commit();

            assertThat(retry.get(30, TimeUnit.SECONDS)).isZero();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertSoftly(softly -> {
            softly.assertThat(generationTaskSnapshot("task-1")).isEqualTo(taskBefore);
            softly.assertThat(structuredWorkSnapshot("task-1")).isEqualTo(worksBefore);
            softly.assertThat(taskAttemptSnapshot("task-1")).isEqualTo(attemptsBefore);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_reference_binding WHERE work_item_id=?
                    """, Integer.class, failedWorkId)).isEqualTo(1);
        });
    }

    /** [Req-ID]: REQ-ESR-012 */
    @Test
    void zeroWriteReconciliationModelFailureRetryRollsBackWithItsOuterTransaction() {
        ReconciliationModelFailureFixture fixture = zeroWriteReconciliationModelFailureFixture();
        ReconciliationRetrySnapshot before = reconciliationRetrySnapshot(fixture.failedWorkId());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
            throw new IllegalStateException("force-test-rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(reconciliationRetrySnapshot(fixture.failedWorkId())).isEqualTo(before);
    }

    /** [Req-ID]: REQ-ESR-013 */
    @Test
    void zeroWriteReconciliationStructuralFailureRetryRollsBackWithItsOuterTransaction() {
        ReconciliationModelFailureFixture fixture = zeroWriteReconciliationStructuralFailureFixture();
        ReconciliationRetrySnapshot before = reconciliationRetrySnapshot(fixture.failedWorkId());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
            throw new IllegalStateException("force-test-rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(reconciliationRetrySnapshot(fixture.failedWorkId())).isEqualTo(before);
    }

    /** [Req-ID]: REQ-ESR-007 */
    @ParameterizedTest
    @ValueSource(strings = {"second-queued", "lease-owner", "lease-expiry", "accepted-hash",
            "running-attempt", "missing-history", "forbidden-history", "partial-binding"})
    void queuedResidueRecoveryRejectsEveryNonExactState(String mutation) {
        ExplicitRetryFixture fixture = queuedRetryResidueFixture();
        switch (mutation) {
            case "second-queued" -> store.register(reviewRegistration(
                    "f".repeat(64), 2, 2, List.of("evidence-2")));
            case "lease-owner" -> jdbc.update(
                    "UPDATE structured_generation_work_item SET lease_owner = 'stale-worker' WHERE id = ?",
                    fixture.failedWorkId());
            case "lease-expiry" -> jdbc.update(
                    "UPDATE structured_generation_work_item SET lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 5 MINUTE) WHERE id = ?",
                    fixture.failedWorkId());
            case "accepted-hash" -> jdbc.update(
                    "UPDATE structured_generation_work_item SET accepted_result_sha256 = ? WHERE id = ?",
                    "f".repeat(64), fixture.failedWorkId());
            case "running-attempt" -> jdbc.update("""
                    INSERT INTO structured_generation_attempt
                    (id, work_item_id, attempt_number, status)
                    VALUES (UUID(), ?, 2, 'RUNNING')
                    """, fixture.failedWorkId());
            case "missing-history" -> jdbc.update(
                    "DELETE FROM structured_generation_attempt WHERE work_item_id = ?", fixture.failedWorkId());
            case "forbidden-history" -> jdbc.update("""
                    UPDATE structured_generation_attempt SET failure_type = 'model_execution_failed'
                    WHERE work_item_id = ?
                    """, fixture.failedWorkId());
            case "partial-binding" -> jdbc.update("""
                    INSERT INTO structured_reference_binding
                    (work_item_id, subject_key, subject_type, reference_type, reference_key)
                    VALUES (?, 'partial-residue', 'REVIEW_FINDING', 'EVIDENCE', 'evidence-1')
                    """, fixture.failedWorkId());
            default -> throw new IllegalArgumentException("Unknown mutation");
        }

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isFalse();
        assertThat(taskRepository.retryFailedBatches("task-1")).isZero();
        assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.FAILED);
    }

    /** [Req-ID]: REQ-ESR-007 */
    @Test
    void queuedResidueRecoveryRollsBackWithItsOuterTransaction() {
        ExplicitRetryFixture fixture = queuedRetryResidueFixture();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
            throw new IllegalStateException("force-test-rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.FAILED);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    fixture.failedWorkId())).isEqualTo("QUEUED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class,
                    fixture.failedWorkId())).isEqualTo(1);
        });
    }

    /** [Req-ID]: REQ-ESR-002, REQ-ESR-005 */
    @Test
    void concurrentSingleUnitCapacityRetriesHaveOneWinnerAndDoNotPrecreateAnAttempt() throws Exception {
        ExplicitRetryFixture fixture = explicitRetryFixture("response_too_large");
        jdbc.update("""
                UPDATE structured_generation_work_item
                SET ordinal_start = 1, ordinal_end = 1,
                    allowed_evidence_keys_json = JSON_ARRAY('evidence-1')
                WHERE id = ?
                """, fixture.failedWorkId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> retry = () -> {
                ready.countDown();
                start.await();
                return taskRepository.retryFailedBatches("task-1");
            };
            Future<Integer> first = executor.submit(retry);
            Future<Integer> second = executor.submit(retry);
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
        }
        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class,
                    fixture.failedWorkId())).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    fixture.failedWorkId())).isEqualTo("QUEUED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_review_finding WHERE task_id = 'task-1'", Integer.class))
                    .isEqualTo(6);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_reference_binding binding
                    JOIN structured_generation_work_item work ON work.id = binding.work_item_id
                    WHERE work.task_id = 'task-1'
                    """, Integer.class)).isEqualTo(48);
        });
    }

    /** [Req-ID]: REQ-ESR-002 */
    @Test
    void anotherStructuralFailureRequiresAnotherExplicitActionBeforeAttemptThree() {
        ExplicitRetryFixture fixture = explicitRetryFixture("structured_output_invalid");
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
        StructuredGenerationAcceptanceStore.WorkClaim secondAttempt = store.claimRegistered(
                "task-1", fixture.failedWorkId(), "worker-2").orElseThrow();
        store.fail(secondAttempt, "structured_output_invalid");
        jdbc.update("UPDATE generation_task SET status = 'GENERATING' WHERE id = 'task-1'");
        taskRepository.failStructuredTask("task-1", StructuredCoverageStatus.PENDING);

        assertThat(store.claimRegistered("task-1", fixture.failedWorkId(), "automatic-worker")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class,
                fixture.failedWorkId())).isEqualTo(2);

        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
        StructuredGenerationAcceptanceStore.WorkClaim thirdAttempt = store.claimRegistered(
                "task-1", fixture.failedWorkId(), "worker-3").orElseThrow();
        assertThat(thirdAttempt.attemptNumber()).isEqualTo(3);
    }

    /** [Req-ID]: REQ-ESR-002 */
    @Test
    void anotherDiagnosedBusinessFailureRequiresAnotherExplicitActionBeforeAttemptThree() {
        StructuredValidationFailure failure = StructuredValidationFailure.of(
                StructuredValidationFailure.Code.REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                "$.requirement_facts[0].function");
        ExplicitRetryFixture fixture = explicitRetryFixture("business_validation_failed", failure);
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
        StructuredGenerationAcceptanceStore.WorkClaim secondAttempt = store.claimRegistered(
                "task-1", fixture.failedWorkId(), "worker-2").orElseThrow();
        store.fail(secondAttempt, "business_validation_failed", failure);
        jdbc.update("UPDATE generation_task SET status = 'GENERATING' WHERE id = 'task-1'");
        taskRepository.failStructuredTask("task-1", StructuredCoverageStatus.PENDING);

        assertThat(store.claimRegistered("task-1", fixture.failedWorkId(), "automatic-worker")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class,
                fixture.failedWorkId())).isEqualTo(2);

        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
        StructuredGenerationAcceptanceStore.WorkClaim thirdAttempt = store.claimRegistered(
                "task-1", fixture.failedWorkId(), "worker-3").orElseThrow();
        assertThat(thirdAttempt.attemptNumber()).isEqualTo(3);
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
        RequirementMaterialReviewValidator.Result changed = new RequirementMaterialReviewValidator.Result(List.of(fact("fact-1", "取消订单")), List.of());
        assertThatThrownBy(() -> store.acceptReview(claim, validator, item, changed)).isInstanceOf(IllegalStateException.class);
    }

    /** [Req-ID]: REQ-FTG-005 */
    @Test
    void rejectsObservedFact00b8BecauseItsNarrationIsNotDirectlySupportedByTheCitedParsedUnit() {
        StructuredGenerationAcceptanceStore.WorkClaim claim = claimReviewWork();
        RequirementMaterialReviewValidator.WorkItem item = reviewItem();
        RequirementMaterialReviewValidator.RequirementFact observed = new RequirementMaterialReviewValidator.RequirementFact(
                "fact-00b802d0f40f0d37173b0a2fd8660f78", "用户中心→账号登录",
                List.of("已注册且状态正常的用户"), List.of("用户在登录页提交账号和密码"),
                List.of("账号", "密码"), List.of("密码必须正确", "用户状态必须正常", "用户必须已注册"),
                List.of("系统进入首页", "首页显示当前用户名称"), List.of(),
                List.of("用户会话状态由未登录变为已登录"), List.of(), List.of(), List.of("evidence-1"));

        Throwable failure = catchThrowable(() -> store.acceptReview(claim, new RequirementMaterialReviewValidator(), item,
                new RequirementMaterialReviewValidator.Result(
                        List.of(observed), List.of(finding("finding-would-have-been-accepted")))));

        assertReviewGroundingFailureLeftNoBusinessRows(claim, item, observed.factKey(), failure);
    }

    /** [Req-ID]: REQ-FTG-005 */
    @ParameterizedTest(name = "rejects unsupported requirement-fact family: {0}")
    @ValueSource(strings = {"function", "roles", "triggerConditions", "inputs", "businessRules", "outputs",
            "permissions", "stateChanges", "exceptionHandling", "externalDependencies"})
    void rejectsUnsupportedTextInEveryRequirementFactNarrativeFamily(String family) {
        StructuredGenerationAcceptanceStore.WorkClaim claim = claimReviewWork();
        RequirementMaterialReviewValidator.WorkItem item = reviewItem();
        RequirementMaterialReviewValidator.RequirementFact unsupported = unsupportedFact(family);

        Throwable failure = catchThrowable(() -> store.acceptReview(claim, new RequirementMaterialReviewValidator(), item,
                new RequirementMaterialReviewValidator.Result(List.of(unsupported), List.of())));

        assertReviewGroundingFailureLeftNoBusinessRows(claim, item, unsupported.factKey(), failure);
    }

    /** [Req-ID]: REQ-FTG-005 */
    @Test
    void acceptsObservedFact9439WhenEveryNarrativeItemIsACompleteParsedUnitFragment() {
        StructuredGenerationAcceptanceStore.WorkClaim claim = claimReviewWork();
        RequirementMaterialReviewValidator.RequirementFact grounded = new RequirementMaterialReviewValidator.RequirementFact(
                "fact-943950fe000553b979502018f04c4c2f", "用户中心→账号登录",
                List.of("已注册且状态正常的用户"), List.of("用户在登录页提交账号和正确密码"),
                List.of("账号", "正确密码"),
                List.of("已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称"),
                List.of("进入首页", "显示当前用户名称"), List.of("已注册且状态正常的用户"),
                List.of("系统进入首页"), List.of(), List.of(), List.of("evidence-1"));

        store.acceptReview(claim, new RequirementMaterialReviewValidator(), reviewItem(),
                new RequirementMaterialReviewValidator.Result(List.of(grounded), List.of()));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_requirement_fact WHERE work_item_id = ?", Integer.class,
                claim.workItemId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_reference_binding WHERE work_item_id = ?", Integer.class,
                claim.workItemId())).isEqualTo(1);
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
                        liveRegistry, "material-1", "requirements_spec", List.of("evidence-1"),
                        Map.of("evidence-1", reviewEvidenceTexts().get("evidence-1"))),
                new RequirementMaterialReviewValidator.Result(List.of(fact("fact-confirmed", "事实显示名称")), List.of()));

        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "2".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 2, 2, "material-1", "功能清单切片",
                List.of("evidence-2"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim extraction = store.claimNext("task-1", "worker-1").orElseThrow();
        store.acceptFunctionListItems(extraction, liveRegistry, List.of(
                new StructuredGenerationAcceptanceStore.FunctionListItem(
                        "fli-confirmed", "订单/最终功能", "已确认功能",
                        List.of("evidence-2"), List.of("订单最终功能"))));

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
        assertThat(store.hasCompletedReconciliationWork("task-1")).isTrue();

        StructuredGenerationAcceptanceStore restartedStore = new StructuredGenerationAcceptanceStore(
                jdbc, new TransactionTemplate(transactionManager), Clock.systemUTC());
        assertThat(restartedStore.acceptedConfirmedFunctions("task-1")).singleElement().satisfies(mapping -> {
            assertThat(mapping.reconciliationKey()).isEqualTo("reconciliation-confirmed");
            assertThat(mapping.functionItems()).extracting(StructuredGenerationAcceptanceStore.AcceptedFunctionItem::path)
                    .containsExactly("订单/最终功能");
            assertThat(mapping.facts()).extracting(StructuredGenerationAcceptanceStore.AcceptedFact::factKey)
                    .containsExactly("fact-confirmed");
            assertThat(mapping.facts().get(0)).satisfies(fact -> {
                assertThat(fact.roles()).containsExactly("role");
                assertThat(fact.evidenceTexts()).containsEntry("evidence-1",
                        "已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称");
            });
        });
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void explicitRetryResumesOnlyTheZeroDownstreamReconciliationStageGap() {
        // This fixture models two actual material roles. Keeping extraction evidence inside the requirement
        // document would make the review stage appear to omit one requirement unit, which recovery must reject.
        jdbc.update("DELETE FROM material_inventory_unit WHERE task_id = 'task-1' AND unit_id = 'evidence-2'");
        jdbc.update("UPDATE material_inventory_document SET total_units = 1 WHERE task_id = 'task-1' AND document_id = 'document-1'");
        jdbc.update("""
                INSERT INTO material_inventory_document
                (task_id, document_id, knowledge_id, document_role, total_units, complete)
                VALUES ('task-1', 'function-document', 'function-knowledge', 'FUNCTION_LIST', 1, TRUE)
                """);
        jdbc.update("""
                INSERT INTO material_inventory_unit
                (task_id, document_id, unit_id, document_role, chunk_index, ordinal, content, start_at, end_at)
                VALUES ('task-1', 'function-document', 'evidence-2', 'FUNCTION_LIST', 0, 1,
                        '订单最终功能允许提交订单', 0, 12)
                """);
        jdbc.update("""
                UPDATE generation_task
                SET task_mode = 'ALL', status = 'GENERATING', structured_processing_status = 'RUNNING',
                    structured_coverage_status = 'PENDING', request_snapshot = JSON_OBJECT('scope', 'frozen')
                WHERE id = 'task-1'
                """);
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true))
                .registerEvidence(new StructuredEvidence("evidence-2", "task-1", "material-1", false, false, true));
        store.register(reviewWork("7".repeat(64)));
        StructuredGenerationAcceptanceStore.WorkClaim review = store.claimNext("task-1", "worker-1").orElseThrow();
        store.acceptReview(review, new RequirementMaterialReviewValidator(), reviewItem(),
                new RequirementMaterialReviewValidator.Result(List.of(fact("fact-stage-gap")), List.of()));
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "8".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 2, 2, "material-1", "功能清单切片",
                List.of("evidence-2"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim extraction = store.claimNext("task-1", "worker-1").orElseThrow();
        store.acceptFunctionListItems(extraction, registry, List.of(
                new StructuredGenerationAcceptanceStore.FunctionListItem(
                        "fli-stage-gap", "订单/最终功能", "已确认功能",
                        List.of("evidence-2"), List.of("订单最终功能"))));
        assertThat(store.hasCompletedMaterialStages("task-1")).isTrue();
        StructuredValidationRegistry persistedRegistry = store.persistedValidationRegistry("task-1");
        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> persistedRegistry.require(
                    StructuredKeyType.REQUIREMENT_FACT, "fact-stage-gap"))).isNull();
            softly.assertThat(catchThrowable(() -> persistedRegistry.require(
                    StructuredKeyType.FUNCTION_LIST_ITEM, "fli-stage-gap"))).isNull();
            softly.assertThat(catchThrowable(() -> persistedRegistry.requireEvidence("evidence-1"))).isNull();
        });
        taskRepository.failStructuredTask("task-1", StructuredCoverageStatus.PENDING);

        assertThat(taskRepository.structuredRetryEligibility("task-1").canRetry()).isTrue();
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);

        assertSoftly(softly -> {
            softly.assertThat(taskRepository.taskStatus("task-1")).isEqualTo(GenerationTaskStatus.QUEUED);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = 'task-1' AND status = 'COMPLETED'",
                    Integer.class)).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = 'task-1' AND operation_name LIKE 'FEATURE_SCOPE_RECONCILIATION%'",
                    Integer.class)).isZero();
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_feature_reconciliation WHERE task_id = 'task-1'", Integer.class))
                    .isZero();
        });
    }

    /** [Req-ID]: REQ-ESR-008 */
    @Test
    void completedReviewStageRestoresAcceptedFactsWhileExtractionRemainsQueued() {
        store.register(reviewWork("7".repeat(64)));
        StructuredGenerationAcceptanceStore.WorkClaim review = store.claimNext("task-1", "worker-1").orElseThrow();
        store.acceptReview(review, new RequirementMaterialReviewValidator(), reviewItem(),
                new RequirementMaterialReviewValidator.Result(List.of(fact("fact-review-resume")), List.of()));
        store.register(reviewWork("9".repeat(64), "evidence-2"));
        StructuredGenerationAcceptanceStore.WorkClaim secondReview =
                store.claimNext("task-1", "worker-1").orElseThrow();
        store.acceptReview(secondReview, new RequirementMaterialReviewValidator(), reviewItem("evidence-2"),
                new RequirementMaterialReviewValidator.Result(List.of(
                        fact("fact-review-resume-2", "订单最终功能", "evidence-2")), List.of()));
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "8".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 2, 2, "material-1", "功能清单切片",
                List.of("evidence-2"), null, null));

        assertSoftly(softly -> {
            softly.assertThat(store.hasCompletedReviewStage("task-1")).isTrue();
            softly.assertThat(store.hasCompletedMaterialStages("task-1")).isFalse();
        });
        StructuredValidationRegistry persistedRegistry = store.persistedValidationRegistry("task-1");
        assertSoftly(softly -> {
            softly.assertThat(catchThrowable(() -> persistedRegistry.require(
                    StructuredKeyType.MATERIAL, "document-1"))).isNull();
            softly.assertThat(catchThrowable(() -> persistedRegistry.require(
                    StructuredKeyType.REQUIREMENT_FACT, "fact-review-resume"))).isNull();
            softly.assertThat(catchThrowable(() -> persistedRegistry.requireEvidence("evidence-1"))).isNull();
        });
    }

    /** [Req-ID]: REQ-ESR-008 */
    @Test
    void historicalSplitParentsDoNotMakeAcceptedReviewLeavesIncomplete() {
        String splitParent = store.register(reviewWork("6".repeat(64)));
        jdbc.update("UPDATE structured_generation_work_item SET status = 'SPLIT' WHERE id = ?", splitParent);
        store.register(reviewWork("7".repeat(64)));
        StructuredGenerationAcceptanceStore.WorkClaim leaf = store.claimNext("task-1", "worker-1").orElseThrow();
        store.acceptReview(leaf, new RequirementMaterialReviewValidator(), reviewItem(),
                new RequirementMaterialReviewValidator.Result(List.of(fact("fact-split-leaf")), List.of()));
        store.register(reviewWork("9".repeat(64), "evidence-2"));
        StructuredGenerationAcceptanceStore.WorkClaim secondLeaf =
                store.claimNext("task-1", "worker-1").orElseThrow();
        store.acceptReview(secondLeaf, new RequirementMaterialReviewValidator(), reviewItem("evidence-2"),
                new RequirementMaterialReviewValidator.Result(List.of(
                        fact("fact-split-leaf-2", "订单最终功能", "evidence-2")), List.of()));
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "8".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 2, 2, "material-1", "功能清单切片",
                List.of("evidence-2"), null, null));

        assertThat(store.hasCompletedReviewStage("task-1")).isTrue();
        assertThat(store.hasCompletedMaterialStages("task-1")).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_work_item WHERE status = 'SPLIT'", Integer.class))
                .isOne();
    }

    /** [Req-ID]: REQ-ESR-008 */
    @ParameterizedTest
    @ValueSource(strings = {"queued-review", "missing-hash", "missing-inventory"})
    void reviewStageResumeFailsClosedForEveryNearState(String mutation) {
        store.register(reviewWork("7".repeat(64)));
        StructuredGenerationAcceptanceStore.WorkClaim review = store.claimNext("task-1", "worker-1").orElseThrow();
        store.acceptReview(review, new RequirementMaterialReviewValidator(), reviewItem(),
                new RequirementMaterialReviewValidator.Result(List.of(fact("fact-review-near-state")), List.of()));
        store.register(reviewWork("9".repeat(64), "evidence-2"));
        StructuredGenerationAcceptanceStore.WorkClaim secondReview =
                store.claimNext("task-1", "worker-1").orElseThrow();
        store.acceptReview(secondReview, new RequirementMaterialReviewValidator(), reviewItem("evidence-2"),
                new RequirementMaterialReviewValidator.Result(List.of(
                        fact("fact-review-near-state-2", "订单最终功能", "evidence-2")), List.of()));
        switch (mutation) {
            case "queued-review" -> jdbc.update("""
                    UPDATE structured_generation_work_item
                    SET status = 'QUEUED', accepted_result_sha256 = NULL
                    WHERE id = ?
                    """, review.workItemId());
            case "missing-hash" -> jdbc.update(
                    "UPDATE structured_generation_work_item SET accepted_result_sha256 = NULL WHERE id = ?",
                    review.workItemId());
            case "missing-inventory" -> {
                jdbc.update("DELETE FROM material_inventory_unit WHERE task_id = 'task-1'");
                jdbc.update("DELETE FROM material_inventory_document WHERE task_id = 'task-1'");
            }
            default -> throw new AssertionError("unknown fixture mutation");
        }

        assertThat(store.hasCompletedReviewStage("task-1")).isFalse();
        assertThatThrownBy(() -> store.persistedValidationRegistry("task-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** [Req-ID]: REQ-ESR-008 */
    @Test
    void reviewStageResumeRejectsAnUncoveredFrozenInventoryUnit() {
        store.register(reviewWork("7".repeat(64)));
        StructuredGenerationAcceptanceStore.WorkClaim review = store.claimNext("task-1", "worker-1").orElseThrow();
        store.acceptReview(review, new RequirementMaterialReviewValidator(), reviewItem(),
                new RequirementMaterialReviewValidator.Result(List.of(fact("fact-incomplete-coverage")), List.of()));

        assertThat(store.hasCompletedReviewStage("task-1")).isFalse();
        assertThatThrownBy(() -> store.persistedValidationRegistry("task-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** [Req-ID]: REQ-FTG-001, REQ-FTG-003 */
    @Test
    void rejectsUngroundedFormalTextBeforeAnyTestcaseRowAndAcceptsGroundedTextAtomically() {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.FUNCTION, "function-1")
                .register(StructuredKeyType.TEST_POINT, "point-1")
                .register(StructuredKeyType.REQUIREMENT_FACT, "fact-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true));
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "0".repeat(64), "functional-testcase-design", "FUNCTIONAL_TESTCASE_DESIGN",
                null, null, null, "testcase", List.of("evidence-1"), "function-1", "point-1"));
        StructuredGenerationAcceptanceStore.WorkClaim claim = store.claimNext("task-1", "worker-1").orElseThrow();
        FunctionalTestcaseResultValidator.WorkItem workItem = groundedWorkItem(registry);
        FunctionalTestcaseResultValidator.Result unsupported = testcaseResult(
                "用户名、手机号或邮箱登录", "用户已设置用户名", "检查 Token/Session", "访问受保护资源");

        assertThatThrownBy(() -> store.acceptTestcases(claim, new FunctionalTestcaseResultValidator(), workItem, unsupported))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_test_point", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_test_case", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_test_case_step", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding", Integer.class)).isZero();

        FunctionalTestcaseResultValidator.Result grounded = testcaseResult(
                "用户中心→账号登录", "用户必须已注册且状态正常", "用户在登录页提交账号和正确密码", "系统进入首页");
        store.acceptTestcases(claim, new FunctionalTestcaseResultValidator(), workItem, grounded);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_test_point", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_test_case", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_test_case_step", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding", Integer.class)).isEqualTo(4);
    }

    /** [Req-ID]: REQ-FTG-009 */
    @Test
    void persistsEveryFrozenHighGranularityTestcaseAndStepField() {
        StructuredValidationRegistry registry = testcaseRegistry();
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "7".repeat(64), "functional-testcase-design", "FUNCTIONAL_TESTCASE_DESIGN",
                null, null, null, "testcase", List.of("evidence-1"), "function-1", "point-1"));
        StructuredGenerationAcceptanceStore.WorkClaim claim = store.claimNext("task-1", "worker-1").orElseThrow();

        FunctionalTestcaseResultValidator.Testcase testcase = highGranularityTestcase("case-high");

        store.acceptTestcases(claim, new FunctionalTestcaseResultValidator(), groundedWorkItem(registry),
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(testcase)));

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("SELECT name_text FROM structured_test_case WHERE case_key = 'case-high'", String.class))
                    .isEqualTo("用户中心→账号登录");
            softly.assertThat(jdbc.queryForObject("SELECT priority FROM structured_test_case WHERE case_key = 'case-high'", String.class))
                    .isEqualTo("HIGH");
            softly.assertThat(jdbc.queryForObject("SELECT inputs_json FROM structured_test_case WHERE case_key = 'case-high'", String.class))
                    .contains("账号", "EQUIVALENCE_PARTITIONING");
            softly.assertThat(jdbc.queryForObject("SELECT expected_results_json FROM structured_test_case WHERE case_key = 'case-high'", String.class))
                    .contains("系统进入首页");
            softly.assertThat(jdbc.queryForObject("SELECT evaluation_criteria FROM structured_test_case WHERE case_key = 'case-high'", String.class))
                    .isEqualTo(FunctionalTestcaseResultValidator.EVALUATION);
            softly.assertThat(jdbc.queryForObject("SELECT result_evaluation_criteria FROM structured_test_case WHERE case_key = 'case-high'", String.class))
                    .isEqualTo(FunctionalTestcaseResultValidator.RESULT_EVALUATION);
            softly.assertThat(jdbc.queryForObject("SELECT result_collection FROM structured_test_case WHERE case_key = 'case-high'", String.class))
                    .isEqualTo(FunctionalTestcaseResultValidator.RESULT_COLLECTION);
            softly.assertThat(jdbc.queryForObject("SELECT author_name FROM structured_test_case WHERE case_key = 'case-high'", String.class))
                    .isEmpty();
            softly.assertThat(jdbc.queryForObject("SELECT evaluation_criteria FROM structured_test_case_step WHERE case_key = 'case-high'", String.class))
                    .isEqualTo(FunctionalTestcaseResultValidator.STEP_EVALUATION);
            softly.assertThat(jdbc.queryForObject("SELECT termination_or_error FROM structured_test_case_step WHERE case_key = 'case-high'", String.class))
                    .isEqualTo(FunctionalTestcaseResultValidator.TERMINATION_OR_ERROR);
            softly.assertThat(jdbc.queryForObject("SELECT result_collection FROM structured_test_case_step WHERE case_key = 'case-high'", String.class))
                    .isEqualTo(FunctionalTestcaseResultValidator.RESULT_COLLECTION);
        });
    }

    /** [Req-ID]: REQ-FTG-009 */
    @Test
    void rollsBackTheTestPointAndEveryHighGranularityRowWhenCaseStorageFails() {
        StructuredValidationRegistry registry = testcaseRegistry();
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "8".repeat(64), "functional-testcase-design", "FUNCTIONAL_TESTCASE_DESIGN",
                null, null, null, "testcase", List.of("evidence-1"), "function-1", "point-1"));
        StructuredGenerationAcceptanceStore.WorkClaim claim = store.claimNext("task-1", "worker-1").orElseThrow();

        assertThatThrownBy(() -> store.acceptTestcases(claim, new FunctionalTestcaseResultValidator(), groundedWorkItem(registry),
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(highGranularityTestcase("x".repeat(129))))))
                .isInstanceOf(RuntimeException.class);
        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_test_point", Integer.class)).isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_test_case", Integer.class)).isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_test_case_step", Integer.class)).isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding", Integer.class)).isZero();
        });
    }

    /** [Req-ID]: REQ-FTG-007, REQ-FTG-008 */
    @Test
    void mergesTheSameRootCauseAcrossSlicesAndStoreRestartWithoutLosingEvidenceOrScope() {
        StructuredGenerationAcceptanceStore.WorkClaim first = claimReviewWork();
        store.acceptReview(first, new RequirementMaterialReviewValidator(), reviewItem("evidence-1"),
                reviewResult("finding-first", RequirementMaterialReviewValidator.RootCauseKind.MISSING_BUSINESS_RULE, "evidence-1"));
        store.register(reviewWork("b".repeat(64), "evidence-2"));
        StructuredGenerationAcceptanceStore.WorkClaim second = store.claimNext("task-1", "worker-2").orElseThrow();
        StructuredGenerationAcceptanceStore restarted = new StructuredGenerationAcceptanceStore(
                jdbc, new TransactionTemplate(transactionManager), Clock.systemUTC());
        restarted.acceptReview(second, new RequirementMaterialReviewValidator(), reviewItem("evidence-2"),
                reviewResult("finding-second", RequirementMaterialReviewValidator.RootCauseKind.MISSING_BUSINESS_RULE, "evidence-2"));

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_review_finding WHERE task_id = 'task-1' "
                    + "AND root_cause_kind = 'MISSING_BUSINESS_RULE'", Integer.class)).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject("SELECT affected_unit_keys_json FROM structured_review_finding "
                    + "WHERE task_id = 'task-1' AND root_cause_kind = 'MISSING_BUSINESS_RULE'", String.class))
                    .contains("evidence-1", "evidence-2");
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding WHERE subject_type = 'REVIEW_FINDING' "
                    + "AND reference_type = 'EVIDENCE'", Integer.class)).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("SELECT bad_source_evidence_key FROM structured_review_finding "
                    + "WHERE task_id = 'task-1' AND root_cause_kind = 'MISSING_BUSINESS_RULE'", String.class)).isEqualTo("evidence-1");
            softly.assertThat(jdbc.queryForObject("SELECT proposed_good_status FROM structured_review_finding "
                    + "WHERE task_id = 'task-1' AND root_cause_kind = 'MISSING_BUSINESS_RULE'", String.class))
                    .isEqualTo("PENDING_CONFIRMATION");
        });
    }

    /** [Req-ID]: REQ-FTG-008 */
    @Test
    void keepsDifferentRootCausesSeparateAndMergesConcurrentWritesForOneRoot() throws Exception {
        store.register(reviewWork("b".repeat(64), "evidence-1"));
        StructuredGenerationAcceptanceStore.WorkClaim first = store.claimNext("task-1", "worker-1").orElseThrow();
        store.register(reviewWork("c".repeat(64), "evidence-2"));
        StructuredGenerationAcceptanceStore.WorkClaim second = store.claimNext("task-1", "worker-2").orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstWrite = workers.submit(acceptReviewAfter(start, store, first, "finding-one",
                    RequirementMaterialReviewValidator.RootCauseKind.MISSING_FUNCTION_SCOPE, "evidence-1"));
            Future<?> secondWrite = workers.submit(acceptReviewAfter(start, store, second, "finding-two",
                    RequirementMaterialReviewValidator.RootCauseKind.MISSING_FUNCTION_SCOPE, "evidence-2"));
            start.countDown();
            firstWrite.get();
            secondWrite.get();
        } finally {
            workers.shutdownNow();
        }
        store.register(reviewWork("d".repeat(64), "evidence-1"));
        StructuredGenerationAcceptanceStore.WorkClaim distinct = store.claimNext("task-1", "worker-3").orElseThrow();
        store.acceptReview(distinct, new RequirementMaterialReviewValidator(), reviewItem("evidence-1"),
                reviewResult("finding-distinct", RequirementMaterialReviewValidator.RootCauseKind.AMBIGUOUS_REQUIREMENT, "evidence-1"));

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_review_finding WHERE task_id = 'task-1'", Integer.class))
                    .isEqualTo(2);
            softly.assertThat(jdbc.queryForObject("SELECT affected_unit_keys_json FROM structured_review_finding WHERE task_id = 'task-1' "
                    + "AND root_cause_kind = 'MISSING_FUNCTION_SCOPE'", String.class)).contains("evidence-1", "evidence-2");
        });
    }

    /** [Req-ID]: REQ-FTG-009 */
    @Test
    void rollsBackEarlierRootCauseMergeWhenALaterFrozenFindingCannotBeStored() {
        StructuredGenerationAcceptanceStore.WorkClaim claim = claimReviewWork();
        RequirementMaterialReviewValidator.Result result = new RequirementMaterialReviewValidator.Result(List.of(), List.of(
                frozenFinding("finding-first", RequirementMaterialReviewValidator.RootCauseKind.MISSING_BUSINESS_RULE, "evidence-1"),
                frozenFinding("x".repeat(129), RequirementMaterialReviewValidator.RootCauseKind.AMBIGUOUS_REQUIREMENT, "evidence-1")));

        assertThatThrownBy(() -> store.acceptReview(claim, new RequirementMaterialReviewValidator(), reviewItem("evidence-1"), result))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_review_finding", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding", Integer.class)).isZero();
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

    /** [Req-ID]: REQ-TGV2-008 */
    @Test
    void processInterruptionDoesNotConsumeTheSecondBusinessAttemptButRealFailuresRemainBounded() {
        StructuredGenerationAcceptanceStore.WorkClaim first = claimReviewWork();
        store.fail(first, "model_execution_failed");
        StructuredGenerationAcceptanceStore.WorkClaim interrupted = store.claimRegistered(
                "task-1", first.workItemId(), "worker-2").orElseThrow();
        jdbc.update("""
                UPDATE structured_generation_attempt
                SET status = 'FAILED', failure_type = 'worker_interrupted', completed_at = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """, interrupted.attemptId());
        jdbc.update("""
                UPDATE structured_generation_work_item
                SET status = 'FAILED', lease_owner = NULL, lease_expires_at = NULL
                WHERE id = ?
                """, interrupted.workItemId());

        StructuredGenerationAcceptanceStore.WorkClaim resumed = store.claimRegistered(
                "task-1", first.workItemId(), "worker-3").orElseThrow();

        assertThat(resumed.attemptNumber()).isEqualTo(3);
        store.fail(resumed, "model_execution_failed");
        assertThat(store.claimRegistered("task-1", first.workItemId(), "worker-4")).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT attempt_number, failure_type FROM structured_generation_attempt
                WHERE work_item_id = ? ORDER BY attempt_number
                """, first.workItemId())).extracting(
                        row -> row.get("attempt_number"), row -> row.get("failure_type"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, "model_execution_failed"),
                        org.assertj.core.groups.Tuple.tuple(2L, "worker_interrupted"),
                        org.assertj.core.groups.Tuple.tuple(3L, "model_execution_failed"));
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
                otherTaskRegistry, "material-1", "requirements_spec", List.of("evidence-1"),
                Map.of("evidence-1", reviewEvidenceTexts().get("evidence-1")));

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

    /** [Req-ID]: REQ-AFCE-003, REQ-AFCE-005 */
    @Test
    void atomicallyAcceptsOneCandidateWindowAndProjectsOnlyJavaAcceptedCandidates() {
        CandidateAcceptanceFixture fixture = candidateAcceptanceFixture("a".repeat(64), List.of("evidence-1", "evidence-2"));

        store.acceptFunctionCandidates(fixture.claim(), candidateWindow(
                fixture.claim().identityKey(), FunctionCandidateExtractionValidator.FinalDecision.ACCEPTED));

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_function_source_outcome", Integer.class)).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_function_candidate", Integer.class)).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_function_outcome_candidate", Integer.class)).isEqualTo(2);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_function_list_item", Integer.class)).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_function_candidate
                    WHERE java_final_decision = 'ACCEPTED' AND function_item_key IS NOT NULL
                    """, Integer.class)).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_function_candidate
                    WHERE java_final_decision = 'PENDING_CONFIRMATION' AND function_item_key IS NULL
                    """, Integer.class)).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    fixture.claim().workItemId())).isEqualTo("COMPLETED");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_attempt WHERE id = ?", String.class,
                    fixture.claim().attemptId())).isEqualTo("COMPLETED");
            StructuredGenerationAcceptanceStore.AggregateState aggregate = store.aggregateState("task-1");
            softly.assertThat(aggregate.acceptedFunctionCandidateCount()).isEqualTo(1);
            softly.assertThat(aggregate.incompleteFunctionScopeCount()).isEqualTo(1);
            softly.assertThat(aggregate.failedFunctionCandidateWorkCount()).isZero();
            softly.assertThat(store.hasFunctionCandidateAudit("task-1")).isTrue();
        });
    }

    /** [Req-ID]: REQ-AFCE-006 */
    @Test
    void countsOnlyAllowlistedTechnicalCandidateFailuresAsPartialEligible() {
        CandidateAcceptanceFixture technical = candidateAcceptanceFixture("7".repeat(64), List.of("evidence-1"));
        store.fail(technical.claim(), "response_too_large");
        CandidateAcceptanceFixture contract = candidateAcceptanceFixture("8".repeat(64), List.of("evidence-2"));
        store.fail(contract.claim(), "invalid_request");

        StructuredGenerationAcceptanceStore.AggregateState aggregate = store.aggregateState("task-1");

        assertThat(aggregate.failedWorkCount()).isEqualTo(2);
        assertThat(aggregate.failedFunctionCandidateWorkCount()).isEqualTo(1);
    }

    /** [Req-ID]: REQ-AFCE-005 */
    @Test
    void replaysTheSameCandidateHashIdempotentlyAndRejectsDifferentContentForCompletedWork() {
        CandidateAcceptanceFixture fixture = candidateAcceptanceFixture("b".repeat(64), List.of("evidence-1", "evidence-2"));
        FunctionCandidateExtractionValidator.ValidatedWindow accepted = candidateWindow(
                fixture.claim().identityKey(), FunctionCandidateExtractionValidator.FinalDecision.ACCEPTED);

        store.acceptFunctionCandidates(fixture.claim(), accepted);
        store.acceptFunctionCandidates(fixture.claim(), accepted);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_function_candidate", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> store.acceptFunctionCandidates(fixture.claim(), candidateWindow(
                fixture.claim().identityKey(), FunctionCandidateExtractionValidator.FinalDecision.REJECTED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different accepted result");
    }

    /** [Req-ID]: REQ-AFCE-005 */
    @Test
    void rollsBackTheWholeCandidateWindowWhenTaskOwnedSourceIdentityConflicts() {
        CandidateAcceptanceFixture first = candidateAcceptanceFixture("c".repeat(64), List.of("evidence-1"));
        FunctionCandidateExtractionValidator.ValidatedWindow firstWindow = singleCandidateWindow(
                first.claim().identityKey(), "d".repeat(64), "功能一");
        store.acceptFunctionCandidates(first.claim(), firstWindow);

        CandidateAcceptanceFixture second = candidateAcceptanceFixture("e".repeat(64), List.of("evidence-1"));
        FunctionCandidateExtractionValidator.ValidatedWindow conflicting = singleCandidateWindow(
                second.claim().identityKey(), "f".repeat(64), "功能二");

        assertThatThrownBy(() -> store.acceptFunctionCandidates(second.claim(), conflicting))
                .isInstanceOf(RuntimeException.class);
        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_function_source_outcome", Integer.class)).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_function_candidate", Integer.class)).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_function_list_item", Integer.class)).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    second.claim().workItemId())).isEqualTo("RUNNING");
        });
    }

    /** [Req-ID]: REQ-AFCE-005 */
    @Test
    void refusesExpiredCandidateClaimsWithoutWritingAuditOrFormalRows() {
        Instant startedAt = Instant.parse("2026-08-26T00:00:00Z");
        store = storeAt(startedAt);
        CandidateAcceptanceFixture fixture = candidateAcceptanceFixture("1".repeat(64), List.of("evidence-1", "evidence-2"));
        store = storeAt(startedAt.plus(Duration.ofMinutes(5)));

        assertThatThrownBy(() -> store.acceptFunctionCandidates(fixture.claim(), candidateWindow(
                fixture.claim().identityKey(), FunctionCandidateExtractionValidator.FinalDecision.ACCEPTED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer active");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_function_candidate", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_function_list_item", Integer.class)).isZero();
    }

    /** [Req-ID]: REQ-AFCE-005 */
    @Test
    void concurrentDifferentCandidateDecisionsPublishExactlyOneCompleteWindow() throws Exception {
        CandidateAcceptanceFixture fixture = candidateAcceptanceFixture("2".repeat(64), List.of("evidence-1", "evidence-2"));
        FunctionCandidateExtractionValidator.ValidatedWindow accepted = candidateWindow(
                fixture.claim().identityKey(), FunctionCandidateExtractionValidator.FinalDecision.ACCEPTED);
        FunctionCandidateExtractionValidator.ValidatedWindow rejected = candidateWindow(
                fixture.claim().identityKey(), FunctionCandidateExtractionValidator.FinalDecision.REJECTED);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = workers.submit(() -> acceptCandidateAfter(start, fixture.claim(), accepted));
            Future<Boolean> second = workers.submit(() -> acceptCandidateAfter(start, fixture.claim(), rejected));
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            workers.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_function_source_outcome", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_function_candidate", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                fixture.claim().workItemId())).isEqualTo("COMPLETED");
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
                new StructuredGenerationAcceptanceStore.FunctionListItem(
                        "item-1", "模块/功能", "由 Java 任务预先确定",
                        List.of("evidence-1"), List.of("已注册且状态正常的用户")));

        store.acceptFunctionListItems(claim, registry, items);
        store.acceptFunctionListItems(claim, registry, items);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_function_list_item", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding", Integer.class)).isEqualTo(1);
        registry.require(StructuredKeyType.FUNCTION_LIST_ITEM, "item-1");
        assertThatThrownBy(() -> store.acceptFunctionListItems(claim, registry, List.of(
                new StructuredGenerationAcceptanceStore.FunctionListItem(
                        "item-1", "模块/功能", "changed",
                        List.of("evidence-1"), List.of("已注册且状态正常的用户")))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StructuredGenerationAcceptanceStore.FunctionListItem(
                "item-2", "模块/功能", "说明", List.of("evidence-1"), List.of("功".repeat(513))))
                .isInstanceOf(IllegalArgumentException.class);
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
                new StructuredGenerationAcceptanceStore.FunctionListItem(
                        "fli-stable", "订单/提交", "提交订单",
                        List.of("evidence-1"), List.of("登录页提交账号"))));

        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "c".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 2, 2, "material-1", "slice 2",
                List.of("evidence-2"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim second = store.claimNext("task-1", "worker-2").orElseThrow();
        StructuredValidationRegistry rebuiltRegistry = StructuredValidationRegistry.forTask("task-1")
                .registerEvidence(new StructuredEvidence("evidence-2", "task-1", "material-1", false, false, true));
        store.acceptFunctionListItems(second, rebuiltRegistry, List.of(
                new StructuredGenerationAcceptanceStore.FunctionListItem(
                        "fli-stable", "订单/提交", "提交订单",
                        List.of("evidence-2"), List.of("订单最终功能"))));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_function_list_item WHERE task_id = 'task-1' AND item_key = 'fli-stable'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_reference_binding WHERE subject_type = 'FUNCTION_LIST_ITEM' "
                + "AND subject_key = 'fli-stable'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = 'task-1' AND status = 'COMPLETED'",
                Integer.class)).isEqualTo(2);
        assertThat(store.acceptedInputs("task-1").functionItems()).singleElement().satisfies(item ->
                assertThat(item.targetQuotes()).containsExactlyInAnyOrder("登录页提交账号", "订单最终功能"));
        rebuiltRegistry.require(StructuredKeyType.FUNCTION_LIST_ITEM, "fli-stable");
    }

    /** [Req-ID]: REQ-FTG-014 */
    @Test
    void restoresHistoricalFunctionItemsWithoutInventingTargetQuotes() {
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", "7".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 1, 1, "material-1", "legacy slice",
                List.of("evidence-1"), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim claim = store.claimNext("task-1", "worker-1").orElseThrow();
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true));
        store.acceptFunctionListItems(claim, registry, List.of(
                new StructuredGenerationAcceptanceStore.FunctionListItem(
                        "fli-legacy", "订单/提交", "提交订单",
                        List.of("evidence-1"), List.of("提交订单"))));

        // V18 intentionally keeps the column nullable so rows accepted by older deployments remain readable.
        jdbc.update("UPDATE structured_function_list_item SET target_quotes_json = NULL WHERE item_key = ?",
                "fli-legacy");

        assertThat(store.acceptedInputs("task-1").functionItems()).singleElement().satisfies(item -> {
            assertThat(item.itemKey()).isEqualTo("fli-legacy");
            assertThat(item.targetQuotes()).isEmpty();
        });
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
                        new StructuredGenerationAcceptanceStore.FunctionListItem(
                                "fli-concurrent", "订单/提交", "提交订单",
                                List.of("evidence-1"), List.of("登录页提交账号"))));
                return null;
            });
            Future<?> secondWrite = workers.submit(() -> {
                start.await();
                store.acceptFunctionListItems(second, secondRegistry, List.of(
                        new StructuredGenerationAcceptanceStore.FunctionListItem(
                                "fli-concurrent", "订单/提交", "提交订单",
                                List.of("evidence-2"), List.of("订单最终功能"))));
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
                new StructuredGenerationAcceptanceStore.FunctionListItem(
                        "item-unpublished", "模块", "说明",
                        List.of("allowed-evidence"), List.of("引用文本")))))
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
                new StructuredGenerationAcceptanceStore.FunctionListItem(
                        "item-outside", "模块", "说明",
                        List.of("outside-evidence"), List.of("引用文本")))))
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
                new StructuredGenerationAcceptanceStore.FunctionListItem(
                        "item-rolled-back", "模块", "说明",
                        List.of("evidence-1"), List.of("已注册且状态正常的用户")),
                new StructuredGenerationAcceptanceStore.FunctionListItem(
                        tooLongKey, "模块", "说明",
                        List.of("evidence-1"), List.of("已注册且状态正常的用户")))))
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

    /** [Req-ID]: REQ-SEW-002 */
    @Test
    void renewsTheExactClaimPastItsOriginalDeadlineWithoutCreatingAnotherAttempt() {
        Instant startedAt = Instant.parse("2026-08-23T00:00:00Z");
        store = storeAt(startedAt);
        StructuredGenerationAcceptanceStore.WorkClaim claim = claimReviewWork();
        Instant originalExpiry = jdbc.queryForObject(
                "SELECT lease_expires_at FROM structured_generation_work_item WHERE id = ?",
                java.sql.Timestamp.class, claim.workItemId()).toInstant();

        store = storeAt(startedAt.plus(Duration.ofMinutes(4)));
        assertThat(store.renewLease(claim)).isTrue();
        Instant renewedExpiry = jdbc.queryForObject(
                "SELECT lease_expires_at FROM structured_generation_work_item WHERE id = ?",
                java.sql.Timestamp.class, claim.workItemId()).toInstant();

        store = storeAt(startedAt.plus(Duration.ofMinutes(6)));
        assertThat(renewedExpiry).isAfter(originalExpiry);
        assertThat(store.claimRegistered("task-1", claim.workItemId(), "worker-2")).isEmpty();
        store.acceptReview(claim, new RequirementMaterialReviewValidator(), reviewItem(),
                new RequirementMaterialReviewValidator.Result(List.of(fact("fact-renewed")), List.of()));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?",
                Integer.class, claim.workItemId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_requirement_fact WHERE work_item_id = ?",
                Integer.class, claim.workItemId())).isEqualTo(1);
    }

    /** [Req-ID]: REQ-SEW-002 */
    @Test
    void refusesToRenewExpiredOrCancelledClaimsWithoutChangingTheirLease() {
        Instant startedAt = Instant.parse("2026-08-23T01:00:00Z");
        store = storeAt(startedAt);
        StructuredGenerationAcceptanceStore.WorkClaim expired = claimReviewWork();
        Instant expiredLease = jdbc.queryForObject(
                "SELECT lease_expires_at FROM structured_generation_work_item WHERE id = ?",
                java.sql.Timestamp.class, expired.workItemId()).toInstant();

        store = storeAt(startedAt.plus(Duration.ofMinutes(5)));
        assertThat(store.renewLease(expired)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT lease_expires_at FROM structured_generation_work_item WHERE id = ?",
                java.sql.Timestamp.class, expired.workItemId()).toInstant()).isEqualTo(expiredLease);

        reset();
        store = storeAt(startedAt);
        StructuredGenerationAcceptanceStore.WorkClaim cancelled = claimReviewWork();
        Instant cancelledLease = jdbc.queryForObject(
                "SELECT lease_expires_at FROM structured_generation_work_item WHERE id = ?",
                java.sql.Timestamp.class, cancelled.workItemId()).toInstant();
        assertThat(taskRepository.requestCancellation("task-1")).isTrue();

        assertThat(store.renewLease(cancelled)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT lease_expires_at FROM structured_generation_work_item WHERE id = ?",
                java.sql.Timestamp.class, cancelled.workItemId()).toInstant()).isEqualTo(cancelledLease);
    }

    /** [Req-ID]: REQ-SEW-002 */
    @Test
    void concurrentRenewalAtExpiryAndRecoveryLeaveOneAuthoritativeAttempt() throws Exception {
        Instant startedAt = Instant.parse("2026-08-23T02:00:00Z");
        store = storeAt(startedAt);
        StructuredGenerationAcceptanceStore.WorkClaim original = claimReviewWork();
        store = storeAt(startedAt.plus(Duration.ofMinutes(5)));
        StructuredGenerationAcceptanceStore boundaryStore = store;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> renewal = workers.submit(() -> {
                start.await();
                return boundaryStore.renewLease(original);
            });
            Future<Boolean> recovery = workers.submit(() -> {
                start.await();
                return boundaryStore.claimRegistered("task-1", original.workItemId(), "worker-2").isPresent();
            });
            start.countDown();

            assertThat(renewal.get()).isFalse();
            assertThat(recovery.get()).isTrue();
        } finally {
            workers.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?",
                Integer.class, original.workItemId())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ? AND status = 'RUNNING'",
                Integer.class, original.workItemId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structured_requirement_fact WHERE work_item_id = ?",
                Integer.class, original.workItemId())).isZero();
    }

    /** [Req-ID]: REQ-FSC-007 */
    @Test
    void atomicallyPersistsTheSameSafeValidationFailureOnAttemptWorkAndTask() {
        StructuredGenerationAcceptanceStore.WorkClaim claim = claimReviewWork();
        StructuredValidationFailure failure = StructuredValidationFailure.of(
                StructuredValidationFailure.Code.REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                "$.requirement_facts[0].business_rules[0]");

        store.fail(claim, "business_validation_failed", failure);

        assertSoftly(softly -> {
            for (String table : List.of("structured_generation_attempt", "structured_generation_work_item")) {
                Map<String, Object> stored = jdbc.queryForMap("SELECT validation_error_code, validation_error_path, "
                        + "validation_error_message FROM " + table + " WHERE id = ?",
                        table.endsWith("attempt") ? claim.attemptId() : claim.workItemId());
                softly.assertThat(stored).containsEntry("validation_error_code", failure.code())
                        .containsEntry("validation_error_path", failure.path())
                        .containsEntry("validation_error_message", failure.message());
            }
            softly.assertThat(jdbc.queryForMap("SELECT validation_error_code, validation_error_path, "
                            + "validation_error_message FROM generation_task WHERE id = ?", claim.taskId()))
                    .containsEntry("validation_error_code", failure.code())
                    .containsEntry("validation_error_path", failure.path())
                    .containsEntry("validation_error_message", failure.message());
            softly.assertThat(jdbc.queryForObject("SELECT failure_type FROM structured_generation_attempt WHERE id = ?",
                    String.class, claim.attemptId())).isEqualTo("business_validation_failed");
        });
    }

    /** [Req-ID]: REQ-FSC-007 */
    @Test
    void rejectsValidationDiagnosticsForNonBusinessFailuresWithoutChangingTheClaim() {
        StructuredGenerationAcceptanceStore.WorkClaim claim = claimReviewWork();
        StructuredValidationFailure failure = StructuredValidationFailure.of(
                StructuredValidationFailure.Code.REVIEW_RESULT_INVALID, "$");

        assertThatThrownBy(() -> store.fail(claim, "model_unavailable", failure))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_attempt WHERE id = ?",
                String.class, claim.attemptId())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_work_item WHERE id = ?",
                String.class, claim.workItemId())).isEqualTo("RUNNING");
    }

    /** [Req-ID]: REQ-FTG-013 */
    @Test
    void persistsAndRestoresTargetContextAndMaterialIdentityBeforeExecution() {
        var registration = new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "7".repeat(64), "requirement-material-quality-review",
                "REQUIREMENT_MATERIAL_REVIEW", 1, 1, "material-1", "requirements",
                List.of("evidence-1"), null, null,
                "document-1", List.of("evidence-2"), null, 0);

        String workId = store.register(registration);
        var restored = store.materialWindowPlans("task-1", "REQUIREMENT_MATERIAL_REVIEW", "material-1");

        assertThat(restored).singleElement().satisfies(window -> {
            assertThat(window.workItemId()).isEqualTo(workId);
            assertThat(window.materialDocumentId()).isEqualTo("document-1");
            assertThat(window.targetEvidenceKeys()).containsExactly("evidence-1");
            assertThat(window.contextEvidenceKeys()).containsExactly("evidence-2");
            assertThat(window.parentWorkItemId()).isNull();
            assertThat(window.splitDepth()).isZero();
            assertThat(window.status()).isEqualTo("QUEUED");
        });
    }

    /** [Req-ID]: REQ-FTG-013 */
    @Test
    void readsHistoricalWindowRowsWithNullSemanticCoordinatesWithoutChangingTheirIdentity() {
        var historical = reviewRegistration("6".repeat(64), 1, 1, List.of("evidence-1"));

        String workId = store.register(historical);
        var restored = store.materialWindowPlans("task-1", "REQUIREMENT_MATERIAL_REVIEW", "material-1");

        assertThat(restored).singleElement().satisfies(window -> {
            assertThat(window.workItemId()).isEqualTo(workId);
            assertThat(window.identityKey()).isEqualTo(historical.identityKey());
            assertThat(window.materialDocumentId()).isNull();
            assertThat(window.contextEvidenceKeys()).isEmpty();
            assertThat(window.parentWorkItemId()).isNull();
            assertThat(window.splitDepth()).isZero();
        });
    }

    /** [Req-ID]: REQ-FTG-013 */
    @Test
    void rejectsContextFromAnotherDocumentOrWithANearestUnitGap() {
        jdbc.update("""
                INSERT INTO material_inventory_document
                (task_id, document_id, knowledge_id, document_role, total_units, complete)
                VALUES ('task-1', 'document-2', 'knowledge-2', 'REQUIREMENT', 1, TRUE)
                """);
        jdbc.update("""
                INSERT INTO material_inventory_unit
                (task_id, document_id, unit_id, document_role, chunk_index, ordinal, content, start_at, end_at)
                VALUES ('task-1', 'document-2', 'other-unit', 'REQUIREMENT', 0, 1, '其他材料', 0, 4)
                """);

        assertThatThrownBy(() -> store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "7".repeat(64), "requirement-material-quality-review",
                "REQUIREMENT_MATERIAL_REVIEW", 1, 1, "material-1", "requirements",
                List.of("evidence-1"), null, null,
                "document-1", List.of("other-unit"), null, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Context");
    }

    /** [Req-ID]: REQ-FTG-013 */
    @Test
    void splitChildrenPersistLineageAndIndependentRecomputedContextAtomically() {
        replaceInventoryWithEightUnits();
        var parent = semanticReviewRegistration("7".repeat(64), 3, 6,
                List.of("unit-3", "unit-4", "unit-5", "unit-6"),
                List.of("unit-1", "unit-2", "unit-7", "unit-8"), null, 0);
        String parentId = store.register(parent);
        var claim = store.claimRegistered("task-1", parentId, "worker-1").orElseThrow();
        var left = semanticReviewRegistration("8".repeat(64), 3, 4,
                List.of("unit-3", "unit-4"), List.of("unit-1", "unit-2", "unit-5", "unit-6"), parentId, 1);
        var right = semanticReviewRegistration("9".repeat(64), 5, 6,
                List.of("unit-5", "unit-6"), List.of("unit-3", "unit-4", "unit-7", "unit-8"), parentId, 1);

        store.splitReviewWork(claim, left, right);

        assertThat(store.materialWindowPlans("task-1", "REQUIREMENT_MATERIAL_REVIEW", "material-1"))
                .filteredOn(window -> parentId.equals(window.parentWorkItemId()))
                .extracting(StructuredGenerationAcceptanceStore.MaterialWindowPlan::targetEvidenceKeys,
                        StructuredGenerationAcceptanceStore.MaterialWindowPlan::contextEvidenceKeys,
                        StructuredGenerationAcceptanceStore.MaterialWindowPlan::splitDepth)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(left.allowedEvidenceKeys(), left.contextEvidenceKeys(), 1),
                        org.assertj.core.groups.Tuple.tuple(right.allowedEvidenceKeys(), right.contextEvidenceKeys(), 1));
    }

    /** [Req-ID]: REQ-FTG-013 */
    @Test
    void concurrentSemanticSplitPublishesOneLineagePairOnly() throws Exception {
        replaceInventoryWithEightUnits();
        var parent = semanticReviewRegistration("7".repeat(64), 3, 6,
                List.of("unit-3", "unit-4", "unit-5", "unit-6"),
                List.of("unit-1", "unit-2", "unit-7", "unit-8"), null, 0);
        String parentId = store.register(parent);
        var claim = store.claimRegistered("task-1", parentId, "worker-1").orElseThrow();
        var left = semanticReviewRegistration("8".repeat(64), 3, 4,
                List.of("unit-3", "unit-4"), List.of("unit-1", "unit-2", "unit-5", "unit-6"), parentId, 1);
        var right = semanticReviewRegistration("9".repeat(64), 5, 6,
                List.of("unit-5", "unit-6"), List.of("unit-3", "unit-4", "unit-7", "unit-8"), parentId, 1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> results = List.of(
                    workers.submit(() -> { start.await(); return catchThrowable(() ->
                            store.splitReviewWork(claim, left, right)); }),
                    workers.submit(() -> { start.await(); return catchThrowable(() ->
                            store.splitReviewWork(claim, left, right)); }));
            start.countDown();
            assertThat(results.stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).filter(java.util.Objects::isNull).count()).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }

        assertThat(store.materialWindowPlans("task-1", "REQUIREMENT_MATERIAL_REVIEW", "material-1"))
                .filteredOn(window -> parentId.equals(window.parentWorkItemId()))
                .hasSize(2)
                .allSatisfy(window -> assertThat(window.splitDepth()).isEqualTo(1));
    }

    /** [Req-ID]: REQ-FTG-010 */
    @Test
    void atomicallySplitsOneOversizedReviewWorkAndCountsOnlyItsLeafChildren() {
        List<String> evidence = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(index -> "unit-" + index).toList();
        var parent = reviewRegistration("a".repeat(64), 1, 32, evidence);
        String parentId = store.register(parent);
        var claim = store.claimRegistered("task-1", parentId, "worker-1").orElseThrow();
        var sibling = reviewRegistration("d".repeat(64), 33, 33, List.of("sibling-unit"));
        String siblingId = store.register(sibling);
        String siblingHash = "9".repeat(64);
        jdbc.update("UPDATE structured_generation_work_item SET status = 'COMPLETED', accepted_result_sha256 = ? WHERE id = ?",
                siblingHash, siblingId);
        jdbc.update("""
                INSERT INTO structured_review_finding
                (work_item_id, task_id, finding_key, issue_type, description, test_design_impact,
                 current_project_recommendation, design_center_guideline_recommendation, handling_level)
                VALUES (?, 'task-1', 'retained-finding', '缺口', '已接受说明', '已接受影响', '已接受建议', '已接受指南', 'IMPROVEMENT')
                """, siblingId);
        jdbc.update("""
                INSERT INTO structured_reference_binding
                (work_item_id, subject_key, subject_type, reference_type, reference_key)
                VALUES (?, 'retained-finding', 'REVIEW_FINDING', 'EVIDENCE', 'retained-evidence')
                """, siblingId);

        var left = reviewRegistration("b".repeat(64), 1, 16, evidence.subList(0, 16));
        var right = reviewRegistration("c".repeat(64), 17, 32, evidence.subList(16, 32));
        store.splitReviewWork(claim, left, right);

        assertSoftly(softly -> {
            softly.assertThat(store.isSplit(parentId)).isTrue();
            softly.assertThat(jdbc.queryForMap("SELECT status, accepted_result_sha256 FROM structured_generation_work_item WHERE id = ?", parentId))
                    .containsEntry("status", "SPLIT").containsEntry("accepted_result_sha256", null);
            softly.assertThat(jdbc.queryForMap("SELECT status, failure_type FROM structured_generation_attempt WHERE id = ?", claim.attemptId()))
                    .containsEntry("status", "FAILED").containsEntry("failure_type", "response_too_large");
            softly.assertThat(jdbc.queryForList("SELECT ordinal_start FROM structured_generation_work_item "
                            + "WHERE task_id = 'task-1' AND status = 'QUEUED' ORDER BY ordinal_start", Integer.class))
                    .containsExactly(1, 17);
            softly.assertThat(store.aggregateState("task-1").totalReviewWork()).isEqualTo(3);
            softly.assertThat(store.aggregateState("task-1").completedReviewWork()).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject("SELECT accepted_result_sha256 FROM structured_generation_work_item WHERE id = ?",
                    String.class, siblingId)).isEqualTo(siblingHash);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_review_finding WHERE task_id = 'task-1'", Integer.class)).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_reference_binding WHERE work_item_id = ?", Integer.class,
                    siblingId)).isEqualTo(1);
        });
    }

    /** [Req-ID]: REQ-FTG-010 */
    @Test
    void splitRollbackKeepsParentRunningWhenAChildIdentityConflicts() {
        List<String> evidence = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(index -> "unit-" + index).toList();
        String parentId = store.register(reviewRegistration("a".repeat(64), 1, 32, evidence));
        var claim = store.claimRegistered("task-1", parentId, "worker-1").orElseThrow();
        store.register(reviewRegistration("b".repeat(64), 2, 16, evidence.subList(0, 15)));

        assertThatThrownBy(() -> store.splitReviewWork(claim,
                reviewRegistration("b".repeat(64), 1, 16, evidence.subList(0, 16)),
                reviewRegistration("c".repeat(64), 17, 32, evidence.subList(16, 32))))
                .isInstanceOf(IllegalStateException.class);

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_work_item WHERE id = ?",
                    String.class, parentId)).isEqualTo("RUNNING");
            softly.assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_attempt WHERE id = ?",
                    String.class, claim.attemptId())).isEqualTo("RUNNING");
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_work_item WHERE identity_key = ?",
                    Integer.class, "c".repeat(64))).isZero();
        });
    }

    /** [Req-ID]: REQ-FTG-010 */
    @Test
    void concurrentReviewSplitPublishesExactlyOnePairOfChildren() throws Exception {
        List<String> evidence = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(index -> "unit-" + index).toList();
        String parentId = store.register(reviewRegistration("a".repeat(64), 1, 32, evidence));
        var claim = store.claimRegistered("task-1", parentId, "worker-1").orElseThrow();
        var left = reviewRegistration("b".repeat(64), 1, 16, evidence.subList(0, 16));
        var right = reviewRegistration("c".repeat(64), 17, 32, evidence.subList(16, 32));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> results = List.of(
                    workers.submit(() -> { start.await(); return catchThrowable(() -> store.splitReviewWork(claim, left, right)); }),
                    workers.submit(() -> { start.await(); return catchThrowable(() -> store.splitReviewWork(claim, left, right)); }));
            start.countDown();
            assertThat(results.stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).filter(java.util.Objects::isNull).count()).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_work_item "
                + "WHERE task_id = 'task-1' AND identity_key IN (?, ?)", Integer.class,
                left.identityKey(), right.identityKey())).isEqualTo(2);
    }

    /** [Req-ID]: REQ-FTG-012 */
    @Test
    void atomicallySplitsOneOversizedFunctionListExtractionWithoutChangingAcceptedRows() {
        List<String> evidence = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(index -> "fn-unit-" + index).toList();
        String parentId = store.register(extractionRegistration("a".repeat(64), 1, 32, evidence));
        var claim = store.claimRegistered("task-1", parentId, "worker-1").orElseThrow();
        String retainedId = store.register(extractionRegistration("d".repeat(64), 33, 33, List.of("retained-unit")));
        String retainedHash = "8".repeat(64);
        jdbc.update("UPDATE structured_generation_work_item SET status = 'COMPLETED', accepted_result_sha256 = ? WHERE id = ?",
                retainedHash, retainedId);
        jdbc.update("""
                INSERT INTO structured_function_list_item
                (work_item_id, task_id, item_key, path_text, description)
                VALUES (?, 'task-1', 'retained-item', '保留路径', '保留说明')
                """, retainedId);
        jdbc.update("""
                INSERT INTO structured_reference_binding
                (work_item_id, subject_key, subject_type, reference_type, reference_key)
                VALUES (?, 'retained-item', 'FUNCTION_LIST_ITEM', 'EVIDENCE', 'retained-unit')
                """, retainedId);

        var left = extractionRegistration("b".repeat(64), 1, 16, evidence.subList(0, 16));
        var right = extractionRegistration("c".repeat(64), 17, 32, evidence.subList(16, 32));
        store.splitFunctionListExtractionWork(claim, left, right);

        assertSoftly(softly -> {
            softly.assertThat(store.isSplit(parentId)).isTrue();
            softly.assertThat(jdbc.queryForMap(
                            "SELECT status, accepted_result_sha256 FROM structured_generation_work_item WHERE id = ?", parentId))
                    .containsEntry("status", "SPLIT").containsEntry("accepted_result_sha256", null);
            softly.assertThat(jdbc.queryForMap(
                            "SELECT status, failure_type FROM structured_generation_attempt WHERE id = ?", claim.attemptId()))
                    .containsEntry("status", "FAILED").containsEntry("failure_type", "response_too_large");
            softly.assertThat(jdbc.queryForList("SELECT ordinal_start FROM structured_generation_work_item "
                            + "WHERE task_id = 'task-1' AND status = 'QUEUED' ORDER BY ordinal_start", Integer.class))
                    .containsExactly(1, 17);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT accepted_result_sha256 FROM structured_generation_work_item WHERE id = ?",
                    String.class, retainedId)).isEqualTo(retainedHash);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_function_list_item WHERE task_id = 'task-1'",
                    Integer.class)).isEqualTo(1);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_reference_binding WHERE work_item_id = ?",
                    Integer.class, retainedId)).isEqualTo(1);
        });
    }

    /** [Req-ID]: REQ-FTG-016 */
    @Test
    void atomicallyPresplitsAQueuedHistoricalExtractionWithoutCreatingAParentAttempt() {
        insertFunctionListInventory(32);
        List<String> evidence = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(index -> "fn-unit-" + index).toList();
        String parentId = store.register(historicalExtractionRegistration(
                "a".repeat(64), 1, 32, evidence));
        var left = historicalExtractionRegistration(
                "b".repeat(64), 1, 16, evidence.subList(0, 16));
        var right = historicalExtractionRegistration(
                "c".repeat(64), 17, 32, evidence.subList(16, 32));

        assertThat(store.splitQueuedHistoricalFunctionListExtractionWork(parentId, left, right)).isTrue();

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class, parentId))
                    .isEqualTo("SPLIT");
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", Integer.class, parentId))
                    .isZero();
            softly.assertThat(jdbc.queryForList("""
                            SELECT ordinal_start, ordinal_end, allowed_evidence_keys_json
                            FROM structured_generation_work_item
                            WHERE task_id = 'task-1' AND identity_key IN (?, ?)
                            ORDER BY ordinal_start
                            """, left.identityKey(), right.identityKey()))
                    .satisfiesExactly(
                            row -> {
                                softly.assertThat(row.get("ordinal_start")).isEqualTo(1L);
                                softly.assertThat(row.get("ordinal_end")).isEqualTo(16L);
                            },
                            row -> {
                                softly.assertThat(row.get("ordinal_start")).isEqualTo(17L);
                                softly.assertThat(row.get("ordinal_end")).isEqualTo(32L);
                            });
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE task_id = 'task-1' AND status = 'QUEUED' AND operation_name = 'FEATURE_SCOPE_EXTRACT'
                    """, Integer.class)).isEqualTo(2);
        });
    }

    /** [Req-ID]: REQ-FTG-016 */
    @Test
    void concurrentHistoricalExtractionPresplitPublishesOnePairOnly() throws Exception {
        insertFunctionListInventory(32);
        List<String> evidence = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(index -> "fn-unit-" + index).toList();
        String parentId = store.register(historicalExtractionRegistration(
                "a".repeat(64), 1, 32, evidence));
        var left = historicalExtractionRegistration(
                "b".repeat(64), 1, 16, evidence.subList(0, 16));
        var right = historicalExtractionRegistration(
                "c".repeat(64), 17, 32, evidence.subList(16, 32));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = List.of(
                    workers.submit(() -> {
                        start.await();
                        return store.splitQueuedHistoricalFunctionListExtractionWork(parentId, left, right);
                    }),
                    workers.submit(() -> {
                        start.await();
                        return store.splitQueuedHistoricalFunctionListExtractionWork(parentId, left, right);
                    }));
            start.countDown();
            assertThat(results.stream().map(result -> {
                try {
                    return result.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList()).containsExactlyInAnyOrder(true, false);
        } finally {
            workers.shutdownNow();
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM structured_generation_work_item
                WHERE task_id = 'task-1' AND identity_key IN (?, ?)
                """, Integer.class, left.identityKey(), right.identityKey())).isEqualTo(2);
    }

    /** [Req-ID]: REQ-FTG-016 */
    @Test
    void historicalExtractionPresplitRollsBackWhenSecondChildConflicts() {
        insertFunctionListInventory(32);
        List<String> evidence = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(index -> "fn-unit-" + index).toList();
        String parentId = store.register(historicalExtractionRegistration(
                "a".repeat(64), 1, 32, evidence));
        var left = historicalExtractionRegistration(
                "b".repeat(64), 1, 16, evidence.subList(0, 16));
        var conflictingRight = historicalExtractionRegistration(
                left.identityKey(), 17, 32, evidence.subList(16, 32));

        assertThatThrownBy(() -> store.splitQueuedHistoricalFunctionListExtractionWork(
                parentId, left, conflictingRight)).isInstanceOf(IllegalStateException.class);

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class, parentId))
                    .isEqualTo("QUEUED");
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE task_id = 'task-1' AND identity_key = ?
                    """, Integer.class, left.identityKey())).isZero();
        });
    }

    /** [Req-ID]: REQ-FTG-016 */
    @Test
    void historicalExtractionPresplitRejectsAcceptedHash() {
        insertFunctionListInventory(32);
        List<String> firstEvidence = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(index -> "fn-unit-" + index).toList();
        String acceptedParent = store.register(historicalExtractionRegistration(
                "a".repeat(64), 1, 32, firstEvidence));
        jdbc.update("UPDATE structured_generation_work_item SET accepted_result_sha256 = ? WHERE id = ?",
                "f".repeat(64), acceptedParent);
        var firstLeft = historicalExtractionRegistration(
                "b".repeat(64), 1, 16, firstEvidence.subList(0, 16));
        var firstRight = historicalExtractionRegistration(
                "c".repeat(64), 17, 32, firstEvidence.subList(16, 32));

        assertThatThrownBy(() -> store.splitQueuedHistoricalFunctionListExtractionWork(
                acceptedParent, firstLeft, firstRight)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM structured_generation_work_item
                WHERE task_id = 'task-1' AND identity_key IN (?, ?)
                """, Integer.class, firstLeft.identityKey(), firstRight.identityKey())).isZero();
    }

    /** [Req-ID]: REQ-FTG-016 */
    @ParameterizedTest
    @ValueSource(strings = {"structured_requirement_fact", "structured_review_finding",
            "structured_function_list_item", "structured_feature_reconciliation", "structured_test_point",
            "structured_test_case", "structured_test_case_step", "structured_reference_binding",
            "structured_function_source_outcome", "structured_function_candidate",
            "structured_function_outcome_candidate"})
    void historicalExtractionPresplitRejectsEveryKindOfPartialBusinessRow(String table) {
        insertFunctionListInventory(32);
        List<String> evidence = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(index -> "fn-unit-" + index).toList();
        String parentId = store.register(historicalExtractionRegistration(
                "a".repeat(64), 1, 32, evidence));
        insertPartialBusinessRow(table, parentId);
        var left = historicalExtractionRegistration(
                "b".repeat(64), 1, 16, evidence.subList(0, 16));
        var right = historicalExtractionRegistration(
                "c".repeat(64), 17, 32, evidence.subList(16, 32));

        assertThatThrownBy(() -> store.splitQueuedHistoricalFunctionListExtractionWork(parentId, left, right))
                .isInstanceOf(IllegalStateException.class);
        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class, parentId))
                    .isEqualTo("QUEUED");
            softly.assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM structured_generation_work_item
                    WHERE task_id = 'task-1' AND identity_key IN (?, ?)
                    """, Integer.class, left.identityKey(), right.identityKey())).isZero();
        });
    }

    /** [Req-ID]: REQ-FTG-012 */
    @Test
    void concurrentFunctionListExtractionSplitPublishesExactlyOnePairOfChildren() throws Exception {
        List<String> evidence = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(index -> "fn-unit-" + index).toList();
        String parentId = store.register(extractionRegistration("a".repeat(64), 1, 32, evidence));
        var claim = store.claimRegistered("task-1", parentId, "worker-1").orElseThrow();
        var left = extractionRegistration("b".repeat(64), 1, 16, evidence.subList(0, 16));
        var right = extractionRegistration("c".repeat(64), 17, 32, evidence.subList(16, 32));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> results = List.of(
                    workers.submit(() -> { start.await(); return catchThrowable(() ->
                            store.splitFunctionListExtractionWork(claim, left, right)); }),
                    workers.submit(() -> { start.await(); return catchThrowable(() ->
                            store.splitFunctionListExtractionWork(claim, left, right)); }));
            start.countDown();
            assertThat(results.stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).filter(java.util.Objects::isNull).count()).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_work_item "
                + "WHERE task_id = 'task-1' AND identity_key IN (?, ?)", Integer.class,
                left.identityKey(), right.identityKey())).isEqualTo(2);
    }

    /** [Req-ID]: REQ-FTG-012 */
    @Test
    void functionListExtractionSplitRejectsAnotherOperationAndRollsBack() {
        List<String> evidence = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(index -> "unit-" + index).toList();
        String parentId = store.register(reviewRegistration("a".repeat(64), 1, 32, evidence));
        var claim = store.claimRegistered("task-1", parentId, "worker-1").orElseThrow();

        assertThatThrownBy(() -> store.splitFunctionListExtractionWork(claim,
                extractionRegistration("b".repeat(64), 1, 16, evidence.subList(0, 16)),
                extractionRegistration("c".repeat(64), 17, 32, evidence.subList(16, 32))))
                .isInstanceOf(IllegalStateException.class);

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_work_item WHERE id = ?",
                    String.class, parentId)).isEqualTo("RUNNING");
            softly.assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_attempt WHERE id = ?",
                    String.class, claim.attemptId())).isEqualTo("RUNNING");
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_work_item WHERE identity_key IN (?, ?)",
                    Integer.class, "b".repeat(64), "c".repeat(64))).isZero();
        });
    }

    /** [Req-ID]: REQ-FTG-012 */
    @Test
    void functionListExtractionSplitRejectsAParentWithPartialBusinessRows() {
        List<String> evidence = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(index -> "fn-unit-" + index).toList();
        String parentId = store.register(extractionRegistration("a".repeat(64), 1, 32, evidence));
        var claim = store.claimRegistered("task-1", parentId, "worker-1").orElseThrow();
        jdbc.update("""
                INSERT INTO structured_function_list_item
                (work_item_id, task_id, item_key, path_text, description)
                VALUES (?, 'task-1', 'partial-item', '部分路径', '不应存在的部分写入')
                """, parentId);

        assertThatThrownBy(() -> store.splitFunctionListExtractionWork(claim,
                extractionRegistration("b".repeat(64), 1, 16, evidence.subList(0, 16)),
                extractionRegistration("c".repeat(64), 17, 32, evidence.subList(16, 32))))
                .isInstanceOf(IllegalStateException.class);

        assertSoftly(softly -> {
            softly.assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_work_item WHERE id = ?",
                    String.class, parentId)).isEqualTo("RUNNING");
            softly.assertThat(jdbc.queryForObject("SELECT status FROM structured_generation_attempt WHERE id = ?",
                    String.class, claim.attemptId())).isEqualTo("RUNNING");
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_work_item WHERE identity_key IN (?, ?)",
                    Integer.class, "b".repeat(64), "c".repeat(64))).isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structured_function_list_item WHERE work_item_id = ?",
                    Integer.class, parentId)).isEqualTo(1);
        });
    }

    private static StructuredGenerationAcceptanceStore.WorkRegistration reviewRegistration(
            String identity, int ordinalStart, int ordinalEnd, List<String> evidence) {
        return new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", identity,
                "requirement-material-quality-review", "REQUIREMENT_MATERIAL_REVIEW",
                ordinalStart, ordinalEnd, "material-1", "requirements", evidence, null, null);
    }

    private static StructuredGenerationAcceptanceStore.WorkRegistration extractionRegistration(
            String identity, int ordinalStart, int ordinalEnd, List<String> evidence) {
        return new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", identity,
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT",
                ordinalStart, ordinalEnd, "material-1", "function list", evidence, null, null);
    }

    private static StructuredGenerationAcceptanceStore.WorkRegistration historicalExtractionRegistration(
            String identity, int ordinalStart, int ordinalEnd, List<String> evidence) {
        return new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", identity,
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT",
                ordinalStart, ordinalEnd, "function-list-1", "function list", evidence, null, null);
    }

    private void insertFunctionListInventory(int count) {
        jdbc.update("""
                INSERT INTO material_inventory_document
                (task_id, document_id, knowledge_id, document_role, total_units, complete)
                VALUES ('task-1', 'function-list-1', 'function-list-1', 'FUNCTION_LIST', ?, TRUE)
                """, count);
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            jdbc.update("""
                    INSERT INTO material_inventory_unit
                    (task_id, document_id, unit_id, document_role, chunk_index, ordinal, content, start_at, end_at)
                    VALUES ('task-1', 'function-list-1', ?, 'FUNCTION_LIST', ?, ?, ?, 0, 4)
                    """, "fn-unit-" + ordinal, ordinal - 1, ordinal, "功能 " + ordinal);
        }
    }

    private static StructuredGenerationAcceptanceStore.WorkRegistration semanticReviewRegistration(
            String identity, int ordinalStart, int ordinalEnd, List<String> target, List<String> context,
            String parentWorkItemId, int splitDepth) {
        return new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", identity,
                "requirement-material-quality-review", "REQUIREMENT_MATERIAL_REVIEW",
                ordinalStart, ordinalEnd, "material-1", "requirements", target, null, null,
                "document-1", context, parentWorkItemId, splitDepth);
    }

    private void replaceInventoryWithEightUnits() {
        jdbc.update("DELETE FROM material_inventory_unit WHERE task_id = 'task-1'");
        jdbc.update("UPDATE material_inventory_document SET total_units = 8 WHERE task_id = 'task-1' AND document_id = 'document-1'");
        for (int ordinal = 1; ordinal <= 8; ordinal++) {
            jdbc.update("""
                    INSERT INTO material_inventory_unit
                    (task_id, document_id, unit_id, document_role, chunk_index, ordinal, content, start_at, end_at)
                    VALUES ('task-1', 'document-1', ?, 'REQUIREMENT', ?, ?, ?, ?, ?)
                    """, "unit-" + ordinal, ordinal - 1, ordinal, "content-" + ordinal,
                    ordinal * 10L, ordinal * 10L + 5);
        }
    }

    private static StructuredValidationRegistry testcaseRegistry() {
        return StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.FUNCTION, "function-1")
                .register(StructuredKeyType.TEST_POINT, "point-1")
                .register(StructuredKeyType.REQUIREMENT_FACT, "fact-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true));
    }

    private static FunctionalTestcaseResultValidator.WorkItem groundedWorkItem(StructuredValidationRegistry registry) {
        FunctionalTestcaseResultValidator.FormalSupport support = new FunctionalTestcaseResultValidator.FormalSupport(
                "fact-1", "用户中心→账号登录", List.of("已注册且状态正常的用户"),
                List.of("用户在登录页提交账号和正确密码"), List.of("账号", "正确密码"),
                List.of("用户必须已注册且状态正常", "密码必须正确"),
                List.of("系统进入首页", "首页显示当前用户名称"), List.of(),
                List.of("用户会话状态从匿名变为已登录"), List.of(), List.of(),
                Map.of("evidence-1", "已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称"));
        return new FunctionalTestcaseResultValidator.WorkItem(
                registry, "function-1", "用户中心→账号登录", "point-1",
                "已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称",
                FunctionalTestcaseResultValidator.TestPointType.NORMAL_BEHAVIOR,
                FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT,
                List.of("fact-1"), List.of("evidence-1"), List.of(), List.of(support));
    }

    private static FunctionalTestcaseResultValidator.Result testcaseResult(
            String title, String precondition, String action, String expected) {
        FunctionalTestcaseResultValidator.Testcase testcase = new FunctionalTestcaseResultValidator.Testcase(
                "case-1", title, List.of(precondition),
                List.of(new FunctionalTestcaseResultValidator.Step(1, action, expected)),
                List.of("fact-1"), List.of("evidence-1"), FunctionalTestcaseResultValidator.CaseStatus.FORMAL, List.of());
        return new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(testcase));
    }

    private static FunctionalTestcaseResultValidator.Testcase highGranularityTestcase(String caseKey) {
        return new FunctionalTestcaseResultValidator.Testcase(
                caseKey, "用户中心→账号登录", "用户中心→账号登录", FunctionalTestcaseResultValidator.Priority.HIGH,
                List.of("用户必须已注册且状态正常"), FunctionalTestcaseResultValidator.Initialization.empty(),
                List.of(new FunctionalTestcaseResultValidator.Input("账号", FunctionalTestcaseResultValidator.InputNature.VALID,
                        FunctionalTestcaseResultValidator.InputSource.MANUAL,
                        FunctionalTestcaseResultValidator.TestMethod.EQUIVALENCE_PARTITIONING,
                        FunctionalTestcaseResultValidator.Authenticity.REAL, "")),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "用户在登录页提交账号和正确密码", "系统进入首页",
                        FunctionalTestcaseResultValidator.STEP_EVALUATION,
                        FunctionalTestcaseResultValidator.TERMINATION_OR_ERROR,
                        FunctionalTestcaseResultValidator.RESULT_COLLECTION)),
                List.of("系统进入首页"), FunctionalTestcaseResultValidator.EVALUATION,
                FunctionalTestcaseResultValidator.RESULT_EVALUATION, List.of(), FunctionalTestcaseResultValidator.RESULT_COLLECTION,
                FunctionalTestcaseResultValidator.AuthoringInformation.empty(), List.of("fact-1"), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.FORMAL, List.of());
    }

    private V2Fixture v2Fixture() {
        return v2Fixture(false);
    }

    private V2Fixture v2FixtureWithCombinedWindow() {
        return v2Fixture(true);
    }

    private V2Fixture v2Fixture(boolean combinedInitialWindow) {
        StructuredGenerationAcceptanceStore.ReconciliationSourceRef itemRef =
                new StructuredGenerationAcceptanceStore.ReconciliationSourceRef("function_list_item", "item-1");
        StructuredGenerationAcceptanceStore.ReconciliationSourceRef factRef =
                new StructuredGenerationAcceptanceStore.ReconciliationSourceRef("requirement_fact", "fact-1");
        StructuredGenerationAcceptanceStore.ReconciliationRunIdentity run =
                new StructuredGenerationAcceptanceStore.ReconciliationRunIdentity(
                        sha256Text("run-v2-task-1"), sha256Text("catalog-v2-task-1"), 1, 1);
        StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow itemWindow = ownerWindow(run.runKey(), List.of(itemRef));
        StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow factWindow = ownerWindow(run.runKey(), List.of(factRef));
        StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow combinedWindow = ownerWindow(run.runKey(), List.of(itemRef, factRef));
        List<StructuredGenerationAcceptanceStore.ReconciliationSourceRef> relationRefs = List.of(itemRef, factRef);
        String relationKey = sha256Text("reconciliation-v2\n" + run.runKey() + "\nexact_match\nconfirmed\n"
                + sourceRefsJson(relationRefs));
        StructuredGenerationAcceptanceStore.ReconciliationRelation relation =
                new StructuredGenerationAcceptanceStore.ReconciliationRelation(
                        relationKey, itemRef, List.of("item-1"), List.of("fact-1"), "exact_match",
                        List.of("evidence-1", "evidence-2"), "范围一致", "confirmed");
        StructuredGenerationAcceptanceStore.ReconciliationRunPlan plan =
                new StructuredGenerationAcceptanceStore.ReconciliationRunPlan(
                        run, combinedInitialWindow ? List.of(combinedWindow) : List.of(itemWindow, factWindow));
        StructuredGenerationAcceptanceStore.ReconciliationPageStage itemPage =
                new StructuredGenerationAcceptanceStore.ReconciliationPageStage(
                        run, itemWindow, itemWindow.ownerSourceRefs(), List.of(relation), sha256Text("item-page-result"));
        StructuredGenerationAcceptanceStore.ReconciliationPageStage factPage =
                new StructuredGenerationAcceptanceStore.ReconciliationPageStage(
                        run, factWindow, factWindow.ownerSourceRefs(), List.of(), sha256Text("fact-page-result"));
        StructuredGenerationAcceptanceStore.ReconciliationRunPublication publication =
                new StructuredGenerationAcceptanceStore.ReconciliationRunPublication(
                        run, List.of(itemWindow.pageKey(), factWindow.pageKey()), List.of(itemRef, factRef),
                        List.of(relation), sha256Text("accepted-v2-run"));
        String workId = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "d".repeat(64), "feature-scope-reconciliation", "FEATURE_SCOPE_RECONCILIATION_V2",
                null, null, null, "task-level-v2", List.of(), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim claim = store.claimRegistered("task-1", workId, "worker-v2").orElseThrow();
        return new V2Fixture(claim, run, plan, itemWindow, factWindow, itemPage, factPage, relation, publication);
    }

    private static StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow ownerWindow(
            String runKey, List<StructuredGenerationAcceptanceStore.ReconciliationSourceRef> refs) {
        return new StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow(
                sha256Text("reconcile-page-v2\n" + runKey + "\n" + sourceRefsJson(refs)), refs);
    }

    private static String sourceRefsJson(List<StructuredGenerationAcceptanceStore.ReconciliationSourceRef> refs) {
        return refs.stream().map(ref -> "{\"source_type\":\"" + ref.sourceType()
                + "\",\"source_key\":\"" + ref.sourceKey() + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String sha256Text(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256Json(Object value) {
        try {
            byte[] json = new ObjectMapper().writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (NoSuchAlgorithmException | JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record V2Fixture(
            StructuredGenerationAcceptanceStore.WorkClaim claim,
            StructuredGenerationAcceptanceStore.ReconciliationRunIdentity run,
            StructuredGenerationAcceptanceStore.ReconciliationRunPlan plan,
            StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow itemWindow,
            StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow factWindow,
            StructuredGenerationAcceptanceStore.ReconciliationPageStage itemPage,
            StructuredGenerationAcceptanceStore.ReconciliationPageStage factPage,
            StructuredGenerationAcceptanceStore.ReconciliationRelation relation,
            StructuredGenerationAcceptanceStore.ReconciliationRunPublication publication) { }

    private static StructuredGenerationAcceptanceStore.WorkRegistration reviewWork(String identityKey) {
        return reviewWork(identityKey, "evidence-1");
    }

    private static StructuredGenerationAcceptanceStore.WorkRegistration reviewWork(String identityKey, String evidenceKey) {
        return new StructuredGenerationAcceptanceStore.WorkRegistration("task-1", identityKey,
                "requirement-material-quality-review", 33, 64, "material-1", "requirements slice 33-64",
                List.of(evidenceKey), null, null);
    }
    private StructuredGenerationAcceptanceStore.WorkClaim claimReviewWork() {
        store.register(reviewWork("a".repeat(64)));
        return store.claimNext("task-1", "worker-1").orElseThrow();
    }

    /** Registers one exact V2 fact window against the retained material inventory. */
    private StructuredGenerationAcceptanceStore.WorkClaim claimV2FactWindow(
            String identity, int ordinal, String evidenceKey) {
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", identity, "requirement-fact-extraction", "REQUIREMENT_FACT_EXTRACTION_V2",
                ordinal, ordinal, "document-1", "需求材料", List.of(evidenceKey), "function-1", null,
                "document-1", List.of(), null, 0));
        return store.claimNext("task-1", "v2-worker-" + ordinal).orElseThrow();
    }

    private static RequirementFactExtractionV2Input v2FactInput(
            String windowKey, String evidenceKey, int ordinal, String content) {
        return new RequirementFactExtractionV2Input("function-1", "订单提交", "业务/订单提交", "",
                "document-1", MaterialContentTypeKey.REQUIREMENTS_SPEC, windowKey,
                List.of(new RequirementFactExtractionV2Input.MaterialUnit(evidenceKey, ordinal, content)), List.of());
    }

    private static RequirementFactExtractionV2Result v2FactResult(
            String windowKey, String evidenceKey, String quote) {
        return new RequirementFactExtractionV2Result("function-1", windowKey, List.of(
                new RequirementFactExtractionV2Result.RequirementFact(
                        RequirementFactExtractionV2Result.FactType.OUTPUT, "系统进入首页",
                        List.of(new StructuredSourceQuoteV2(evidenceKey, quote)))), List.of());
    }

    private static FunctionalTestcaseDesignV2Input v2TestcaseInput() {
        return new FunctionalTestcaseDesignV2Input("function-1", "订单提交", "业务/订单提交", "",
                new FunctionalTestcaseDesignV2Input.TestPoint("point-1",
                        FunctionalTestcaseDesignV2Input.TestPointType.NORMAL_BEHAVIOR,
                        FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT, "提交订单", List.of()),
                List.of(new FunctionalTestcaseDesignV2Input.RequirementFact("fact-1",
                        RequirementFactExtractionV2Result.FactType.BUSINESS_RULE, "提交订单",
                        List.of(new StructuredSourceQuoteV2("evidence-1", "提交订单")))));
    }

    private static FunctionalTestcaseDesignV2Result.Testcase v2Testcase(
            FunctionalTestcaseDesignV2Result.CaseStatus status, List<String> missing) {
        var step = new FunctionalTestcaseDesignV2Result.Step(1, "提交订单", "提交订单",
                "实际结果符合预期", "", "记录结果");
        return new FunctionalTestcaseDesignV2Result.Testcase("提交订单", "提交订单",
                FunctionalTestcaseDesignV2Result.Priority.MEDIUM, List.of(),
                new FunctionalTestcaseDesignV2Result.Initialization(List.of(), List.of(), List.of(), List.of()),
                List.of(), List.of(step), List.of("提交订单"), "全部步骤符合预期", "任一步失败则不通过",
                List.of(), "记录结果", List.of("fact-1"), List.of("evidence-1"), status, missing);
    }
    private static RequirementMaterialReviewValidator.WorkItem reviewItem() {
        return reviewItem("evidence-1");
    }

    private static RequirementMaterialReviewValidator.WorkItem reviewItem(String evidenceKey) {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-1")
                .registerEvidence(new StructuredEvidence(evidenceKey, "task-1", "material-1", false, false, true));
        return new RequirementMaterialReviewValidator.WorkItem(
                registry, "material-1", "requirements_spec", List.of(evidenceKey),
                Map.of(evidenceKey, reviewEvidenceTexts().get(evidenceKey)));
    }
    private static Map<String, String> reviewEvidenceTexts() {
        return Map.of(
                "evidence-1", "用户中心→账号登录 功能 role 提交订单 取消订单 事实显示名称 "
                        + "已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称",
                "evidence-2", "订单最终功能允许提交订单");
    }
    private static RequirementMaterialReviewValidator.RequirementFact fact(String key) {
        return fact(key, "功能");
    }
    private static RequirementMaterialReviewValidator.RequirementFact fact(String key, String function) {
        return fact(key, function, "evidence-1");
    }

    private static RequirementMaterialReviewValidator.RequirementFact fact(
            String key, String function, String evidenceKey) {
        List<String> roles = "evidence-1".equals(evidenceKey) ? List.of("role") : List.of();
        return new RequirementMaterialReviewValidator.RequirementFact(key, function, roles, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(evidenceKey));
    }

    private StructuredGenerationAcceptanceStore storeAt(Instant instant) {
        return new StructuredGenerationAcceptanceStore(jdbc, new TransactionTemplate(transactionManager),
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    /** Builds the generic V2 shape produced when every fact window is atomically rejected before publication. */
    private V2AtomicityRecoveryFixture v2AtomicityRecoveryFixture() {
        return v2FactRecoveryFixture(StructuredValidationFailure.Code.FACT_ATOMICITY_INVALID,
                List.of(V2GenerationPlanner.missingFormalFactInformation()));
    }

    /** Builds the same shape with an already-validated, reader-safe unable-to-generate explanation. */
    private V2AtomicityRecoveryFixture v2AtomicityRecoveryFixture(List<String> fallbackMissingInformation) {
        return v2FactRecoveryFixture(StructuredValidationFailure.Code.FACT_ATOMICITY_INVALID,
                fallbackMissingInformation);
    }

    private V2AtomicityRecoveryFixture v2FactRecoveryFixture(
            StructuredValidationFailure.Code factFailureCode, List<String> fallbackMissingInformation) {
        jdbc.update("""
                UPDATE generation_task
                SET task_mode='ALL', status='GENERATING', structured_processing_status='RUNNING',
                    structured_coverage_status='PENDING', request_snapshot=JSON_OBJECT('scope', 'frozen-v2'),
                    workflow_version='2.0', input_version='2.0', artifact_version='2.0',
                    approved_scope_version='scope-v2'
                WHERE id='task-1'
                """);
        jdbc.update("""
                INSERT INTO v2_approved_function
                (task_id, function_key, stable_sequence, scope_version, name_text, path_text, description_text)
                VALUES ('task-1', 'function-2', 2, 'scope-v2', '结果查询', '业务/结果查询', '')
                """);
        List<ApprovedFunctionScope.ApprovedFunction> functions = List.of(
                new ApprovedFunctionScope.ApprovedFunction("function-1", "订单提交", "业务/订单提交", ""),
                new ApprovedFunctionScope.ApprovedFunction("function-2", "结果查询", "业务/结果查询", ""));
        List<String> factWorkIds = new java.util.ArrayList<>();
        List<String> fallbackWorkIds = new java.util.ArrayList<>();
        V2GenerationPlanner planner = new V2GenerationPlanner();
        for (int index = 0; index < functions.size(); index++) {
            ApprovedFunctionScope.ApprovedFunction function = functions.get(index);
            String identity = Character.toString((char) ('a' + index)).repeat(64);
            String factWorkId = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                    "task-1", identity, "requirement-fact-extraction", "REQUIREMENT_FACT_EXTRACTION_V2",
                    1, 1, "document-1", "需求材料", List.of("evidence-1"), function.functionKey(), null,
                    "document-1", List.of(), null, 0));
            StructuredGenerationAcceptanceStore.WorkClaim factClaim = store.claimRegistered(
                    "task-1", factWorkId, "fact-worker-" + index).orElseThrow();
            store.fail(factClaim, "business_validation_failed", StructuredValidationFailure.of(
                    factFailureCode,
                    "$.requirement_facts[0].statement"));
            factWorkIds.add(factWorkId);

            V2GenerationPlanner.TestPointPlan fallback = planner.missingFormalFactTestPoint("task-1", function);
            String fallbackWorkId = store.registerMissingFactFallback(fallback);
            StructuredGenerationAcceptanceStore.WorkClaim fallbackClaim = store.claimRegistered(
                    "task-1", fallbackWorkId, "fallback-worker-" + index).orElseThrow();
            store.acceptTestcasesV2(fallbackClaim, new FunctionalTestcaseV2Validator(), fallback.input(),
                     new FunctionalTestcaseDesignV2Result(function.functionKey(),
                             fallback.input().testPoint().testPointKey(),
                             FunctionalTestcaseDesignV2Result.GenerationOutcome.UNABLE_TO_GENERATE,
                             fallbackMissingInformation, List.of()));
            fallbackWorkIds.add(fallbackWorkId);
        }
        jdbc.update("""
                UPDATE generation_task
                SET status='PARTIAL', structured_processing_status='FAILED',
                    structured_coverage_status='UNABLE_TO_GENERATE',
                    result_snapshot=JSON_OBJECT('terminal', true), artifact_id='artifact-v2',
                    artifact_sha256=?, artifact_path='fixture-v2.xlsx'
                WHERE id='task-1'
                """, "f".repeat(64));
        return new V2AtomicityRecoveryFixture(List.copyOf(factWorkIds), List.copyOf(fallbackWorkIds));
    }

    /** Captures fields that explicit recovery must retain byte-for-byte while current statuses/artifact coordinates change. */
    private Map<String, Object> v2AtomicityImmutableAuditSnapshot(V2AtomicityRecoveryFixture fixture) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("task-contract", jdbc.queryForList("""
                SELECT request_snapshot, workflow_version, input_version, artifact_version, approved_scope_version
                FROM generation_task WHERE id='task-1'
                """));
        snapshot.put("attempts", jdbc.queryForList("""
                SELECT id, work_item_id, attempt_number, status, failure_type,
                       validation_error_code, validation_error_path, completed_at
                FROM structured_generation_attempt
                WHERE work_item_id IN (?, ?, ?, ?) ORDER BY work_item_id, attempt_number
                """, fixture.factWorkIds().get(0), fixture.factWorkIds().get(1),
                fixture.fallbackWorkIds().get(0), fixture.fallbackWorkIds().get(1)));
        snapshot.put("fallback-hashes", jdbc.queryForList("""
                SELECT id, accepted_result_sha256 FROM structured_generation_work_item
                WHERE id IN (?, ?) ORDER BY id
                """, fixture.fallbackWorkIds().get(0), fixture.fallbackWorkIds().get(1)));
        snapshot.put("points", jdbc.queryForList("""
                SELECT work_item_id, task_id, test_point_key, function_key, function_name, test_point_type,
                       basis, description, missing_information_json, formal_coverage_satisfied
                FROM structured_test_point WHERE task_id='task-1' ORDER BY work_item_id, test_point_key
                """));
        snapshot.put("outcomes", jdbc.queryForList("""
                SELECT work_item_id, task_id, test_point_key, function_key, generation_outcome,
                       missing_information_json, formal_coverage_satisfied
                FROM v2_generation_outcome WHERE task_id='task-1' ORDER BY work_item_id
                """));
        snapshot.put("publications", jdbc.queryForList("""
                SELECT work_item_id, task_id, publication_type, input_sha256, result_sha256, published_at
                FROM v2_work_publication WHERE task_id='task-1' ORDER BY work_item_id
                """));
        return Map.copyOf(snapshot);
    }

    /** Captures both mutable retry coordinates and immutable audit rows for transaction rollback assertions. */
    private Map<String, Object> v2AtomicityFullSnapshot(V2AtomicityRecoveryFixture fixture) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("task", jdbc.queryForList("""
                SELECT status, structured_processing_status, structured_coverage_status, result_snapshot,
                       artifact_id, artifact_sha256, artifact_path, validation_error_code, validation_error_path
                FROM generation_task WHERE id='task-1'
                """));
        snapshot.put("work", jdbc.queryForList("""
                SELECT id, status, accepted_result_sha256, coverage_status, lease_owner, lease_expires_at,
                       validation_error_code, validation_error_path, validation_error_message
                FROM structured_generation_work_item WHERE task_id='task-1' ORDER BY id
                """));
        snapshot.put("audit", v2AtomicityImmutableAuditSnapshot(fixture));
        return Map.copyOf(snapshot);
    }

    private ExplicitRetryFixture explicitRetryFixture(String failureType) {
        return explicitRetryFixture(failureType, null);
    }

    private record V2AtomicityRecoveryFixture(List<String> factWorkIds, List<String> fallbackWorkIds) { }

    private record RecoveredFactWork(
            String workId, String identityKey, String functionKey, String evidenceKey, int ordinal) { }

    private ExplicitRetryFixture explicitRetryFixture(
            String failureType, StructuredValidationFailure validationFailure) {
        jdbc.update("""
                UPDATE generation_task
                SET task_mode = 'ALL', status = 'GENERATING', structured_processing_status = 'RUNNING',
                    structured_coverage_status = 'PENDING', request_snapshot = JSON_OBJECT('scope', 'frozen')
                WHERE id = 'task-1'
                """);
        String first = store.register(reviewWork("1".repeat(64)));
        String second = store.register(reviewWork("2".repeat(64)));
        String failed = store.register(reviewWork("3".repeat(64)));
        for (int index = 0; index < 2; index++) {
            String workId = index == 0 ? first : second;
            String hash = (index + 1 + "").repeat(64);
            jdbc.update("UPDATE structured_generation_work_item SET status = 'COMPLETED', accepted_result_sha256 = ? WHERE id = ?",
                    hash, workId);
            jdbc.update("""
                    INSERT INTO structured_generation_attempt
                    (id, work_item_id, attempt_number, status, completed_at)
                    VALUES (UUID(), ?, 1, 'COMPLETED', CURRENT_TIMESTAMP(6))
                    """, workId);
        }
        for (int findingIndex = 0; findingIndex < 6; findingIndex++) {
            String workId = findingIndex < 3 ? first : second;
            String findingKey = "finding-" + findingIndex;
            jdbc.update("""
                    INSERT INTO structured_review_finding
                    (work_item_id, task_id, finding_key, issue_type, description, test_design_impact,
                     current_project_recommendation, design_center_guideline_recommendation, handling_level)
                    VALUES (?, 'task-1', ?, '缺口', '说明', '影响', '建议', '指南', 'IMPROVEMENT')
                    """, workId, findingKey);
            for (int bindingIndex = 0; bindingIndex < 8; bindingIndex++) {
                jdbc.update("""
                        INSERT INTO structured_reference_binding
                        (work_item_id, subject_key, subject_type, reference_type, reference_key)
                        VALUES (?, ?, 'REVIEW_FINDING', 'EVIDENCE', ?)
                        """, workId, findingKey, "fixture-evidence-" + bindingIndex);
            }
        }
        StructuredGenerationAcceptanceStore.WorkClaim failedClaim = store.claimRegistered(
                "task-1", failed, "worker-1").orElseThrow();
        if (validationFailure == null) {
            store.fail(failedClaim, failureType);
        } else {
            store.fail(failedClaim, failureType, validationFailure);
        }
        taskRepository.failStructuredTask("task-1", StructuredCoverageStatus.PENDING);
        return new ExplicitRetryFixture(List.of(first, second), failed);
    }

    private SplitExtractionRetryFixture exhaustedNetworkExtractionFixture() {
        jdbc.update("""
                UPDATE generation_task
                SET task_mode = 'ALL', status = 'GENERATING', structured_processing_status = 'RUNNING',
                    structured_coverage_status = 'PENDING', request_snapshot = JSON_OBJECT('scope', 'frozen')
                WHERE id = 'task-1'
                """);
        insertFunctionListInventory(32);
        List<String> evidence = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(index -> "fn-unit-" + index).toList();
        var parent = historicalExtractionRegistration("a".repeat(64), 1, 32, evidence);
        var completedChild = historicalExtractionRegistration("b".repeat(64), 1, 16, evidence.subList(0, 16));
        var failedChild = historicalExtractionRegistration("c".repeat(64), 17, 32, evidence.subList(16, 32));
        String parentId = store.register(parent);
        assertThat(store.splitQueuedHistoricalFunctionListExtractionWork(parentId, completedChild, failedChild)).isTrue();
        String completedChildId = store.register(completedChild);
        String failedChildId = store.register(failedChild);

        jdbc.update("""
                UPDATE structured_generation_work_item
                SET status = 'COMPLETED', accepted_result_sha256 = ?
                WHERE id = ?
                """, "8".repeat(64), completedChildId);
        jdbc.update("""
                INSERT INTO structured_generation_attempt
                (id, work_item_id, attempt_number, status, completed_at)
                VALUES (UUID(), ?, 1, 'COMPLETED', CURRENT_TIMESTAMP(6))
                """, completedChildId);
        for (int index = 1; index <= 54; index++) {
            String itemKey = "completed-item-" + index;
            String evidenceKey = "fn-unit-" + (((index - 1) % 16) + 1);
            jdbc.update("""
                    INSERT INTO structured_function_list_item
                    (work_item_id, task_id, item_key, path_text, description)
                    VALUES (?, 'task-1', ?, ?, ?)
                    """, completedChildId, itemKey, "已完成路径/" + index, "已完成说明" + index);
            jdbc.update("""
                    INSERT INTO structured_reference_binding
                    (work_item_id, subject_key, subject_type, reference_type, reference_key)
                    VALUES (?, ?, 'FUNCTION_LIST_ITEM', 'EVIDENCE', ?)
                    """, completedChildId, itemKey, evidenceKey);
        }

        StructuredGenerationAcceptanceStore.WorkClaim firstAttempt = store.claimRegistered(
                "task-1", failedChildId, "network-worker-1").orElseThrow();
        store.fail(firstAttempt, "model_execution_failed");
        StructuredGenerationAcceptanceStore.WorkClaim secondAttempt = store.claimRegistered(
                "task-1", failedChildId, "network-worker-2").orElseThrow();
        store.fail(secondAttempt, "model_execution_failed");
        taskRepository.failStructuredTask("task-1", StructuredCoverageStatus.PENDING);
        return new SplitExtractionRetryFixture(parentId, completedChildId, failedChildId);
    }

    private ExplicitRetryFixture queuedRetryResidueFixture() {
        ExplicitRetryFixture fixture = explicitRetryFixture("structured_output_invalid");
        assertThat(taskRepository.retryFailedBatches("task-1")).isEqualTo(1);
        jdbc.update("""
                UPDATE generation_task
                SET status = 'GENERATING', structured_processing_status = 'RUNNING',
                    structured_coverage_status = 'PENDING'
                WHERE id = 'task-1'
                """);
        taskRepository.failStructuredTask("task-1", StructuredCoverageStatus.PENDING);
        return fixture;
    }

    private ExplicitRetryFixture expiredRunningReconciliationResidueFixture() {
        jdbc.update("""
                UPDATE generation_task
                SET task_mode = 'ALL', status = 'GENERATING', structured_processing_status = 'RUNNING',
                    structured_coverage_status = 'PENDING', request_snapshot = JSON_OBJECT('scope', 'frozen')
                WHERE id = 'task-1'
                """);
        String completed = store.register(reviewWork("9".repeat(64)));
        jdbc.update("""
                UPDATE structured_generation_work_item
                SET status = 'COMPLETED', accepted_result_sha256 = ? WHERE id = ?
                """, "9".repeat(64), completed);
        jdbc.update("""
                INSERT INTO structured_generation_attempt
                (id, work_item_id, attempt_number, status, completed_at)
                VALUES (UUID(), ?, 1, 'COMPLETED', CURRENT_TIMESTAMP(6))
                """, completed);
        String running = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "d".repeat(64), "feature-scope-reconciliation", "FEATURE_SCOPE_RECONCILIATION_V2",
                null, null, null, "task-level-v2", List.of(), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim claim = store.claimRegistered(
                "task-1", running, "reconciliation-worker-1").orElseThrow();
        StructuredGenerationAcceptanceStore.ReconciliationSourceRef source =
                new StructuredGenerationAcceptanceStore.ReconciliationSourceRef(
                        "function_list_item", "fixture-item-1");
        StructuredGenerationAcceptanceStore.ReconciliationRunIdentity run =
                new StructuredGenerationAcceptanceStore.ReconciliationRunIdentity(
                        sha256Text("expired-residue-run"), sha256Text("expired-residue-catalog"), 1, 0);
        StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow window =
                ownerWindow(run.runKey(), List.of(source));
        store.initializeReconciliationRun(claim,
                new StructuredGenerationAcceptanceStore.ReconciliationRunPlan(run, List.of(window)));
        jdbc.update("""
                UPDATE structured_generation_work_item
                SET lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)
                WHERE id = ?
                """, running);
        taskRepository.failStructuredTask("task-1", StructuredCoverageStatus.PENDING,
                StructuredValidationFailure.of(
                        StructuredValidationFailure.Code.STRUCTURED_COORDINATOR_CONCURRENCY_FAILURE,
                        "$.reconciliation"));
        return new ExplicitRetryFixture(List.of(completed), running);
    }

    private InvalidReconciliationStagingFixture invalidCompletedReconciliationStagingFixture() {
        return invalidCompletedReconciliationStagingFixture(8, 1);
    }

    private InvalidReconciliationStagingFixture invalidCompletedReconciliationStagingFixture(
            int sourceCount, int ownerWindowSize) {
        jdbc.update("""
                UPDATE generation_task
                SET task_mode = 'ALL', status = 'GENERATING', structured_processing_status = 'RUNNING',
                    structured_coverage_status = 'PENDING', request_snapshot = JSON_OBJECT('scope', 'frozen')
                WHERE id = 'task-1'
                """);
        String completed = store.register(reviewWork("8".repeat(64)));
        jdbc.update("""
                UPDATE structured_generation_work_item
                SET status = 'COMPLETED', accepted_result_sha256 = ? WHERE id = ?
                """, "8".repeat(64), completed);
        jdbc.update("""
                INSERT INTO structured_generation_attempt
                (id, work_item_id, attempt_number, status, completed_at)
                VALUES (UUID(), ?, 1, 'COMPLETED', CURRENT_TIMESTAMP(6))
                """, completed);
        jdbc.update("""
                INSERT INTO structured_requirement_fact
                (work_item_id, task_id, fact_key, function_name, roles_json, trigger_conditions_json,
                 inputs_json, business_rules_json, outputs_json, permissions_json, state_changes_json,
                 exception_handling_json, external_dependencies_json)
                VALUES (?, 'task-1', 'preserved-fact', '保留功能', JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(),
                        JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY())
                """, completed);
        jdbc.update("""
                INSERT INTO structured_review_finding
                (work_item_id, task_id, finding_key, issue_type, description, test_design_impact,
                 current_project_recommendation, design_center_guideline_recommendation, handling_level)
                VALUES (?, 'task-1', 'preserved-finding', '缺口', '说明', '影响', '建议', '指南', 'IMPROVEMENT')
                """, completed);
        jdbc.update("""
                INSERT INTO structured_function_list_item
                (work_item_id, task_id, item_key, path_text, description)
                VALUES (?, 'task-1', 'preserved-function', '保留/功能', '说明')
                """, completed);
        jdbc.update("""
                INSERT INTO structured_reference_binding
                (work_item_id, subject_key, subject_type, reference_type, reference_key)
                VALUES (?, 'preserved-fact', 'REQUIREMENT_FACT', 'EVIDENCE', 'evidence-1')
                """, completed);

        String failed = store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", "7".repeat(64), "feature-scope-reconciliation", "FEATURE_SCOPE_RECONCILIATION_V2",
                null, null, null, "task-level-v2", List.of(), null, null));
        StructuredGenerationAcceptanceStore.WorkClaim claim = store.claimRegistered(
                "task-1", failed, "invalid-staging-worker").orElseThrow();
        List<StructuredGenerationAcceptanceStore.ReconciliationSourceRef> sources =
                java.util.stream.IntStream.rangeClosed(1, sourceCount)
                        .mapToObj(index -> new StructuredGenerationAcceptanceStore.ReconciliationSourceRef(
                                 "function_list_item", "staged-item-%04d".formatted(index)))
                        .toList();
        StructuredGenerationAcceptanceStore.ReconciliationRunIdentity run =
                new StructuredGenerationAcceptanceStore.ReconciliationRunIdentity(
                        sha256Text("invalid-staging-run" + (sourceCount == 8 && ownerWindowSize == 1
                                ? "" : "-" + sourceCount + "-" + ownerWindowSize)),
                        sha256Text("invalid-staging-catalog" + (sourceCount == 8
                                ? "" : "-" + sourceCount)), sourceCount, 0);
        List<StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow> windows =
                java.util.stream.IntStream.iterate(0, start -> start < sources.size(), start -> start + ownerWindowSize)
                        .mapToObj(start -> ownerWindow(run.runKey(),
                                sources.subList(start, Math.min(start + ownerWindowSize, sources.size()))))
                        .toList();
        store.initializeReconciliationRun(claim,
                new StructuredGenerationAcceptanceStore.ReconciliationRunPlan(run, windows));
        for (int index = 0; index < windows.size(); index++) {
            var window = windows.get(index);
            List<StructuredGenerationAcceptanceStore.ReconciliationSourceRef> windowSources =
                    window.ownerSourceRefs();
            String relationKey = sha256Text("reconciliation-v2\n" + run.runKey()
                    + "\nfunction_list_only\nconfirmed\n" + sourceRefsJson(windowSources));
            var relation = new StructuredGenerationAcceptanceStore.ReconciliationRelation(
                    relationKey, windowSources.get(0), windowSources.stream()
                            .map(StructuredGenerationAcceptanceStore.ReconciliationSourceRef::sourceKey).toList(),
                    List.of(), "function_list_only",
                    List.of("evidence-1"), "仅见功能清单", "confirmed");
            store.stageReconciliationPage(claim,
                    new StructuredGenerationAcceptanceStore.ReconciliationPageStage(
                            run, window, window.ownerSourceRefs(), List.of(relation),
                            sha256Text("invalid-page-result-" + index)));
        }
        StructuredValidationFailure failure = StructuredValidationFailure.of(
                StructuredValidationFailure.Code.RECONCILIATION_V2_RESULT_INVALID,
                "$.reconciliation_run");
        store.fail(claim, "business_validation_failed", failure);
        taskRepository.failStructuredTask("task-1", StructuredCoverageStatus.PENDING, failure);
        return new InvalidReconciliationStagingFixture(completed, failed,
                new StructuredGenerationAcceptanceStore.ReconciliationRunPlan(run, windows));
    }

    /** Builds the durable shape left when a V2 page model call fails before that planned page writes staging. */
    private ReconciliationModelFailureFixture zeroWriteReconciliationModelFailureFixture() {
        return zeroWriteReconciliationFailureFixture("model_execution_failed", 2);
    }

    private ReconciliationModelFailureFixture zeroWriteReconciliationStructuralFailureFixture() {
        return zeroWriteReconciliationFailureFixture("structured_output_invalid", 3);
    }

    private ReconciliationModelFailureFixture zeroWriteReconciliationFailureFixture(
            String failureType, int completedPageCount) {
        return zeroWriteReconciliationFailureFixture(failureType, 8, 1, completedPageCount);
    }

    private ReconciliationModelFailureFixture zeroWriteReconciliationFailureFixture(
            String failureType, int sourceCount, int ownerWindowSize, int completedPageCount) {
        InvalidReconciliationStagingFixture base =
                invalidCompletedReconciliationStagingFixture(sourceCount, ownerWindowSize);
        List<StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow> planned =
                base.plan().initialOwnerWindows().subList(completedPageCount, base.plan().initialOwnerWindows().size());
        for (StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow window : planned) {
            jdbc.update("""
                    DELETE FROM structured_reconciliation_relation_stage_binding
                    WHERE work_item_id = ? AND reconciliation_key IN (
                        SELECT reconciliation_key FROM structured_reconciliation_relation_stage
                        WHERE work_item_id = ? AND page_key = ?)
                    """, base.failedWorkId(), base.failedWorkId(), window.pageKey());
            jdbc.update("""
                    DELETE FROM structured_reconciliation_relation_stage WHERE work_item_id = ? AND page_key = ?
                    """, base.failedWorkId(), window.pageKey());
            jdbc.update("""
                    UPDATE structured_reconciliation_page_stage
                    SET status = 'PLANNED', completed_owner_source_refs_json = NULL,
                        result_sha256 = NULL, completed_at = NULL
                    WHERE work_item_id = ? AND page_key = ?
                    """, base.failedWorkId(), window.pageKey());
        }
        jdbc.update("""
                UPDATE structured_generation_attempt
                SET failure_type = ?, validation_error_code = NULL,
                    validation_error_path = NULL, validation_error_message = NULL
                WHERE work_item_id = ? AND attempt_number = 1
                """, failureType, base.failedWorkId());
        jdbc.update("""
                UPDATE structured_generation_work_item
                SET validation_error_code = NULL, validation_error_path = NULL, validation_error_message = NULL
                WHERE id = ?
                """, base.failedWorkId());
        jdbc.update("""
                UPDATE generation_task
                SET validation_error_code = NULL, validation_error_path = NULL, validation_error_message = NULL
                WHERE id = 'task-1'
                """);
        return new ReconciliationModelFailureFixture(base.failedWorkId(), base.plan(), List.copyOf(planned));
    }

    private List<Map<String, Object>> reconciliationRunSnapshot(String workItemId) {
        return jdbc.queryForList("""
                SELECT work_item_id, task_id, run_key, catalog_sha256, function_item_count,
                       requirement_fact_count, catalog_source_refs_json, initial_page_keys_json,
                       status, accepted_result_sha256, created_at, updated_at
                FROM structured_reconciliation_run WHERE work_item_id = ?
                """, workItemId);
    }

    private List<Map<String, Object>> reconciliationPageSnapshot(String workItemId) {
        return jdbc.queryForList("""
                SELECT work_item_id, page_key, run_key, catalog_sha256, parent_page_key, status,
                       first_source_type, first_source_key, owner_source_refs_json,
                       completed_owner_source_refs_json, result_sha256, created_at, completed_at
                FROM structured_reconciliation_page_stage WHERE work_item_id = ? ORDER BY page_key
                """, workItemId);
    }

    private List<Map<String, Object>> reconciliationRelationSnapshot(String workItemId) {
        return jdbc.queryForList("""
                SELECT work_item_id, page_key, reconciliation_key, owner_source_type, owner_source_key,
                       classification, scope_recommendation, confirmation_status
                FROM structured_reconciliation_relation_stage WHERE work_item_id = ?
                ORDER BY page_key, reconciliation_key
                """, workItemId);
    }

    private List<Map<String, Object>> reconciliationStageBindingSnapshot(String workItemId) {
        return jdbc.queryForList("""
                SELECT work_item_id, reconciliation_key, reference_type, reference_key
                FROM structured_reconciliation_relation_stage_binding WHERE work_item_id = ?
                ORDER BY reconciliation_key, reference_type, reference_key
                """, workItemId);
    }

    private List<Map<String, Object>> attemptSnapshot(String workItemId) {
        return jdbc.queryForList("""
                SELECT id, work_item_id, attempt_number, status, failure_type, validation_error_code,
                       validation_error_path, validation_error_message, created_at, completed_at
                FROM structured_generation_attempt WHERE work_item_id = ? ORDER BY attempt_number
                """, workItemId);
    }

    private ReconciliationRetrySnapshot reconciliationRetrySnapshot(String workItemId) {
        return new ReconciliationRetrySnapshot(
                generationTaskSnapshot("task-1"),
                structuredWorkSnapshot("task-1"),
                taskAttemptSnapshot("task-1"),
                reconciliationRunSnapshot(workItemId),
                reconciliationPageSnapshot(workItemId),
                reconciliationRelationSnapshot(workItemId),
                reconciliationStageBindingSnapshot(workItemId),
                referenceBindingSnapshot("task-1"),
                taskOwnedBusinessSnapshot("task-1"),
                reconciliationSourceTerminalSnapshot("task-1"),
                materialInventoryDocumentSnapshot("task-1"),
                materialInventoryUnitSnapshot("task-1"));
    }

    private Map<String, List<Map<String, Object>>> taskOwnedBusinessSnapshot(String taskId) {
        List<String> tables = List.of(
                "structured_requirement_fact",
                "structured_review_finding",
                "structured_function_list_item",
                "structured_feature_reconciliation",
                "structured_test_point",
                "structured_test_case",
                "structured_test_case_step",
                "structured_reference_binding",
                "structured_function_source_outcome",
                "structured_function_candidate",
                "structured_function_outcome_candidate");
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        for (String table : tables) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT business_row.* FROM %s business_row
                    JOIN structured_generation_work_item work ON work.id = business_row.work_item_id
                    WHERE work.task_id = ?
                    """.formatted(table), taskId);
            rows.sort(java.util.Comparator.comparing(row -> new TreeMap<>(row).toString()));
            snapshot.put(table, List.copyOf(rows));
        }
        return Map.copyOf(snapshot);
    }

    private List<Map<String, Object>> generationTaskSnapshot(String taskId) {
        return jdbc.queryForList("SELECT * FROM generation_task WHERE id = ?", taskId);
    }

    private List<Map<String, Object>> structuredWorkSnapshot(String taskId) {
        return jdbc.queryForList("""
                SELECT * FROM structured_generation_work_item WHERE task_id = ? ORDER BY created_at, id
                """, taskId);
    }

    private List<Map<String, Object>> taskAttemptSnapshot(String taskId) {
        return jdbc.queryForList("""
                SELECT attempt.* FROM structured_generation_attempt attempt
                JOIN structured_generation_work_item work ON work.id = attempt.work_item_id
                WHERE work.task_id = ? ORDER BY attempt.work_item_id, attempt.attempt_number
                """, taskId);
    }

    private List<Map<String, Object>> referenceBindingSnapshot(String taskId) {
        return jdbc.queryForList("""
                SELECT binding.* FROM structured_reference_binding binding
                JOIN structured_generation_work_item work ON work.id = binding.work_item_id
                WHERE work.task_id = ?
                ORDER BY binding.work_item_id, binding.subject_key, binding.subject_type,
                         binding.reference_type, binding.reference_key
                """, taskId);
    }

    private List<Map<String, Object>> reconciliationSourceTerminalSnapshot(String taskId) {
        return jdbc.queryForList("""
                SELECT * FROM structured_reconciliation_source_terminal
                WHERE task_id = ? ORDER BY source_type, source_key
                """, taskId);
    }

    private List<Map<String, Object>> materialInventoryDocumentSnapshot(String taskId) {
        return jdbc.queryForList("""
                SELECT * FROM material_inventory_document WHERE task_id = ? ORDER BY document_id
                """, taskId);
    }

    private List<Map<String, Object>> materialInventoryUnitSnapshot(String taskId) {
        return jdbc.queryForList("""
                SELECT * FROM material_inventory_unit
                WHERE task_id = ? ORDER BY document_id, ordinal, unit_id
                """, taskId);
    }

    private void awaitTaskLockHeldByRetry(String taskId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (Connection probe = jdbc.getDataSource().getConnection()) {
                probe.setAutoCommit(false);
                try (PreparedStatement statement = probe.prepareStatement(
                        "SELECT id FROM generation_task WHERE id = ? FOR UPDATE NOWAIT")) {
                    statement.setString(1, taskId);
                    statement.executeQuery();
                    probe.rollback();
                } catch (SQLException error) {
                    probe.rollback();
                    if (error.getErrorCode() == 3572) {
                        return;
                    }
                    throw error;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Retry transaction did not acquire the task lock within 10 seconds");
    }

    private int countForTask(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id = 'task-1'", Integer.class);
    }

    private void insertCollidingInventoryUnit() {
        jdbc.update("""
                INSERT INTO material_inventory_document
                (task_id, document_id, knowledge_id, document_role, total_units, complete)
                VALUES ('task-1', 'document-2', 'knowledge-2', 'REQUIREMENT', 1, TRUE)
                """);
        jdbc.update("""
                INSERT INTO material_inventory_unit
                (task_id, document_id, unit_id, document_role, chunk_index, ordinal, content, start_at, end_at)
                VALUES ('task-1', 'document-2', 'evidence-1', 'REQUIREMENT', 0, 1, '碰撞测试单元', 0, 6)
                """);
    }

    private CandidateAcceptanceFixture candidateAcceptanceFixture(String windowKey, List<String> evidenceKeys) {
        store.register(new StructuredGenerationAcceptanceStore.WorkRegistration(
                "task-1", windowKey, "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT",
                1, evidenceKeys.size(), "material-1", "candidate slice", evidenceKeys, null, null));
        return new CandidateAcceptanceFixture(store.claimNext("task-1", "candidate-worker").orElseThrow());
    }

    private boolean acceptCandidateAfter(CountDownLatch start,
            StructuredGenerationAcceptanceStore.WorkClaim claim,
            FunctionCandidateExtractionValidator.ValidatedWindow window) throws InterruptedException {
        start.await();
        try {
            store.acceptFunctionCandidates(claim, window);
            return true;
        } catch (IllegalStateException expectedConcurrentLoss) {
            return false;
        }
    }

    private static FunctionCandidateExtractionValidator.ValidatedWindow candidateWindow(
            String windowKey, FunctionCandidateExtractionValidator.FinalDecision acceptedDecision) {
        FunctionCandidateExtractionValidator.ValidatedCandidate accepted = new FunctionCandidateExtractionValidator.ValidatedCandidate(
                "3".repeat(64), "用户中心/账号登录", "使用账号登录系统", "提交账号",
                List.of("evidence-1"), FunctionCandidateExtractionResult.RecommendedStatus.ACCEPTED,
                acceptedDecision, "grounded_function", List.of());
        FunctionCandidateExtractionValidator.ValidatedCandidate pending = new FunctionCandidateExtractionValidator.ValidatedCandidate(
                "4".repeat(64), "订单/提交", "提交订单", "订单最终功能",
                List.of("evidence-2"), FunctionCandidateExtractionResult.RecommendedStatus.PENDING_CONFIRMATION,
                FunctionCandidateExtractionValidator.FinalDecision.PENDING_CONFIRMATION,
                "insufficient_detail", List.of("提交约束待确认"));
        FunctionCandidateExtractionValidator.FinalDecision firstSourceDecision = acceptedDecision;
        return new FunctionCandidateExtractionValidator.ValidatedWindow(windowKey, List.of(
                new FunctionCandidateExtractionValidator.ValidatedSourceOutcome(
                        "evidence-1", FunctionCandidateExtractionResult.Disposition.LINKED,
                        List.of(accepted.candidateRef()), "candidate_linked", firstSourceDecision),
                new FunctionCandidateExtractionValidator.ValidatedSourceOutcome(
                        "evidence-2", FunctionCandidateExtractionResult.Disposition.LINKED,
                        List.of(pending.candidateRef()), "candidate_linked",
                        FunctionCandidateExtractionValidator.FinalDecision.PENDING_CONFIRMATION)),
                List.of(accepted, pending), new FunctionCandidateExtractionResult.NormalizationSummary(2, 0, 0, 0));
    }

    private static FunctionCandidateExtractionValidator.ValidatedWindow singleCandidateWindow(
            String windowKey, String candidateRef, String path) {
        FunctionCandidateExtractionValidator.ValidatedCandidate candidate = new FunctionCandidateExtractionValidator.ValidatedCandidate(
                candidateRef, path, "功能说明", "已注册且状态正常的用户", List.of("evidence-1"),
                FunctionCandidateExtractionResult.RecommendedStatus.ACCEPTED,
                FunctionCandidateExtractionValidator.FinalDecision.ACCEPTED,
                "grounded_function", List.of());
        return new FunctionCandidateExtractionValidator.ValidatedWindow(windowKey, List.of(
                new FunctionCandidateExtractionValidator.ValidatedSourceOutcome(
                        "evidence-1", FunctionCandidateExtractionResult.Disposition.LINKED,
                        List.of(candidateRef), "candidate_linked",
                        FunctionCandidateExtractionValidator.FinalDecision.ACCEPTED)),
                List.of(candidate), new FunctionCandidateExtractionResult.NormalizationSummary(1, 0, 0, 0));
    }

    private void insertSecondFailedReviewLeaf() {
        String secondFailed = store.register(reviewRegistration(
                "f".repeat(64), 2, 2, List.of("evidence-2")));
        jdbc.update("UPDATE structured_generation_work_item SET status = 'FAILED' WHERE id = ?", secondFailed);
        jdbc.update("""
                INSERT INTO structured_generation_attempt
                (id, work_item_id, attempt_number, status, failure_type, completed_at)
                VALUES (UUID(), ?, 1, 'FAILED', 'response_too_large', CURRENT_TIMESTAMP(6))
                """, secondFailed);
    }

    private void insertPartialBusinessRow(String table, String workId) {
        switch (table) {
            case "structured_requirement_fact" -> jdbc.update("""
                    INSERT INTO structured_requirement_fact
                    (work_item_id, task_id, fact_key, function_name, roles_json, trigger_conditions_json,
                     inputs_json, business_rules_json, outputs_json, permissions_json, state_changes_json,
                     exception_handling_json, external_dependencies_json)
                    VALUES (?, 'task-1', 'partial-fact', '部分事实', JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(),
                            JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY())
                    """, workId);
            case "structured_review_finding" -> jdbc.update("""
                    INSERT INTO structured_review_finding
                    (work_item_id, task_id, finding_key, issue_type, description, test_design_impact,
                     current_project_recommendation, design_center_guideline_recommendation, handling_level)
                    VALUES (?, 'task-1', 'partial-finding', '缺口', '说明', '影响', '建议', '指南', 'IMPROVEMENT')
                    """, workId);
            case "structured_function_list_item" -> jdbc.update("""
                    INSERT INTO structured_function_list_item
                    (work_item_id, task_id, item_key, path_text, description)
                    VALUES (?, 'task-1', 'partial-item', '功能路径', '功能说明')
                    """, workId);
            case "structured_feature_reconciliation" -> jdbc.update("""
                    INSERT INTO structured_feature_reconciliation
                    (work_item_id, task_id, reconciliation_key, classification, scope_recommendation,
                     confirmation_status)
                    VALUES (?, 'task-1', 'partial-reconciliation', 'exact_match', '范围一致', 'confirmed')
                    """, workId);
            case "structured_test_point" -> jdbc.update("""
                    INSERT INTO structured_test_point
                    (work_item_id, task_id, test_point_key, function_key, function_name, test_point_type,
                     basis, description, missing_information_json, formal_coverage_satisfied)
                    VALUES (?, 'task-1', 'partial-point', 'function-1', '功能', 'NORMAL_BEHAVIOR',
                            'FORMAL_REQUIREMENT', '测试点', JSON_ARRAY(), FALSE)
                    """, workId);
            case "structured_test_case" -> insertPartialTestCase(workId);
            case "structured_test_case_step" -> {
                insertPartialTestCase(workId);
                jdbc.update("""
                        INSERT INTO structured_test_case_step
                        (work_item_id, case_key, step_no, action_text, expected_text)
                        VALUES (?, 'partial-case', 1, '执行动作', '预期结果')
                        """, workId);
            }
            case "structured_reference_binding" -> jdbc.update("""
                    INSERT INTO structured_reference_binding
                    (work_item_id, subject_key, subject_type, reference_type, reference_key)
                    VALUES (?, 'partial-subject', 'REVIEW_FINDING', 'EVIDENCE', 'evidence-1')
                    """, workId);
            case "structured_function_source_outcome" -> insertCandidateSourceOutcome(workId);
            case "structured_function_candidate" -> insertCandidate(workId);
            case "structured_function_outcome_candidate" -> {
                insertCandidateSourceOutcome(workId);
                insertCandidate(workId);
                jdbc.update("""
                        INSERT INTO structured_function_outcome_candidate (work_item_id, unit_key, candidate_ref)
                        VALUES (?, 'partial-unit', ?)
                        """, workId, "8".repeat(64));
            }
            default -> throw new IllegalArgumentException("Unknown business table");
        }
    }

    private void insertPartialTestCase(String workId) {
        jdbc.update("""
                INSERT INTO structured_test_case
                (work_item_id, task_id, case_key, title, preconditions_json, case_status,
                 missing_information_json)
                VALUES (?, 'task-1', 'partial-case', '部分用例', JSON_ARRAY(), 'FORMAL', JSON_ARRAY())
                """, workId);
    }

    private record ExplicitRetryFixture(List<String> completedWorkIds, String failedWorkId) { }

    private record InvalidReconciliationStagingFixture(
            String completedWorkId,
            String failedWorkId,
            StructuredGenerationAcceptanceStore.ReconciliationRunPlan plan) { }

    private record ReconciliationModelFailureFixture(
            String failedWorkId,
            StructuredGenerationAcceptanceStore.ReconciliationRunPlan plan,
            List<StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow> plannedWindows) { }

    private record ReconciliationRetrySnapshot(
            List<Map<String, Object>> tasks,
            List<Map<String, Object>> works,
            List<Map<String, Object>> attempts,
            List<Map<String, Object>> runs,
            List<Map<String, Object>> pages,
            List<Map<String, Object>> relations,
            List<Map<String, Object>> stageBindings,
            List<Map<String, Object>> referenceBindings,
            Map<String, List<Map<String, Object>>> businessRows,
            List<Map<String, Object>> sourceTerminals,
            List<Map<String, Object>> inventoryDocuments,
            List<Map<String, Object>> inventoryUnits) { }

    private record CandidateAcceptanceFixture(StructuredGenerationAcceptanceStore.WorkClaim claim) { }

    private record SplitExtractionRetryFixture(
            String parentWorkId, String completedChildId, String failedChildId) { }

    private static RequirementMaterialReviewValidator.RequirementFact unsupportedFact(String family) {
        String unsupported = "材料没有写明的内容";
        return new RequirementMaterialReviewValidator.RequirementFact(
                "fact-unsupported-" + family,
                "function".equals(family) ? unsupported : "用户中心账号登录",
                "roles".equals(family) ? List.of(unsupported) : List.of(),
                "triggerConditions".equals(family) ? List.of(unsupported) : List.of(),
                "inputs".equals(family) ? List.of(unsupported) : List.of(),
                "businessRules".equals(family) ? List.of(unsupported) : List.of(),
                "outputs".equals(family) ? List.of(unsupported) : List.of(),
                "permissions".equals(family) ? List.of(unsupported) : List.of(),
                "stateChanges".equals(family) ? List.of(unsupported) : List.of(),
                "exceptionHandling".equals(family) ? List.of(unsupported) : List.of(),
                "externalDependencies".equals(family) ? List.of(unsupported) : List.of(),
                List.of("evidence-1"));
    }

    private void assertReviewGroundingFailureLeftNoBusinessRows(
            StructuredGenerationAcceptanceStore.WorkClaim claim,
            RequirementMaterialReviewValidator.WorkItem item,
            String factKey,
            Throwable failure) {
        assertSoftly(softly -> {
            softly.assertThat(failure).isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_requirement_fact WHERE work_item_id = ?", Integer.class,
                    claim.workItemId())).isZero();
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_review_finding WHERE work_item_id = ?", Integer.class,
                    claim.workItemId())).isZero();
            softly.assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structured_reference_binding WHERE work_item_id = ?", Integer.class,
                    claim.workItemId())).isZero();
            softly.assertThat(jdbc.queryForObject(
                    "SELECT status FROM structured_generation_work_item WHERE id = ?", String.class,
                    claim.workItemId())).isEqualTo("RUNNING");
            softly.assertThat(catchThrowable(() -> item.registry().require(
                    StructuredKeyType.REQUIREMENT_FACT, factKey))).isInstanceOf(IllegalArgumentException.class);
        });
    }
    private static RequirementMaterialReviewValidator.ReviewFinding finding(String key) {
        return new RequirementMaterialReviewValidator.ReviewFinding(
                key, RequirementMaterialReviewValidator.RootCauseKind.AMBIGUOUS_REQUIREMENT,
                "需求表述存在歧义", new RequirementMaterialReviewValidator.AffectedScope(List.of("evidence-1"), "账号登录范围"),
                new RequirementMaterialReviewValidator.BadSourceExample("evidence-1", "提交订单"),
                new RequirementMaterialReviewValidator.ProposedGoodExample(
                        RequirementMaterialReviewValidator.ProposalStatus.PENDING_CONFIRMATION,
                        "建议补充明确的执行条件（待需求方确认）。"),
                "材料未明确账号登录的执行条件。", List.of("evidence-1"), "无法形成可执行的正式测试场景。",
                "请当前项目补充执行条件。", "建议统一需求条件表述。",
                RequirementMaterialReviewValidator.HandlingLevel.IMPROVEMENT);
    }

    private static Callable<Void> acceptReviewAfter(CountDownLatch start, StructuredGenerationAcceptanceStore acceptanceStore,
            StructuredGenerationAcceptanceStore.WorkClaim claim, String findingKey,
            RequirementMaterialReviewValidator.RootCauseKind rootCauseKind, String evidenceKey) {
        return () -> {
            start.await();
            acceptanceStore.acceptReview(claim, new RequirementMaterialReviewValidator(), reviewItem(evidenceKey),
                    reviewResult(findingKey, rootCauseKind, evidenceKey));
            return null;
        };
    }

    private static RequirementMaterialReviewValidator.Result reviewResult(String findingKey,
            RequirementMaterialReviewValidator.RootCauseKind rootCauseKind, String evidenceKey) {
        return new RequirementMaterialReviewValidator.Result(List.of(), List.of(frozenFinding(findingKey, rootCauseKind, evidenceKey)));
    }

    private static RequirementMaterialReviewValidator.ReviewFinding frozenFinding(String findingKey,
            RequirementMaterialReviewValidator.RootCauseKind rootCauseKind, String evidenceKey) {
        String quote = "evidence-1".equals(evidenceKey) ? "已注册且状态正常的用户" : "订单最终功能";
        return new RequirementMaterialReviewValidator.ReviewFinding(findingKey, rootCauseKind, "业务规则缺失",
                new RequirementMaterialReviewValidator.AffectedScope(List.of(evidenceKey), "当前范围缺少业务规则说明。"),
                new RequirementMaterialReviewValidator.BadSourceExample(evidenceKey, quote),
                new RequirementMaterialReviewValidator.ProposedGoodExample(
                        RequirementMaterialReviewValidator.ProposalStatus.PENDING_CONFIRMATION, "建议补充明确业务规则，待需求方确认。"),
                "当前材料缺少明确业务规则说明。", List.of(evidenceKey), "测试设计无法覆盖完整业务规则。",
                "当前项目应补充可执行的业务规则。", "设计中心应固化业务规则编写要求。",
                RequirementMaterialReviewValidator.HandlingLevel.IMPROVEMENT);
    }
}
