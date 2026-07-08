package com.probeflow.testagent.demorun;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoRunApplicationServiceIssue01Tests {

    @TempDir
    private Path outputDir;

    @Test
    void fakeBaselineReturnsCompletedDemoResultWithStableV4Contract() {
        var service = new DemoRunApplicationService();

        var result = service.run(DemoRunRequest.fakeBaseline("order-suite-demo", outputDir));

        assertThat(result.schemaVersion()).isEqualTo("v4-demo-run-result.v1");
        assertThat(result.runId()).isNotBlank();
        assertThat(result.fixtureId()).isEqualTo("order-suite-demo");
        assertThat(result.fixtureVersion()).isEqualTo("2026.07.order.v1");
        assertThat(result.providerMode()).isEqualTo(DemoRunProviderMode.FAKE);
        assertThat(result.status()).isEqualTo(DemoRunStatus.COMPLETED);
        assertThat(result.usesRealLlm()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(result.provider().fakeBaseline()).isTrue();
        assertThat(result.provider().externalDependencyPolicy())
            .contains(
                "default demo run does not require a real LLM key",
                "default demo run does not require real embedding",
                "default demo run uses fake HTTP gateway only"
            );
        assertThat(result.artifacts())
            .extracting(DemoRunArtifactReference::artifactType)
            .containsExactly("JSON_REPORT", "MARKDOWN_REPORT");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void fakeBaselineExposesCoreDemoSectionsWithoutLeakingHarnessDomainObjects() {
        var service = new DemoRunApplicationService();

        var result = service.run(DemoRunRequest.fakeBaseline("order-suite-demo", outputDir));

        assertReady(result.plan(), "plan");
        assertReady(result.context(), "context");
        assertReady(result.tools(), "tools");
        assertReady(result.suite(), "suite");
        assertReady(result.execution(), "execution");
        assertReady(result.variableAudit(), "variable-audit");
        assertReady(result.failureAnalysis(), "failure-analysis");
        assertReady(result.memoryFeedback(), "memory-feedback");
        assertReady(result.evaluation(), "evaluation");

        assertThat(result.suite().summary())
            .containsEntry("sourceSectionId", "generated-suite-draft")
            .containsKey("summary");
        assertThat(result.execution().summary())
            .containsEntry("sourceSectionId", "execution-result");
        assertThat(result.variableAudit().summary())
            .containsEntry("sourceSectionId", "variable-audit");
        assertThat(result.failureAnalysis().summary())
            .containsEntry("sourceSectionId", "failure-analysis");
        assertThat(result.memoryFeedback().summary())
            .containsEntry("sourceSectionId", "memory-feedback");
        assertThat(result.evaluation().summary())
            .containsEntry("sourceSectionId", "evaluation-comparison");

        assertThat(result.plan().summary()).containsEntry("planner", "Manual Suite Agent Harness");
        assertThat(result.context().summary()).containsEntry("usesRealEmbedding", false);
        assertThat(result.tools().summary())
            .containsEntry("reusedExistingHarness", true)
            .containsEntry("usesExternalHttp", false);
        assertToolCallsUseExistingHarness(result.tools().summary());
    }

    @SuppressWarnings("unchecked")
    private void assertToolCallsUseExistingHarness(Map<String, Object> tools) {
        var toolCalls = (List<Map<String, Object>>) tools.get("toolCalls");
        assertThat(toolCalls)
            .extracting(call -> call.get("sourceSectionId"))
            .containsExactly(
                "business-flow-discovery",
                "generated-suite-draft",
                "execution-result",
                "variable-audit",
                "failure-analysis",
                "memory-feedback",
                "evaluation-comparison"
            );
    }

    private void assertReady(DemoRunSectionView section, String sectionId) {
        assertThat(section).isNotNull();
        assertThat(section.sectionId()).isEqualTo(sectionId);
        assertThat(section.status()).isNotEqualTo("NOT_RUN");
        assertThat(section.summary()).isNotEmpty();
    }
}
