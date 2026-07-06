package com.probeflow.testagent.controlledplanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanDecisionDomainModelTests {

    @Test
    void supportsFivePlannerActionsAsStructuredDecisions() {
        var proposedStep = ProposedPlanStep.of(
            "RETRIEVE_KNOWLEDGE",
            "Retrieve auth notes",
            "Fetch context before generating cases.",
            "knowledge.retrieve-context"
        );
        var humanInput = RequiredHumanInput.blocking(
            "Missing environment",
            "Which test environment should be used?",
            List.of(HumanInputField.required("targetEnvironment", "string", "Configured environment name."))
        );

        assertThat(PlanDecision.continuePlan("Existing plan remains valid.", 0.9d).action())
            .isEqualTo(PlannerAction.CONTINUE);
        assertThat(PlanDecision.insertStep("Knowledge is missing.", 0.8d, ToolRiskLevel.LOW, "knowledge.retrieve-context", proposedStep).action())
            .isEqualTo(PlannerAction.INSERT_STEP);
        assertThat(PlanDecision.replan("Previous execution changed task state.", 0.55d, ToolRiskLevel.MEDIUM, List.of("Execution failed")).action())
            .isEqualTo(PlannerAction.REPLAN);
        assertThat(PlanDecision.waitForHuman("Environment is ambiguous.", 0.3d, ToolRiskLevel.HIGH, humanInput).action())
            .isEqualTo(PlannerAction.WAIT_FOR_HUMAN);
        assertThat(PlanDecision.stop("Task is complete.", 1.0d, ToolRiskLevel.LOW, List.of()).action())
            .isEqualTo(PlannerAction.STOP);
    }

    @Test
    void requiredHumanInputIsActionableAndBlocking() {
        var input = RequiredHumanInput.blocking(
            "Missing credentials policy",
            "Can this task use the staging OAuth client?",
            List.of(HumanInputField.required("approval", "boolean", "Whether staging credentials may be used."))
        );
        var decision = PlanDecision.waitForHuman("Credential use needs confirmation.", 0.42d, ToolRiskLevel.HIGH, input);

        assertThat(decision.requiredHumanInput()).isEqualTo(input);
        assertThat(decision.requiredHumanInput().blocking()).isTrue();
        assertThat(decision.requiredHumanInput().inputSchema())
            .extracting(HumanInputField::name)
            .containsExactly("approval");
    }

    @Test
    void confidenceIsNormalizedAndRiskLevelUsesToolContractRiskScale() {
        var low = PlanDecision.continuePlan("Too low confidence is clamped.", -1.0d);
        var high = PlanDecision.stop("Too high confidence is clamped.", 2.0d, ToolRiskLevel.CRITICAL, List.of("Unsafe"));

        assertThat(low.confidence()).isZero();
        assertThat(high.confidence()).isEqualTo(1.0d);
        assertThat(high.riskLevel()).isEqualTo(ToolRiskLevel.CRITICAL);
    }

    @Test
    void blockedAndFailedDecisionsAreSafeStopResultsWithBlockers() {
        var blocked = PlanDecision.blocked("Planner output requested an unsafe action.", List.of("V1 boundary"));
        var failed = PlanDecision.failed("Planner output could not be parsed.", List.of("Unknown action"));

        assertThat(blocked.blocked()).isTrue();
        assertThat(blocked.action()).isEqualTo(PlannerAction.STOP);
        assertThat(blocked.confidence()).isZero();
        assertThat(blocked.blockers()).containsExactly("V1 boundary");
        assertThat(failed.failed()).isTrue();
        assertThat(failed.action()).isEqualTo(PlannerAction.STOP);
        assertThat(failed.blockers()).containsExactly("Unknown action");
    }

    @Test
    void decisionHasStableAuditSummaryAndSourceLlmMetadata() {
        var decision = PlanDecision.insertStep(
            "Need draft generation.",
            0.77d,
            ToolRiskLevel.MEDIUM,
            "testcase.generate-drafts",
            ProposedPlanStep.of("GENERATE_CASES", "Generate drafts", "Create reviewed draft candidates.", "testcase.generate-drafts")
        ).withSourceLlmCall("llm-call-1", true);

        assertThat(decision.decisionId()).startsWith("plan-");
        assertThat(decision.sourceLlmCallId()).isEqualTo("llm-call-1");
        assertThat(decision.fakeProvider()).isTrue();
        assertThat(decision.auditSummary())
            .containsEntry("action", "INSERT_STEP")
            .containsEntry("status", "PROPOSED")
            .containsEntry("riskLevel", "MEDIUM")
            .containsEntry("proposedToolName", "testcase.generate-drafts")
            .containsEntry("sourceLlmCallId", "llm-call-1")
            .containsEntry("fakeProvider", true);
    }

    @Test
    void proposedWaitForHumanRequiresHumanInputDetails() {
        assertThatThrownBy(() -> new PlanDecision(
            null,
            PlanDecisionStatus.PROPOSED,
            PlannerAction.WAIT_FOR_HUMAN,
            "Ask for missing scope.",
            0.4d,
            ToolRiskLevel.MEDIUM,
            null,
            null,
            null,
            List.of(),
            null,
            false
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("WAIT_FOR_HUMAN");
    }
}
