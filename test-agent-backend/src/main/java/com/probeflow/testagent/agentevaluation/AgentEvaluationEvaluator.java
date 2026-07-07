package com.probeflow.testagent.agentevaluation;

public interface AgentEvaluationEvaluator {

    String capability();

    boolean supports(GoldenTaskFixture fixture);

    EvaluationCaseResult evaluate(
        EvaluationDataset dataset,
        GoldenTaskFixture fixture,
        EvaluationRunContext context
    );
}
