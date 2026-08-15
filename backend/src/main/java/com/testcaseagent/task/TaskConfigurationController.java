package com.testcaseagent.task;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser configuration seam that exposes no credentials or raw knowledge coordinates.
 *
 * [Req-ID]: REQ-KAG-006, REQ-SCP-004
 */
@RestController
@RequestMapping("/api/task-options")
public final class TaskConfigurationController {

    private final AuthorizedTaskScopeResolver resolver;

    public TaskConfigurationController(AuthorizedTaskScopeResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping
    public TaskOptionsResponse options() {
        return new TaskOptionsResponse(resolver.browserOptions());
    }

    public record TaskOptionsResponse(List<SafeTaskScopeOption> scopeOptions) {
    }
}
