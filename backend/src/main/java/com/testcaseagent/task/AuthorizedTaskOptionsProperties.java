package com.testcaseagent.task;

import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.fewshot.ExampleQualityKind;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-only configuration for browser-selectable task scopes.
 *
 * [Req-ID]: REQ-KAG-006, REQ-SCP-004
 */
@ConfigurationProperties(prefix = "app.authorized-task-options")
public class AuthorizedTaskOptionsProperties {

    private List<Option> options = List.of();

    public List<Option> getOptions() {
        return options;
    }

    public void setOptions(List<Option> options) {
        this.options = options == null ? List.of() : List.copyOf(options);
    }

    public List<TaskScopeOption> toTaskScopeOptions() {
        return options.stream().map(Option::toTaskScopeOption).toList();
    }

    public static class Option {
        private String id;
        private String label;
        private String agentId;
        private String requirementKnowledgeBaseId;
        private String systemId;
        private String versionId;
        private String projectId;
        private List<String> requirementDocumentIds = List.of();
        private String exampleKnowledgeBaseId;
        private List<String> exampleGoodDocumentIds = List.of();
        private List<String> exampleBadDocumentIds = List.of();
        private List<String> requirementAdmissionTypeKeys = List.of();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
        public String getRequirementKnowledgeBaseId() { return requirementKnowledgeBaseId; }
        public void setRequirementKnowledgeBaseId(String requirementKnowledgeBaseId) { this.requirementKnowledgeBaseId = requirementKnowledgeBaseId; }
        public String getSystemId() { return systemId; }
        public void setSystemId(String systemId) { this.systemId = systemId; }
        public String getVersionId() { return versionId; }
        public void setVersionId(String versionId) { this.versionId = versionId; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public List<String> getRequirementDocumentIds() { return requirementDocumentIds; }
        public void setRequirementDocumentIds(List<String> requirementDocumentIds) { this.requirementDocumentIds = requirementDocumentIds == null ? List.of() : List.copyOf(requirementDocumentIds); }
        public String getExampleKnowledgeBaseId() { return exampleKnowledgeBaseId; }
        public void setExampleKnowledgeBaseId(String exampleKnowledgeBaseId) { this.exampleKnowledgeBaseId = exampleKnowledgeBaseId; }
        public List<String> getExampleGoodDocumentIds() { return exampleGoodDocumentIds; }
        public void setExampleGoodDocumentIds(List<String> exampleGoodDocumentIds) {
            this.exampleGoodDocumentIds = exampleGoodDocumentIds == null ? List.of() : List.copyOf(exampleGoodDocumentIds);
        }
        public List<String> getExampleBadDocumentIds() { return exampleBadDocumentIds; }
        public void setExampleBadDocumentIds(List<String> exampleBadDocumentIds) {
            this.exampleBadDocumentIds = exampleBadDocumentIds == null ? List.of() : List.copyOf(exampleBadDocumentIds);
        }
        public List<String> getRequirementAdmissionTypeKeys() { return requirementAdmissionTypeKeys; }
        public void setRequirementAdmissionTypeKeys(List<String> requirementAdmissionTypeKeys) {
            this.requirementAdmissionTypeKeys = requirementAdmissionTypeKeys == null ? List.of() : List.copyOf(requirementAdmissionTypeKeys);
        }

        private TaskScopeOption toTaskScopeOption() {
            return new TaskScopeOption(id, label, agentId,
                    new RequirementScope(requirementKnowledgeBaseId, systemId, versionId, "admission_material", projectId,
                            requirementDocumentIds.stream().map(RequirementDocumentCoordinate::new).toList()),
                    ExampleScope.freeze(exampleKnowledgeBaseId, expectedExampleQualityKinds()), requirementAdmissionTypeKeys);
        }

        private Map<String, ExampleQualityKind> expectedExampleQualityKinds() {
            Map<String, ExampleQualityKind> expectedKinds = new LinkedHashMap<>();
            exampleGoodDocumentIds.forEach(id -> putExpectedKind(expectedKinds, id, ExampleQualityKind.GOOD_CASE));
            exampleBadDocumentIds.forEach(id -> putExpectedKind(expectedKinds, id, ExampleQualityKind.BAD_CASE));
            return Map.copyOf(expectedKinds);
        }

        private static void putExpectedKind(Map<String, ExampleQualityKind> expectedKinds, String id, ExampleQualityKind kind) {
            if (expectedKinds.put(id, kind) != null) {
                throw new IllegalArgumentException("Example document must have exactly one configured quality kind: " + id);
            }
        }
    }
}
