package com.testcaseagent.web;

import com.testcaseagent.task.GenerationTaskConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Starts the test-case generation orchestration backend.
 *
 * [Spec-Ref]: generation-task-lifecycle
 * [Req-ID]: REQ-TSK-001
 */
@SpringBootApplication
@Import(GenerationTaskConfiguration.class)
public class TestCaseAgentApplication {

    /**
     * Starts the Spring application context.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(TestCaseAgentApplication.class, args);
    }
}
