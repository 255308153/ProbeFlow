package com.probeflow.testagent.policyvalidator;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolCatalogService;
import com.probeflow.testagent.agentpolicy.ToolCapabilityGroup;
import com.probeflow.testagent.agentpolicy.ToolContract;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import com.probeflow.testagent.agentpolicy.ToolExecutionMode;
import com.probeflow.testagent.agentpolicy.ToolInputSchema;
import com.probeflow.testagent.agentpolicy.ToolName;
import com.probeflow.testagent.agentpolicy.ToolNames;
import com.probeflow.testagent.agentpolicy.ToolOutputSchema;
import com.probeflow.testagent.agentpolicy.ToolPrecondition;
import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.agentpolicy.ToolSchemaField;
import com.probeflow.testagent.agentpolicy.ToolSchemaType;
import com.probeflow.testagent.controlledplanner.ContextBundleSummary;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlannerInput;
import com.probeflow.testagent.controlledplanner.PlannerInputFactory;
import com.probeflow.testagent.controlledplanner.PlannerInputRequest;
import com.probeflow.testagent.controlledplanner.PlannerTaskState;
import com.probeflow.testagent.controlledplanner.ProposedPlanStep;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyValidatorToolPolicyValidationTests {

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);
    private final PolicyValidatorService validator = new PolicyValidatorService(registry, policyService);

    @Test
    void unknownOrInvalidToolNameIsBlockedBeforeVisibilityOrPolicyChecks() {
        var result = validate(
            insertStep("imaginary.make-cases"),
            input(AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.TEST_DESIGN)),
            AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.TEST_DESIGN),
            Map.of("taskId", "task-1"),
            Set.of(ToolPrecondition.TASK_EXISTS)
        );

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.UNKNOWN_TOOL);
        assertThat(result.blockers()).containsExactly("UNKNOWN_TOOL");
    }

    @Test
    void registeredToolNotVisibleInPlannerInputIsBlocked() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING);
        var input = withoutVisibleTool(input(policy), "knowledge.retrieve-context");

        var result = validate(
            insertStep("knowledge.retrieve-context"),
            input,
            policy,
            knowledgeInput(),
            Set.of(ToolPrecondition.TASK_EXISTS)
        );

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.TOOL_NOT_VISIBLE);
        assertThat(result.blockers()).containsExactly("TOOL_NOT_VISIBLE");
    }

    @Test
    void toolNotWhitelistedByAgentPolicyIsBlocked() {
        var policy = AgentPolicy.v2Phase2Default()
            .withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING)
            .withWhitelistedTools(Set.of(ToolNames.MEMORY_BUILD_CONTEXT));

        var result = validate(
            insertStep("knowledge.retrieve-context"),
            input(policy),
            policy,
            knowledgeInput(),
            Set.of(ToolPrecondition.TASK_EXISTS)
        );

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.TOOL_NOT_WHITELISTED);
        assertThat(result.blockers()).containsExactly("TOOL_NOT_WHITELISTED");
    }

    @Test
    void toolNotAllowedInCurrentTaskPhaseIsBlocked() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.TEST_DESIGN);

        var result = validate(
            insertStep("knowledge.retrieve-context"),
            input(policy),
            policy,
            knowledgeInput(),
            Set.of(ToolPrecondition.TASK_EXISTS)
        );

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.TOOL_NOT_ALLOWED_IN_TASK_PHASE);
        assertThat(result.blockers()).containsExactly("TOOL_NOT_ALLOWED_IN_TASK_PHASE");
    }

    @Test
    void contractBlockedToolIsBlockedByAgentPolicyService() {
        var blockedTool = blockedTool();
        var customRegistry = new ToolContractRegistry(List.of(blockedTool));
        var customPolicyService = new AgentPolicyService(customRegistry);
        var customValidator = new PolicyValidatorService(customRegistry, customPolicyService);
        var policy = new AgentPolicy(
            Set.of(blockedTool.name()),
            null,
            AgentTaskPhase.CONTEXT_BUILDING,
            true
        );

        var result = customValidator.validate(new PolicyValidationRequest(
            insertStep(blockedTool.name().value()),
            input(customRegistry, customPolicyService, policy),
            policy,
            Map.of("taskId", "task-1"),
            Set.of(ToolPrecondition.TASK_EXISTS)
        ));

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.TOOL_BLOCKED_BY_CONTRACT);
        assertThat(result.blockers()).containsExactly("TOOL_BLOCKED_BY_CONTRACT");
    }

    @Test
    void toolInputSchemaFailuresAreBlockedWithStableReasonCodes() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.TEST_DESIGN);
        var input = input(policy);
        var decision = insertStep("testcase.generate-drafts");

        assertThat(validate(
            decision,
            input,
            policy,
            Map.of("taskId", "task-1", "generationMode", "SINGLE"),
            Set.of(ToolPrecondition.TASK_EXISTS, ToolPrecondition.API_SPEC_AVAILABLE, ToolPrecondition.CONTEXT_BUNDLE_AVAILABLE)
        ).reasonCode()).isEqualTo(PolicyValidationReasonCode.MISSING_REQUIRED_INPUT);

        assertThat(validate(
            decision,
            input,
            policy,
            Map.of("taskId", 42, "apiSpecId", "api-1", "generationMode", "SINGLE"),
            Set.of(ToolPrecondition.TASK_EXISTS, ToolPrecondition.API_SPEC_AVAILABLE, ToolPrecondition.CONTEXT_BUNDLE_AVAILABLE)
        ).reasonCode()).isEqualTo(PolicyValidationReasonCode.INVALID_INPUT_TYPE);

        assertThat(validate(
            decision,
            input,
            policy,
            Map.of("taskId", "task-1", "apiSpecId", "api-1", "generationMode", "ODD"),
            Set.of(ToolPrecondition.TASK_EXISTS, ToolPrecondition.API_SPEC_AVAILABLE, ToolPrecondition.CONTEXT_BUNDLE_AVAILABLE)
        ).reasonCode()).isEqualTo(PolicyValidationReasonCode.INVALID_INPUT_VALUE);
    }

    @Test
    void missingRequiredToolPreconditionIsBlocked() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.TEST_DESIGN);

        var result = validate(
            insertStep("testcase.generate-drafts"),
            input(policy),
            policy,
            Map.of("taskId", "task-1", "apiSpecId", "api-1", "generationMode", "SINGLE"),
            Set.of(ToolPrecondition.TASK_EXISTS, ToolPrecondition.API_SPEC_AVAILABLE)
        );

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.MISSING_PRECONDITION);
        assertThat(result.blockers()).containsExactly("MISSING_PRECONDITION");
    }

    @Test
    void legalVisibleWhitelistedToolWithValidInputAndPreconditionsIsAllowed() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING);

        var result = validate(
            insertStep("knowledge.retrieve-context"),
            input(policy),
            policy,
            knowledgeInput(),
            Set.of(ToolPrecondition.TASK_EXISTS)
        );

        assertThat(result.allowed()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.TOOL_ALLOWED_BY_POLICY);
        assertThat(result.proposedToolName()).isEqualTo("knowledge.retrieve-context");
        assertThat(result.auditSummary()).containsEntry("proposedToolName", "knowledge.retrieve-context");
    }

    private PolicyValidationResult validate(
        PlanDecision decision,
        PlannerInput input,
        AgentPolicy policy,
        Map<String, Object> toolInput,
        Set<ToolPrecondition> preconditions
    ) {
        return validator.validate(new PolicyValidationRequest(decision, input, policy, toolInput, preconditions));
    }

    private PlanDecision insertStep(String toolName) {
        return PlanDecision.insertStep(
            "Need tool-backed planning step.",
            0.82d,
            ToolRiskLevel.LOW,
            toolName,
            ProposedPlanStep.of("USE_TOOL", "Use " + toolName, "Planner recommends the registered tool.", toolName)
        );
    }

    private PlannerInput input(AgentPolicy policy) {
        return input(registry, policyService, policy);
    }

    private PlannerInput input(ToolContractRegistry toolRegistry, AgentPolicyService toolPolicyService, AgentPolicy policy) {
        return new PlannerInputFactory(new PlannerSafeToolCatalogService(toolRegistry, toolPolicyService)).build(
            new PlannerInputRequest(
                PlannerTaskState.of("task-policy-tool", "API_TEST", "PLANNING", "step-1", List.of("USE_TOOL")),
                policy,
                null,
                ContextBundleSummary.of("Tool policy validation context", List.of("wiki/tool-policy"), 1, 0, 200),
                List.of()
            )
        );
    }

    private PlannerInput withoutVisibleTool(PlannerInput input, String toolName) {
        return new PlannerInput(
            input.planningTraceId(),
            input.taskState(),
            input.currentPhase(),
            input.workflowMode(),
            input.lastStepOutcome(),
            input.contextSummary(),
            input.availableTools().stream()
                .filter(tool -> !tool.name().equals(toolName))
                .toList(),
            input.constraints()
        );
    }

    private Map<String, Object> knowledgeInput() {
        return Map.of("taskId", "task-1", "query", "auth boundary");
    }

    private ToolContract blockedTool() {
        return ToolContract.of(
            ToolName.of("internal.blocked-tool"),
            ToolCapabilityGroup.KNOWLEDGE,
            "Blocked internal test tool.",
            ToolInputSchema.of("Blocked test input.", List.of(
                ToolSchemaField.required("taskId", ToolSchemaType.STRING, "Task id.")
            ), "{\"taskId\":\"task-1\"}"),
            ToolOutputSchema.of("No output.", List.of()),
            Set.of(ToolPrecondition.TASK_EXISTS),
            ToolRiskLevel.MEDIUM,
            ToolExecutionMode.BLOCKED,
            false,
            Set.of("internal")
        );
    }
}
