package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.fewshot.ExampleQualityKind;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.scope.ParsedMaterial;
import com.testcaseagent.scope.ParsedMaterialUnit;
import com.testcaseagent.scope.RequirementMaterialReaderPort;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.scope.ScopeViolation;
import com.testcaseagent.testcase.FewShotPolicy;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * HTTP/SSE adapter for the existing knowledge-engine agent.
 *
 * <p>The HTTP request remains JSON because that is the engine's API transport, while the agent query
 * and completed answer use the deliberately small Markdown contract. An explicit SSE {@code complete}
 * event is required before the Markdown reaches task parsing.</p>
 *
 * [Req-ID]: REQ-KAG-001, REQ-KAG-002, REQ-KAG-003, REQ-KAG-004, REQ-KAG-005, REQ-SCP-001,
 * REQ-SCP-003, REQ-FEW-002, REQ-FEW-003, REQ-ANA-004, REQ-KSI-004
 */
public final class WebClientKnowledgeAgentAdapter implements KnowledgeAgentPort, RequirementMaterialReaderPort, StructuredSkillExecutionPort {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final Set<String> PARSED_UNITS_BUSINESS_ERRORS = Set.of(
            "document_not_ready",
            "cursor_signing_unavailable",
            "invalid_cursor",
            "document_not_current",
            "parsed_unit_integrity_error",
            "unit_too_large");
    private static final String GENERATION_SKILL = "functional-testcase-design";
    private static final String RECONCILIATION_SKILL = "feature-scope-reconciliation";
    /** A failed setup is isolated and retried before any business material is sent. */
    private static final int SKILL_PREPARATION_ATTEMPTS = 3;
    private final WebClient webClient;
    private final String apiKey;
    private final Duration timeout;
    private final int maxAgentDiscoveryAttempts;
    private final int maxEventCharacters;
    /** Holds one preparation/business pair only; callers close it before the next bounded work item. */
    private final ThreadLocal<PreparedSkillSession> preparedSession = new ThreadLocal<>();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ObjectMapper structuredObjectMapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);

    public WebClientKnowledgeAgentAdapter(String apiBaseUrl, String apiKey, Duration timeout,
            int maxAgentDiscoveryAttempts, int maxEventCharacters) {
        this.webClient = WebClient.builder().baseUrl(requireText(apiBaseUrl, "apiBaseUrl"))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)).build();
        this.apiKey = requireText(apiKey, "apiKey");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        if (maxAgentDiscoveryAttempts < 1) throw new IllegalArgumentException("maxAgentDiscoveryAttempts must be at least one");
        if (maxEventCharacters < 1) throw new IllegalArgumentException("maxEventCharacters must be at least one");
        this.maxAgentDiscoveryAttempts = maxAgentDiscoveryAttempts;
        this.maxEventCharacters = maxEventCharacters;
    }

    @Override
    public KnowledgeAgentInvocationResult invoke(KnowledgeAgentInvocation invocation) {
        if (preparedSession.get() == null) requireConfiguredAgent(invocation.agentId());
        List<SelectedExample> examples = loadEnabledExamples(invocation.exampleScope(), invocation.fewShotPolicy());
        SessionSelection session = sessionFor(invocation.agentId(), invocation.requirementScope(),
                invocation.requirementAdmissionTypeKeys(), GENERATION_SKILL);
        String markdown = invokeChat(session.sessionId(),
                AgentChatRequest.forGeneration(invocation, MarkdownPrompt.generation(invocation.prompt(), examples)), GENERATION_SKILL,
                session.requiresReadSkillEvidence(), false);
        return new KnowledgeAgentInvocationResult(session.sessionId(), List.of(), markdown);
    }

    @Override
    public void prepareGenerationSession(KnowledgeAgentInvocation invocation) {
        prepareSession(invocation.agentId(), invocation.requirementScope(), invocation.requirementAdmissionTypeKeys(), GENERATION_SKILL);
    }

    /**
     * Invokes the reconciliation Skill with only the frozen requirement evidence scope. The prompt
     * is forwarded unchanged so the caller owns its candidate and evidence instructions.
     *
     * [Req-ID]: REQ-KSI-001, REQ-KSI-002, REQ-KSI-003, REQ-BFA-003
     */
    @Override
    public KnowledgeAgentInvocationResult reconcileFeatures(FeatureReconciliationInvocation invocation) {
        if (preparedSession.get() == null) requireConfiguredAgent(invocation.agentId());
        SessionSelection session = sessionFor(invocation.agentId(), invocation.requirementScope(),
                invocation.requirementAdmissionTypeKeys(), RECONCILIATION_SKILL);
        String markdown = invokeChat(session.sessionId(), AgentChatRequest.forReconciliation(invocation), RECONCILIATION_SKILL,
                session.requiresReadSkillEvidence(), false);
        return new KnowledgeAgentInvocationResult(session.sessionId(), List.of(), markdown);
    }

    @Override
    public void prepareReconciliationSession(FeatureReconciliationInvocation invocation) {
        prepareSession(invocation.agentId(), invocation.requirementScope(), invocation.requirementAdmissionTypeKeys(), RECONCILIATION_SKILL);
    }

    @Override
    public void closePreparedSession() {
        preparedSession.remove();
    }

    /** Executes the material-review route without creating a chat session or parsing SSE/Markdown. [Req-ID]: REQ-SKI-002, REQ-SKI-004 */
    @Override
    public StructuredSkillSuccessEnvelope<RequirementMaterialQualityReviewResult> reviewRequirementMaterial(
            RequirementMaterialQualityReviewInvocation invocation) {
        return invokeStructured(invocation.sessionId(), invocation.agentId(), StructuredSkillName.REQUIREMENT_MATERIAL_QUALITY_REVIEW,
                invocation.requirementScope(), invocation.input(), RequirementMaterialQualityReviewResult.class);
    }

    /** Executes the feature-reconciliation route without ordinary Agent Chat fallback. [Req-ID]: REQ-SKI-002, REQ-SKI-004 */
    @Override
    public StructuredSkillSuccessEnvelope<FeatureScopeReconciliationResult> reconcileFeatureScope(
            FeatureScopeReconciliationInvocation invocation) {
        return invokeStructured(invocation.sessionId(), invocation.agentId(), StructuredSkillName.FEATURE_SCOPE_RECONCILIATION,
                invocation.requirementScope(), invocation.input(), FeatureScopeReconciliationResult.class);
    }

    /** Executes one testcase-design route without SSE, tools, or Markdown parsing. [Req-ID]: REQ-SKI-002, REQ-SKI-004 */
    @Override
    public StructuredSkillSuccessEnvelope<FunctionalTestcaseDesignResult> designFunctionalTestcases(
            FunctionalTestcaseDesignInvocation invocation) {
        return invokeStructured(invocation.sessionId(), invocation.agentId(), StructuredSkillName.FUNCTIONAL_TESTCASE_DESIGN,
                invocation.requirementScope(), invocation.input(), FunctionalTestcaseDesignResult.class);
    }

    private <T> StructuredSkillSuccessEnvelope<T> invokeStructured(String sessionId, String agentId, StructuredSkillName skillName,
            RequirementScope requirementScope, Object input, Class<T> resultType) {
        StructuredSkillScope scope = StructuredSkillScope.from(requirementScope);
        StructuredSkillRequest request = new StructuredSkillRequest(agentId, skillName.wireValue(), scope.knowledgeBaseIds(),
                scope.knowledgeIds(), scope.systemScopes(), input);
        byte[] body;
        try {
            body = structuredObjectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException exception) {
            throw new StructuredSkillExecutionException(StructuredSkillErrorType.INVALID_REQUEST, false);
        }
        if (body.length > 2 * 1024 * 1024) throw new StructuredSkillExecutionException(StructuredSkillErrorType.REQUEST_TOO_LARGE, false);
        try {
            StructuredHttpResponse response = webClient.post().uri("/agent-chat/{sessionId}/isolated-skill", sessionId).header(API_KEY_HEADER, apiKey)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).bodyValue(body)
                    .exchangeToMono(clientResponse -> clientResponse.bodyToMono(String.class).defaultIfEmpty("")
                            .map(value -> new StructuredHttpResponse(clientResponse.statusCode().is2xxSuccessful(), value))).block(timeout);
            if (response == null) throw new StructuredSkillExecutionException(StructuredSkillErrorType.MODEL_EXECUTION_FAILED, false);
            return parseStructuredResponse(response.body(), skillName, resultType);
        } catch (StructuredSkillExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (hasCause(exception, DataBufferLimitException.class)) {
                throw new StructuredSkillExecutionException(StructuredSkillErrorType.RESPONSE_TOO_LARGE, false);
            }
            throw new StructuredSkillExecutionException(StructuredSkillErrorType.MODEL_EXECUTION_FAILED, false);
        }
    }

    private <T> StructuredSkillSuccessEnvelope<T> parseStructuredResponse(String response, StructuredSkillName expectedSkill, Class<T> resultType) {
        if (response.getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024) {
            throw new StructuredSkillExecutionException(StructuredSkillErrorType.RESPONSE_TOO_LARGE, false);
        }
        try {
            JsonNode root = structuredObjectMapper.readTree(response);
            if (root == null || !root.isObject() || !root.path("success").isBoolean()) throw invalidStructuredResponse();
            if (!root.path("success").booleanValue()) throw structuredFailure(root);
            exactStructuredFields(root, Set.of("success", "data"));
            JsonNode data = root.path("data");
            if (!data.isObject()) throw invalidStructuredResponse();
            exactStructuredFields(data, Set.of("schema_version", "skill_name", "repair_attempted", "result"));
            if (!"1.0".equals(data.path("schema_version").asText()) || !expectedSkill.wireValue().equals(data.path("skill_name").asText())
                    || !data.path("repair_attempted").isBoolean() || !data.path("result").isObject()) throw invalidStructuredResponse();
            validateResultFields(data.path("result"), expectedSkill);
            T result = structuredObjectMapper.treeToValue(data.path("result"), resultType);
            return new StructuredSkillSuccessEnvelope<>(true, new StructuredSkillSuccess<>("1.0", expectedSkill.wireValue(),
                    data.path("repair_attempted").booleanValue(), result));
        } catch (StructuredSkillExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidStructuredResponse();
        }
    }

    private StructuredSkillExecutionException structuredFailure(JsonNode root) {
        JsonNode details = root.path("error").path("details");
        try {
            if (!details.isObject()) return invalidStructuredResponse();
            Set<String> detailFields = new HashSet<>(); details.fieldNames().forEachRemaining(detailFields::add);
            if (!(detailFields.equals(Set.of("type")) || detailFields.equals(Set.of("type", "repair_attempted")))) return invalidStructuredResponse();
            if (details.has("repair_attempted") && !details.path("repair_attempted").isBoolean()) return invalidStructuredResponse();
            boolean repaired = details.path("repair_attempted").isBoolean() && details.path("repair_attempted").booleanValue();
            return new StructuredSkillExecutionException(StructuredSkillErrorType.fromWire(details.path("type").asText()), repaired);
        } catch (RuntimeException exception) { return invalidStructuredResponse(); }
    }

    private static StructuredSkillExecutionException invalidStructuredResponse() {
        return new StructuredSkillExecutionException(StructuredSkillErrorType.STRUCTURED_OUTPUT_INVALID, false);
    }

    private static void exactStructuredFields(JsonNode object, Set<String> expected) {
        Set<String> actual = new HashSet<>(); object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw invalidStructuredResponse();
    }

    private static void validateResultFields(JsonNode result, StructuredSkillName skill) {
        switch (skill) {
            case REQUIREMENT_MATERIAL_QUALITY_REVIEW -> {
                exactStructuredFields(result, Set.of("requirement_facts", "review_findings"));
                exactArrayObjects(result.path("requirement_facts"), Set.of("fact_key", "function", "roles", "trigger_conditions", "inputs",
                        "business_rules", "outputs", "permissions", "state_changes", "exception_handling", "external_dependencies", "evidence_keys"));
                exactArrayObjects(result.path("review_findings"), Set.of("finding_key", "issue_type", "description", "evidence_keys",
                        "test_design_impact", "current_project_recommendation", "design_center_guideline_recommendation", "handling_level"));
            }
            case FEATURE_SCOPE_RECONCILIATION -> {
                exactStructuredFields(result, Set.of("reconciliations"));
                exactArrayObjects(result.path("reconciliations"), Set.of("reconciliation_key", "function_list_item_keys", "requirement_fact_keys",
                        "classification", "evidence_keys", "scope_recommendation", "confirmation_status"));
            }
            case FUNCTIONAL_TESTCASE_DESIGN -> {
                exactStructuredFields(result, Set.of("function_key", "test_point_key", "testcases"));
                JsonNode testcases = result.path("testcases");
                exactArrayObjects(testcases, Set.of("case_key", "title", "preconditions", "steps", "requirement_fact_keys", "evidence_keys",
                        "case_status", "missing_information"));
                for (JsonNode testcase : testcases) exactArrayObjects(testcase.path("steps"), Set.of("step_no", "action", "expected"));
            }
        }
    }

    private static void exactArrayObjects(JsonNode values, Set<String> expectedFields) {
        if (!values.isArray()) throw invalidStructuredResponse();
        for (JsonNode value : values) { if (!value.isObject()) throw invalidStructuredResponse(); exactStructuredFields(value, expectedFields); }
    }

    /**
     * Reads the current persisted chunks page by page and accepts them only after the explicit
     * terminal page proves a complete, contiguous enumeration. A bad page must fail the entire
     * document read so callers never mistake partial material for formal evidence.
     *
     * [Req-ID]: REQ-SMR-001, REQ-SMR-002, REQ-SMR-003, REQ-SMR-004
     */
    @Override
    public ParsedMaterial readAll(RequirementScope scope, String knowledgeId, int requestedLimit) {
        Objects.requireNonNull(scope, "scope must not be null");
        String requestedKnowledgeId = requireText(knowledgeId, "knowledgeId");
        if (scope.documents().stream().noneMatch(document -> requestedKnowledgeId.equals(document.documentId()))) {
            throw new ScopeViolation("Requested material document is outside frozen RequirementScope");
        }
        if (requestedLimit < 1) throw new IllegalArgumentException("requestedLimit must be positive");
        int effectiveLimit = Math.min(requestedLimit, 100);
        List<ParsedMaterialUnit> acceptedUnits = new ArrayList<>();
        Set<String> unitIds = new HashSet<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        Integer totalUnits = null;
        int expectedOrdinal = 1;
        ParsedUnitsPageUnit previousUnit = null;

        while (true) {
            ParsedUnitsPage page = fetchParsedUnitsPage(requestedKnowledgeId, effectiveLimit, cursor);
            if (!requestedKnowledgeId.equals(page.knowledgeId())) {
                throw parsedUnitsFailure("response knowledge_id does not match requested document");
            }
            if (page.totalUnits() < 0) throw parsedUnitsFailure("total_units must not be negative");
            if (totalUnits == null) totalUnits = page.totalUnits();
            else if (totalUnits.intValue() != page.totalUnits()) throw parsedUnitsFailure("total_units changed between pages");
            if (page.units() == null) throw parsedUnitsFailure("units must be present");
            if (page.nextCursor() != null && page.nextCursor().isBlank()) throw parsedUnitsFailure("next_cursor must be non-blank when present");
            if (page.complete() && page.nextCursor() != null) throw parsedUnitsFailure("complete page must not include next_cursor");
            if (!page.complete() && page.nextCursor() == null) throw parsedUnitsFailure("non-complete page is missing next_cursor");
            if (page.units().isEmpty() && page.nextCursor() != null) throw parsedUnitsFailure("page with next_cursor made no progress");

            for (ParsedUnitsPageUnit unit : page.units()) {
                if (unit == null || unit.unitId() == null || unit.unitId().isBlank() || unit.content() == null) {
                    throw parsedUnitsFailure("unit is incomplete");
                }
                if (!unitIds.add(unit.unitId())) throw parsedUnitsFailure("unit_id repeats across pages");
                if (unit.ordinal() != expectedOrdinal) throw parsedUnitsFailure("ordinal is not strictly continuous");
                if (previousUnit != null && (unit.chunkIndex() < previousUnit.chunkIndex()
                        || (unit.chunkIndex() == previousUnit.chunkIndex()
                        && unit.unitId().compareTo(previousUnit.unitId()) <= 0))) {
                    throw parsedUnitsFailure("units are outside stable order");
                }
                acceptedUnits.add(new ParsedMaterialUnit(unit.unitId(), unit.chunkIndex(), unit.ordinal(), unit.content(),
                        unit.startAt(), unit.endAt()));
                previousUnit = unit;
                expectedOrdinal++;
            }

            if (page.nextCursor() == null) {
                if (!page.complete()) throw parsedUnitsFailure("final page is not complete");
                if (acceptedUnits.size() != totalUnits) throw parsedUnitsFailure("received unit count does not equal total_units");
                return new ParsedMaterial(requestedKnowledgeId, totalUnits, acceptedUnits);
            }
            if (page.complete()) throw parsedUnitsFailure("complete page must be final");
            if (!cursors.add(page.nextCursor())) throw parsedUnitsFailure("next_cursor loop detected");
            cursor = page.nextCursor();
        }
    }

    private ParsedUnitsPage fetchParsedUnitsPage(String knowledgeId, int limit, String cursor) {
        try {
            ParsedUnitsEnvelope envelope = webClient.get().uri(builder -> {
                        var query = builder.path("/knowledge/{knowledgeId}/parsed-units").queryParam("limit", limit);
                        if (cursor != null) query.queryParam("cursor", cursor);
                        return query.build(knowledgeId);
                    }).header(API_KEY_HEADER, apiKey).exchangeToMono(response -> {
                        if (response.statusCode().isError()) {
                            String message = response.statusCode().value() == 403
                                    ? "Parsed material request forbidden" : "Parsed material request failed";
                            return Mono.error(new KnowledgeAgentInvocationException(message));
                        }
                        return response.bodyToMono(String.class).map(this::parseParsedUnitsEnvelope);
                    }).block(timeout);
            if (envelope == null) throw parsedUnitsFailure("response is missing");
            if (!envelope.success()) {
                String code = envelope.error() == null ? null : envelope.error().code();
                if (code == null || !PARSED_UNITS_BUSINESS_ERRORS.contains(code)) {
                    throw parsedUnitsFailure("business error response is invalid");
                }
                throw parsedUnitsFailure("business error: " + code);
            }
            if (envelope.data() == null) throw parsedUnitsFailure("success response has no data");
            return envelope.data();
        } catch (KnowledgeAgentInvocationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (hasCause(exception, TimeoutException.class) || String.valueOf(exception.getMessage()).contains("Timeout on blocking read")) {
                throw new KnowledgeAgentInvocationException("Parsed material request timed out", exception);
            }
            throw new KnowledgeAgentInvocationException("Parsed material response is invalid", exception);
        }
    }

    private static KnowledgeAgentInvocationException parsedUnitsFailure(String reason) {
        return new KnowledgeAgentInvocationException("Parsed material read rejected: " + reason);
    }

    private ParsedUnitsEnvelope parseParsedUnitsEnvelope(String body) {
        try {
            JsonNode envelope = objectMapper.readTree(body);
            if (envelope == null || !envelope.isObject() || !envelope.path("success").isBoolean()) {
                throw parsedUnitsFailure("response envelope is invalid");
            }
            if (envelope.path("success").booleanValue()) {
                requireExactFields(envelope, Set.of("success", "data"), "response envelope");
                JsonNode data = envelope.path("data");
                if (!data.isObject()) throw parsedUnitsFailure("success response has invalid data");
                requireExactFields(data, Set.of("knowledge_id", "total_units", "units", "next_cursor", "complete"), "data");
                JsonNode units = data.path("units");
                if (!units.isArray()) throw parsedUnitsFailure("data units must be an array");
                for (JsonNode unit : units) {
                    if (!unit.isObject()) throw parsedUnitsFailure("unit must be an object");
                    requireExactFields(unit, Set.of("unit_id", "chunk_index", "ordinal", "content", "start_at", "end_at"), "unit");
                }
            }
            return objectMapper.treeToValue(envelope, ParsedUnitsEnvelope.class);
        } catch (KnowledgeAgentInvocationException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw parsedUnitsFailure("response is not valid JSON");
        }
    }

    private static void requireExactFields(JsonNode object, Set<String> expected, String subject) {
        Set<String> actual = new HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw parsedUnitsFailure(subject + " does not match the fixed parsed-units contract");
    }

    private List<SelectedExample> loadEnabledExamples(ExampleScope scope, FewShotPolicy policy) {
        if (policy == FewShotPolicy.NONE) return List.of();
        return scope.documentIds().stream()
                .map(id -> loadEnabledExample(scope.knowledgeBaseId(), id, scope.expectedQualityKind(id))).toList();
    }

    private SelectedExample loadEnabledExample(String expectedKnowledgeBaseId, String documentId,
            ExampleQualityKind expectedQualityKind) {
        KnowledgeEnvelope metadata = webClient.get().uri("/knowledge/{id}", documentId).header(API_KEY_HEADER, apiKey)
                .exchangeToMono(response -> response.statusCode().isError()
                        ? response.createException().flatMap(error -> Mono.error(new KnowledgeAgentInvocationException("Example metadata request failed", error)))
                        : response.bodyToMono(KnowledgeEnvelope.class)).block(timeout);
        if (metadata == null || !metadata.success() || metadata.data() == null
                || !expectedKnowledgeBaseId.equals(metadata.data().knowledgeBaseId())
                || !"completed".equals(metadata.data().parseStatus()) || !"enabled".equals(metadata.data().enableStatus())) {
            throw new KnowledgeAgentInvocationException("Example document is not enabled and ready");
        }
        String markdown = webClient.get().uri("/knowledge/{id}/preview", documentId).header(API_KEY_HEADER, apiKey)
                .accept(MediaType.valueOf("text/markdown")).exchangeToMono(response -> response.statusCode().isError()
                        ? response.createException().flatMap(error -> Mono.error(new KnowledgeAgentInvocationException("Example preview request failed", error)))
                        : response.bodyToMono(String.class)).block(timeout);
        return SelectedExample.from(documentId, expectedQualityKind, markdown);
    }

    private void requireConfiguredAgent(String agentId) {
        for (int attempt = 1; attempt <= maxAgentDiscoveryAttempts; attempt++) {
            try {
                AgentEnvelope response = webClient.get().uri("/agents/{agentId}", agentId).header(API_KEY_HEADER, apiKey)
                        .exchangeToMono(clientResponse -> {
                            if (clientResponse.statusCode().isError()) {
                                return clientResponse.createException().flatMap(error -> Mono.error(new KnowledgeAgentInvocationException("Knowledge agent discovery failed", error)));
                            }
                            return clientResponse.bodyToMono(AgentEnvelope.class);
                        }).block(timeout);
                if (response == null || !response.success() || response.data() == null || !agentId.equals(response.data().id())) {
                    throw new KnowledgeAgentInvocationException("Configured agent was not found");
                }
                return;
            } catch (RuntimeException exception) {
                KnowledgeAgentInvocationException failure = invocationFailure("Knowledge agent discovery failed", exception);
                if (!isSafePreparationTransportFailure(failure)) {
                    throw failure;
                }
                if (attempt == maxAgentDiscoveryAttempts) {
                    throw new KnowledgeAgentInvocationException("Knowledge agent discovery transient failure after "
                            + maxAgentDiscoveryAttempts + " attempts", failure);
                }
            }
        }
    }

    private String createSession() {
        try {
            SessionEnvelope response = webClient.post().uri("/sessions").header(API_KEY_HEADER, apiKey)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(new SessionRequest("测试用例生成任务", "Java Web 批次任务"))
                    .exchangeToMono(clientResponse -> clientResponse.statusCode().isError()
                            ? clientResponse.createException().flatMap(error -> Mono.error(new KnowledgeAgentInvocationException("Knowledge agent session creation failed", error)))
                            : clientResponse.bodyToMono(SessionEnvelope.class)).block(timeout);
            if (response == null || !response.success() || response.data() == null || response.data().id() == null || response.data().id().isBlank()) {
                throw new KnowledgeAgentInvocationException("Knowledge agent session response has no id");
            }
            return response.data().id();
        } catch (RuntimeException exception) {
            throw invocationFailure("Knowledge agent session creation failed", exception);
        }
    }

    /**
     * Accepts agent output only when the requested Skill was actually loaded through the existing
     * SSE tool protocol. A completed answer alone is insufficient because it does not prove which
     * specialized instructions were available to the remote agent.
     *
     * [Req-ID]: REQ-KSI-001, REQ-KSI-002, REQ-KSI-003
     */
    private void prepareSession(String agentId, RequirementScope scope, List<String> requirementAdmissionTypeKeys, String skillName) {
        if (preparedSession.get() != null) {
            throw new KnowledgeAgentSkillPreparationException("Knowledge agent Skill session is already prepared on this worker thread",
                    false, null);
        }
        try {
            requireConfiguredAgent(agentId);
        } catch (RuntimeException exception) {
            throw preparationFailure(exception);
        }
        KnowledgeAgentInvocationException failure = null;
        for (int attempt = 1; attempt <= SKILL_PREPARATION_ATTEMPTS; attempt++) {
            try {
                String sessionId = createSession();
                invokeChat(sessionId, AgentChatRequest.forSkillPreparation(agentId, scope, requirementAdmissionTypeKeys, skillName),
                        skillName, true, true);
                preparedSession.set(new PreparedSkillSession(sessionId, agentId, scope, requirementAdmissionTypeKeys, skillName));
                return;
            } catch (RuntimeException exception) {
                failure = invocationFailure("Knowledge agent Skill preparation failed", exception);
                if (!isSafePreparationTransportFailure(failure)) {
                    throw new KnowledgeAgentSkillPreparationException(failure.getMessage(), false, failure);
                }
            }
        }
        throw new KnowledgeAgentSkillPreparationException("Knowledge agent Skill preparation failed after "
                + SKILL_PREPARATION_ATTEMPTS + " attempts: " + (failure == null ? "unknown failure" : failure.getMessage()),
                true, failure);
    }

    private KnowledgeAgentSkillPreparationException preparationFailure(RuntimeException exception) {
        KnowledgeAgentInvocationException failure = invocationFailure("Knowledge agent Skill preparation failed", exception);
        return new KnowledgeAgentSkillPreparationException(failure.getMessage(), isSafePreparationTransportFailure(failure), failure);
    }

    private SessionSelection sessionFor(String agentId, RequirementScope scope, List<String> requirementAdmissionTypeKeys,
            String skillName) {
        PreparedSkillSession prepared = preparedSession.get();
        if (prepared == null) return new SessionSelection(createSession(), true);
        if (!prepared.matches(agentId, scope, requirementAdmissionTypeKeys, skillName)) {
            throw new KnowledgeAgentInvocationException("Prepared Knowledge agent Skill session does not match the current frozen stage scope");
        }
        // The isolated endpoint clears conversational runtime state for every call. A preparation
        // turn cannot prove that a later business turn has read the pinned Skill.
        return new SessionSelection(prepared.sessionId(), true);
    }

    private String invokeChat(String sessionId, AgentChatRequest request, String expectedSkillName, boolean requireReadSkillEvidence,
            boolean preparationOnly) {
        try {
            AnswerAccumulator answer = webClient.post().uri("/agent-chat/{sessionId}/isolated-skill", sessionId)
                    .header(API_KEY_HEADER, apiKey).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(request).exchangeToFlux(clientResponse -> clientResponse.statusCode().isError()
                            ? clientResponse.createException().flatMapMany(error -> Flux.error(new KnowledgeAgentInvocationException("Knowledge agent chat request failed", error)))
                            : clientResponse.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() { }))
                    .timeout(timeout).map(event -> parseRawEvent(event, expectedSkillName, preparationOnly)).takeUntil(ParsedStreamEvent::complete)
                    .reduce(new AnswerAccumulator(), AnswerAccumulator::append).block(timeout.plusMillis(100));
            if (answer == null || !answer.complete()) throw new KnowledgeAgentInvocationException("Knowledge agent SSE ended without an explicit complete event");
            if (requireReadSkillEvidence && !answer.hasSuccessfulReadSkill()) {
                throw new KnowledgeAgentInvocationException("Knowledge agent SSE completed without required read_skill evidence");
            }
            return answer.content();
        } catch (RuntimeException exception) {
            throw invocationFailure("Knowledge agent SSE request failed", exception);
        }
    }

    private ParsedStreamEvent parseRawEvent(ServerSentEvent<String> rawEvent, String expectedSkillName, boolean preparationOnly) {
        if (!"message".equals(rawEvent.event())) throw new KnowledgeAgentInvocationException("Knowledge agent SSE event must be message");
        String data = rawEvent.data();
        if (data == null || data.length() > maxEventCharacters) throw new KnowledgeAgentInvocationException("Knowledge agent SSE event exceeds maximum size");
        AgentStreamData streamData = parseStreamData(data);
        if ("error".equals(streamData.responseType()) && (streamData.done() || preparationOnly)) {
            throw new KnowledgeAgentInvocationException(preparationOnly
                    ? "Knowledge agent Skill preparation error: " + streamData.message()
                    : "Knowledge agent terminal error: " + streamData.message());
        }
        if ("tool_call".equals(streamData.responseType())) {
            if (preparationOnly && !isReadSkillTool(streamData.data())) {
                throw new KnowledgeAgentInvocationException("Knowledge agent Skill preparation may only call the exact read_skill");
            }
            if (!preparationOnly && !isIsolatedSkillTool(streamData.data())) {
                throw new KnowledgeAgentInvocationException("Knowledge agent isolated Skill call emitted a forbidden tool");
            }
            if (!isReadSkillTool(streamData.data())) {
                return ParsedStreamEvent.none();
            }
            String callId = toolCallId(streamData.data());
            String actualSkillName = skillName(streamData.data());
            if (actualSkillName == null) {
                if (callId == null) {
                    throw new KnowledgeAgentInvocationException("Knowledge agent Skill preparation read_skill declaration requires a stable tool_call_id");
                }
                return ParsedStreamEvent.readSkillDeclaration(callId);
            }
            if (!expectedSkillName.equals(actualSkillName)) {
                throw new KnowledgeAgentInvocationException("Knowledge agent Skill preparation may only call the exact read_skill");
            }
            if (callId == null) {
                throw new KnowledgeAgentInvocationException("Knowledge agent Skill preparation read_skill call requires a stable tool_call_id");
            }
            return ParsedStreamEvent.exactReadSkillCall(callId);
        }
        if (preparationOnly && "tool_result".equals(streamData.responseType()) && hasNamedNonReadSkillTool(streamData.data())) {
            throw new KnowledgeAgentInvocationException("Knowledge agent Skill preparation may only call read_skill");
        }
        if (!preparationOnly && "tool_result".equals(streamData.responseType())
                && hasNamedForbiddenIsolatedSkillTool(streamData.data())) {
            throw new KnowledgeAgentInvocationException("Knowledge agent isolated Skill call emitted a forbidden tool");
        }
        if (preparationOnly && "tool_result".equals(streamData.responseType()) && skillName(streamData.data()) != null
                && !expectedSkillName.equals(skillName(streamData.data()))) {
            throw new KnowledgeAgentInvocationException("Knowledge agent Skill preparation may only return the exact read_skill");
        }
        if (preparationOnly && "tool_result".equals(streamData.responseType())
                && streamData.data().path("success").isBoolean() && !successfulToolResult(streamData.data())) {
            throw new KnowledgeAgentInvocationException("Knowledge agent Skill preparation read_skill failed");
        }
        if ("tool_result".equals(streamData.responseType()) && toolCallId(streamData.data()) != null
                && (skillName(streamData.data()) == null || expectedSkillName.equals(skillName(streamData.data())))) {
            return ParsedStreamEvent.readSkillResult(toolCallId(streamData.data()), successfulToolResult(streamData.data()));
        }
        return new ParsedStreamEvent("answer".equals(streamData.responseType()) ? streamData.content() : "",
                "complete".equals(streamData.responseType()) && streamData.done(), false, false, false, null, null, false);
    }

    private static boolean isReadSkillTool(JsonNode data) {
        return data != null && data.isObject() && "read_skill".equals(textField(data, "tool_name"));
    }

    private static boolean hasNamedNonReadSkillTool(JsonNode data) {
        String toolName = textField(data, "tool_name");
        return toolName != null && !"read_skill".equals(toolName);
    }

    private static boolean isIsolatedSkillTool(JsonNode data) {
        String toolName = textField(data, "tool_name");
        return "read_skill".equals(toolName) || "execute_skill_script".equals(toolName);
    }

    private static boolean hasNamedForbiddenIsolatedSkillTool(JsonNode data) {
        String toolName = textField(data, "tool_name");
        return toolName != null && !"read_skill".equals(toolName) && !"execute_skill_script".equals(toolName);
    }

    private static String skillName(JsonNode data) {
        String direct = textField(data, "skill_name");
        if (direct != null) return direct;
        for (String field : List.of("arguments", "args", "input")) {
            JsonNode nested = data.get(field);
            if (nested != null && nested.isObject()) {
                String value = textField(nested, "skill_name");
                if (value != null) return value;
            }
        }
        return null;
    }

    private static String toolCallId(JsonNode data) {
        for (String field : List.of("tool_call_id", "call_id", "id")) {
            String value = textField(data, field);
            if (value != null) return value;
        }
        return null;
    }

    private static String textField(JsonNode data, String field) {
        JsonNode value = data.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static boolean successfulToolResult(JsonNode data) {
        return data.path("success").isBoolean() && data.path("success").booleanValue();
    }

    private AgentStreamData parseStreamData(String data) {
        try {
            AgentStreamData event = objectMapper.readValue(data, AgentStreamData.class);
            if (event.responseType() == null || event.responseType().isBlank()) {
                throw new KnowledgeAgentInvocationException("Knowledge agent SSE event has no response_type");
            }
            return event;
        } catch (JsonProcessingException exception) {
            throw new KnowledgeAgentInvocationException("Knowledge agent SSE event must contain JSON data", exception);
        }
    }

    private static boolean isTransientStatus(int status) { return status == 408 || status == 429 || status == 502 || status == 503 || status == 504; }
    private static KnowledgeAgentInvocationException invocationFailure(String context, RuntimeException exception) {
        if (exception instanceof KnowledgeAgentInvocationException known) return known;
        if (hasCause(exception, TimeoutException.class) || String.valueOf(exception.getMessage()).contains("Timeout on blocking read")) {
            return new KnowledgeAgentInvocationException(context + " timed out", exception);
        }
        return new KnowledgeAgentInvocationException(context, exception);
    }
    private static boolean isSafePreparationTransportFailure(KnowledgeAgentInvocationException failure) {
        if (hasCause(failure, TimeoutException.class) || hasCause(failure, WebClientRequestException.class)
                || String.valueOf(failure.getMessage()).contains("timed out")) return true;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof WebClientResponseException response && isTransientStatus(response.getStatusCode().value())) return true;
        }
        return false;
    }
    private static boolean hasCause(Throwable value, Class<? extends Throwable> type) { for (Throwable current = value; current != null; current = current.getCause()) if (type.isInstance(current)) return true; return false; }
    private static String requireText(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank"); return value; }

    private record SessionRequest(String title, String description) { }
    private record StructuredSkillRequest(@JsonProperty("agent_id") String agentId, @JsonProperty("skill_name") String skillName,
            @JsonProperty("knowledge_base_ids") List<String> knowledgeBaseIds, @JsonProperty("knowledge_ids") List<String> knowledgeIds,
            @JsonProperty("system_scopes") List<SystemScopePayload> systemScopes, Object input) { }
    private record StructuredHttpResponse(boolean successful, String body) { }
    private record SessionEnvelope(boolean success, SessionData data) { }
    private record SessionData(String id) { }
    private record AgentEnvelope(boolean success, AgentSummary data) { }
    private record AgentSummary(String id) { }
    private record KnowledgeEnvelope(boolean success, KnowledgeData data) { }
    private record KnowledgeData(@JsonProperty("knowledge_base_id") String knowledgeBaseId,
            @JsonProperty("parse_status") String parseStatus, @JsonProperty("enable_status") String enableStatus) { }
    private record ParsedUnitsEnvelope(boolean success, ParsedUnitsPage data, ParsedUnitsError error) { }
    private record ParsedUnitsError(String code) { }
    private record ParsedUnitsPage(@JsonProperty("knowledge_id") String knowledgeId,
            @JsonProperty("total_units") int totalUnits, List<ParsedUnitsPageUnit> units,
            @JsonProperty("next_cursor") String nextCursor, boolean complete) { }
    private record ParsedUnitsPageUnit(@JsonProperty("unit_id") String unitId,
            @JsonProperty("chunk_index") int chunkIndex, int ordinal, String content,
            @JsonProperty("start_at") long startAt, @JsonProperty("end_at") long endAt) { }
    private record AgentStreamData(@JsonProperty("response_type") String responseType, boolean done, String content, String message,
            JsonNode data) { }
    private record SessionSelection(String sessionId, boolean requiresReadSkillEvidence) { }
    private record PreparedSkillSession(String sessionId, String agentId, RequirementScope requirementScope,
            List<String> requirementAdmissionTypeKeys, String skillName) {
        private PreparedSkillSession {
            requirementAdmissionTypeKeys = List.copyOf(requirementAdmissionTypeKeys);
        }
        private boolean matches(String agentId, RequirementScope scope, List<String> requirementAdmissionTypeKeys, String skillName) {
            return this.agentId.equals(agentId) && requirementScope.equals(scope)
                    && this.requirementAdmissionTypeKeys.equals(requirementAdmissionTypeKeys)
                    && this.skillName.equals(skillName);
        }
    }
    private record ParsedStreamEvent(String content, boolean complete, boolean readSkillDeclaration, boolean exactReadSkillCall,
            boolean readSkillResult, String readSkillCallId, String readSkillResultId, boolean readSkillResultSuccess) {
        private static ParsedStreamEvent readSkillDeclaration(String toolCallId) {
            return new ParsedStreamEvent("", false, true, false, false, toolCallId, null, false);
        }
        private static ParsedStreamEvent exactReadSkillCall(String toolCallId) {
            return new ParsedStreamEvent("", false, false, true, false, toolCallId, null, false);
        }
        private static ParsedStreamEvent readSkillResult(String toolCallId, boolean success) {
            return new ParsedStreamEvent("", false, false, false, true, null, toolCallId, success);
        }
        private static ParsedStreamEvent none() {
            return new ParsedStreamEvent("", false, false, false, false, null, null, false);
        }
    }
    private final class AnswerAccumulator {
        private final String content;
        private final boolean complete;
        private final Set<String> pendingReadSkillDeclarationIds;
        private final Set<String> pendingExactReadSkillCallIds;
        private final Set<String> seenExactReadSkillCallIds;
        private final int pendingUnnamedReadSkillCalls;
        private final boolean successfulReadSkill;
        private final boolean failedReadSkill;
        private AnswerAccumulator() { this("", false, Set.of(), Set.of(), Set.of(), 0, false, false); }
        private AnswerAccumulator(String content, boolean complete, Set<String> pendingReadSkillDeclarationIds,
                Set<String> pendingExactReadSkillCallIds, Set<String> seenExactReadSkillCallIds,
                int pendingUnnamedReadSkillCalls, boolean successfulReadSkill, boolean failedReadSkill) {
            this.content = content;
            this.complete = complete;
            this.pendingReadSkillDeclarationIds = pendingReadSkillDeclarationIds;
            this.pendingExactReadSkillCallIds = pendingExactReadSkillCallIds;
            this.seenExactReadSkillCallIds = seenExactReadSkillCallIds;
            this.pendingUnnamedReadSkillCalls = pendingUnnamedReadSkillCalls;
            this.successfulReadSkill = successfulReadSkill;
            this.failedReadSkill = failedReadSkill;
        }
        private AnswerAccumulator append(ParsedStreamEvent event) {
            String next = content + (event.content() == null ? "" : event.content());
            if (next.length() > maxEventCharacters) throw new KnowledgeAgentInvocationException("Knowledge agent SSE answer exceeds maximum size");
            Set<String> declarations = new HashSet<>(pendingReadSkillDeclarationIds);
            Set<String> pending = new HashSet<>(pendingExactReadSkillCallIds);
            Set<String> seen = new HashSet<>(seenExactReadSkillCallIds);
            int unnamed = pendingUnnamedReadSkillCalls;
            boolean success = successfulReadSkill;
            boolean failure = failedReadSkill;
            if (event.readSkillDeclaration()) {
                declarations.add(event.readSkillCallId());
            }
            if (event.exactReadSkillCall()) {
                if (!seen.isEmpty()) {
                    throw new KnowledgeAgentInvocationException("Knowledge agent Skill preparation may call the exact read_skill only once");
                }
                seen.add(event.readSkillCallId());
                declarations.remove(event.readSkillCallId());
                pending.add(event.readSkillCallId());
            }
            if (event.readSkillResult()) {
                boolean matched = event.readSkillResultId() != null
                        ? pending.remove(event.readSkillResultId())
                        : pending.isEmpty() && unnamed == 1;
                if (matched) {
                    if (event.readSkillResultId() == null) unnamed--;
                    if (event.readSkillResultSuccess()) success = true;
                    else failure = true;
                }
            }
            return new AnswerAccumulator(next, event.complete(), Set.copyOf(declarations), Set.copyOf(pending), Set.copyOf(seen), unnamed, success, failure);
        }
        private String content() { return content; }
        private boolean complete() { return complete; }
        private boolean hasSuccessfulReadSkill() {
            return successfulReadSkill && !failedReadSkill && pendingReadSkillDeclarationIds.isEmpty()
                    && pendingExactReadSkillCallIds.isEmpty() && pendingUnnamedReadSkillCalls == 0;
        }
    }

    private record AgentChatRequest(@JsonProperty("agent_id") String agentId, String query,
            @JsonProperty("agent_enabled") boolean agentEnabled, @JsonProperty("knowledge_base_ids") List<String> knowledgeBaseIds,
            @JsonProperty("knowledge_ids") List<String> knowledgeIds, @JsonProperty("system_scopes") List<LegacySystemScopePayload> systemScopes,
            @JsonProperty("web_search_enabled") boolean webSearchEnabled, @JsonProperty("disable_title") boolean disableTitle, String channel,
            @JsonProperty("skill_names") List<String> skillNames) {
        private static AgentChatRequest forGeneration(KnowledgeAgentInvocation invocation, String query) {
            return from(invocation.agentId(), invocation.requirementScope(), invocation.requirementAdmissionTypeKeys(), query, GENERATION_SKILL);
        }
        private static AgentChatRequest forReconciliation(FeatureReconciliationInvocation invocation) {
            return from(invocation.agentId(), invocation.requirementScope(), invocation.requirementAdmissionTypeKeys(),
                    invocation.prompt(), RECONCILIATION_SKILL);
        }
        private static AgentChatRequest forSkillPreparation(String agentId, RequirementScope scope, List<String> types, String skillName) {
            // KEE treats non-casual preparation instructions as a retrieval query before its agent loop.
            // The fixed token avoids that pre-execution; exact skill_names plus SSE evidence remain the gate.
            return from(agentId, scope, types, "你好", skillName);
        }
        private static AgentChatRequest from(String agentId, RequirementScope scope, List<String> types, String query, String skillName) {
            List<String> documents = scope.documents().stream().map(document -> document.documentId()).toList();
            return new AgentChatRequest(agentId, query, true, List.of(scope.knowledgeBaseId()), documents,
                    List.of(LegacySystemScopePayload.from(scope, types)), false, true, "api", List.of(skillName));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record LegacySystemScopePayload(@JsonProperty("knowledge_base_id") String knowledgeBaseId,
            @JsonProperty("version_id") String versionId, @JsonProperty("content_categories") List<String> contentCategories,
            @JsonProperty("admission_type_keys") List<String> admissionTypeKeys, @JsonProperty("project_id") String projectId,
            @JsonProperty("knowledge_ids") List<String> knowledgeIds) {
        private static LegacySystemScopePayload from(RequirementScope scope, List<String> types) {
            return new LegacySystemScopePayload(scope.knowledgeBaseId(), scope.versionId(), List.of(scope.materialCategory()), types,
                    scope.projectId(), scope.documents().stream().map(document -> document.documentId()).toList());
        }
    }

    private record SelectedExample(String kind, String content, String whyBad, String correctedPattern) {
        private static SelectedExample from(String id, ExampleQualityKind expectedKind, String markdown) {
            if (markdown == null || !markdown.startsWith("---\n")) throw new KnowledgeAgentInvocationException("Example preview has no front matter");
            int end = markdown.indexOf("\n---", 4);
            if (end < 0) throw new KnowledgeAgentInvocationException("Example preview has invalid front matter");
            Map<String, String> fields = java.util.Arrays.stream(markdown.substring(4, end).split("\\R")).map(line -> line.split(":", 2))
                    .filter(parts -> parts.length == 2).collect(java.util.stream.Collectors.toMap(parts -> parts[0].trim(), parts -> parts[1].trim(), (left, right) -> left));
            String kind = expectedKind == ExampleQualityKind.GOOD_CASE ? "GOOD" : "BAD";
            if (!kind.equals(fields.get("quality_kind"))) throw new KnowledgeAgentInvocationException("Example preview quality kind does not match its configured whitelist");
            String body = markdown.substring(end + 4).trim();
            if ("GOOD".equals(kind)) return new SelectedExample(kind, body, null, null);
            return new SelectedExample(kind, section(body, "bad_case"), section(body, "why_bad"), section(body, "corrected_pattern"));
        }
        private static String section(String markdown, String heading) {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("(?ms)^##\\s+" + java.util.regex.Pattern.quote(heading) + "\\s*$\\R(.*?)(?=^##\\s|\\z)").matcher(markdown);
            if (!match.find() || match.group(1).isBlank()) throw new KnowledgeAgentInvocationException("Bad example preview is missing " + heading);
            return match.group(1).trim();
        }
    }

    private static final class MarkdownPrompt {
        private MarkdownPrompt() { }
        private static String generation(String supplementalNote, List<SelectedExample> examples) {
            StringBuilder prompt = new StringBuilder("请仅基于本次已限定的正式需求材料，为一个功能点生成测试用例。正式事实和审查发现只能来自这些需求材料；示例只用于写法参考。不要 JSON、不要代码块、不要图片，严格按以下两张 Markdown 表返回：\n"
                    + "## 需求与功能清单审查发现\n| 序号 | 对象/功能点 | 问题分类 | 证据对照 |\n|---|---|---|---|\n"
                    + "## 测试用例\n| 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |\n|---|---|---|---|---|---|\n");
            if (!supplementalNote.isBlank()) prompt.append("补充说明：").append(supplementalNote).append('\n');
            for (SelectedExample example : examples) {
                prompt.append("参考").append(example.kind()).append("示例（仅写法参考）：\n").append(example.content()).append('\n');
                if (example.whyBad() != null) prompt.append("为什么不好：").append(example.whyBad()).append("\n改正方式：").append(example.correctedPattern()).append('\n');
            }
            return prompt.toString();
        }
    }

}
