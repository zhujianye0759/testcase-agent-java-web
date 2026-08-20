package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/** Typed result of one function test-point testcase-design call. [Req-ID]: REQ-SKI-004 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FunctionalTestcaseDesignResult(@JsonProperty("function_key") String functionKey,
        @JsonProperty("test_point_key") String testPointKey, List<Testcase> testcases) {
    public FunctionalTestcaseDesignResult { functionKey=StructuredSkillContract.key(functionKey,"functionKey"); testPointKey=StructuredSkillContract.key(testPointKey,"testPointKey"); testcases=StructuredSkillContract.list(testcases,"testcases",1,50); StructuredSkillContract.uniqueKeys(testcases.stream().map(Testcase::caseKey).toList(),"testcase"); }
    public enum CaseStatus { FORMAL, PENDING_CONFIRMATION; @JsonValue public String wireValue(){return name().toLowerCase(java.util.Locale.ROOT);} @JsonCreator public static CaseStatus fromWire(String value){return valueOf(value.toUpperCase(java.util.Locale.ROOT));} }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Testcase(@JsonProperty("case_key") String caseKey, String title, List<String> preconditions, List<Step> steps,
            @JsonProperty("requirement_fact_keys") List<String> requirementFactKeys, @JsonProperty("evidence_keys") List<String> evidenceKeys,
            @JsonProperty("case_status") CaseStatus caseStatus, @JsonProperty("missing_information") List<String> missingInformation) {
        public Testcase { caseKey=StructuredSkillContract.key(caseKey,"caseKey"); title=StructuredSkillContract.text(title,"title"); preconditions=StructuredSkillContract.texts(preconditions,"preconditions"); steps=StructuredSkillContract.list(steps,"steps",1,50); for(int index=0;index<steps.size();index++)if(steps.get(index).stepNo()!=index+1)throw new IllegalArgumentException("step numbers must be continuous"); requirementFactKeys=StructuredSkillContract.keyReferences(requirementFactKeys,"requirementFactKeys"); evidenceKeys=StructuredSkillContract.keyReferences(evidenceKeys,"evidenceKeys"); if(caseStatus==null)throw new IllegalArgumentException("caseStatus must not be null"); missingInformation=StructuredSkillContract.texts(missingInformation,"missingInformation"); }
    }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Step(@JsonProperty("step_no") int stepNo, String action, String expected) { public Step { if(stepNo<1)throw new IllegalArgumentException("stepNo must be at least one"); action=StructuredSkillContract.text(action,"action"); expected=StructuredSkillContract.text(expected,"expected"); } }
}
