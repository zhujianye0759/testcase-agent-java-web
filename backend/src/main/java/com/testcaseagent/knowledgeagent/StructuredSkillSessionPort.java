package com.testcaseagent.knowledgeagent;

/** Creates an empty KEE session used only as the path coordinate for synchronous isolated-Skill calls. */
public interface StructuredSkillSessionPort {
    /** Opens a session without creating chat messages or preparing an ordinary Agent Skill loop. */
    String openStructuredSession();
}
