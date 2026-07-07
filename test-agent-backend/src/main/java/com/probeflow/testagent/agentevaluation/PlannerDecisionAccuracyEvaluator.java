package com.probeflow.testagent.agentevaluation;

import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.AgentWorkflowMode;
import com.probeflow.testagent.controlledplanner.ContextBundleSummary;
import com.probeflow.testagent.controlledplanner.ControlledPlanner;
import com.probeflow.testagent.controlledplanner.ControlledPlannerException;
import com.probeflow.testagent.controlledplanner.FakeControlledPlanner;
import com.probeflow.testagent.controlledplanner.LastStepOutcomeSnapshot;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlannerConstraint;
import com.probeflow.testagent.controlledplanner.PlannerInput;
import com.probeflow.testagent.controlledplanner.PlannerTaskState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PlannerDecisionAccuracyEvaluator implements AgentEvaluationEvaluator {

    public static final String METRIC_NAME = "planner-decision";

    private final ControlledPlanner planner;

    public PlannerDecisionAccuracyEvaluator(ControlledPlanner planner) {
        this.planner = planner;
    }

    @Override
    public String capability() {
        return METRIC_NAME;
    }

    @Override
    public boolean supports(GoldenTaskFixture fixture) {
        return fixture.fixtureType() == EvaluationFixtureType.PLANNER_DECISION;
    }

    @Override
    public EvaluationCaseResult evaluate(
        EvaluationDataset dataset,
        GoldenTaskFixture fixture,
        EvaluationRunContext context
    ) {
        PlanDecision decision;
        try {
            decision = planner.plan(plannerInput(fixture, context));
        } catch (ControlledPlannerException exception) {
            decision = PlanDecision.failed("Planner threw exception: " + exception.getMessage(), List.of(exception.getMessage()));
        }

        var expected = fixture.expectedResults();
        var actual = actualSummary(decision);
        var mismatches = mismatches(expected, decision);
        var score = score(expected, mismatches);
        var passed = score >= dataset.thresholdFor(METRIC_NAME) && mismatches.isEmpty();
        var diagnostic = mismatches.isEmpty()
            ? "Planner decision matched expected action, step/tool, confidence, risk, blockers and human input."
            : String.join("; ", mismatches);
        var metric = new EvaluationMetricResult(
            METRIC_NAME,
            score,
            dataset.thresholdFor(METRIC_NAME),
            dataset.weightFor(METRIC_NAME),
            passed,
            actual,
            expected,
            diagnostic
        );
        if (passed) {
            return EvaluationCaseResult.passed(
                fixture.fixtureId(),
                fixture.capabilityTags(),
                "Planner returned " + decision.status() + " " + decision.action(),
                expected.toString(),
                List.of(metric)
            );
        }
        return EvaluationCaseResult.failed(
            fixture.fixtureId(),
            fixture.capabilityTags(),
            "Planner returned " + decision.status() + " " + decision.action(),
            expected.toString(),
            mismatches,
            List.of(metric)
        );
    }

    private PlannerInput plannerInput(GoldenTaskFixture fixture, EvaluationRunContext context) {
        var setup = fixture.setupMetadata();
        var scenario = text(setup, "fakePlannerScenario", "CONTINUE");
        var constraints = new ArrayList<PlannerConstraint>();
        constraints.add(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, scenario));
        if (setup.containsKey("replanningTrigger")) {
            constraints.add(PlannerConstraint.of("REPLANNING_TRIGGER", String.valueOf(setup.get("replanningTrigger"))));
        }
        return PlannerInput.of(
            PlannerTaskState.of(
                text(setup, "taskId", "eval-task-" + context.runId()),
                text(setup, "taskType", "API_TEST"),
                text(setup, "taskStatus", "EXECUTING"),
                text(setup, "currentStepId", "step-1"),
                list(setup.get("remainingStepTypes"))
            ),
            AgentTaskPhase.ANY,
            AgentWorkflowMode.SEMI_AUTOMATIC,
            LastStepOutcomeSnapshot.none(),
            ContextBundleSummary.of("Evaluation planner fixture context", List.of(), 0, 0, 600),
            List.of(),
            constraints
        );
    }

    private Map<String, Object> actualSummary(PlanDecision decision) {
        var actual = new LinkedHashMap<String, Object>();
        actual.put("plannerStatus", decision.status().name());
        actual.put("plannerAction", decision.action().name());
        actual.put("proposedStepType", decision.proposedPlanStep() == null ? null : decision.proposedPlanStep().stepType());
        actual.put("proposedToolName", decision.proposedToolName());
        actual.put("confidence", decision.confidence());
        actual.put("riskLevel", decision.riskLevel().name());
        actual.put("blockers", decision.blockers());
        actual.put("requiredHumanInputFields", humanInputFields(decision));
        actual.put("fakeProvider", decision.fakeProvider());
        return actual;
    }

    private List<String> mismatches(Map<String, Object> expected, PlanDecision decision) {
        var mismatches = new ArrayList<String>();
        compareText(expected, "plannerStatus", decision.status().name(), "planner status mismatch", mismatches);
        compareText(expected, "plannerAction", decision.action().name(), "action mismatch", mismatches);
        compareText(
            expected,
            "proposedStepType",
            decision.proposedPlanStep() == null ? null : decision.proposedPlanStep().stepType(),
            "proposed step mismatch",
            mismatches
        );
        compareText(expected, "proposedToolName", decision.proposedToolName(), "tool mismatch", mismatches);
        compareText(expected, "riskLevel", decision.riskLevel().name(), "risk mismatch", mismatches);
        compareListContains(expected, "blockers", decision.blockers(), "blocker mismatch", mismatches);
        compareListContains(
            expected,
            "requiredHumanInputFields",
            humanInputFields(decision),
            "human input mismatch",
            mismatches
        );
        compareConfidence(expected, decision.confidence(), mismatches);
        if (decision.failed() && !"FAILED".equals(expected.get("plannerStatus"))) {
            mismatches.add("planner failed decision: " + decision.blockers());
        }
        if (decision.blocked() && !"BLOCKED".equals(expected.get("plannerStatus"))) {
            mismatches.add("planner blocked decision: " + decision.blockers());
        }
        return mismatches;
    }

    private double score(Map<String, Object> expected, List<String> mismatches) {
        var checks = expected.keySet().stream()
            .filter(key -> List.of(
                "plannerStatus",
                "plannerAction",
                "proposedStepType",
                "proposedToolName",
                "confidenceMin",
                "confidenceMax",
                "riskLevel",
                "blockers",
                "requiredHumanInputFields"
            ).contains(key))
            .count();
        if (checks == 0) {
            return mismatches.isEmpty() ? 1.0d : 0.0d;
        }
        return Math.max(0.0d, Math.round(((checks - mismatches.size()) / (double) checks) * 10000.0d) / 10000.0d);
    }

    private void compareText(
        Map<String, Object> expected,
        String key,
        String actual,
        String label,
        List<String> mismatches
    ) {
        if (!expected.containsKey(key)) {
            return;
        }
        var expectedValue = String.valueOf(expected.get(key));
        if (!equalsIgnoreCase(expectedValue, actual)) {
            mismatches.add(label + ": expected " + expectedValue + " but was " + actual);
        }
    }

    private void compareListContains(
        Map<String, Object> expected,
        String key,
        List<String> actual,
        String label,
        List<String> mismatches
    ) {
        if (!expected.containsKey(key)) {
            return;
        }
        var expectedValues = list(expected.get(key));
        var actualValues = actual.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
        var missing = expectedValues.stream()
            .filter(value -> !actualValues.contains(value.toLowerCase(Locale.ROOT)))
            .toList();
        if (!missing.isEmpty()) {
            mismatches.add(label + ": missing " + missing + " from " + actual);
        }
    }

    private void compareConfidence(Map<String, Object> expected, double actualConfidence, List<String> mismatches) {
        var min = number(expected.get("confidenceMin"));
        var max = number(expected.get("confidenceMax"));
        if (min != null && actualConfidence < min) {
            mismatches.add("confidence mismatch: expected >= " + min + " but was " + actualConfidence);
        }
        if (max != null && actualConfidence > max) {
            mismatches.add("confidence mismatch: expected <= " + max + " but was " + actualConfidence);
        }
    }

    private List<String> humanInputFields(PlanDecision decision) {
        if (decision.requiredHumanInput() == null) {
            return List.of();
        }
        return decision.requiredHumanInput().inputSchema().stream()
            .map(field -> field.name())
            .toList();
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

    private String text(Map<String, Object> values, String key, String fallback) {
        var value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private boolean equalsIgnoreCase(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return right != null && left.equalsIgnoreCase(right);
    }
}
