package com.probeflow.testagent.controlledplanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import org.junit.jupiter.api.Test;

class PlanDecisionParserTests {

    private final PlanDecisionParser parser = new PlanDecisionParser(new ObjectMapper());

    @Test
    void parsesStructuredContinueDecision() {
        var decision = parser.parse("""
            {
              "action": "CONTINUE",
              "reasoning": "Existing V1 plan remains valid.",
              "confidence": 0.82,
              "riskLevel": "LOW"
            }
            """);

        assertThat(decision.proposed()).isTrue();
        assertThat(decision.action()).isEqualTo(PlannerAction.CONTINUE);
        assertThat(decision.reasoning()).isEqualTo("Existing V1 plan remains valid.");
        assertThat(decision.confidence()).isEqualTo(0.82d);
        assertThat(decision.riskLevel()).isEqualTo(ToolRiskLevel.LOW);
    }

    @Test
    void parsesInsertStepAndPreservesUnknownToolNameForFutureValidation() {
        var decision = parser.parse("""
            {
              "action": "INSERT_STEP",
              "reasoning": "A missing intermediate tool-backed step was identified.",
              "confidence": 0.64,
              "risk_level": "MEDIUM",
              "proposed_tool_name": "unknown.future-tool",
              "proposed_plan_step": {
                "step_type": "FUTURE_TOOL_STEP",
                "title": "Use future tool",
                "description": "The later policy validator must reject or allow this.",
                "proposed_tool_name": "unknown.future-tool"
              }
            }
            """);

        assertThat(decision.proposed()).isTrue();
        assertThat(decision.action()).isEqualTo(PlannerAction.INSERT_STEP);
        assertThat(decision.proposedToolName()).isEqualTo("unknown.future-tool");
        assertThat(decision.proposedPlanStep().stepType()).isEqualTo("FUTURE_TOOL_STEP");
        assertThat(decision.proposedPlanStep().proposedToolName()).isEqualTo("unknown.future-tool");
        assertThat(decision.riskLevel()).isEqualTo(ToolRiskLevel.MEDIUM);
    }

    @Test
    void parsesReplanWaitForHumanAndStopDecisions() {
        var replan = parser.parse("""
            {
              "action": "REPLAN",
              "reasoning": "The previous step changed the remaining work.",
              "confidence": 0.58,
              "risk": "HIGH",
              "blockers": ["execution failed", "context stale"]
            }
            """);
        var wait = parser.parse("""
            {
              "action": "WAIT_FOR_HUMAN",
              "reasoning": "The target environment is ambiguous.",
              "confidence": 0.33,
              "riskLevel": "HIGH",
              "requiredHumanInput": {
                "reason": "Missing deployment target",
                "question": "Which environment should this task plan against?",
                "blocking": true,
                "inputSchema": [
                  {"name": "environment", "type": "string", "description": "Environment name", "required": true}
                ]
              }
            }
            """);
        var stop = parser.parse("""
            {
              "action": "STOP",
              "reasoning": "The task is complete.",
              "confidence": 0.97,
              "riskLevel": "LOW",
              "blockers": []
            }
            """);

        assertThat(replan.action()).isEqualTo(PlannerAction.REPLAN);
        assertThat(replan.blockers()).containsExactly("execution failed", "context stale");
        assertThat(wait.action()).isEqualTo(PlannerAction.WAIT_FOR_HUMAN);
        assertThat(wait.requiredHumanInput().question()).contains("Which environment");
        assertThat(wait.requiredHumanInput().inputSchema())
            .extracting(HumanInputField::name)
            .containsExactly("environment");
        assertThat(stop.action()).isEqualTo(PlannerAction.STOP);
        assertThat(stop.reasoning()).isEqualTo("The task is complete.");
    }

    @Test
    void unknownActionReturnsSafeFailedDecision() {
        var decision = parser.parse("""
            {
              "action": "RUN_TOOL_NOW",
              "reasoning": "Execute immediately.",
              "confidence": 0.9,
              "riskLevel": "CRITICAL"
            }
            """);

        assertThat(decision.failed()).isTrue();
        assertThat(decision.action()).isEqualTo(PlannerAction.STOP);
        assertThat(decision.blockers()).containsExactly("UNKNOWN_ACTION");
    }

    @Test
    void missingRequiredFieldsReturnSafeFailedDecision() {
        var decision = parser.parse("""
            {
              "action": "CONTINUE",
              "riskLevel": "LOW"
            }
            """);

        assertThat(decision.failed()).isTrue();
        assertThat(decision.action()).isEqualTo(PlannerAction.STOP);
        assertThat(decision.blockers()).containsExactly("MISSING_REASONING");
    }

    @Test
    void freeTextReturnsSafeFailedDecision() {
        var decision = parser.parse("We should probably continue because things look fine.");

        assertThat(decision.failed()).isTrue();
        assertThat(decision.action()).isEqualTo(PlannerAction.STOP);
        assertThat(decision.blockers()).containsExactly("NON_STRUCTURED_OUTPUT");
    }

    @Test
    void waitForHumanWithoutInputDetailsReturnsSafeFailedDecision() {
        var decision = parser.parse("""
            {
              "action": "WAIT_FOR_HUMAN",
              "reasoning": "Need a person.",
              "confidence": 0.2,
              "riskLevel": "HIGH"
            }
            """);

        assertThat(decision.failed()).isTrue();
        assertThat(decision.action()).isEqualTo(PlannerAction.STOP);
        assertThat(decision.blockers()).containsExactly("MISSING_HUMAN_INPUT");
    }
}
