package com.testcaseagent.task;

import java.util.regex.Pattern;

/**
 * Removes common credentials and internal locations before a failure crosses the task boundary.
 *
 * [Req-ID]: REQ-KAG-006
 */
public final class SensitiveValueRedactor {

    private static final Pattern API_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)(api[_-]?key|token|secret|password)\\s*([=:])\\s*[^\\s,;]+" );
    private static final Pattern URL = Pattern.compile("https?://[^\\s,;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\s,;]+" );
    private static final Pattern UNIX_PATH = Pattern.compile(
            "(?<![A-Za-z0-9])/(?:etc|var|opt|usr|home|root|tmp|srv|proc|sys|dev|mnt|data|workspace)(?:/[^\\s,;]+)+" );

    private SensitiveValueRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isBlank()) {
            return "External operation failed";
        }
        String redacted = API_KEY.matcher(value).replaceAll("<redacted>");
        redacted = NAMED_SECRET.matcher(redacted).replaceAll("$1$2<redacted>");
        redacted = URL.matcher(redacted).replaceAll("<external-url>");
        redacted = WINDOWS_PATH.matcher(redacted).replaceAll("<internal-path>");
        return UNIX_PATH.matcher(redacted).replaceAll("<internal-path>");
    }
}
