package com.probeflow.testagent.businessflowdiscovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentHarness;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunRequest;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentSectionSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BusinessFlowDiscoveryIssue07HarnessReportTests {

    @TempDir
    private Path outputDir;

    @Test
    void orderSuiteDemoHarnessReportIncludesRealBusinessFlowDiscoverySection() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));

        var discovery = result.sections().stream()
            .filter(section -> section.sectionId().equals("business-flow-discovery"))
            .findFirst()
            .orElseThrow();
        assertThat(discovery.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(discovery.summary().get("status")).isEqualTo("COMPLETED");
        assertThat(discovery.summary().get("usesRealLlm")).isEqualTo(false);
        assertThat(discovery.summary().get("usesExternalHttp")).isEqualTo(false);
        assertThat(discovery.summary().toString())
            .contains("Create order -> Pay order -> Query order")
            .contains("confidence")
            .contains("requiresHumanReview=false")
            .contains("API_SPEC_STRUCTURE")
            .contains("KNOWLEDGE")
            .contains("MEMORY");

        var generatedSuiteDraft = result.sections().stream()
            .filter(section -> section.sectionId().equals("generated-suite-draft"))
            .findFirst()
            .orElseThrow();
        assertThat(generatedSuiteDraft.source()).isEqualTo(ManualSuiteAgentSectionSource.FIXTURE);
        assertThat(generatedSuiteDraft.summary().get("phaseNote").toString())
            .contains("V3-3 DependencyLinker")
            .doesNotContain("real V3-3");

        var json = new ObjectMapper().readTree(Files.readString(artifactPath(result, "JSON_REPORT")));
        var sections = json.get("sections").toString();
        assertThat(sections)
            .contains("business-flow-discovery")
            .contains("Create order -> Pay order -> Query order")
            .contains("apiSpecEvidenceCount")
            .contains("knowledgeEvidenceCount")
            .contains("memoryEvidenceCount");

        var markdown = Files.readString(artifactPath(result, "MARKDOWN_REPORT"));
        assertThat(markdown).contains("business-flow-discovery");
    }

    private Path artifactPath(com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunResult result, String type) {
        return result.artifacts().stream()
            .filter(artifact -> artifact.artifactType().equals(type))
            .findFirst()
            .map(artifact -> Path.of(artifact.path()))
            .orElseThrow();
    }
}
