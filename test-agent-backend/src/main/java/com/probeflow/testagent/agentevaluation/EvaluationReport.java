package com.probeflow.testagent.agentevaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record EvaluationReport(
    String runId,
    Map<String, Object> runSummary,
    List<Map<String, Object>> caseSummary,
    Map<String, Map<String, Object>> metricSummary,
    List<String> recommendedFixes,
    String humanReadableSummary
) {

    public EvaluationReport {
        runSummary = runSummary == null ? Map.of() : Map.copyOf(runSummary);
        caseSummary = caseSummary == null ? List.of() : List.copyOf(caseSummary);
        metricSummary = metricSummary == null ? Map.of() : Map.copyOf(metricSummary);
        recommendedFixes = recommendedFixes == null ? List.of() : List.copyOf(recommendedFixes);
        humanReadableSummary = humanReadableSummary == null ? "" : humanReadableSummary.trim();
    }

    public static EvaluationReport from(EvaluationRun run, List<EvaluationCaseResult> caseResults) {
        var cases = caseResults == null ? List.<EvaluationCaseResult>of() : caseResults;
        var runSummary = new LinkedHashMap<String, Object>();
        runSummary.put("runId", run.runId());
        runSummary.put("datasetName", run.datasetName());
        runSummary.put("datasetVersion", run.datasetVersion());
        runSummary.put("profile", run.profile());
        runSummary.put("providerMode", run.providerMode().name());
        runSummary.put("status", run.status().name());
        runSummary.put("overallScore", run.overallScore());
        runSummary.put("startedAt", run.startedAt());
        runSummary.put("completedAt", run.completedAt());

        var caseSummary = cases.stream()
            .map(EvaluationReport::caseSummary)
            .toList();
        var metricSummary = metricSummary(cases);
        var fixes = cases.stream()
            .flatMap(result -> result.metricResults().stream())
            .filter(metric -> !metric.passed())
            .map(metric -> metric.metricName() + ": " + metric.diagnosticMessage())
            .distinct()
            .toList();
        var human = "Evaluation " + run.runId()
            + " for " + run.datasetName() + " " + run.datasetVersion()
            + " completed with " + run.status()
            + " at score " + run.overallScore() + ".";
        return new EvaluationReport(run.runId(), runSummary, caseSummary, metricSummary, fixes, human);
    }

    public EvaluationReport withRecommendedFix(String capability, String message) {
        var fixes = new ArrayList<>(recommendedFixes);
        fixes.add(capability + ": " + message);
        return new EvaluationReport(runId, runSummary, caseSummary, metricSummary, fixes, humanReadableSummary);
    }

    private static Map<String, Object> caseSummary(EvaluationCaseResult result) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("fixtureId", result.fixtureId());
        summary.put("status", result.status().name());
        summary.put("capabilityTags", result.capabilityTags());
        summary.put("actualSummary", result.actualSummary());
        summary.put("expectedSummary", result.expectedSummary());
        summary.put("failureReasons", result.failureReasons());
        summary.put("metricCount", result.metricResults().size());
        return summary;
    }

    private static Map<String, Map<String, Object>> metricSummary(List<EvaluationCaseResult> caseResults) {
        var byMetric = new LinkedHashMap<String, List<EvaluationMetricResult>>();
        for (var caseResult : caseResults) {
            for (var metric : caseResult.metricResults()) {
                byMetric.computeIfAbsent(metric.metricName(), ignored -> new ArrayList<>()).add(metric);
            }
        }
        var summary = new LinkedHashMap<String, Map<String, Object>>();
        byMetric.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> summary.put(entry.getKey(), summarizeMetric(entry.getValue())));
        return summary;
    }

    private static Map<String, Object> summarizeMetric(List<EvaluationMetricResult> metrics) {
        var sorted = metrics.stream()
            .sorted(Comparator.comparing(EvaluationMetricResult::diagnosticMessage))
            .toList();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("count", sorted.size());
        summary.put("passed", sorted.stream().filter(EvaluationMetricResult::passed).count());
        summary.put("failed", sorted.stream().filter(metric -> !metric.passed()).count());
        summary.put("averageScore", sorted.stream().mapToDouble(EvaluationMetricResult::score).average().orElse(0.0d));
        summary.put("diagnostics", sorted.stream().map(EvaluationMetricResult::diagnosticMessage).toList());
        return summary;
    }
}
