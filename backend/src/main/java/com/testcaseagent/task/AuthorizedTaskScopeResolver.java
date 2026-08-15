package com.testcaseagent.task;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves a browser option to a fixed server-side agent and knowledge scope.
 *
 * [Req-ID]: REQ-KAG-004, REQ-KAG-006, REQ-SCP-004
 */
public final class AuthorizedTaskScopeResolver {

    private final Map<String, TaskScopeOption> optionsById;
    private final List<SafeTaskScopeOption> browserOptions;

    public AuthorizedTaskScopeResolver(List<TaskScopeOption> options) {
        optionsById = List.copyOf(options).stream().collect(Collectors.toUnmodifiableMap(
                TaskScopeOption::id, Function.identity(), (left, right) -> {
                    throw new IllegalArgumentException("Duplicate task scope option: " + left.id());
                }));
        browserOptions = optionsById.values().stream()
                .map(option -> new SafeTaskScopeOption(option.id(), option.label()))
                .sorted(java.util.Comparator.comparing(SafeTaskScopeOption::id))
                .toList();
    }

    public CreateGenerationTaskRequest resolve(CreateGenerationTaskCommand command) {
        TaskScopeOption option = optionsById.get(command.scopeOptionId());
        if (option == null) {
            throw new IllegalArgumentException("Requested task scope option is not authorized");
        }
        if (command.taskMode() == com.testcaseagent.testcase.GenerationTaskMode.ALL) {
            return new CreateGenerationTaskRequest(command.taskMode(), "all-pending", List.of(), Map.of(), command.fewShotPolicy(),
                    command.schemaVersion(), command.promptVersion(), option.agentId(), option.requirementScope(), option.exampleScope(),
                    option.requirementAdmissionTypeKeys(), command.prompt().isBlank() ? "请发现全部功能。" : command.prompt());
        }
        String featureId = featureId(command.featureDescription());
        String prompt = command.prompt().isBlank() ? "" : command.prompt() + "\n";
        return new CreateGenerationTaskRequest(
                command.taskMode(), featureId, List.of(featureId), Map.of(featureId, command.featureDescription()), command.fewShotPolicy(), command.schemaVersion(),
                command.promptVersion(), option.agentId(), option.requirementScope(), option.exampleScope(),
                option.requirementAdmissionTypeKeys(), prompt + "目标功能描述：" + command.featureDescription());
    }

    public List<SafeTaskScopeOption> browserOptions() {
        return browserOptions;
    }

    private static String featureId(String description) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(description.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "feature-" + java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
