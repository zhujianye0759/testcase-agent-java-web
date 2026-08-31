package com.testcaseagent.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.testcaseagent.validation.StructuredValidationFailure;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

/** [Req-ID]: REQ-CWR-004 */
class WorkflowDiagnosticsTest {

    @Test
    void preservesModelDiagnosticsAndInternalCoordinatesButRedactsCredentials() {
        String diagnostic = "documentId=doc-1; unitId=unit-2; candidateIds=candidate-3\n"
                + "X-API-Key: sk-real-secret\nAuthorization: Bearer bearer-secret\n"
                + "Proxy-Authorization=proxy-secret\nAPI key: api-key-secret\ntoken=token-secret\n"
                + "secret: named-secret\npassword=db-secret\n"
                + "{\"Authorization\":\"Bearer json-auth-secret\",\"X-API-Key\":\"json-api-secret\","
                + "\"token\":\"json-token-secret\",\"secret\":\"json-secret\","
                + "\"password\":\"json-password\",\"Authorization\":\"Bearer escaped\\\"json-auth-secret\"}\n"
                + "{\\\"Authorization\\\":\\\"Bearer fully-escaped-authorization\\\","
                + "\\\"Proxy-Authorization\\\":\\\"fully-escaped-proxy\\\","
                + "\\\"X-API-Key\\\":\\\"fully-escaped-api-key\\\","
                + "\\\"API key\\\":\\\"fully-escaped-named-api-key\\\","
                + "\\\"token\\\":\\\"fully-escaped-token\\\","
                + "\\\"secret\\\":\\\"fully-escaped-secret\\\","
                + "\\\"password\\\":\\\"fully-escaped-password\\\"}\n"
                + "curl -H \"Authorization: Bearer curl-auth-secret\" -H \"X-API-Key: curl-api-secret\"\n"
                + "业务正文：材料审查结果";

        String sanitized = WorkflowDiagnostics.sanitize(diagnostic);

        assertThat(sanitized).contains("documentId=doc-1", "unitId=unit-2", "candidateIds=candidate-3", "业务正文：材料审查结果")
                .doesNotContain("sk-real-secret", "bearer-secret", "proxy-secret", "api-key-secret", "token-secret",
                        "named-secret", "db-secret", "json-auth-secret", "json-api-secret", "json-token-secret",
                        "json-secret", "json-password", "fully-escaped-authorization", "fully-escaped-proxy",
                        "fully-escaped-api-key", "fully-escaped-named-api-key", "fully-escaped-token",
                        "fully-escaped-secret", "fully-escaped-password", "curl-auth-secret", "curl-api-secret");
    }

    @Test
    void sanitizesEveryProductionCorrelationFieldBeforeLogging() {
        Logger logger = (Logger) LoggerFactory.getLogger("workflow.diagnostics");
        ch.qos.logback.classic.Level previousLevel = logger.getLevel();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        logger.addAppender(events);
        try {
            WorkflowDiagnostics.reconciliation("Authorization=task-secret", 1, 2, 3,
                    "curl -H \"X-API-Key: event-secret\"", "documentId=doc-1; unitId=unit-2; candidateIds=candidate-3");
            WorkflowDiagnostics.generation("token=generation-task-secret", "secret=batch-secret", "password=attempt-secret",
                    "Proxy-Authorization: event-proxy-secret", "业务正文：材料审查结果");

            assertThat(events.list).hasSize(2);
            String written = events.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(written).contains("stage=final-reconciliation", "stage=test-case-generation", "documentId=doc-1",
                            "unitId=unit-2", "candidateIds=candidate-3", "业务正文：材料审查结果")
                    .doesNotContain("task-secret", "event-secret", "generation-task-secret", "batch-secret",
                            "attempt-secret", "event-proxy-secret");
        } finally {
            logger.detachAppender(events);
            logger.setLevel(previousLevel);
            events.stop();
        }
    }

    /** [Req-ID]: REQ-FSC-007 */
    @Test
    void correlatesOnlyEnumeratedStructuredValidationFieldsWithoutRejectedContent() {
        Logger logger = (Logger) LoggerFactory.getLogger("workflow.diagnostics");
        ch.qos.logback.classic.Level previousLevel = logger.getLevel();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        logger.addAppender(events);
        try {
            StructuredValidationFailure failure = StructuredValidationFailure.directEvidence(
                    "$.requirement_facts[0].statement",
                    java.util.List.of(StructuredValidationFailure.DirectEvidenceReason.LITERAL_UNSUPPORTED));

            WorkflowDiagnostics.structuredValidationFailure(
                    "task-1", "work-1", "attempt-1", 1, failure);

            assertThat(events.list).hasSize(1);
            assertThat(events.list.get(0).getFormattedMessage())
                    .contains("taskId=task-1", "workId=work-1", "attemptId=attempt-1", "attempt=1",
                            failure.code(), failure.path(), failure.message())
                    .doesNotContain("payload", "model response", "material content", "password=secret",
                            "direct_evidence_reasons", "LITERAL_UNSUPPORTED");
        } finally {
            logger.detachAppender(events);
            logger.setLevel(previousLevel);
            events.stop();
        }
    }

