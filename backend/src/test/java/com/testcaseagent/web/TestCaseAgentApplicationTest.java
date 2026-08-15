package com.testcaseagent.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies that the public Spring Boot application entry point starts.
 *
 * [Test-Ref]: TestCaseAgentApplicationTest#contextLoads
 * [Req-ID]: REQ-TSK-001
 */
@SpringBootTest(properties = {
        "app.knowledge-agent.enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
class TestCaseAgentApplicationTest {

    @Test
    void contextLoads() {
        // The assertion is successful Spring context creation at the application seam.
    }
}
