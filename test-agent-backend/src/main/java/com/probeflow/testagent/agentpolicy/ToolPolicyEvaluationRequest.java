package com.probeflow.testagent.agentpolicy;

import java.util.Map;
import java.util.Set;

public record ToolPolicyEvaluationRequest(
    ToolName toolName,
    AgentPolicy policy,
    Map<String, Object> input,
    Set<ToolPrecondition> satisfiedPreconditions
) {

    public ToolPolicyEvaluationRequest {
        if (toolName == null) {
            throw new IllegalArgumentException("tool name is required");
        }
        policy = policy == null ? AgentPolicy.v2Phase2Default() : policy;
        input = input == null ? Map.of() : Map.copyOf(input);
        satisfiedPreconditions = satisfiedPreconditions == null ? Set.of() : Set.copyOf(satisfiedPreconditions);
    }

    public static ToolPolicyEvaluationRequest of(
        ToolName toolName,
        AgentPolicy policy,
        Map<String, Object> input,
        Set<ToolPrecondition> satisfiedPreconditions
    ) {
        return new ToolPolicyEvaluationRequest(toolName, policy, input, satisfiedPreconditions);
    }
}
