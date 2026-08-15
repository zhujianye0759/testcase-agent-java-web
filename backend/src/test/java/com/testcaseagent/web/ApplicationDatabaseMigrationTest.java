package com.testcaseagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the application-owned MySQL schema boundary.
 *
 * [Test-Ref]: ApplicationDatabaseMigrationTest
 * [Req-ID]: REQ-TSK-006, REQ-EXP-005
 */
@Testcontainers
@SpringBootTest
class ApplicationDatabaseMigrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testcase_agent")
            .withUsername("testcase_agent")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("app.artifacts.root", () -> "./var/test-artifacts");
    }

    @Test
    void appliesTheBaselineToAnIndependentMySql8Database(
            @Autowired DataSource dataSource,
            @Autowired JdbcTemplate jdbcTemplate,
            @Autowired Environment environment) throws SQLException {
        var datasourceUrl = environment.getRequiredProperty("spring.datasource.url");

        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            assertThat(metadata.getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(metadata.getDatabaseMajorVersion()).isEqualTo(8);
        }
        assertThat(datasourceUrl).startsWith("jdbc:mysql:");
        assertThat(datasourceUrl).doesNotContainIgnoringCase("knowledge");
        assertThat(jdbcTemplate.queryForObject(
                "select schema_owner from application_schema_metadata where id = 1",
                String.class)).isEqualTo("testcase-agent-java-web");
        assertThat(environment.getRequiredProperty("app.artifacts.root"))
                .isEqualTo("./var/test-artifacts");
    }
}
