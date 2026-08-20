package com.testcaseagent.knowledgeagent;

/** Fail-closed structured execution failure that never retains model or response text. [Req-ID]: REQ-SKI-005 */
public final class StructuredSkillExecutionException extends KnowledgeAgentInvocationException {
    private final StructuredSkillErrorType type;
    private final boolean repairAttempted;
    public StructuredSkillExecutionException(StructuredSkillErrorType type, boolean repairAttempted) {
        super("Structured Skill execution failed: " + type.wireValue()); this.type = type; this.repairAttempted = repairAttempted;
    }
    /** @return the stable, response-safe failure classification */
    public StructuredSkillErrorType type() { return type; }
    /** @return whether KEE reported use of its one allowed formatting repair */
    public boolean repairAttempted() { return repairAttempted; }
}
