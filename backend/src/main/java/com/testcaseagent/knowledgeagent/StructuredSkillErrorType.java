package com.testcaseagent.knowledgeagent;

/** Stable failure classes supplied by KEE's isolated structured endpoint. [Req-ID]: REQ-SKI-005 */
public enum StructuredSkillErrorType {
    INVALID_REQUEST("invalid_request"), REQUEST_TOO_LARGE("request_too_large"),
    SESSION_NOT_FOUND("session_not_found"), FORBIDDEN("forbidden"),
    UNSUPPORTED_CONTRACT_VERSION("unsupported_contract_version"), UNSUPPORTED_SKILL("unsupported_skill"),
    SKILL_UNAVAILABLE("skill_unavailable"), MODEL_UNAVAILABLE("model_unavailable"),
    MODEL_EXECUTION_FAILED("model_execution_failed"), STRUCTURED_OUTPUT_INVALID("structured_output_invalid"),
    RESPONSE_TOO_LARGE("response_too_large");
    private final String wireValue;
    StructuredSkillErrorType(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }
    static StructuredSkillErrorType fromWire(String value) { for (StructuredSkillErrorType type : values()) if (type.wireValue.equals(value)) return type; throw new IllegalArgumentException("unknown structured error type"); }
}
