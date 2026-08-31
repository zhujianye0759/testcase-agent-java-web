package com.testcaseagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.ApachePoiWorkbookExporter;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.featureaudit.FeatureAuditService;
import com.testcaseagent.featureaudit.FrozenFeatureService;
import com.testcaseagent.featureaudit.RequirementMaterialTraversalService;
import com.testcaseagent.knowledgeagent.KnowledgeAgentProperties;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.knowledgeagent.StructuredSkillExecutionPort;
import com.testcaseagent.knowledgeagent.StructuredSkillSessionPort;
import com.testcaseagent.structuredgeneration.StructuredGenerationAcceptanceStore;
import com.testcaseagent.knowledgeagent.WebClientKnowledgeAgentAdapter;
import com.testcaseagent.knowledgeagent.WebClientKnowledgeScopeCatalogAdapter;
import com.testcaseagent.scope.DynamicScopeCatalogService;
import com.testcaseagent.scope.KnowledgeScopeCatalogPort;
import com.testcaseagent.scope.RequirementMaterialReaderPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the task endpoint only when server-side integration dependencies exist.
 *
 * [Req-ID]: REQ-TSK-001, REQ-KAG-002, REQ-KAG-009, REQ-CAT-003, REQ-CAT-005, REQ-EXP-001
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.knowledge-agent", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({TaskGenerationProfileProperties.class, KnowledgeAgentProperties.class})
public class GenerationTaskConfiguration {

    @Bean
    @ConditionalOnMissingBean(KnowledgeScopeCatalogPort.class)
    KnowledgeScopeCatalogPort knowledgeScopeCatalogPort(
            KnowledgeAgentProperties properties, TaskGenerationProfileProperties profile) {
        return new WebClientKnowledgeScopeCatalogAdapter(properties.getApiBaseUrl(), properties.getApiKey(),
                properties.getTimeout(), profile.getKnowledgeBasePageSize(), profile.getDocumentPageSize());
    }

    @Bean
    DynamicScopeCatalogService dynamicScopeCatalogService(
            KnowledgeScopeCatalogPort port, TaskGenerationProfileProperties profile) {
        return new DynamicScopeCatalogService(port, profile.getCatalogCacheTtl(), Clock.systemUTC());
    }

    @Bean
    DynamicTaskScopeResolver dynamicTaskScopeResolver(
            DynamicScopeCatalogService catalogService, TaskGenerationProfileProperties profile) {
        return new DynamicTaskScopeResolver(catalogService, profile);
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeAgentPort.class)
    @ConditionalOnProperty(prefix = "app.knowledge-agent", name = {"api-base-url", "api-key"})
    KnowledgeAgentPort knowledgeAgentPort(KnowledgeAgentProperties properties) {
        return new WebClientKnowledgeAgentAdapter(
                properties.getApiBaseUrl(), properties.getApiKey(), properties.getTimeout(),
                properties.getMaxAgentDiscoveryAttempts(), properties.getMaxEventCharacters(),
                properties.getFeatureReconciliationV2RequestMaxBytes(),
                properties.getFeatureReconciliationV2ResponseMaxBytes(),
                properties.getStructuredContractV2RequestMaxBytes(),
                KnowledgeAgentProperties.DEFAULT_STRUCTURED_CONTRACT_V2_RESPONSE_MAX_BYTES);
    }

    /**
     * Exposes the KEE adapter's separate parsed-material capability without coupling core workflow code to HTTP.
     * Test configurations may provide an independent port implementation.
     *
     * [Req-ID]: REQ-SMR-001, REQ-SMR-002
     */
    @Bean
    @ConditionalOnMissingBean(RequirementMaterialReaderPort.class)
    RequirementMaterialReaderPort requirementMaterialReaderPort(KnowledgeAgentPort knowledgeAgentPort) {
        if (knowledgeAgentPort instanceof RequirementMaterialReaderPort reader) return reader;
        throw new IllegalStateException("Configured KnowledgeAgentPort does not provide parsed material reading");
    }

    @Bean
    StructuredSkillExecutionPort structuredSkillExecutionPort(KnowledgeAgentPort knowledgeAgentPort) {
        if (knowledgeAgentPort instanceof StructuredSkillExecutionPort structured) return structured;
        throw new IllegalStateException("Configured KnowledgeAgentPort does not provide isolated structured Skill execution");
    }

    @Bean
    StructuredSkillSessionPort structuredSkillSessionPort(KnowledgeAgentPort knowledgeAgentPort) {
        if (knowledgeAgentPort instanceof StructuredSkillSessionPort sessions) return sessions;
        throw new IllegalStateException("Configured KnowledgeAgentPort does not provide structured session coordinates");
    }

