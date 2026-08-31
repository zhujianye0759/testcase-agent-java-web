package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Strict public protocol V1 result returned by KEE after its own canonicalization.
 *
 * <p>This DTO protects wire shape and scalar bounds only. Cross-record closure, evidence ownership,
 * identity hashes, and Java's final decision belong to {@code FunctionCandidateExtractionValidator}.</p>
 *
 * [Req-ID]: REQ-AFCE-002, REQ-AFCE-003
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FunctionCandidateExtractionResult(
        String operation,
        @JsonProperty("protocol_version") String protocolVersion,
        @JsonProperty("window_key") String windowKey,
        @JsonProperty("source_outcomes") List<SourceOutcome> sourceOutcomes,
        List<Candidate> candidates,
        @JsonProperty("normalization_summary") NormalizationSummary normalizationSummary) {
    private static final String SHA256_PATTERN = "[0-9a-f]{64}";

    public FunctionCandidateExtractionResult {
        if (!FunctionCandidateExtractionInput.OPERATION.equals(operation)) {
            throw new IllegalArgumentException("operation must be extract_function_candidates");
        }
        if (!FunctionCandidateExtractionInput.PROTOCOL_VERSION.equals(protocolVersion)) {
            throw new IllegalArgumentException("protocolVersion must be 1");
        }
        windowKey = sha256(windowKey, "windowKey");
        sourceOutcomes = StructuredSkillContract.list(sourceOutcomes, "sourceOutcomes", 1, 32);
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
        if (candidates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("candidates must not contain null");
        }
        if (normalizationSummary == null) {
            throw new IllegalArgumentException("normalizationSummary must not be null");
        }
    }

    /** One target-owned terminal result from KEE's canonical response. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceOutcome(
            @JsonProperty("unit_key") String unitKey,
            Disposition disposition,
            @JsonProperty("candidate_refs") List<String> candidateRefs,
            @JsonProperty("reason_code") String reasonCode) {
        public SourceOutcome {
            unitKey = StructuredSkillContract.key(unitKey, "unitKey");
            candidateRefs = List.copyOf(Objects.requireNonNull(candidateRefs, "candidateRefs must not be null"))
                    .stream().map(value -> sha256(value, "candidateRef")).toList();
            if (new HashSet<>(candidateRefs).size() != candidateRefs.size()) {
                throw new IllegalArgumentException("candidateRefs must be unique");
            }
            if (disposition == null) {
                throw new IllegalArgumentException("disposition must not be null");
            }
            reasonCode = StructuredSkillContract.key(reasonCode, "reasonCode");
        }
    }

    /** One KEE candidate suggestion; Java makes the final retain-or-downgrade decision later. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Candidate(
            @JsonProperty("candidate_ref") String candidateRef,
            String path,
            String description,
            @JsonProperty("target_quote") String targetQuote,
            @JsonProperty("evidence_keys") List<String> evidenceKeys,
            @JsonProperty("recommended_status") RecommendedStatus recommendedStatus,
            @JsonProperty("reason_code") String reasonCode,
            @JsonProperty("missing_information") List<String> missingInformation) {
        public Candidate {
            candidateRef = sha256(candidateRef, "candidateRef");
            path = StructuredSkillContract.text(path, "path");
            description = StructuredSkillContract.text(description, "description");
            targetQuote = StructuredSkillContract.text(targetQuote, "targetQuote");
            evidenceKeys = StructuredSkillContract.list(evidenceKeys, "evidenceKeys", 1, 32);
            evidenceKeys.forEach(value -> StructuredSkillContract.key(value, "evidenceKey"));
            StructuredSkillContract.uniqueKeys(evidenceKeys, "evidenceKey");
            if (recommendedStatus == null) {
                throw new IllegalArgumentException("recommendedStatus must not be null");
            }
            reasonCode = StructuredSkillContract.key(reasonCode, "reasonCode");
            missingInformation = StructuredSkillContract.texts(missingInformation, "missingInformation");
        }
    }

    /** Counts created by KEE canonicalization; their cross-record consistency is checked later. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record NormalizationSummary(
            @JsonProperty("model_candidate_count") int modelCandidateCount,
            @JsonProperty("downgraded_candidate_count") int downgradedCandidateCount,
            @JsonProperty("discarded_candidate_count") int discardedCandidateCount,
            @JsonProperty("auto_unresolved_unit_count") int autoUnresolvedUnitCount) {
        public NormalizationSummary {
            if (modelCandidateCount < 0 || downgradedCandidateCount < 0 || discardedCandidateCount < 0
                    || autoUnresolvedUnitCount < 0) {
                throw new IllegalArgumentException("normalization counts must not be negative");
            }
        }
    }

    /** Public source outcome states. */
    public enum Disposition {
        LINKED, NO_FUNCTION, UNRESOLVED;

        @JsonValue
        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        public static Disposition fromWire(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    /** KEE may suggest acceptance or confirmation; Java may only retain or downgrade it. */
    public enum RecommendedStatus {
        ACCEPTED, PENDING_CONFIRMATION;

        @JsonValue
        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        public static RecommendedStatus fromWire(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    private static String sha256(String value, String name) {
        String checked = StructuredSkillContract.key(value, name);
        if (!checked.matches(SHA256_PATTERN)) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return checked;
    }
}
