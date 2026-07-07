package com.probeflow.testagent.agentevaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentEvaluationApplicationServiceSmokeTests {

    private final AgentEvaluationApplicationService service = new AgentEvaluationApplicationService(
        new EvaluationDatasetRegistry(),
        List.of(new SmokeEvaluationEvaluator())
    );

    @Test
    void runsSmokeDatasetWithDeterministicFakeProviderAndStructuredReport() {
        var result = service.runDefaultDataset();

        assertThat(result.run().datasetName()).isEqualTo(EvaluationDatasetRegistry.SMOKE_DATASET);
        assertThat(result.run().datasetVersion()).isEqualTo("2026-07-07");
        assertThat(result.run().profile()).isEqualTo("ci-deterministic");
        assertThat(result.run().providerMode()).isEqualTo(EvaluationProviderMode.DETERMINISTIC_FAKE);
        assertThat(result.run().status()).isEqualTo(EvaluationRunStatus.PASSED);
        assertThat(result.run().overallScore()).isEqualTo(1.0d);

        assertThat(result.report().runSummary())
            .containsEntry("datasetName", EvaluationDatasetRegistry.SMOKE_DATASET)
            .containsEntry("providerMode", EvaluationProviderMode.DETERMINISTIC_FAKE.name())
            .containsEntry("status", EvaluationRunStatus.PASSED.name());
        assertThat(result.report().caseSummary()).hasSize(1);
        assertThat(result.report().caseSummary().getFirst())
            .containsEntry("fixtureId", "smoke-foundation")
            .containsEntry("status", EvaluationCaseStatus.PASSED.name());
        assertThat(result.report().metricSummary())
            .containsKey("evaluation-foundation");
        assertThat(result.report().recommendedFixes()).isEmpty();
        assertThat(result.report().humanReadableSummary())
            .contains(EvaluationDatasetRegistry.SMOKE_DATASET)
            .contains("PASSED");
    }

    @Test
    void repeatedSmokeRunsUseIsolatedRunIdsAndDoNotDependOnPersistedFixtureState() {
        var first = service.runDefaultDataset();
        var second = service.runDefaultDataset();

        assertThat(first.run().status()).isEqualTo(EvaluationRunStatus.PASSED);
        assertThat(second.run().status()).isEqualTo(EvaluationRunStatus.PASSED);
        assertThat(first.run().runId()).isNotEqualTo(second.run().runId());
        assertThat(first.report().caseSummary().getFirst()).containsEntry("fixtureId", "smoke-foundation");
        assertThat(second.report().caseSummary().getFirst()).containsEntry("fixtureId", "smoke-foundation");
    }
}