    /** [Req-ID]: REQ-ESR-006 */
    @Test
    void coordinatorDiagnosticsAcceptOnlyFixedFieldsAndNeverAnExceptionPayload() {
        Logger logger = (Logger) LoggerFactory.getLogger("workflow.diagnostics");
        ch.qos.logback.classic.Level previousLevel = logger.getLevel();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        logger.addAppender(events);
        try {
            StructuredValidationFailure failure = StructuredValidationFailure.of(
                    StructuredValidationFailure.Code.STRUCTURED_COORDINATOR_STATE_FAILURE,
                    "$.function_extraction_pre_split");

            WorkflowDiagnostics.structuredCoordinatorFailure("task-safe-stage", failure);

            assertThat(events.list).hasSize(1);
            assertThat(events.list.get(0).getFormattedMessage())
                    .contains("taskId=task-safe-stage", "stage=structured-coordinator-failure",
                            failure.code(), failure.path(), failure.message())
                    .doesNotContain("stack", "payload", "request", "response", "material content",
                            "Authorization", "password", "credential");
        } finally {
            logger.detachAppender(events);
            logger.setLevel(previousLevel);
            events.stop();
        }
    }

    @Test
    void configuresDedicatedRollingDiagnosticFile() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertThat(input).isNotNull();
            String configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(configuration).contains("workflow.diagnostics", "RollingFileAppender",
                    "TESTCASE_DIAGNOSTIC_LOG_FILE", "TESTCASE_DIAGNOSTIC_LOG_MAX_HISTORY");
        }
    }

    @Test
    void writesSanitizedDiagnosticsToTheConfiguredRollingFile(@TempDir Path directory) throws Exception {
        String property = "TESTCASE_DIAGNOSTIC_LOG_FILE";
        String previous = System.getProperty(property);
        Path diagnosticFile = directory.resolve("workflow-diagnostics.log");
        LoggerContext context = new LoggerContext();
        try {
            System.setProperty(property, diagnosticFile.toString());
            context.setMDCAdapter(new LogbackMDCAdapter());
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            try (InputStream input = getClass().getResourceAsStream("/logback-spring.xml")) {
                assertThat(input).isNotNull();
                configurator.doConfigure(input);
            }
            context.start();
            Logger logger = context.getLogger("workflow.diagnostics");
            Appender<?> appender = logger.getAppender("WORKFLOW_DIAGNOSTICS");
            assertThat(appender).isNotNull();
            assertThat(appender.isStarted()).isTrue();
            assertThat(logger.isInfoEnabled()).isTrue();
            assertThat(((FileAppender<?>) appender).getFile()).isEqualTo(diagnosticFile.toString());
            String payload = WorkflowDiagnostics.sanitize("documentId=doc-1; unitId=unit-2; candidateIds=candidate-3\n"
                    + "Authorization=Bearer bearer-secret\nProxy-Authorization: proxy-secret\nAPI key=api-key-secret\n"
                    + "token: token-secret\nsecret=named-secret\npassword=db-secret\n"
                    + "{\"Authorization\":\"Bearer json-auth-secret\",\"X-API-Key\":\"json-api-secret\","
                    + "\"token\":\"json-token-secret\",\"secret\":\"json-secret\","
                    + "\"password\":\"json-password\",\"Authorization\":\"Bearer escaped\\\"json-auth-secret\"}\n"
                    + "{\\\"Authorization\\\":\\\"Bearer fully-escaped-authorization\\\","
                    + "\\\"Proxy-Authorization\\\":\\\"fully-escaped-proxy\\\","
                    + "\\\"X-API-Key\\\":\\\"fully-escaped-api-key\\\","
                    + "\\\"API key\\\":\\\"fully-escaped-named-api-key\\\","
                    + "\\\"token\\\":\\\"fully-escaped-token\\\","
                    + "\\\"secret\\\":\\\"fully-escaped-secret\\\","
                    + "\\\"password\\\":\\\"fully-escaped-password\\\"}\n"
                    + "curl -H \"Authorization: Bearer curl-auth-secret\" -H \"X-API-Key: curl-api-secret\"\n"
                    + "业务正文：材料审查结果");
            logger.info("payload:\n{}", payload);
            context.stop();

            String written = Files.readString(diagnosticFile, StandardCharsets.UTF_8);
            assertThat(written).contains("documentId=doc-1", "unitId=unit-2", "candidateIds=candidate-3", "业务正文：材料审查结果")
                    .doesNotContain("bearer-secret", "proxy-secret", "api-key-secret", "token-secret", "named-secret",
                            "db-secret", "json-auth-secret", "json-api-secret", "json-token-secret", "json-secret",
                            "json-password", "fully-escaped-authorization", "fully-escaped-proxy",
                            "fully-escaped-api-key", "fully-escaped-named-api-key", "fully-escaped-token",
                            "fully-escaped-secret", "fully-escaped-password", "curl-auth-secret", "curl-api-secret");
        } finally {
            context.stop();
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }
}
