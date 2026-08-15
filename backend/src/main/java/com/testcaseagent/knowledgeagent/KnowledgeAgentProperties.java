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

    private String apiBaseUrl;
    private String apiKey;
    private Duration timeout = Duration.ofMinutes(5);
    private int maxAgentDiscoveryAttempts = 2;
    private int maxEventCharacters = 1_000_000;

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
}
