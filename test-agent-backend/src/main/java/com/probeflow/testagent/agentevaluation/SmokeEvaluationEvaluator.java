package com.probeflow.testagent.agentevaluation;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SmokeEvaluationEvaluator implements AgentEvaluationEvaluator {

    @Override
    public String capability() {
        return "evaluation-foundation";
    }

    @Override
    public boolean supports(GoldenTaskFixture fixture) {
        return fixture.fixtureType() == EvaluationFixtureType.SMOKE;
    }

    @Override
    public EvaluationCaseResult evaluate(
        EvaluationDataset dataset,
        GoldenTaskFixture fixture,
        EvaluationRunContext context
    ) {
        var usesRealProvider = !context.deterministicFake();
        var expected = fixture.expectedResults();
        var actual = Map.<String, Object>of(
            "providerMode", context.providerMode().name(),
            "runProfile", context.runProfile(),
            "fixtureNamespace", context.fixtureNamespace(),
            "usesRealProvider", usesRealProvider
        );
        var passed = !usesRealProvider
            && context.fixtureNamespace().contains(context.runId())
            && context.providerMode().name().equals(expected.get("providerMode"));
        var metric = passed
            ? EvaluationMetricResult.passed(
                capability(),
                dataset.thresholdFor(capability()),
                dataset.weightFor(capability()),
                "Smoke fixture used deterministic fake provider and isolated namespace.",
                actual,
                expected
            )
            : EvaluationMetricResult.failed(
                capability(),
                dataset.thresholdFor(capability()),
                dataset.weightFor(capability()),
                "Smoke fixture did not use deterministic fake provider or isolated namespace.",
                actual,
                expected
            );
        if (passed) {
            return EvaluationCaseResult.passed(
                fixture.fixtureId(),
                fixture.capabilityTags(),
                "Smoke fixture passed with deterministic fake provider.",
                expected.toString(),
                java.util.List.of(metric)
            );
        }
        return EvaluationCaseResult.failed(
            fixture.fixtureId(),
            fixture.capabilityTags(),
            "Smoke fixture failed provider or namespace checks.",
            expected.toString(),
            java.util.List.of("smoke-provider-or-namespace-mismatch"),
            java.util.List.of(metric)
        );
    }
}
