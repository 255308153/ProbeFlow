package com.probeflow.testagent.agentevaluation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class V3SuiteAgentCapabilityEvaluator implements AgentEvaluationEvaluator {

    public static final String DEPENDENCY_COVERAGE_METRIC = "v3-suite-dependency-coverage";
    public static final String VARIABLE_AUDIT_METRIC = "v3-suite-variable-audit-correctness";
    public static final String FAILURE_ANALYSIS_METRIC = "v3-suite-failure-analysis-accuracy";
    public static final String MEMORY_CANDIDATE_METRIC = "v3-suite-memory-candidate-quality";
    public static final String HARNESS_COMPLETENESS_METRIC = "v3-suite-harness-completeness";
    public static final String SECRET_REDACTION_METRIC = "v3-suite-secret-redaction";

    private static final Set<String> LEGAL_SECTION_SOURCES = Set.of("REAL", "FIXTURE", "STAGED", "PENDING_RUNTIME", "NOT_RUN");

    @Override
    public String capability() {
        return "v3-suite-agent-capability";
    }

    @Override
    public boolean supports(GoldenTaskFixture fixture) {
        return fixture.fixtureType() == EvaluationFixtureType.V3_SUITE_AGENT;
    }

    @Override
    public EvaluationCaseResult evaluate(
        EvaluationDataset dataset,
        GoldenTaskFixture fixture,
        EvaluationRunContext context
    ) {
        var actual = actual(fixture, context);
        var expected = fixture.expectedResults();
        var metrics = List.of(
            metric(dataset, DEPENDENCY_COVERAGE_METRIC, dependencyDiagnostics(actual, expected), actual, expected),
            metric(dataset, VARIABLE_AUDIT_METRIC, variableAuditDiagnostics(actual, expected), actual, expected),
            metric(dataset, FAILURE_ANALYSIS_METRIC, failureAnalysisDiagnostics(actual, expected), actual, expected),
            metric(dataset, MEMORY_CANDIDATE_METRIC, memoryCandidateDiagnostics(actual, expected), actual, expected),
            metric(dataset, HARNESS_COMPLETENESS_METRIC, harnessDiagnostics(actual, expected), actual, expected),
            metric(dataset, SECRET_REDACTION_METRIC, redactionDiagnostics(actual, expected), actual, expected)
        );
        var failureReasons = metrics.stream()
            .filter(metric -> !metric.passed())
            .map(EvaluationMetricResult::diagnosticMessage)
            .toList();
        var summary = "V3 suite capability fixture evaluated with providerMode="
            + context.providerMode().name()
            + ", usesRealProvider="
            + !context.deterministicFake()
            + ", metrics="
            + metrics.size();
        if (failureReasons.isEmpty()) {
            return EvaluationCaseResult.passed(
                fixture.fixtureId(),
                fixture.capabilityTags(),
                summary,
                expected.toString(),
                metrics
            );
        }
        return EvaluationCaseResult.failed(
            fixture.fixtureId(),
            fixture.capabilityTags(),
            summary,
            expected.toString(),
            failureReasons,
            metrics
        );
    }

    private Map<String, Object> actual(GoldenTaskFixture fixture, EvaluationRunContext context) {
        var actual = new LinkedHashMap<String, Object>();
        actual.put("fixtureId", fixture.fixtureId());
        actual.put("providerMode", context.providerMode().name());
        actual.put("usesRealProvider", !context.deterministicFake());
        actual.put("usesRealLlm", false);
        actual.put("usesRealEmbedding", false);
        actual.put("usesExternalHttp", false);
        actual.put("suiteDraft", map(fixture.setupMetadata().get("suiteDraft")));
        actual.put("variableAudit", map(fixture.setupMetadata().get("variableAudit")));
        actual.put("failureAnalysis", map(fixture.setupMetadata().get("failureAnalysis")));
        actual.put("memoryCandidate", map(fixture.setupMetadata().get("memoryCandidate")));
        actual.put("harness", map(fixture.setupMetadata().get("harness")));
        actual.put("report", map(fixture.setupMetadata().get("report")));
        return actual;
    }

    private EvaluationMetricResult metric(
        EvaluationDataset dataset,
        String metricName,
        List<String> diagnostics,
        Map<String, Object> actual,
        Map<String, Object> expected
    ) {
        var score = score(diagnostics);
        var passed = diagnostics.isEmpty() && score >= dataset.thresholdFor(metricName);
        return new EvaluationMetricResult(
            metricName,
            score,
            dataset.thresholdFor(metricName),
            dataset.weightFor(metricName),
            passed,
            scopedActual(actual, metricName),
            expected,
            passed ? metricName + " matched V3 suite fixture expectations." : String.join("; ", diagnostics)
        );
    }

    private Map<String, Object> scopedActual(Map<String, Object> actual, String metricName) {
        return switch (metricName) {
            case DEPENDENCY_COVERAGE_METRIC -> mapOf("suiteDraft", actual.get("suiteDraft"));
            case VARIABLE_AUDIT_METRIC -> mapOf("variableAudit", actual.get("variableAudit"));
            case FAILURE_ANALYSIS_METRIC -> mapOf("failureAnalysis", actual.get("failureAnalysis"));
            case MEMORY_CANDIDATE_METRIC -> mapOf("memoryCandidate", actual.get("memoryCandidate"));
            case HARNESS_COMPLETENESS_METRIC -> mapOf("harness", actual.get("harness"));
            case SECRET_REDACTION_METRIC -> mapOf(
                "suiteDraft", actual.get("suiteDraft"),
                "variableAudit", actual.get("variableAudit"),
                "failureAnalysis", actual.get("failureAnalysis"),
                "memoryCandidate", actual.get("memoryCandidate"),
                "harness", actual.get("harness"),
                "report", actual.get("report"),
                "usesRealLlm", actual.get("usesRealLlm"),
                "usesRealEmbedding", actual.get("usesRealEmbedding"),
                "usesExternalHttp", actual.get("usesExternalHttp")
            );
            default -> actual;
        };
    }

    private List<String> dependencyDiagnostics(Map<String, Object> actual, Map<String, Object> expected) {
        var diagnostics = new ArrayList<String>();
        var suiteDraft = map(actual.get("suiteDraft"));
        var expectedPairs = list(expected.get("expectedDependencyPairs"));
        var pairs = list(suiteDraft.get("dependencyPairs"));
        var extractRules = list(suiteDraft.get("extractRules"));
        var variableReferences = list(suiteDraft.get("variableReferences"));
        for (var expectedPair : expectedPairs) {
            if (!pairs.contains(expectedPair)) {
                diagnostics.add("missing dependency pair " + expectedPair);
            }
        }
        for (var rule : extractRules) {
            var ruleMap = map(rule);
            var variableName = text(ruleMap.get("targetKey"));
            if (variableName.isBlank() || !containsVariableReference(variableReferences, variableName)) {
                diagnostics.add("extractRule " + ruleMap.get("id") + " has no matching variable reference");
            }
        }
        if (extractRules.isEmpty()) {
            diagnostics.add("missing extractRules for producer steps");
        }
        if (variableReferences.isEmpty()) {
            diagnostics.add("missing variable references for consumer steps");
        }
        return diagnostics;
    }

    private boolean containsVariableReference(List<Object> variableReferences, String variableName) {
        return variableReferences.stream()
            .map(this::map)
            .map(reference -> text(reference.get("expression")))
            .anyMatch(expression -> expression.contains(variableName));
    }

    private List<String> variableAuditDiagnostics(Map<String, Object> actual, Map<String, Object> expected) {
        var diagnostics = new ArrayList<String>();
        var audit = map(actual.get("variableAudit"));
        requireContainsAll(diagnostics, "missing written variable", list(expected.get("expectedVariableWrites")), list(audit.get("writes")));
        requireContainsAll(diagnostics, "missing consumed variable", list(expected.get("expectedVariableConsumers")), list(audit.get("consumes")));
        requireContainsAll(diagnostics, "missing overwrite audit", list(expected.get("expectedVariableOverwrites")), list(audit.get("overwrites")));
        requireContainsAll(
            diagnostics,
            "missing diagnostic",
            list(expected.get("expectedMissingDiagnostics")),
            list(audit.get("missingDiagnostics"))
        );
        return diagnostics;
    }

    private List<String> failureAnalysisDiagnostics(Map<String, Object> actual, Map<String, Object> expected) {
        var diagnostics = new ArrayList<String>();
        var failure = map(actual.get("failureAnalysis"));
        requireEquals(diagnostics, "classification", expected.get("expectedFailureClassification"), failure.get("classification"));
        requireEquals(diagnostics, "root step", expected.get("expectedRootStep"), failure.get("rootStep"));
        requireContainsAll(
            diagnostics,
            "missing affected downstream step",
            list(expected.get("expectedAffectedDownstreamSteps")),
            list(failure.get("affectedDownstreamSteps"))
        );
        var expectedSuggestion = text(expected.get("expectedNextSuggestionContains"));
        if (!expectedSuggestion.isBlank() && !text(failure.get("nextSuggestion")).contains(expectedSuggestion)) {
            diagnostics.add("nextSuggestion missing " + expectedSuggestion);
        }
        return diagnostics;
    }

    private List<String> memoryCandidateDiagnostics(Map<String, Object> actual, Map<String, Object> expected) {
        var diagnostics = new ArrayList<String>();
        var candidate = map(actual.get("memoryCandidate"));
        requirePresent(diagnostics, "sourceRef", candidate.get("sourceRef"));
        requireContainsAll(
            diagnostics,
            "missing memory tag",
            list(expected.get("expectedMemoryTags")),
            list(candidate.get("tags"))
        );
        if (decimal(candidate.get("confidence")) < decimal(expected.getOrDefault("expectedMemoryConfidenceMin", 0.0d))) {
            diagnostics.add("memory confidence below expected minimum");
        }
        requireContainsAll(
            diagnostics,
            "missing memory evidence",
            list(expected.get("expectedMemoryEvidence")),
            list(candidate.get("evidence"))
        );
        requirePresent(diagnostics, "applicableWhen", candidate.get("applicableWhen"));
        if (containsSecret(candidate)) {
            diagnostics.add("memory candidate leaked sensitive data");
        }
        return diagnostics;
    }

    private List<String> harnessDiagnostics(Map<String, Object> actual, Map<String, Object> expected) {
        var diagnostics = new ArrayList<String>();
        var harness = map(actual.get("harness"));
        var sections = map(harness.get("sections"));
        for (var requiredSection : list(expected.get("expectedHarnessSections"))) {
            var source = text(sections.get(text(requiredSection)));
            if (source.isBlank()) {
                diagnostics.add("missing harness section " + requiredSection);
            } else if (!LEGAL_SECTION_SOURCES.contains(source)) {
                diagnostics.add("illegal harness source " + source + " for section " + requiredSection);
            }
        }
        var realSections = list(expected.get("expectedHarnessRealSections"));
        for (var section : realSections) {
            var source = text(sections.get(text(section)));
            if (!"REAL".equals(source)) {
                diagnostics.add("harness section " + section + " expected REAL but was " + source);
            }
        }
        return diagnostics;
    }

    private List<String> redactionDiagnostics(Map<String, Object> actual, Map<String, Object> expected) {
        var diagnostics = new ArrayList<String>();
        if (Boolean.TRUE.equals(actual.get("usesRealLlm"))) {
            diagnostics.add("default V3 suite dataset used a real LLM");
        }
        if (Boolean.TRUE.equals(actual.get("usesRealEmbedding"))) {
            diagnostics.add("default V3 suite dataset used a real embedding provider");
        }
        if (Boolean.TRUE.equals(actual.get("usesExternalHttp"))) {
            diagnostics.add("default V3 suite dataset used external HTTP");
        }
        for (var forbidden : list(expected.get("forbiddenSecrets"))) {
            var secret = text(forbidden);
            if (!secret.isBlank() && serialize(actual).contains(secret)) {
                diagnostics.add("secret leakage detected: " + secret);
            }
        }
        if (containsSecret(actual)) {
            diagnostics.add("sensitive key retained an unredacted value");
        }
        return diagnostics;
    }

    private void requireContainsAll(List<String> diagnostics, String label, List<Object> expected, List<Object> actual) {
        for (var value : expected) {
            if (!actual.contains(value)) {
                diagnostics.add(label + " " + value);
            }
        }
    }

    private void requireEquals(List<String> diagnostics, String label, Object expected, Object actual) {
        if (expected != null && !String.valueOf(expected).equals(String.valueOf(actual))) {
            diagnostics.add(label + " mismatch: expected " + expected + " but was " + actual);
        }
    }

    private void requirePresent(List<String> diagnostics, String label, Object value) {
        if (text(value).isBlank()) {
            diagnostics.add("missing " + label);
        }
    }

    private double score(List<String> diagnostics) {
        return diagnostics.isEmpty() ? 1.0d : 0.0d;
    }

    private boolean containsSecret(Object value) {
        if (value instanceof Map<?, ?> source) {
            for (var entry : source.entrySet()) {
                var key = text(entry.getKey()).toLowerCase(Locale.ROOT);
                var entryValue = entry.getValue();
                if (sensitiveKey(key) && !redacted(entryValue)) {
                    return true;
                }
                if (containsSecret(entryValue)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().anyMatch(this::containsSecret);
        }
        return false;
    }

    private boolean sensitiveKey(String key) {
        return key.contains("authorization")
            || key.contains("cookie")
            || key.contains("password")
            || key.contains("secret")
            || key.contains("token")
            || key.contains("apikey")
            || key.contains("credential");
    }

    private boolean redacted(Object value) {
        var text = text(value).toLowerCase(Locale.ROOT);
        return text.isBlank() || text.contains("[redacted]") || text.contains("redacted");
    }

    private String serialize(Object value) {
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> source) {
            var result = new LinkedHashMap<String, Object>();
            source.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private Map<String, Object> mapOf(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (var i = 0; i + 1 < pairs.length; i += 2) {
            result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return result;
    }

    private List<Object> list(Object value) {
        if (value instanceof List<?> source) {
            return new ArrayList<>(source);
        }
        if (value instanceof Collection<?> source) {
            return new ArrayList<>(source);
        }
        if (value == null) {
            return List.of();
        }
        return List.of(value);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private double decimal(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(text(value));
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }
}
