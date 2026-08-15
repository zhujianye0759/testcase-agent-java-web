package com.testcaseagent.task;

import com.testcaseagent.scope.DynamicScopeCatalogService;
import com.testcaseagent.scope.ScopeCatalogUnavailableException;
import com.testcaseagent.scope.ScopeCatalogView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Browser configuration seam that exposes no credentials or raw knowledge coordinates.
 *
 * [Req-ID]: REQ-KAG-006, REQ-CAT-002, REQ-CAT-003, REQ-WEB-009
 */
@RestController
@RequestMapping("/api/task-options")
public final class TaskConfigurationController {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskConfigurationController.class);

    private final DynamicScopeCatalogService catalogService;

    public TaskConfigurationController(DynamicScopeCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public TaskOptionsResponse options(@RequestParam(defaultValue = "false") boolean refresh) {
        try {
            return new TaskOptionsResponse(catalogService.catalog(refresh).view());
        } catch (ScopeCatalogUnavailableException exception) {
            LOGGER.warn("Unable to refresh the KEE scope catalog: {}", exception.getMessage(), exception);
            throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "知识范围暂时无法读取，请稍后刷新重试");
        }
    }

    public record TaskOptionsResponse(ScopeCatalogView scopeCatalog) {
    }
}
