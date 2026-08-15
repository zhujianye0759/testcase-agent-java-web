package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.testcaseagent.scope.KnowledgeScopeCatalogPort;
import com.testcaseagent.scope.ScopeCatalogUnavailableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * KEE read adapter used only to build server-owned task scope snapshots.
 *
 * [Req-ID]: REQ-KAG-009, REQ-CAT-001, REQ-CAT-003
 */
public final class WebClientKnowledgeScopeCatalogAdapter implements KnowledgeScopeCatalogPort {
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final int MAX_CATALOG_PAGE_BYTES = 8 * 1024 * 1024;

    private final WebClient webClient;
    private final String apiKey;
    private final Duration timeout;
    private final int knowledgeBasePageSize;
    private final int documentPageSize;

    public WebClientKnowledgeScopeCatalogAdapter(
            String apiBaseUrl, String apiKey, Duration timeout, int knowledgeBasePageSize, int documentPageSize) {
        this.webClient = WebClient.builder().baseUrl(requireText(apiBaseUrl, "apiBaseUrl"))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_CATALOG_PAGE_BYTES)).build();
        this.apiKey = requireText(apiKey, "apiKey");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        this.knowledgeBasePageSize = requirePageSize(knowledgeBasePageSize, "knowledgeBasePageSize");
        this.documentPageSize = requirePageSize(documentPageSize, "documentPageSize");
    }

    @Override
    public List<KnowledgeBase> listKnowledgeBases() {
        List<KnowledgeBase> values = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        do {
            String uri = cursor == null
                    ? "/knowledge-bases/selector-options?limit=" + knowledgeBasePageSize
                    : "/knowledge-bases/selector-options?limit=" + knowledgeBasePageSize + "&cursor=" + cursor;
            JsonNode envelope = get(uri, "Knowledge-base catalog request failed");
            requireSuccess(envelope, "Knowledge-base catalog response");
            JsonNode data = requireArray(envelope, "data", "Knowledge-base catalog response");
            data.forEach(item -> values.add(new KnowledgeBase(
                    requiredText(item, "id"), requiredText(item, "name"), requiredText(item, "type"))));
            boolean hasMore = envelope.path("has_more").asBoolean(false);
            cursor = textOrNull(envelope, "next_cursor");
            if (hasMore && (data.isEmpty() || cursor == null || !cursors.add(cursor))) {
                throw new ScopeCatalogUnavailableException("Knowledge-base catalog pagination is inconsistent");
            }
            if (!hasMore) cursor = null;
        } while (cursor != null);
        return List.copyOf(values);
    }

    @Override
    public Optional<ScopeContainer> getScopeContainer(String knowledgeBaseId) {
        try {
            JsonNode envelope = get("/knowledge-bases/" + path(knowledgeBaseId) + "/scope-container",
                    "Scope-container request failed");
            requireSuccess(envelope, "Scope-container response");
            JsonNode data = requireObject(envelope, "data", "Scope-container response");
            return Optional.of(new ScopeContainer(requiredText(data, "knowledge_base_id"),
                    requiredText(data, "container_type"), textOrNull(data, "system_id"), textOrNull(data, "system_name")));
        } catch (ScopeCatalogUnavailableException exception) {
            if (hasStatus(exception, HttpStatus.NOT_FOUND.value())) return Optional.empty();
            throw exception;
        }
    }

    @Override
    public List<SystemVersion> listSystemVersions(String knowledgeBaseId) {
        JsonNode envelope = get("/knowledge-bases/" + path(knowledgeBaseId) + "/system-versions",
                "System-version request failed");
        requireSuccess(envelope, "System-version response");
        List<SystemVersion> values = new ArrayList<>();
        requireArray(envelope, "data", "System-version response").forEach(item -> values.add(new SystemVersion(
                requiredText(item, "id"), requiredText(item, "system_id"), requiredText(item, "display_name"),
                requiredText(item, "status"), item.path("is_current").asBoolean(false))));
        return List.copyOf(values);
    }

    @Override
    public List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
        List<KnowledgeDocument> values = new ArrayList<>();
        int page = 1;
        int total;
        do {
            JsonNode envelope = get("/knowledge-bases/" + path(knowledgeBaseId) + "/knowledge?page=" + page
                    + "&page_size=" + documentPageSize, "Knowledge-document request failed");
            requireSuccess(envelope, "Knowledge-document response");
            JsonNode data = requireArray(envelope, "data", "Knowledge-document response");
            data.forEach(item -> values.add(toDocument(item)));
            total = envelope.path("total").asInt(values.size());
            if (data.isEmpty() && values.size() < total) {
                throw new ScopeCatalogUnavailableException("Knowledge-document pagination ended before total was reached");
            }
            page++;
        } while (values.size() < total);
        return List.copyOf(values);
    }

    private KnowledgeDocument toDocument(JsonNode item) {
        JsonNode scope = item.path("knowledge_scope");
        DocumentScope documentScope = scope.isObject() ? new DocumentScope(
                textOrNull(scope, "system_id"), textOrNull(scope, "version_id"), textOrNull(scope, "project_id"),
                textOrNull(scope, "content_category"), textOrNull(scope, "content_type_key"),
                textOrNull(scope, "content_type_name")) : null;
        return new KnowledgeDocument(requiredText(item, "id"), requiredText(item, "knowledge_base_id"),
                textOrNull(item, "parse_status"), textOrNull(item, "enable_status"), documentScope);
    }

    private JsonNode get(String uri, String failureMessage) {
        try {
            JsonNode response = webClient.get().uri(uri).header(API_KEY_HEADER, apiKey).retrieve()
                    .bodyToMono(JsonNode.class).block(timeout);
            if (response == null) throw new ScopeCatalogUnavailableException(failureMessage + ": empty response");
            return response;
        } catch (ScopeCatalogUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ScopeCatalogUnavailableException(failureMessage, exception);
        }
    }

    private static void requireSuccess(JsonNode envelope, String label) {
        if (!envelope.path("success").asBoolean(false)) {
            throw new ScopeCatalogUnavailableException(label + " is unsuccessful");
        }
    }

    private static JsonNode requireArray(JsonNode parent, String field, String label) {
        JsonNode value = parent.path(field);
        if (!value.isArray()) throw new ScopeCatalogUnavailableException(label + " has no " + field + " array");
        return value;
    }

    private static JsonNode requireObject(JsonNode parent, String field, String label) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) throw new ScopeCatalogUnavailableException(label + " has no " + field + " object");
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = textOrNull(node, field);
        if (value == null) throw new ScopeCatalogUnavailableException("Catalog field is missing: " + field);
        return value;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private static String path(String value) {
        return requireText(value, "path value");
    }

    private static int requirePageSize(int value, String field) {
        if (value < 1 || value > 100) throw new IllegalArgumentException(field + " must be between 1 and 100");
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static boolean hasStatus(Throwable value, int status) {
        for (Throwable current = value; current != null; current = current.getCause()) {
            if (current instanceof WebClientResponseException response && response.getStatusCode().value() == status) return true;
        }
        return false;
    }
}
