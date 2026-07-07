package com.probeflow.testagent.agentevaluation;

import java.util.List;
import java.util.Map;

public record GoldenTaskFixture(
    String fixtureId,
    List<String> capabilityTags,
    String inputSummary,
    EvaluationFixtureType fixtureType,
    Map<String, Object> expectedResults,
    Map<String, Object> setupMetadata
) {

    public GoldenTaskFixture {
        if (fixtureId == null || fixtureId.isBlank()) {
            throw new IllegalArgumentException("fixtureId is required");
        }
        fixtureId = fixtureId.trim();
        capabilityTags = capabilityTags == null ? List.of() : capabilityTags.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        inputSummary = inputSummary == null ? "" : inputSummary.trim();
        fixtureType = fixtureType == null ? EvaluationFixtureType.SMOKE : fixtureType;
        expectedResults = expectedResults == null ? Map.of() : Map.copyOf(expectedResults);
        setupMetadata = setupMetadata == null ? Map.of() : Map.copyOf(setupMetadata);
    }
}
