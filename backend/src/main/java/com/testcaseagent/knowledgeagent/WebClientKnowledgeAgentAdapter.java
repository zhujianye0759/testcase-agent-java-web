package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.fewshot.ExampleQualityKind;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.markdown.MarkdownFeatureListParser;
import com.testcaseagent.markdown.MarkdownFeatureRow;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
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
 * REQ-SCP-003, REQ-FEW-002, REQ-FEW-003, REQ-ANA-004
 */
public final class WebClientKnowledgeAgentAdapter implements KnowledgeAgentPort {

    private static final String API_KEY_HEADER = "X-API-Key";
    private final WebClient webClient;
    private final String apiKey;
    private final Duration timeout;
    private final int maxAgentDiscoveryAttempts;
    private final int maxEventCharacters;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final MarkdownFeatureListParser featureListParser = new MarkdownFeatureListParser();

    public WebClientKnowledgeAgentAdapter(String apiBaseUrl, String apiKey, Duration timeout,
            int maxAgentDiscoveryAttempts, int maxEventCharacters) {
        this.webClient = WebClient.builder().baseUrl(requireText(apiBaseUrl, "apiBaseUrl")).build();
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
        requireConfiguredAgent(invocation.agentId());
        List<SelectedExample> examples = loadEnabledExamples(invocation.exampleScope(), invocation.fewShotPolicy());
        String sessionId = createSession();
        String markdown = invokeChat(sessionId,
                AgentChatRequest.forGeneration(invocation, MarkdownPrompt.generation(invocation.prompt(), examples)));
        return new KnowledgeAgentInvocationResult(sessionId, List.of(), markdown);
    }

