package com.probeflow.testagent.controlledplanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.AgentWorkflowMode;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolCatalogService;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolView;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import com.probeflow.testagent.orchestration.StepOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlannerInputFactoryTests {

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);
    private final PlannerInputFactory factory = new PlannerInputFactory(new PlannerSafeToolCatalogService(registry, policyService));

    @Test
    void buildsPlannerInputFromTaskPhaseWorkflowLastOutcomeContextAndPlannerSafeTools() {
        var policy = AgentPolicy.v2Phase2Default()
            .withTaskPhase(AgentTaskPhase.TEST_DESIGN)
            .withWorkflowMode(AgentWorkflowMode.REVIEW_REQUIRED);

        var input = factory.build(new PlannerInputRequest(
            PlannerTaskState.of(
                "task-123",
                "API_REGRESSION",
                "CASE_GENERATED",
                "step-generate",
                List.of("GENERATE_CASES", "EXECUTE_SUITE")
            ),
            policy,
            StepOutcome.succeeded("Knowledge context loaded", List.of("knowledge:1")),
            ContextBundleSummary.of("Auth notes and memory summary", List.of("wiki/auth", "memory/task"), 2, 1, 400),
            List.of(PlannerConstraint.of("SUGGEST_ONLY", "Planner can suggest decisions but cannot execute tools."))
        ));

        assertThat(input.planningTraceId()).startsWith("planner-");
        assertThat(input.taskState().taskId()).isEqualTo("task-123");
        assertThat(input.currentPhase()).isEqualTo(AgentTaskPhase.TEST_DESIGN);
        assertThat(input.workflowMode()).isEqualTo(AgentWorkflowMode.REVIEW_REQUIRED);
        assertThat(input.lastStepOutcome().stepStatus()).isEqualTo("SUCCESS");
        assertThat(input.lastStepOutcome().resultRefs()).containsExactly("knowledge:1");
        assertThat(input.contextSummary().citationRefs()).containsExactly("memory/task", "wiki/auth");
        assertThat(input.contextSummary().knowledgeHitCount()).isEqualTo(2);
        assertThat(input.availableTools()).extracting(PlannerSafeToolView::name)
            .contains("testcase.generate-drafts", "http.execute-approved-case");
        assertThat(input.constraints()).extracting(PlannerConstraint::code)
            .containsExactly("SUGGEST_ONLY");
    }

    @Test
    void plannerInputUsesPlannerSafeToolViewsWithPolicyStateInsteadOfToolContracts() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING);
        var input = factory.build(new PlannerInputRequest(
            PlannerTaskState.of("task-tools", "API_TEST", "ANALYZING", null, List.of("RETRIEVE_KNOWLEDGE")),
            policy,
            null,
            ContextBundleSummary.empty(),
            List.of()
        ));

        assertThat(input.availableTools()).allSatisfy(tool -> {
            assertThat(tool).isInstanceOf(PlannerSafeToolView.class);
            assertThat(tool.policyStatus()).isNotNull();
            assertThat(tool.policyReasonCode()).isNotNull();
        });
        assertThat(input.availableTools().stream()
            .filter(tool -> tool.name().equals("knowledge.retrieve-context"))
            .findFirst()
            .orElseThrow()
            .blocked()).isFalse();
        assertThat(input.availableTools().stream()
            .filter(tool -> tool.name().equals("testcase.generate-drafts"))
            .findFirst()
            .orElseThrow()
            .blocked()).isTrue();
    }

    @Test
    void plannerInputDoesNotExposeImplementationDetailsOrUnboundedRawContext() {
        var longRawText = "raw-context ".repeat(300);
        var input = factory.build(new PlannerInputRequest(
            PlannerTaskState.of("task-safe", "API_TEST", "PENDING", null, List.of()),
            AgentPolicy.v2Phase2Default(),
            StepOutcome.blocked("Need target env", List.of("Missing target environment")),
            ContextBundleSummary.of(longRawText, List.of("wiki/raw"), 10, 8, 700),
            List.of()
        ));
        var rendered = input.toString();

        assertThat(input.lastStepOutcome().hasBlockers()).isTrue();
        assertThat(input.contextSummary().summary()).hasSizeLessThanOrEqualTo(1_203);
        assertThat(rendered)
            .doesNotContain("ApplicationService")
            .doesNotContain("Repository")
            .doesNotContain("EntityManager")
            .doesNotContain("DataSource")
            .doesNotContain("HttpClient")
            .doesNotContain("RestTemplate")
            .doesNotContain("Spring")
            .doesNotContain("Bean")
            .doesNotContain("com.probeflow");
    }
}
