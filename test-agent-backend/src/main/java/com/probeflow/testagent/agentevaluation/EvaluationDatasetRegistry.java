package com.probeflow.testagent.agentevaluation;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class EvaluationDatasetRegistry {

    public static final String SMOKE_DATASET = "v2-phase-8-smoke";
    public static final String PLANNER_DATASET = "v2-phase-8-planner";

    public EvaluationDataset load(String datasetName) {
        var effectiveName = datasetName == null || datasetName.isBlank() ? SMOKE_DATASET : datasetName.trim();
        return switch (effectiveName) {
            case SMOKE_DATASET -> smokeDataset();
            case PLANNER_DATASET -> plannerDataset();
            default -> throw new IllegalArgumentException("Unknown evaluation dataset: " + effectiveName);
        };
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

    private EvaluationDataset plannerDataset() {
        return new EvaluationDataset(
            PLANNER_DATASET,
            "2026-07-07",
            List.of(
                plannerFixture("planner-continue", "CONTINUE", Map.of(
                    "plannerStatus", "PROPOSED",
                    "plannerAction", "CONTINUE",
                    "confidenceMin", 0.9d,
                    "confidenceMax", 1.0d,
                    "riskLevel", "LOW"
                )),
                plannerFixture("planner-insert-step", "INSERT_STEP", Map.of(
                    "plannerStatus", "PROPOSED",
                    "plannerAction", "INSERT_STEP",
                    "proposedStepType", "GENERATE_CASES",
                    "proposedToolName", "testcase.generate-drafts",
                    "confidenceMin", 0.8d,
                    "confidenceMax", 0.9d,
                    "riskLevel", "MEDIUM"
                )),
                plannerFixture("planner-replan", "REPLAN", Map.of(
                    "plannerStatus", "PROPOSED",
                    "plannerAction", "REPLAN",
                    "confidenceMin", 0.6d,
                    "confidenceMax", 0.7d,
                    "riskLevel", "MEDIUM",
                    "blockers", List.of("State changed after last step")
                )),
                plannerFixture("planner-wait-for-human", "WAIT_FOR_HUMAN", Map.of(
                    "plannerStatus", "PROPOSED",
                    "plannerAction", "WAIT_FOR_HUMAN",
                    "confidenceMin", 0.3d,
                    "confidenceMax", 0.4d,
                    "riskLevel", "HIGH",
                    "requiredHumanInputFields", List.of("targetEnvironment")
                )),
                plannerFixture("planner-stop", "STOP", Map.of(
                    "plannerStatus", "PROPOSED",
                    "plannerAction", "STOP",
                    "confidenceMin", 0.8d,
                    "confidenceMax", 0.9d,
                    "riskLevel", "LOW",
                    "blockers", List.of("No further planning action is required")
                ))
            ),
            0.8d,
            Map.of("planner-decision", 0.8d),
            Map.of("planner-decision", 2.0d),
            Map.of()
        );
    }

    private GoldenTaskFixture plannerFixture(String fixtureId, String scenario, Map<String, Object> expected) {
        return new GoldenTaskFixture(
            fixtureId,
            List.of("planner-decision"),
            "Evaluate deterministic fake planner scenario " + scenario + ".",
            EvaluationFixtureType.PLANNER_DECISION,
            expected,
            Map.of("fakePlannerScenario", scenario, "isolated", true, "usesRealLlm", false)
        );
    }
}
