package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * [Test-Ref]: SensitiveValueRedactorTest
 * [Req-ID]: REQ-KAG-006
 */
class SensitiveValueRedactorTest {

    @Test
    void removesCredentialsUrlsAndFilesystemLocationsFromExternalFailures() {
        String syntheticKey = "sk-" + "example-secret-value";
        String message = SensitiveValueRedactor.redact(
                "apiKey=" + syntheticKey + " failed at http://internal.service/api and C:\\workspace\\secret.txt");

        assertThat(message).doesNotContain(syntheticKey, "http://internal.service", "C:\\workspace\\secret.txt");
        assertThat(message).contains("<redacted>", "<external-url>", "<internal-path>");
    }

    @Test
    void preservesReaderFacingChineseSlashSeparatedChoices() {
        assertThat(SensitiveValueRedactor.redact("账号被禁用/锁定/未激活时提示用户"))
                .isEqualTo("账号被禁用/锁定/未激活时提示用户");
    }

    @Test
    void stillRemovesTrustedUnixAbsolutePaths() {
        assertThat(SensitiveValueRedactor.redact("failed at /var/lib/testcase-agent/secret.log"))
                .isEqualTo("failed at <internal-path>");
    }
}
