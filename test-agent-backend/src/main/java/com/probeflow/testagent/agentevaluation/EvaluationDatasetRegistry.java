package com.probeflow.testagent.agentevaluation;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class EvaluationDatasetRegistry {

    public static final String SMOKE_DATASET = "v2-phase-8-smoke";

    public EvaluationDataset load(String datasetName) {
        var effectiveName = datasetName == null || datasetName.isBlank() ? SMOKE_DATASET : datasetName.trim();
        if (!SMOKE_DATASET.equals(effectiveName)) {
            throw new IllegalArgumentException("Unknown evaluation dataset: " + effectiveName);
        }
        return smokeDataset();
    }

    private EvaluationDataset smokeDataset() {
        return new EvaluationDataset(
            SMOKE_DATASET,
            "2026-07-07",
            List.of(new GoldenTaskFixture(
                "smoke-foundation",
                List.of("evaluation-foundation"),
                "Run the deterministic smoke fixture through the evaluation harness.",
                EvaluationFixtureType.SMOKE,
                Map.of("providerMode", EvaluationProviderMode.DETERMINISTIC_FAKE.name(), "status", "PASSED"),
                Map.of("isolated", true, "usesRealLlm", false, "usesRealEmbedding", false, "usesExternalHttp", false)
            )),
            0.8d,
            Map.of("evaluation-foundation", 0.8d),
            Map.of("evaluation-foundation", 1.0d),
            Map.of("smoke-foundation", 0.8d)
        );
    }
}
