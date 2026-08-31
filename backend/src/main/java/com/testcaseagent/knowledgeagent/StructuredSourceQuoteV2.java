package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One V2 continuous source quotation bound to a parsed-unit identity. [Req-ID]: REQ-TGV2-004 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record StructuredSourceQuoteV2(
        @JsonProperty("evidence_key") String evidenceKey,
        String quote) {
    public StructuredSourceQuoteV2 {
        evidenceKey = StructuredSkillContract.key(evidenceKey, "evidenceKey");
        quote = StructuredSkillContract.text(quote, "quote");
    }
}
