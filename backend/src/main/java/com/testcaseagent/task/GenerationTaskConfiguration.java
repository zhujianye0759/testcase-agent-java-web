package com.testcaseagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.ApachePoiWorkbookExporter;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.knowledgeagent.KnowledgeAgentProperties;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.knowledgeagent.WebClientKnowledgeAgentAdapter;
import com.testcaseagent.knowledgeagent.WebClientKnowledgeScopeCatalogAdapter;
import com.testcaseagent.scope.DynamicScopeCatalogService;
import com.testcaseagent.scope.KnowledgeScopeCatalogPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.time.Clock;

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
                properties.getMaxAgentDiscoveryAttempts(), properties.getMaxEventCharacters());
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
    GenerationWorkflow generationWorkflow(
            GenerationTaskRepository repository,
            KnowledgeAgentPort knowledgeAgentPort,
            WorkbookExporter workbookExporter,
            ObjectMapper objectMapper,
            TaskExecutionQueue taskExecutionQueue,
            TaskExecutor generationTaskExecutor) {
        return new GenerationWorkflow(repository, knowledgeAgentPort, workbookExporter, objectMapper,
                taskExecutionQueue, generationTaskExecutor);
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
        return ignored -> workflow.recoverExpiredClaims();
    }

    @Bean
    ArtifactDownloadController artifactDownloadController(GenerationTaskRepository repository) {
        return new ArtifactDownloadController(repository);
    }
}
