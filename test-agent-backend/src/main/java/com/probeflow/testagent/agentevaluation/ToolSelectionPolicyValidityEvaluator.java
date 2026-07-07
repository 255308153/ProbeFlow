package com.probeflow.testagent.agentevaluation;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.AgentWorkflowMode;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolCatalogService;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import com.probeflow.testagent.agentpolicy.ToolName;
import com.probeflow.testagent.agentpolicy.ToolPrecondition;
import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.controlledplanner.ContextBundleSummary;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlannerInput;
import com.probeflow.testagent.controlledplanner.PlannerInputFactory;
import com.probeflow.testagent.controlledplanner.PlannerInputRequest;
import com.probeflow.testagent.controlledplanner.PlannerTaskState;
import com.probeflow.testagent.controlledplanner.ProposedPlanStep;
import com.probeflow.testagent.policyvalidator.PolicyValidationRequest;
import com.probeflow.testagent.policyvalidator.PolicyValidatorService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ToolSelectionPolicyValidityEvaluator implements AgentEvaluationEvaluator {

    public static final String METRIC_NAME = "policy-validation";

    private final ToolContractRegistry registry;
    private final AgentPolicyService policyService;
    private final PolicyValidatorService validator;

    public ToolSelectionPolicyValidityEvaluator(
        ToolContractRegistry registry,
        AgentPolicyService policyService,
        PolicyValidatorService validator
    ) {
        this.registry = registry;
        this.policyService = policyService;
        this.validator = validator;
    }

    @Override
    public String capability() {
        return METRIC_NAME;
    }

    @Override
    public boolean supports(GoldenTaskFixture fixture) {
        return fixture.fixtureType() == EvaluationFixtureType.TOOL_POLICY;
    }

    @Override
    public EvaluationCaseResult evaluate(
        EvaluationDataset dataset,
        GoldenTaskFixture fixture,
        EvaluationRunContext context
    ) {
        var setup = fixture.setupMetadata();
        var toolName = text(setup, "toolName", text(fixture.expectedResults(), "toolName", "knowledge.retrieve-context"));
        var policy = policy(setup);
        var decision = PlanDecision.insertStep(
            "Evaluation fixture proposed tool " + toolName,
            number(setup.get("confidence"), 0.82d),
            riskLevel(setup.get("riskLevel")),
            toolName,
            ProposedPlanStep.of("USE_TOOL", "Use " + toolName, "Evaluation fixture tool proposal.", toolName)
        );
        var result = validator.validate(new PolicyValidationRequest(
            decision,
            plannerInput(setup, policy),
            policy,
            toolInput(setup),
            preconditions(setup)
        ));

        var expected = fixture.expectedResults();
        var actual = new LinkedHashMap<String, Object>();
        actual.put("toolName", toolName);
        actual.put("policyStatus", result.status().name());
        actual.put("policyReason", result.reasonCode().name());
        actual.put("blockers", result.blockers());
        actual.put("message", result.message());

        var mismatches = new ArrayList<String>();
        compare(expected, "toolName", toolName, "tool mismatch", mismatches);
        compare(expected, "policyStatus", result.status().name(), "policy status mismatch", mismatches);
        compare(expected, "policyReason", result.reasonCode().name(), "policy rejection reason mismatch", mismatches);
        var passed = mismatches.isEmpty();
        var diagnostic = passed
            ? "PolicyValidator accepted the expected tool policy outcome."
            : String.join("; ", mismatches);
        var metric = new EvaluationMetricResult(
            METRIC_NAME,
            passed ? 1.0d : 0.0d,
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
                "Policy validation returned " + result.status() + " " + result.reasonCode(),
                expected.toString(),
                List.of(metric)
            );
        }
        return EvaluationCaseResult.failed(
            fixture.fixtureId(),
            fixture.capabilityTags(),
            "Policy validation returned " + result.status() + " " + result.reasonCode(),
            expected.toString(),
            mismatches,
            List.of(metric)
        );
    }

    private PlannerInput plannerInput(Map<String, Object> setup, AgentPolicy policy) {
        var input = new PlannerInputFactory(new PlannerSafeToolCatalogService(registry, policyService)).build(
            new PlannerInputRequest(
                PlannerTaskState.of("task-policy-eval", "API_TEST", "PLANNING", "step-1", List.of("USE_TOOL")),
                policy,
                null,
                ContextBundleSummary.of("Evaluation tool policy context", List.of("eval/tool-policy"), 1, 0, 200),
                List.of()
            )
        );
        if (!setup.containsKey("visibleToolNames")) {
            return input;
        }
        var visible = new LinkedHashSet<>(list(setup.get("visibleToolNames")));
        return new PlannerInput(
            input.planningTraceId(),
            input.taskState(),
            input.currentPhase(),
            input.workflowMode(),
            input.lastStepOutcome(),
            input.contextSummary(),
            input.availableTools().stream()
                .filter(tool -> visible.contains(tool.name()))
                .toList(),
            input.constraints()
        );
    }

    private AgentPolicy policy(Map<String, Object> setup) {
        var policy = AgentPolicy.v2Phase2Default()
            .withTaskPhase(taskPhase(setup.get("taskPhase")))
            .withWorkflowMode(workflowMode(setup.get("workflowMode")));
        if (setup.containsKey("whitelistedTools")) {
            var names = list(setup.get("whitelistedTools")).stream()
                .map(ToolName::of)
                .collect(java.util.stream.Collectors.toSet());
            policy = policy.withWhitelistedTools(names);
        }
        return policy;
    }

    private Map<String, Object> toolInput(Map<String, Object> setup) {
        var value = setup.get("toolInput");
        if (value instanceof Map<?, ?> map) {
            var copied = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> copied.put(String.valueOf(key), item));
            return copied;
        }
        return Map.of("taskId", "task-1", "query", "auth boundary");
    }

    private Set<ToolPrecondition> preconditions(Map<String, Object> setup) {
        return list(setup.get("satisfiedPreconditions")).stream()
            .map(value -> ToolPrecondition.valueOf(value.toUpperCase(Locale.ROOT)))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private void compare(
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
        if (!expectedValue.equalsIgnoreCase(actual)) {
            mismatches.add(label + ": expected " + expectedValue + " but was " + actual);
        }
    }

    private AgentTaskPhase taskPhase(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return AgentTaskPhase.ANY;
        }
        return AgentTaskPhase.valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
    }

    private AgentWorkflowMode workflowMode(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return AgentWorkflowMode.SEMI_AUTOMATIC;
        }
        return AgentWorkflowMode.valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
    }

    private ToolRiskLevel riskLevel(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return ToolRiskLevel.LOW;
        }
        return ToolRiskLevel.valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private String text(Map<String, Object> values, String key, String fallback) {
        var value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
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
