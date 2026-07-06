package com.probeflow.testagent.policyvalidator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolCatalogService;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import com.probeflow.testagent.controlledplanner.ContextBundleSummary;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlannerAction;
import com.probeflow.testagent.controlledplanner.PlannerInput;
import com.probeflow.testagent.controlledplanner.PlannerInputFactory;
import com.probeflow.testagent.controlledplanner.PlannerInputRequest;
import com.probeflow.testagent.controlledplanner.PlannerTaskState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyValidationResultContractTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);
    private final PlannerInputFactory inputFactory = new PlannerInputFactory(
        new PlannerSafeToolCatalogService(registry, policyService)
    );
    private final PolicyValidatorService validator = new PolicyValidatorService();

    @Test
    void safeContinueDecisionReturnsAllowedResultWithStableReasonCode() {
        var decision = PlanDecision.continuePlan("Continue deterministic workflow", 0.91d)
            .withSourceLlmCall("llm-call-continue", true);

        var result = validator.validate(new PolicyValidationRequest(decision, input(), AgentPolicy.v2Phase2Default()));

        assertThat(result.allowed()).isTrue();
        assertThat(result.status()).isEqualTo(PolicyValidationStatus.ALLOWED);
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.SAFE_CONTINUE);
        assertThat(result.message()).contains("continue");
        assertThat(result.blockers()).isEmpty();
        assertThat(result.decisionId()).isEqualTo(decision.decisionId());
        assertThat(result.plannerAction()).isEqualTo(PlannerAction.CONTINUE);
        assertThat(result.proposedToolName()).isNull();
        assertThat(result.sourceLlmCallId()).isEqualTo("llm-call-continue");
    }

    @Test
    void auditSummaryLinksPlannerDecisionLlmCallActionStatusAndReasonCode() {
        var decision = PlanDecision.continuePlan("Continue deterministic workflow", 0.88d)
            .withSourceLlmCall("llm-call-audit", true);

        var result = validator.validate(new PolicyValidationRequest(decision, input(), AgentPolicy.v2Phase2Default()));
        var summary = result.auditSummary();

        assertThat(summary)
            .containsEntry("decisionId", decision.decisionId())
            .containsEntry("sourceLlmCallId", "llm-call-audit")
            .containsEntry("plannerAction", "CONTINUE")
            .containsEntry("proposedToolName", null)
            .containsEntry("validationStatus", "ALLOWED")
            .containsEntry("reasonCode", "SAFE_CONTINUE");
        assertThatThrownBy(() -> summary.put("reasonCode", "MUTATED"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullRequestOrMissingDecisionFailsClearly() {
        assertThatThrownBy(() -> validator.validate(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("policy validation request is required");

        assertThatThrownBy(() -> new PolicyValidationRequest(null, input(), AgentPolicy.v2Phase2Default()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("planner decision is required");
    }

    @Test
    void policyValidatorDoesNotCallLlmExecuteToolsOrPersistDomainState() throws Exception {
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
            .doesNotContain("TaskMemoryService")
            .doesNotContain("ReportGenerationApplicationService")
            .doesNotContain(".save(")
            .doesNotContain(".delete(");
    }

    private PlannerInput input() {
        return inputFactory.build(new PlannerInputRequest(
            PlannerTaskState.of("task-policy", "API_TEST", "ANALYZING", "step-1", List.of("CONTINUE")),
            AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.ANY),
            null,
            ContextBundleSummary.of("Policy validation compact context", List.of("wiki/policy"), 1, 0, 200),
            List.of()
        ));
    }
}
