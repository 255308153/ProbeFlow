package com.probeflow.testagent.policyvalidator;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolCatalogService;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import com.probeflow.testagent.agentpolicy.ToolPrecondition;
import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.controlledplanner.ContextBundleSummary;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlannerInput;
import com.probeflow.testagent.controlledplanner.PlannerInputFactory;
import com.probeflow.testagent.controlledplanner.PlannerInputRequest;
import com.probeflow.testagent.controlledplanner.PlannerTaskState;
import com.probeflow.testagent.controlledplanner.ProposedPlanStep;
import com.probeflow.testagent.orchestration.StepOutcome;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyValidatorServiceEndToEndCompositionTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);
    private final PolicyValidatorService validator = new PolicyValidatorService(registry, policyService);

    @Test
    void exposesSingleStableValidationEntrypoint() {
        assertThat(PolicyValidatorService.class.getDeclaredMethods())
            .filteredOn(method -> Modifier.isPublic(method.getModifiers()))
            .extracting(java.lang.reflect.Method::getName)
            .containsExactly("validate");
    }

    @Test
    void legalLowRiskPlannerDecisionReturnsAllowedWithCompleteAuditSummary() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.TEST_DESIGN);
        var decision = insertStep("testcase.generate-drafts", ToolRiskLevel.MEDIUM)
            .withSourceLlmCall("llm-call-allowed", true);

        var result = validator.validate(new PolicyValidationRequest(
            decision,
            input(policy, context(), StepOutcome.succeeded("ApiSpec created", List.of("apiSpec:api-1"))),
            policy,
            Map.of("taskId", "task-1", "apiSpecId", "api-1", "generationMode", "SINGLE"),
            Set.of()
        ));

        assertThat(result.allowed()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.TOOL_ALLOWED_BY_POLICY);
        assertThat(result.auditSummary())
            .containsEntry("decisionId", decision.decisionId())
            .containsEntry("sourceLlmCallId", "llm-call-allowed")
            .containsEntry("plannerAction", "INSERT_STEP")
            .containsEntry("proposedToolName", "testcase.generate-drafts")
            .containsEntry("validationStatus", "ALLOWED")
            .containsEntry("reasonCode", "TOOL_ALLOWED_BY_POLICY");
    }

    @Test
    void humanConfirmationPlannerDecisionReturnsConfirmationResultWithReason() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.EXECUTION);
        var decision = insertStep("http.execute-approved-case", ToolRiskLevel.LOW)
            .withSourceLlmCall("llm-call-human", true);

        var result = validator.validate(new PolicyValidationRequest(
            decision,
            input(policy, context(), StepOutcome.succeeded("Case approved", List.of("case-1", "reviewed"))),
            policy,
            Map.of("taskId", "task-1", "testCaseId", "case-1", "targetEnvironment", "local"),
            Set.of(
                ToolPrecondition.TASK_EXISTS,
                ToolPrecondition.TEST_CASE_EXISTS,
                ToolPrecondition.TEST_CASE_DRAFT_REVIEWED,
                ToolPrecondition.EXECUTION_READINESS_CONFIRMED
            )
        ));

        assertThat(result.requiresHumanConfirmation()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.HUMAN_CONFIRMATION_REQUIRED);
        assertThat(result.blockers()).containsExactly("HUMAN_CONFIRMATION_REQUIRED");
        assertThat(result.auditSummary())
            .containsEntry("sourceLlmCallId", "llm-call-human")
            .containsEntry("validationStatus", "REQUIRES_HUMAN_CONFIRMATION")
            .containsEntry("reasonCode", "HUMAN_CONFIRMATION_REQUIRED");
    }

    @Test
    void severeBoundaryViolationReturnsBlockedAndIsNotOverriddenByLaterAllowedBranches() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.ANY);
        var decision = insertStep("browser.run-playwright", ToolRiskLevel.LOW)
            .withSourceLlmCall("llm-call-blocked", true);

        var result = validator.validate(new PolicyValidationRequest(
            decision,
            input(policy, context(), StepOutcome.succeeded("Context loaded", List.of("context-1"))),
            policy,
            Map.of("taskId", "task-1"),
            Set.of()
        ));

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.V1_BOUNDARY_BLOCKED);
        assertThat(result.blockers()).containsExactly("V1_BOUNDARY_BLOCKED");
        assertThat(result.auditSummary())
            .containsEntry("sourceLlmCallId", "llm-call-blocked")
            .containsEntry("validationStatus", "BLOCKED")
            .containsEntry("reasonCode", "V1_BOUNDARY_BLOCKED");
    }

    @Test
    void compositionLayerDoesNotCallLlmExecuteToolsOrPersistTaskState() throws Exception {
        var source = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/policyvalidator/PolicyValidatorService.java"
        ));

        assertThat(source)
            .doesNotContain("LlmApplicationService")
            .doesNotContain("LlmProvider")
            .doesNotContain("ToolRouter")
            .doesNotContain("PlanStepRunner")
            .doesNotContain("TaskRepository")
            .doesNotContain("PlanStepRepository")
            .doesNotContain("TestCaseRepository")
            .doesNotContain("EntityManager")
            .doesNotContain("DataSource")
            .doesNotContain(".save(")
            .doesNotContain(".delete(");
    }

    private PlanDecision insertStep(String toolName, ToolRiskLevel riskLevel) {
        return PlanDecision.insertStep(
            "Need a composed policy validation path.",
            0.88d,
            riskLevel,
            toolName,
            ProposedPlanStep.of("USE_TOOL", "Use " + toolName, "Planner recommends the registered tool.", toolName)
        );
    }

    private PlannerInput input(AgentPolicy policy, ContextBundleSummary context, StepOutcome outcome) {
        return new PlannerInputFactory(new PlannerSafeToolCatalogService(registry, policyService)).build(
            new PlannerInputRequest(
                PlannerTaskState.of("task-policy-composition", "API_TEST", "PLANNING", "step-1", List.of("USE_TOOL")),
                policy,
                outcome,
                context,
                List.of()
            )
        );
    }

    private ContextBundleSummary context() {
        return ContextBundleSummary.of("Composed policy context", List.of("wiki/policy-composition"), 1, 1, 200);
    }
}
