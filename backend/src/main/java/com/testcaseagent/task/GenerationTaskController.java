package com.testcaseagent.task;

import com.testcaseagent.scope.ScopeCatalogUnavailableException;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Shared HTTP seam for the initial durable generation task.
 *
 * [Req-ID]: REQ-TSK-001, REQ-TSK-002, REQ-TSK-004, REQ-TSK-005, REQ-TSK-007, REQ-WEB-002,
 * REQ-WEB-004, REQ-CAT-004
 */
@RestController
@RequestMapping("/api/tasks")
public final class GenerationTaskController {

    private final GenerationWorkflow workflow;
    private final DynamicTaskScopeResolver taskScopeResolver;
    public GenerationTaskController(GenerationWorkflow workflow, DynamicTaskScopeResolver taskScopeResolver) {
        this.workflow = workflow;
        this.taskScopeResolver = taskScopeResolver;
    }

    @PostMapping
    public ResponseEntity<TaskCreatedResponse> create(@RequestBody CreateGenerationTaskCommand command) {
        try {
            CreateGenerationTaskRequest request = taskScopeResolver.resolve(command);
            String taskId = workflow.create(request);
            return ResponseEntity.created(URI.create("/api/tasks/" + taskId)).body(new TaskCreatedResponse(taskId));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (ScopeCatalogUnavailableException exception) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "知识范围暂时无法读取，请稍后刷新重试");
        }
    }

    @GetMapping("/{taskId}")
    public GenerationTaskDetailResponse detail(@PathVariable String taskId) {
        try {
            return GenerationTaskDetailResponse.from(workflow.detail(taskId));
        } catch (GenerationTaskNotFoundException exception) {
            throw new ResponseStatusException(NOT_FOUND, "Task not found");
        }
    }

    @GetMapping
    public GenerationTaskPage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query) {
        try {
            return workflow.list(page, size, query);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String taskId) {
        try {
            workflow.cancel(taskId);
            return ResponseEntity.noContent().build();
        } catch (GenerationTaskNotFoundException exception) {
            throw new ResponseStatusException(NOT_FOUND, "Task not found");
        }
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<Void> retry(@PathVariable String taskId) {
        try {
            workflow.retryFailedBatches(taskId);
            return ResponseEntity.noContent().build();
        } catch (GenerationTaskNotFoundException exception) {
            throw new ResponseStatusException(NOT_FOUND, "Task not found");
        }
    }

    public record TaskCreatedResponse(String id) {
    }
}
