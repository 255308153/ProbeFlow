package com.probeflow.testagent.agentevaluation;

import com.probeflow.testagent.memory.ContextBundle;
import com.probeflow.testagent.memory.ContextCitation;
import com.probeflow.testagent.memory.MemoryUsageConsumer;
import com.probeflow.testagent.memory.UnifiedContextBuilder;
import com.probeflow.testagent.memory.UnifiedContextQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ContextCitationUsefulnessEvaluator implements AgentEvaluationEvaluator {

    public static final String METRIC_NAME = "context-citation";

    private final UnifiedContextBuilder contextBuilder;

    public ContextCitationUsefulnessEvaluator(UnifiedContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
    }

    @Override
    public String capability() {
        return METRIC_NAME;
    }

    @Override
    public boolean supports(GoldenTaskFixture fixture) {
        return fixture.fixtureType() == EvaluationFixtureType.CONTEXT_CITATION;
    }

    @Override
    public EvaluationCaseResult evaluate(
        EvaluationDataset dataset,
        GoldenTaskFixture fixture,
        EvaluationRunContext context
    ) {
        var bundle = contextBuilder.build(query(fixture, context));
        var expected = fixture.expectedResults();
        var actual = actual(bundle);
        var diagnostics = diagnostics(expected, bundle);
        var score = score(expected, diagnostics);
        var passed = diagnostics.isEmpty() && score >= dataset.thresholdFor(METRIC_NAME);
        var metric = new EvaluationMetricResult(
            METRIC_NAME,
            score,
            dataset.thresholdFor(METRIC_NAME),
            dataset.weightFor(METRIC_NAME),
            passed,
            actual,
            expected,
            passed ? "Context citations, coverage and budget matched fixture expectations." : String.join("; ", diagnostics)
        );
        if (passed) {
            return EvaluationCaseResult.passed(
                fixture.fixtureId(),
                fixture.capabilityTags(),
                "Context bundle cited expected knowledge and memory sources.",
                expected.toString(),
                List.of(metric)
            );
        }
        return EvaluationCaseResult.failed(
            fixture.fixtureId(),
            fixture.capabilityTags(),
            "Context bundle missed expected citation or quality checks.",
            expected.toString(),
            diagnostics,
            List.of(metric)
        );
    }

    private UnifiedContextQuery query(GoldenTaskFixture fixture, EvaluationRunContext context) {
        var setup = fixture.setupMetadata();
        return new UnifiedContextQuery(
            text(setup, "taskId", null),
            text(setup, "sessionId", null),
            text(setup, "apiSpecId", null),
            null,
            text(setup, "stageProfile", "failure_analysis"),
            text(setup, "rawQuery", fixture.inputSummary()),
            text(setup, "systemName", null),
            text(setup, "moduleName", null),
            text(setup, "apiPath", null),
            text(setup, "errorCode", null),
            list(setup.get("tags")),
            integer(setup.get("tokenBudget"), 600),
            MemoryUsageConsumer.AGENT_EVALUATION,
            fixture.fixtureId() + ":" + context.runId()
        );
    }

    private Map<String, Object> actual(ContextBundle bundle) {
        var actual = new LinkedHashMap<String, Object>();
        actual.put("knowledgeCitations", refs(bundle, "knowledge_chunk"));
        actual.put("memoryCitations", refs(bundle, "long_term_memory"));
        actual.put("allCitations", allRefs(bundle));
        actual.put("coverage", coverage(bundle));
        actual.put("requestedTokenBudget", bundle.budget().requestedTokenBudget());
        actual.put("totalEstimatedTokens", bundle.budget().totalEstimatedTokens());
        actual.put("pruned", bundle.budget().pruned());
        actual.put("lowConfidence", bundle.coverage().lowConfidence());
        return actual;
    }

    private List<String> diagnostics(Map<String, Object> expected, ContextBundle bundle) {
        var diagnostics = new ArrayList<String>();
        missingRefs(
            "missing knowledge citation",
            list(expected.get("expectedKnowledgeCitations")),
            refs(bundle, "knowledge_chunk"),
            diagnostics
        );
        missingRefs(
            "missing memory citation",
            list(expected.get("expectedMemoryCitations")),
            refs(bundle, "long_term_memory"),
            diagnostics
        );
        missingCoverage(list(expected.get("expectedCoverage")), coverage(bundle), diagnostics);
        irrelevantCitationPenalty(expected, bundle, diagnostics);
        budgetDiagnostics(expected, bundle, diagnostics);
        if (expected.containsKey("expectedLowConfidence")) {
            var expectedLowConfidence = Boolean.parseBoolean(String.valueOf(expected.get("expectedLowConfidence")));
            if (bundle.coverage().lowConfidence() != expectedLowConfidence) {
                diagnostics.add(
                    "low confidence mismatch: expected " + expectedLowConfidence + " but was " + bundle.coverage().lowConfidence()
                );
            }
        }
        return diagnostics;
    }

    private void missingRefs(
        String label,
        List<String> expectedRefs,
        List<String> actualRefs,
        List<String> diagnostics
    ) {
        var actual = actualRefs.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
        var missing = expectedRefs.stream()
            .filter(expected -> !actual.contains(expected.toLowerCase(Locale.ROOT)))
            .toList();
        if (!missing.isEmpty()) {
            diagnostics.add(label + ": " + missing);
        }
    }

    private void missingCoverage(List<String> expectedCoverage, Map<String, Boolean> actualCoverage, List<String> diagnostics) {
        var missing = expectedCoverage.stream()
            .filter(key -> !actualCoverage.getOrDefault(key, false))
            .toList();
        if (!missing.isEmpty()) {
            diagnostics.add("coverage gap: " + missing);
        }
    }

    private void irrelevantCitationPenalty(Map<String, Object> expected, ContextBundle bundle, List<String> diagnostics) {
        if (!expected.containsKey("allowedIrrelevantCitationCount")) {
            return;
        }
        var expectedRefs = new LinkedHashSet<String>();
        expectedRefs.addAll(list(expected.get("expectedKnowledgeCitations")));
        expectedRefs.addAll(list(expected.get("expectedMemoryCitations")));
        var normalizedExpected = expectedRefs.stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        var irrelevant = bundle.citations().stream()
            .filter(this::retrievedContextCitation)
            .filter(citation -> citationRefs(citation).stream()
                .noneMatch(ref -> normalizedExpected.contains(ref.toLowerCase(Locale.ROOT))))
            .count();
        var allowed = integer(expected.get("allowedIrrelevantCitationCount"), 0);
        if (irrelevant > allowed) {
            diagnostics.add("irrelevant citation count " + irrelevant + " exceeds allowed " + allowed);
        }
    }

    private void budgetDiagnostics(Map<String, Object> expected, ContextBundle bundle, List<String> diagnostics) {
        var expectedBudget = expected.containsKey("tokenBudget")
            ? integer(expected.get("tokenBudget"), bundle.budget().requestedTokenBudget())
            : null;
        if (expectedBudget == null) {
            return;
        }
        if (bundle.budget().requestedTokenBudget() != expectedBudget) {
            diagnostics.add(
                "budget mismatch: expected requested " + expectedBudget + " but was " + bundle.budget().requestedTokenBudget()
            );
        }
        if (bundle.budget().totalEstimatedTokens() > expectedBudget) {
            diagnostics.add(
                "budget overflow: total " + bundle.budget().totalEstimatedTokens() + " exceeds requested " + expectedBudget
            );
        }
    }

    private double score(Map<String, Object> expected, List<String> diagnostics) {
        var checks = 0;
        checks += list(expected.get("expectedKnowledgeCitations")).size();
        checks += list(expected.get("expectedMemoryCitations")).size();
        checks += list(expected.get("expectedCoverage")).size();
        checks += expected.containsKey("allowedIrrelevantCitationCount") ? 1 : 0;
        checks += expected.containsKey("tokenBudget") ? 1 : 0;
        checks += expected.containsKey("expectedLowConfidence") ? 1 : 0;
        if (checks == 0) {
            return diagnostics.isEmpty() ? 1.0d : 0.0d;
        }
        return Math.max(0.0d, Math.round(((checks - diagnostics.size()) / (double) checks) * 10000.0d) / 10000.0d);
    }

    private List<String> refs(ContextBundle bundle, String citationType) {
        return bundle.citations().stream()
            .filter(citation -> citationType.equals(citation.citationType()))
            .flatMap(citation -> citationRefs(citation).stream())
            .distinct()
            .toList();
    }

    private List<String> allRefs(ContextBundle bundle) {
        return bundle.citations().stream()
            .flatMap(citation -> citationRefs(citation).stream())
            .distinct()
            .toList();
    }

    private List<String> citationRefs(ContextCitation citation) {
        var refs = new ArrayList<String>();
        if (citation.sourceId() != null && !citation.sourceId().isBlank()) {
            refs.add(citation.sourceId());
        }
        if (citation.sourceRef() != null && !citation.sourceRef().isBlank()) {
            refs.add(citation.sourceRef());
        }
        return refs;
    }

    private boolean retrievedContextCitation(ContextCitation citation) {
        return "knowledge_chunk".equals(citation.citationType())
            || "long_term_memory".equals(citation.citationType());
    }

    private Map<String, Boolean> coverage(ContextBundle bundle) {
        var coverage = new LinkedHashMap<String, Boolean>();
        coverage.put("api", bundle.coverage().hasApiContext());
        coverage.put("task-state", bundle.coverage().hasTaskState());
        coverage.put("session", bundle.coverage().hasSessionContext());
        coverage.put("task-memory", bundle.coverage().hasTaskMemory());
        coverage.put("knowledge", bundle.coverage().hasKnowledgeContext());
        coverage.put("memory", bundle.coverage().hasLongTermMemory());
        return coverage;
    }

    private String text(Map<String, Object> values, String key, String fallback) {
        var value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private Integer integer(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private List<String> list(Object value) {
        if (value instanceof Iterable<?> iterable) {
            var values = new ArrayList<String>();
            for (var item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item).trim());
                }
            }
            return values;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return List.of();
        }
        return List.of(String.valueOf(value).trim());
    }
}
