package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Material categories accepted by the material-quality-review Skill. [Req-ID]: REQ-SKI-003 */
public enum MaterialContentTypeKey {
    REQUIREMENTS_SPEC, WORK_ORDER_PLAN, PROTOTYPE, REQUIREMENT_LIST;
    @JsonValue public String wireValue() { return name().toLowerCase(java.util.Locale.ROOT); }
    @JsonCreator public static MaterialContentTypeKey fromWire(String value) { return valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
}
