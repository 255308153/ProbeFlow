package com.probeflow.testagent.agentevaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AgentEvaluationApplicationService {

    private static final String DEFAULT_PROFILE = "ci-deterministic";

    private final EvaluationDatasetRegistry datasets;
    private final List<AgentEvaluationEvaluator> evaluators;

    public AgentEvaluationApplicationService(EvaluationDatasetRegistry datasets, List<AgentEvaluationEvaluator> evaluators) {
        this.datasets = datasets;
        this.evaluators = evaluators == null ? List.of() : List.copyOf(evaluators);
    }

    public AgentEvaluationResult runDefaultDataset() {
        return run(new EvaluationRunRequest(null, DEFAULT_PROFILE, EvaluationProviderMode.DETERMINISTIC_FAKE));
    }

    public AgentEvaluationResult run(EvaluationRunRequest request) {
        var effectiveRequest = request == null
            ? new EvaluationRunRequest(null, DEFAULT_PROFILE, EvaluationProviderMode.DETERMINISTIC_FAKE)
            : request;
        var dataset = datasets.load(effectiveRequest.datasetName());
        var runId = "eval-" + UUID.randomUUID();
        var startedAt = Instant.now();
        var context = new EvaluationRunContext(
            runId,
            effectiveRequest.runProfile(),
            effectiveRequest.providerMode(),
            "fixture-" + runId
        );
        var caseResults = new ArrayList<EvaluationCaseResult>();

        try {
            for (var fixture : dataset.fixtures()) {
                caseResults.add(evaluateFixture(dataset, fixture, context));
            }
            var completedAt = Instant.now();
            var overallScore = weightedScore(dataset, caseResults);
            var status = runStatus(dataset, caseResults, overallScore);
            var run = new EvaluationRun(
                runId,
                dataset.name(),
                dataset.version(),
                context.runProfile(),
                context.providerMode(),
                status,
                overallScore,
                startedAt,
                completedAt,
                runSummary(status, dataset, caseResults, overallScore)
            );
            return new AgentEvaluationResult(run, EvaluationReport.from(run, caseResults));
        } catch (RuntimeException exception) {
            var completedAt = Instant.now();
            var run = new EvaluationRun(
                runId,
                dataset.name(),
                dataset.version(),
                context.runProfile(),
                context.providerMode(),
                EvaluationRunStatus.ERROR,
                0.0d,
                startedAt,
                completedAt,
                "Evaluation run errored: " + exception.getMessage()
            );
            var report = EvaluationReport.from(run, caseResults).withRecommendedFix(
                "evaluation-foundation",
                "Fix evaluation harness error: " + exception.getMessage()
            );
            return new AgentEvaluationResult(run, report);
        }
    }

    private EvaluationCaseResult evaluateFixture(
        EvaluationDataset dataset,
        GoldenTaskFixture fixture,
        EvaluationRunContext context
    ) {
        var evaluator = evaluators.stream()
            .sorted(Comparator.comparing(AgentEvaluationEvaluator::capability))
            .filter(candidate -> candidate.supports(fixture))
            .findFirst();
        if (evaluator.isEmpty()) {
            var metric = EvaluationMetricResult.failed(
                "evaluation-foundation",
                dataset.thresholdFor("evaluation-foundation"),
                dataset.weightFor("evaluation-foundation"),
                "No evaluator supports fixture " + fixture.fixtureId(),
                java.util.Map.of("fixtureId", fixture.fixtureId()),
                fixture.expectedResults()
            );
            return EvaluationCaseResult.failed(
                fixture.fixtureId(),
                fixture.capabilityTags(),
                "No evaluator available",
                fixture.expectedResults().toString(),
                List.of("missing-evaluator"),
                List.of(metric)
            );
        }
        var result = evaluator.orElseThrow().evaluate(dataset, fixture, context);
        return result.withStatus(caseStatus(dataset, result));
    }

    private EvaluationCaseStatus caseStatus(EvaluationDataset dataset, EvaluationCaseResult result) {
        if (result.status() == EvaluationCaseStatus.ERROR) {
            return EvaluationCaseStatus.ERROR;
        }
        var score = weightedScore(dataset, List.of(result));
        var threshold = dataset.caseThresholdFor(result.fixtureId());
        return result.metricResults().stream().allMatch(EvaluationMetricResult::passed) && score >= threshold
            ? EvaluationCaseStatus.PASSED
            : EvaluationCaseStatus.FAILED;
    }

    private EvaluationRunStatus runStatus(
        EvaluationDataset dataset,
        List<EvaluationCaseResult> caseResults,
        double overallScore
    ) {
        if (caseResults.stream().anyMatch(result -> result.status() == EvaluationCaseStatus.ERROR)) {
            return EvaluationRunStatus.ERROR;
        }
        if (caseResults.isEmpty()) {
            return EvaluationRunStatus.PARTIAL;
        }
        if (caseResults.stream().allMatch(result -> result.status() == EvaluationCaseStatus.PASSED)
            && overallScore >= dataset.defaultThreshold()) {
            return EvaluationRunStatus.PASSED;
        }
        return EvaluationRunStatus.FAILED;
    }

    private double weightedScore(EvaluationDataset dataset, List<EvaluationCaseResult> caseResults) {
        var totalWeight = 0.0d;
        var weighted = 0.0d;
        for (var caseResult : caseResults) {
            for (var metric : caseResult.metricResults()) {
                var weight = metric.weight() <= 0.0d ? dataset.weightFor(metric.metricName()) : metric.weight();
                totalWeight += weight;
                weighted += metric.score() * weight;
            }
        }
        if (totalWeight == 0.0d) {
            return 0.0d;
        }
        return Math.round((weighted / totalWeight) * 10000.0d) / 10000.0d;
    }

    private String runSummary(
        EvaluationRunStatus status,
        EvaluationDataset dataset,
        List<EvaluationCaseResult> caseResults,
        double overallScore
    ) {
        return "Dataset " + dataset.name() + " " + dataset.version()
            + " finished with " + status
            + ", cases=" + caseResults.size()
            + ", score=" + overallScore;
    }
}