    @Bean
    GenerationTaskRepository generationTaskRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        return new GenerationTaskRepository(jdbcTemplate, objectMapper, transactionManager);
    }

    @Bean(destroyMethod = "shutdown")
    TaskExecutor generationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("generation-task-");
        executor.initialize();
        return executor;
    }

    @Bean
    TaskExecutionQueue taskExecutionQueue(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new TaskExecutionQueue(jdbcTemplate, transactionManager);
    }

    @Bean
    StructuredGenerationAcceptanceStore structuredGenerationAcceptanceStore(JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        return new StructuredGenerationAcceptanceStore(jdbcTemplate,
                new org.springframework.transaction.support.TransactionTemplate(transactionManager), Clock.systemUTC(), objectMapper);
    }

    /** Shared daemon scheduler for exact-attempt structured lease renewal. [Req-ID]: REQ-SEW-003 */
    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService structuredWorkLeaseScheduler() {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newScheduledThreadPool(5, runnable -> {
            Thread thread = new Thread(runnable, "structured-work-lease-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Renews a five-minute work lease once per minute while its exact attempt is active. [Req-ID]: REQ-SEW-002, REQ-SEW-003 */
    @Bean
    StructuredWorkLeaseHeartbeat structuredWorkLeaseHeartbeat(
            StructuredGenerationAcceptanceStore acceptanceStore,
            ScheduledExecutorService structuredWorkLeaseScheduler) {
        return new ScheduledStructuredWorkLeaseHeartbeat(
                acceptanceStore, structuredWorkLeaseScheduler, Duration.ofMinutes(1));
    }

    @Bean
    RequirementMaterialTraversalService requirementMaterialTraversalService(
            @Qualifier("requirementMaterialReaderPort") RequirementMaterialReaderPort materialReader,
            GenerationTaskRepository repository) {
        return new RequirementMaterialTraversalService(materialReader, repository);
    }

    @Bean
    FeatureAuditService featureAuditService(GenerationTaskRepository repository, KnowledgeAgentPort knowledgeAgentPort) {
        return new FeatureAuditService(repository, knowledgeAgentPort);
    }

    @Bean
    FrozenFeatureService frozenFeatureService(GenerationTaskRepository repository) {
        return new FrozenFeatureService(repository);
    }

    /**
     * Creates artifacts only under the application-owned configured root; task execution must not
     * construct a workbook writer ad hoc because export safety is a shared workflow boundary.
     *
     * [Req-ID]: REQ-EXP-001
     */
    @Bean
    @ConditionalOnMissingBean(WorkbookExporter.class)
    WorkbookExporter workbookExporter(@Value("${app.artifacts.root}") String artifactRoot) {
        return new ApachePoiWorkbookExporter(Path.of(artifactRoot));
    }

    @Bean
    StructuredAllGenerationCoordinator structuredAllGenerationCoordinator(GenerationTaskRepository repository,
            RequirementMaterialTraversalService traversalService,
            @Qualifier("structuredSkillExecutionPort") StructuredSkillExecutionPort executionPort,
            @Qualifier("structuredSkillSessionPort") StructuredSkillSessionPort sessionPort,
            StructuredGenerationAcceptanceStore acceptanceStore,
            WorkbookExporter workbookExporter, ObjectMapper objectMapper,
            StructuredWorkLeaseHeartbeat leaseHeartbeat) {
        StructuredAllGenerationCoordinator historical = new DefaultStructuredAllGenerationCoordinator(
                repository, traversalService, executionPort, sessionPort, acceptanceStore, workbookExporter,
                objectMapper, leaseHeartbeat);
        StructuredAllGenerationCoordinator v2 = new V2StructuredAllGenerationCoordinator(
                repository, traversalService, executionPort, sessionPort, acceptanceStore, workbookExporter,
                objectMapper, leaseHeartbeat);
        return new VersionedStructuredAllGenerationCoordinator(historical, v2);
    }

    @Bean
    GenerationWorkflow generationWorkflow(
            GenerationTaskRepository repository,
            KnowledgeAgentPort knowledgeAgentPort,
            WorkbookExporter workbookExporter,
            ObjectMapper objectMapper,
            TaskExecutionQueue taskExecutionQueue,
            TaskExecutor generationTaskExecutor,
            RequirementMaterialTraversalService materialTraversalService,
            FeatureAuditService featureAuditService,
            FrozenFeatureService frozenFeatureService,
            StructuredAllGenerationCoordinator structuredAllCoordinator) {
        return new GenerationWorkflow(repository, knowledgeAgentPort, workbookExporter, objectMapper,
                taskExecutionQueue, generationTaskExecutor, materialTraversalService, featureAuditService,
                frozenFeatureService, structuredAllCoordinator);
    }

    @Bean
    GenerationTaskController generationTaskController(
            GenerationWorkflow workflow, DynamicTaskScopeResolver taskScopeResolver) {
        return new GenerationTaskController(workflow, taskScopeResolver);
    }

    @Bean
    TaskConfigurationController taskConfigurationController(DynamicScopeCatalogService catalogService) {
        return new TaskConfigurationController(catalogService);
    }

    @Bean
    ApplicationRunner generationTaskRecoveryRunner(GenerationWorkflow workflow) {
        return ignored -> workflow.recoverAtStartup();
    }

    @Bean
    ArtifactDownloadController artifactDownloadController(GenerationTaskRepository repository) {
        return new ArtifactDownloadController(repository);
    }
}