    @Override
    public List<MarkdownFeatureRow> discoverFeatures(FeatureDiscoveryInvocation invocation) {
        requireConfiguredAgent(invocation.agentId());
        String sessionId = createSession();
        String markdown = invokeChat(sessionId,
                AgentChatRequest.forDiscovery(invocation, MarkdownPrompt.discovery()));
        return featureListParser.parse(markdown);
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
                            if (isTransientStatus(clientResponse.statusCode().value())) {
                                return clientResponse.createException().flatMap(error -> Mono.error(new TransientAgentDiscoveryFailure(error)));
                            }
                            if (clientResponse.statusCode().isError()) {
                                return clientResponse.createException().flatMap(error -> Mono.error(new KnowledgeAgentInvocationException("Knowledge agent discovery failed", error)));
                            }
                            return clientResponse.bodyToMono(AgentEnvelope.class);
                        }).block(timeout);
                if (response == null || !response.success() || response.data() == null || !agentId.equals(response.data().id())) {
                    throw new KnowledgeAgentInvocationException("Configured agent was not found");
                }
                return;
            } catch (TransientAgentDiscoveryFailure failure) {
                if (attempt == maxAgentDiscoveryAttempts) {
                    throw new KnowledgeAgentInvocationException("Knowledge agent discovery transient failure after " + maxAgentDiscoveryAttempts + " attempts", failure);
                }
            } catch (RuntimeException exception) {
                if (hasCause(exception, TimeoutException.class) || String.valueOf(exception.getMessage()).contains("Timeout on blocking read")) {
                    throw new KnowledgeAgentInvocationException("Knowledge agent discovery timed out", exception);
                }
                throw exception;
            }
        }
    }

    private String createSession() {
        SessionEnvelope response = webClient.post().uri("/sessions").header(API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(new SessionRequest("测试用例生成任务", "Java Web 批次任务"))
                .exchangeToMono(clientResponse -> clientResponse.statusCode().isError()
                        ? clientResponse.createException().flatMap(error -> Mono.error(new KnowledgeAgentInvocationException("Knowledge agent session creation failed", error)))
                        : clientResponse.bodyToMono(SessionEnvelope.class)).block(timeout);
        if (response == null || !response.success() || response.data() == null || response.data().id() == null || response.data().id().isBlank()) {
            throw new KnowledgeAgentInvocationException("Knowledge agent session response has no id");
        }
        return response.data().id();
    }

    private String invokeChat(String sessionId, AgentChatRequest request) {
        try {
            AnswerAccumulator answer = webClient.post().uri("/agent-chat/{sessionId}", sessionId)
                    .header(API_KEY_HEADER, apiKey).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(request).exchangeToFlux(clientResponse -> clientResponse.statusCode().isError()
                            ? clientResponse.createException().flatMapMany(error -> Flux.error(new KnowledgeAgentInvocationException("Knowledge agent chat request failed", error)))
                            : clientResponse.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() { }))
                    .timeout(timeout).map(this::parseRawEvent).takeUntil(ParsedStreamEvent::complete)
                    .reduce(new AnswerAccumulator(), AnswerAccumulator::append).block(timeout.plusMillis(100));
            if (answer == null || !answer.complete()) throw new KnowledgeAgentInvocationException("Knowledge agent SSE ended without an explicit complete event");
            return answer.content();
        } catch (RuntimeException exception) {
            if (hasCause(exception, TimeoutException.class)) throw new KnowledgeAgentInvocationException("Knowledge agent SSE request timed out", exception);
            throw exception;
        }
    }

    private ParsedStreamEvent parseRawEvent(ServerSentEvent<String> rawEvent) {
        if (!"message".equals(rawEvent.event())) throw new KnowledgeAgentInvocationException("Knowledge agent SSE event must be message");
        String data = rawEvent.data();
        if (data == null || data.length() > maxEventCharacters) throw new KnowledgeAgentInvocationException("Knowledge agent SSE event exceeds maximum size");
        AgentStreamData streamData = parseStreamData(data);
        if ("error".equals(streamData.responseType()) && streamData.done()) throw new KnowledgeAgentInvocationException("Knowledge agent terminal error: " + streamData.message());
        return new ParsedStreamEvent("answer".equals(streamData.responseType()) ? streamData.content() : "",
                "complete".equals(streamData.responseType()) && streamData.done());
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
    private static boolean hasCause(Throwable value, Class<? extends Throwable> type) { for (Throwable current = value; current != null; current = current.getCause()) if (type.isInstance(current)) return true; return false; }
    private static String requireText(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank"); return value; }

    private record SessionRequest(String title, String description) { }
    private record SessionEnvelope(boolean success, SessionData data) { }
    private record SessionData(String id) { }
    private record AgentEnvelope(boolean success, AgentSummary data) { }
    private record AgentSummary(String id) { }
    private record KnowledgeEnvelope(boolean success, KnowledgeData data) { }
    private record KnowledgeData(@JsonProperty("knowledge_base_id") String knowledgeBaseId,
            @JsonProperty("parse_status") String parseStatus, @JsonProperty("enable_status") String enableStatus) { }
    private record AgentStreamData(@JsonProperty("response_type") String responseType, boolean done, String content, String message) { }
    private record ParsedStreamEvent(String content, boolean complete) { }
    private final class AnswerAccumulator {
        private final String content;
        private final boolean complete;
        private AnswerAccumulator() { this("", false); }
        private AnswerAccumulator(String content, boolean complete) { this.content = content; this.complete = complete; }
        private AnswerAccumulator append(ParsedStreamEvent event) {
            String next = content + (event.content() == null ? "" : event.content());
            if (next.length() > maxEventCharacters) throw new KnowledgeAgentInvocationException("Knowledge agent SSE answer exceeds maximum size");
            return new AnswerAccumulator(next, event.complete());
        }
        private String content() { return content; }
        private boolean complete() { return complete; }
    }

    private record AgentChatRequest(@JsonProperty("agent_id") String agentId, String query,
            @JsonProperty("agent_enabled") boolean agentEnabled, @JsonProperty("knowledge_base_ids") List<String> knowledgeBaseIds,
            @JsonProperty("knowledge_ids") List<String> knowledgeIds, @JsonProperty("system_scopes") List<SystemScopePayload> systemScopes,
            @JsonProperty("web_search_enabled") boolean webSearchEnabled, @JsonProperty("disable_title") boolean disableTitle, String channel) {
        private static AgentChatRequest forGeneration(KnowledgeAgentInvocation invocation, String query) {
            return from(invocation.agentId(), invocation.requirementScope(), invocation.requirementAdmissionTypeKeys(), query);
        }
        private static AgentChatRequest forDiscovery(FeatureDiscoveryInvocation invocation, String query) {
            return from(invocation.agentId(), invocation.requirementScope(), invocation.requirementAdmissionTypeKeys(), query);
        }
        private static AgentChatRequest from(String agentId, RequirementScope scope, List<String> types, String query) {
            List<String> documents = scope.documents().stream().map(document -> document.documentId()).toList();
            return new AgentChatRequest(agentId, query, true, List.of(scope.knowledgeBaseId()), documents,
                    List.of(SystemScopePayload.from(scope, types)), false, true, "api");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record SystemScopePayload(@JsonProperty("knowledge_base_id") String knowledgeBaseId,
            @JsonProperty("version_id") String versionId, @JsonProperty("content_categories") List<String> contentCategories,
            @JsonProperty("admission_type_keys") List<String> admissionTypeKeys, @JsonProperty("project_id") String projectId,
            @JsonProperty("knowledge_ids") List<String> knowledgeIds) {
        private static SystemScopePayload from(RequirementScope scope, List<String> types) {
            return new SystemScopePayload(scope.knowledgeBaseId(), scope.versionId(), List.of(scope.materialCategory()), types,
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
        private static String discovery() {
            return "请仅基于本次已限定的正式材料列出可生成测试用例的全部功能点。不要解释、不要 JSON、不要代码块；严格只返回：\n"
                    + "## 功能点清单\n| 序号 | 功能点 |\n|---|---|\n| 1 | 功能名称 |";
        }
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

    private static final class TransientAgentDiscoveryFailure extends RuntimeException {
        private TransientAgentDiscoveryFailure(Throwable cause) { super(cause); }
    }
}
