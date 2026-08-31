package com.testcaseagent.knowledgeagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Configuration contract for the long-running structured execution window. */
class KnowledgeAgentPropertiesTest {

    /** [Req-ID]: REQ-SEW-001 */
    @Test
    void defaultsToThreeThousandSecondsPlusResponseProcessingMargin() {
        assertThat(new KnowledgeAgentProperties().getTimeout()).isEqualTo(Duration.ofSeconds(3060));
    }

    /** [Req-ID]: REQ-SEW-001 */
    @Test
    void retainsTheExistingPositiveDurationOverride() {
        KnowledgeAgentProperties properties = new KnowledgeAgentProperties();

        properties.setTimeout(Duration.ofSeconds(3120));

        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(3120));
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void givesOnlyReconciliationV2ItsDedicatedSixteenAndFourMiBBudgets() {
        KnowledgeAgentProperties properties = new KnowledgeAgentProperties();

        assertThat(properties.getFeatureReconciliationV2RequestMaxBytes()).isEqualTo(16 * 1024 * 1024);
        assertThat(properties.getFeatureReconciliationV2ResponseMaxBytes()).isEqualTo(4 * 1024 * 1024);
    }
}
