package com.testcaseagent.task;

import com.testcaseagent.export.ApachePoiWorkbookExporter;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.scope.RequirementMaterialReaderPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/** [Test-Ref]: GenerationTaskConfigurationTest [Req-ID]: REQ-EXP-001 */
class GenerationTaskConfigurationTest {

    @TempDir
    Path artifactRoot;

    @Test
    void providesApachePoiExporterForTheConfiguredArtifactRoot() {
        WorkbookExporter exporter = new GenerationTaskConfiguration().workbookExporter(artifactRoot.toString());

        assertThat(exporter).isInstanceOf(ApachePoiWorkbookExporter.class);
    }

    @Test
    void exposesTheAdapterParsedMaterialPortWithoutPreventingAnIndependentTestPort() {
        KnowledgeAgentPort dualPort = mock(KnowledgeAgentPort.class,
                withSettings().extraInterfaces(RequirementMaterialReaderPort.class));

        RequirementMaterialReaderPort reader = new GenerationTaskConfiguration().requirementMaterialReaderPort(dualPort);

        assertThat(reader).isSameAs(dualPort);
        assertThatThrownBy(() -> new GenerationTaskConfiguration().requirementMaterialReaderPort(mock(KnowledgeAgentPort.class)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("parsed material");
    }

}
