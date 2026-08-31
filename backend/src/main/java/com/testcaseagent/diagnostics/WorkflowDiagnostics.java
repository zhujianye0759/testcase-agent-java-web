package com.testcaseagent.diagnostics;

import com.testcaseagent.validation.StructuredValidationFailure;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes task-correlated remote-model diagnostics to the dedicated rolling logger.
 *
 * <p>Reader-facing task state must remain safe and compact; this module is the only seam that retains prompts,
 * terminal Markdown and internal material coordinates for controlled operations diagnosis. Credentials are removed
 * before every write. [Req-ID]: REQ-CWR-004</p>
 */
public final class WorkflowDiagnostics {

    private static final Logger LOGGER = LoggerFactory.getLogger("workflow.diagnostics");
    private static final Pattern RAW_API_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}");
    private static final Pattern QUOTED_JSON_SECRET = Pattern.compile(
            "(?i)((?:\\\\)?\"(?:authorization|proxy-authorization|x-api-key|api[ _-]?key|token|secret|password)"
                    + "(?:\\\\)?\"\\s*:\\s*(?:\\\\)?\")(?:\\\\.|[^\"\\\\])*((?:\\\\)?\")");
    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)\\b((?:authorization|proxy-authorization|x-api-key|api[ _-]?key|token|secret|password)\\s*[:=]\\s*)"
                    + "(?:bearer\\s+)?(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;\\r\\n}]+)");

    private WorkflowDiagnostics() {
    }

    /** Records one final-reconciliation request, response or validation outcome. */
    public static void reconciliation(
            String taskId, int pageNumber, int totalPages, int attempt, String event, String payload) {
        record(taskId, "final-reconciliation", pageNumber, totalPages, attempt, event, payload);
    }

    /** Records one frozen-feature generation request, response or batch outcome. */
    public static void generation(String taskId, String batchId, String attemptId, String event, String payload) {
        record(taskId, "test-case-generation", null, null, null,
                event + " batchId=" + batchId + " attemptId=" + attemptId, payload);
    }

    /**
     * Records only the enumerated safe fields of a structured business-validation failure.
     * Rejected model output, material text, and arbitrary exception messages are not accepted. [Req-ID]: REQ-FSC-007
     */
    public static void structuredValidationFailure(String taskId, String workId, String attemptId, int attempt,
            StructuredValidationFailure failure) {
        StructuredValidationFailure safe = Objects.requireNonNull(failure, "failure must not be null");
        LOGGER.info("taskId={} stage=structured-business-validation workId={} attemptId={} attempt={} "
                        + "code={} path={} message={}",
                sanitize(taskId), sanitize(workId), sanitize(attemptId), attempt,
                safe.code(), safe.path(), safe.message());
    }

    /**
     * Records only the fixed task-level coordinator diagnostic.
     * Arbitrary exception text and stacks are intentionally not accepted by this API. [Req-ID]: REQ-ESR-006
     */
    public static void structuredCoordinatorFailure(String taskId, StructuredValidationFailure failure) {
        StructuredValidationFailure safe = Objects.requireNonNull(failure, "failure must not be null");
        LOGGER.info("taskId={} stage=structured-coordinator-failure code={} path={} category={}",
                sanitize(taskId), safe.code(), safe.path(), safe.message());
    }

    static String sanitize(String value) {
        if (value == null) return "";
        String sanitized = QUOTED_JSON_SECRET.matcher(value).replaceAll("$1<credential-redacted>$2");
        sanitized = INLINE_SECRET.matcher(sanitized).replaceAll("$1<credential-redacted>");
        return RAW_API_KEY.matcher(sanitized).replaceAll("<credential-redacted>");
    }

    private static void record(
            String taskId, String stage, Integer pageNumber, Integer totalPages, Integer attempt, String event, String payload) {
        LOGGER.info("taskId={} stage={} page={} totalPages={} attempt={} event={}\npayload:\n{}",
                sanitize(taskId), sanitize(stage), pageNumber, totalPages, attempt, sanitize(event), sanitize(payload));
    }
}
