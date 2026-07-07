package com.probeflow.testagent.agentevaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportUsefulnessNoSecretEvaluatorTests {

    @Autowired
    private ReportUsefulnessNoSecretEvaluator evaluator;

    @Test
    void applicationServiceRunsReportUsefulnessDatasetThroughReportGeneration() {
        var service = new AgentEvaluationApplicationService(
            new EvaluationDatasetRegistry(),
            List.of(evaluator)
        );

        var result = service.run(new EvaluationRunRequest(
            EvaluationDatasetRegistry.REPORT_USEFULNESS_DATASET,
            "ci-deterministic",
            EvaluationProviderMode.DETERMINISTIC_FAKE
        ));

        assertThat(result.run().status())
            .as(result.report().humanReadableSummary() + " fixes=" + result.report().recommendedFixes())
            .isEqualTo(EvaluationRunStatus.PASSED);
        assertThat(result.report().metricSummary()).containsKey(ReportUsefulnessNoSecretEvaluator.METRIC_NAME);
        assertThat(result.report().caseSummary())
            .extracting(summary -> summary.get("fixtureId"))
            .containsExactly("report-usefulness-auth-failure");
    }

    @Test
    void acceptsUsefulSanitizedFixedReport() {
        var result = evaluator.evaluate(
            dataset(),
            fixture(
                "report-usefulness-fixed-pass",
                report(
                    "Task report snapshot state=BASIC_SNAPSHOT passRate=0.0000.",
                    "Execution summary has blocking risk.",
                    1,
                    0,
                    1,
                    List.of(finding(true)),
                    List.of(suggestion()),
                    metadata(true, true)
                ),
                expected()
            ),
            context("eval-report-fixed-pass")
        );

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.PASSED);
        assertThat(result.metricResults()).singleElement()
            .satisfies(metric -> assertThat(metric.actual())
                .containsEntry("secretLeakageDetected", false)
                .containsEntry("memoryFeedbackSummaryPresent", true));
    }

    @Test
    void reportsMissingSectionsEvidenceRecommendationAndSecretLeakageWithoutEchoingSecretValue() {
        var leakedValue = "super-secret-report-token";
        var result = evaluator.evaluate(
            dataset(),
            fixture(
                "report-usefulness-fixed-fail",
                report(
                    "",
                    "",
                    0,
                    0,
                    0,
                    List.of(Map.of(
                        "type", "EXECUTION_OUTCOME",
                        "evidenceType", "FACTUAL",
                        "Authorization", leakedValue
                    )),
                    List.of(),
                    Map.of("memoryFeedback", Map.of(), "executionSummary", Map.of())
                ),
                expected()
            ),
            context("eval-report-fixed-fail")
        );

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.FAILED);
        assertThat(result.metricResults()).singleElement()
            .satisfies(metric -> {
                assertThat(metric.passed()).isFalse();
                assertThat(metric.actual()).containsEntry("secretLeakageDetected", true);
                assertThat(metric.diagnosticMessage())
                    .contains("missing section")
                    .contains("missing evidence")
                    .contains("missing recommendation")
                    .contains("secret leakage")
                    .doesNotContain(leakedValue);
                assertThat(metric.actual().toString()).doesNotContain(leakedValue);
            });
    }

    private GoldenTaskFixture fixture(
        String fixtureId,
        Map<String, Object> report,
        Map<String, Object> expected
    ) {
        return new GoldenTaskFixture(
            fixtureId,
            List.of("report-usefulness"),
            "Evaluate report usefulness fixture.",
            EvaluationFixtureType.REPORT_USEFULNESS,
            expected,
            Map.of("report", report)
        );
    }

    private EvaluationDataset dataset() {
        return new EvaluationDataset(
            "report-usefulness-test",
            "2026-07-07",
            List.of(),
            0.8d,
            Map.of(ReportUsefulnessNoSecretEvaluator.METRIC_NAME, 0.8d),
            Map.of(ReportUsefulnessNoSecretEvaluator.METRIC_NAME, 1.5d),
            Map.of()
        );
    }

    private EvaluationRunContext context(String runId) {
        return new EvaluationRunContext(
            runId,
            "ci-deterministic",
            EvaluationProviderMode.DETERMINISTIC_FAKE,
            "fixture-" + runId
        );
    }

    private Map<String, Object> expected() {
        return Map.of(
            "expectedReportSections", List.of(
                "summary",
                "execution-stats",
                "failure-evidence",
                "recommendations",
                "source-references",
                "memory-learning-summary"
            ),
            "expectedEvidenceTypes", List.of("execution", "observation", "citation", "memory-feedback"),
            "expectedRecommendationContains", "Inspect credentials",
            "forbidSecretLeakage", true
        );
    }

    private Map<String, Object> report(
        String summary,
        String riskSummary,
        int caseCount,
        int passCount,
        int failCount,
        List<Map<String, Object>> findings,
        List<Map<String, Object>> suggestions,
        Map<String, Object> metadata
    ) {
        return Map.of(
            "summary", summary,
            "riskSummary", riskSummary,
            "caseCount", caseCount,
            "passCount", passCount,
            "failCount", failCount,
            "warningCount", 0,
            "findings", findings,
            "suggestions", suggestions,
            "metadata", metadata
        );
    }

    private Map<String, Object> finding(boolean includeObservation) {
        var sourceReferences = includeObservation
            ? Map.of(
                "executionIds", List.of("execution-1"),
                "observationIds", List.of("observation-1"),
                "caseIds", List.of("case-1"),
                "apiSpecIds", List.of("api-1")
            )
            : Map.of(
                "executionIds", List.of("execution-1"),
                "observationIds", List.of(),
                "caseIds", List.of("case-1"),
                "apiSpecIds", List.of("api-1")
            );
        return Map.of(
            "type", "EXECUTION_OUTCOME",
            "classification", "AUTH_ISSUE",
            "evidenceType", "FACTUAL_AND_INFERRED",
            "factualEvidence", List.of("overallStatus=FAILED", "statusCode=401"),
            "inferredEvidence", includeObservation
                ? List.of(Map.of("observationId", "observation-1", "summary", "Auth failed"))
                : List.of(),
            "sourceReferences", sourceReferences,
            "Authorization", "[REDACTED]"
        );
    }

    private Map<String, Object> suggestion() {
        return Map.of(
            "category", "AUTH",
            "priority", "P0",
            "action", "Inspect credentials and permissions before rerunning affected cases.",
            "sourceReferences", Map.of(
                "executionIds", List.of("execution-1"),
                "observationIds", List.of("observation-1"),
                "caseIds", List.of("case-1"),
                "apiSpecIds", List.of("api-1")
            )
        );
    }

    private Map<String, Object> metadata(boolean includeMemory, boolean includeObservation) {
        return Map.of(
            "executionSummary", Map.of(
                "executionCount", 1,
                "passed", 0,
                "failed", 1,
                "warning", 0,
                "skipped", 0,
                "blocked", 0
            ),
            "coverage", Map.of(
                "targetApiSpecIds", List.of("api-1"),
                "testedApiSpecIds", List.of("api-1")
            ),
            "analysisCoverage", Map.of(
                "generatedObservationIds", includeObservation ? List.of("observation-1") : List.of()
            ),
            "memoryFeedback", includeMemory
                ? Map.of(
                    "summary", "Task memory items=0, acceptedLongTermMemories=0, generatedRejectedCandidates=0.",
                    "generatedCandidateAttemptCount", 1,
                    "generatedCandidateResults", List.of(Map.of("executionId", "execution-1", "attempted", true))
                )
                : Map.of()
        );
    }
}
