package com.probeflow.testagent.controlledplanner;

import com.probeflow.testagent.agentpolicy.PlannerSafeToolView;
import com.probeflow.testagent.llm.LlmApplicationService;
import com.probeflow.testagent.llm.LlmCallRequest;
import com.probeflow.testagent.llm.LlmExecutionOptions;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class LlmBackedControlledPlanner implements ControlledPlanner {

    public static final String TEMPLATE_ID = "v2.controlled-planner.v1";
    public static final String PURPOSE = "CONTROLLED_PLANNER";

    private final LlmApplicationService llm;
    private final PlanDecisionParser parser;

    public LlmBackedControlledPlanner(LlmApplicationService llm, PlanDecisionParser parser) {
        this.llm = llm;
        this.parser = parser;
    }

    @Override
    public PlanDecision plan(PlannerInput input) {
        if (input == null) {
            throw new IllegalArgumentException("planner input is required");
        }
        var result = llm.call(LlmCallRequest.forTemplate(
            input.taskState().taskId(),
            input.taskState().currentPlanStepId(),
            TEMPLATE_ID,
            promptVariables(input),
            new LlmExecutionOptions(null, null, 0.0d, 800, 2_000, 0)
        ));
        if (!result.succeeded()) {
            return PlanDecision.failed(
                "LLM-backed controlled planner call failed safely.",
                List.of(result.callResult().status().name(), nullToEmpty(result.callResult().errorMessage()))
            ).withSourceLlmCall(result.llmCallId(), result.callResult().fakeProvider());
        }
        var response = result.callResult().response();
        return parser.parse(response.text())
            .withSourceLlmCall(result.llmCallId(), response.fakeProvider());
    }

    Map<String, Object> promptVariables(PlannerInput input) {
        return Map.of(
            "taskState", taskState(input.taskState()),
            "currentPhase", input.currentPhase().name(),
            "workflowMode", input.workflowMode().name(),
            "lastStepOutcome", lastStepOutcome(input.lastStepOutcome()),
            "contextSummary", contextSummary(input.contextSummary()),
            "availableTools", tools(input.availableTools()),
            "constraints", constraints(input.constraints())
        );
    }

    private String taskState(PlannerTaskState state) {
        return "taskId=" + state.taskId()
            + "; type=" + nullToEmpty(state.taskType())
            + "; status=" + state.taskStatus()
            + "; currentPlanStepId=" + nullToEmpty(state.currentPlanStepId())
            + "; remainingStepTypes=" + state.remainingStepTypes();
    }

    private String lastStepOutcome(LastStepOutcomeSnapshot outcome) {
        return "stepStatus=" + nullToEmpty(outcome.stepStatus())
            + "; taskStatus=" + nullToEmpty(outcome.taskStatus())
            + "; summary=" + compact(outcome.summary())
            + "; resultRefs=" + outcome.resultRefs()
            + "; blockers=" + outcome.blockers()
            + "; stopOrchestration=" + outcome.stopOrchestration();
    }

    private String contextSummary(ContextBundleSummary summary) {
        return "summary=" + compact(summary.summary())
            + "; citationRefs=" + summary.citationRefs()
            + "; knowledgeHitCount=" + summary.knowledgeHitCount()
            + "; memoryItemCount=" + summary.memoryItemCount()
            + "; tokenBudget=" + summary.tokenBudget();
    }

    private String tools(List<PlannerSafeToolView> tools) {
        return tools.stream()
            .map(tool -> tool.name()
                + "[status=" + tool.policyStatus()
                + ",risk=" + tool.riskLevel()
                + ",mode=" + tool.executionMode()
                + ",group=" + tool.capabilityGroup()
                + "]")
            .sorted()
            .toList()
            .toString();
    }

    private String constraints(List<PlannerConstraint> constraints) {
        return constraints.stream()
            .map(constraint -> constraint.code() + "=" + constraint.description())
            .sorted()
            .toList()
            .toString();
    }

    private String compact(String value) {
        var cleaned = nullToEmpty(value).trim();
        if (cleaned.length() <= 300) {
            return cleaned;
        }
        return cleaned.substring(0, 300).trim() + "...";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
