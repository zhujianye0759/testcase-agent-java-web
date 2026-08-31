package com.testcaseagent.knowledgeagent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-only external agent transport settings. This type is never serialized to the browser.
 *
 * [Req-ID]: REQ-KAG-006
 */
@ConfigurationProperties(prefix = "app.knowledge-agent")
public class KnowledgeAgentProperties {

    /** V2 carries one complete comparison catalog and therefore has its own verified transport budget. */
    public static final int DEFAULT_RECONCILIATION_V2_REQUEST_MAX_BYTES = 16 * 1024 * 1024;
    public static final int DEFAULT_RECONCILIATION_V2_RESPONSE_MAX_BYTES = 4 * 1024 * 1024;
    public static final int DEFAULT_STRUCTURED_CONTRACT_V2_REQUEST_MAX_BYTES = 16 * 1024 * 1024;
    public static final int MIN_STRUCTURED_CONTRACT_V2_REQUEST_MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_STRUCTURED_CONTRACT_V2_REQUEST_MAX_BYTES = 64 * 1024 * 1024;
    public static final int DEFAULT_STRUCTURED_CONTRACT_V2_RESPONSE_MAX_BYTES = 4 * 1024 * 1024;

    private String apiBaseUrl;
    private String apiKey;
    private Duration timeout = Duration.ofSeconds(3060);
    private int maxAgentDiscoveryAttempts = 2;
    private int maxEventCharacters = 1_000_000;
    private int featureReconciliationV2RequestMaxBytes = DEFAULT_RECONCILIATION_V2_REQUEST_MAX_BYTES;
    private int featureReconciliationV2ResponseMaxBytes = DEFAULT_RECONCILIATION_V2_RESPONSE_MAX_BYTES;
    private int structuredContractV2RequestMaxBytes = DEFAULT_STRUCTURED_CONTRACT_V2_REQUEST_MAX_BYTES;

    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxAgentDiscoveryAttempts() { return maxAgentDiscoveryAttempts; }
    public void setMaxAgentDiscoveryAttempts(int maxAgentDiscoveryAttempts) { this.maxAgentDiscoveryAttempts = maxAgentDiscoveryAttempts; }
    public int getMaxEventCharacters() { return maxEventCharacters; }
    public void setMaxEventCharacters(int maxEventCharacters) { this.maxEventCharacters = maxEventCharacters; }
    public int getFeatureReconciliationV2RequestMaxBytes() { return featureReconciliationV2RequestMaxBytes; }
    public void setFeatureReconciliationV2RequestMaxBytes(int value) {
        if (value < 1) throw new IllegalArgumentException("featureReconciliationV2RequestMaxBytes must be positive");
        this.featureReconciliationV2RequestMaxBytes = value;
    }
    public int getFeatureReconciliationV2ResponseMaxBytes() { return featureReconciliationV2ResponseMaxBytes; }
    public void setFeatureReconciliationV2ResponseMaxBytes(int value) {
        if (value < 1) throw new IllegalArgumentException("featureReconciliationV2ResponseMaxBytes must be positive");
        this.featureReconciliationV2ResponseMaxBytes = value;
    }
    public int getStructuredContractV2RequestMaxBytes() { return structuredContractV2RequestMaxBytes; }
    public void setStructuredContractV2RequestMaxBytes(int value) {
        if (value < MIN_STRUCTURED_CONTRACT_V2_REQUEST_MAX_BYTES
                || value > MAX_STRUCTURED_CONTRACT_V2_REQUEST_MAX_BYTES) {
            this.structuredContractV2RequestMaxBytes = DEFAULT_STRUCTURED_CONTRACT_V2_REQUEST_MAX_BYTES;
            return;
        }
        this.structuredContractV2RequestMaxBytes = value;
    }
}
