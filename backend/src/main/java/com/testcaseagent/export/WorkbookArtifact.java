package com.testcaseagent.export;

import java.nio.file.Path;

/** Application-owned artifact metadata; Phase 1 creates no automatic deletion path. [Req-ID]: REQ-EXP-005, REQ-EXP-006 */
public record WorkbookArtifact(String artifactId, String sha256, Path path) {
}
