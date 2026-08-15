package com.testcaseagent.task;

import com.testcaseagent.export.ApachePoiWorkbookExporter;
import com.testcaseagent.export.WorkbookExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** [Test-Ref]: GenerationTaskConfigurationTest [Req-ID]: REQ-EXP-001 */
class GenerationTaskConfigurationTest {

    @TempDir
    Path artifactRoot;

    @Test
    void providesApachePoiExporterForTheConfiguredArtifactRoot() {
        WorkbookExporter exporter = new GenerationTaskConfiguration().workbookExporter(artifactRoot.toString());

        assertThat(exporter).isInstanceOf(ApachePoiWorkbookExporter.class);
    }
}
