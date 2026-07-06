package com.probeflow.testagent.agentpolicy;

import java.util.Set;

public record AgentPolicy(
    Set<ToolName> whitelistedTools,
    AgentWorkflowMode workflowMode,
    AgentTaskPhase taskPhase,
    boolean enforceV1BoundaryRules
) {

    public AgentPolicy {
        whitelistedTools = whitelistedTools == null ? Set.of() : Set.copyOf(whitelistedTools);
        workflowMode = workflowMode == null ? AgentWorkflowMode.SEMI_AUTOMATIC : workflowMode;
        taskPhase = taskPhase == null ? AgentTaskPhase.ANY : taskPhase;
    }

    public static AgentPolicy v2Phase2Default() {
        return new AgentPolicy(
            Set.of(
                ToolNames.API_ANALYZE_SOURCE,
                ToolNames.KNOWLEDGE_RETRIEVE_CONTEXT,
                ToolNames.MEMORY_BUILD_CONTEXT,
                ToolNames.TESTCASE_GENERATE_DRAFTS,
                ToolNames.TESTCASE_REVIEW_DRAFT,
                ToolNames.HTTP_EXECUTE_APPROVED_CASE,
                ToolNames.FAILURE_ANALYZE_EXECUTION,
                ToolNames.REPORT_GENERATE_TASK
            ),
            AgentWorkflowMode.SEMI_AUTOMATIC,
            AgentTaskPhase.ANY,
            true
        );
    }

    public AgentPolicy withWorkflowMode(AgentWorkflowMode workflowMode) {
        return new AgentPolicy(whitelistedTools, workflowMode, taskPhase, enforceV1BoundaryRules);
    }

    public AgentPolicy withTaskPhase(AgentTaskPhase taskPhase) {
        return new AgentPolicy(whitelistedTools, workflowMode, taskPhase, enforceV1BoundaryRules);
    }

    public AgentPolicy withWhitelistedTools(Set<ToolName> whitelistedTools) {
        return new AgentPolicy(whitelistedTools, workflowMode, taskPhase, enforceV1BoundaryRules);
    }

    public boolean isWhitelisted(ToolName toolName) {
        return whitelistedTools.contains(toolName);
    }
}
