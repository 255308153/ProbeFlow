package com.probeflow.testagent.agentpolicy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolSchemaPreconditionValidationTests {

    private final AgentPolicyService policyService = new AgentPolicyService(new ToolContractRegistry());
    private final AgentPolicy policy = AgentPolicy.v2Phase2Default().withWorkflowMode(AgentWorkflowMode.AUTOMATIC);

    @Test
    void missingRequiredInputBlocksBeforeAnyExecutionCanHappen() {
        var decision = policyService.evaluate(ToolPolicyEvaluationRequest.of(
            ToolNames.TESTCASE_GENERATE_DRAFTS,
            policy,
            Map.of("taskId", "task-1", "generationMode", "SINGLE"),
            Set.of(
                ToolPrecondition.TASK_EXISTS,
                ToolPrecondition.API_SPEC_AVAILABLE,
                ToolPrecondition.CONTEXT_BUNDLE_AVAILABLE
            )
        ));

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.MISSING_REQUIRED_INPUT);
        assertThat(decision.message()).contains("apiSpecId");
    }

    @Test
    void invalidInputTypeIsBlockedWithStructuredReasonCode() {
        var decision = policyService.evaluate(ToolPolicyEvaluationRequest.of(
            ToolNames.API_ANALYZE_SOURCE,
            policy,
            Map.of("taskId", "task-1", "sourceMaterialId", 42),
            Set.of(ToolPrecondition.TASK_EXISTS, ToolPrecondition.SOURCE_MATERIAL_AVAILABLE)
        ));

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.INVALID_INPUT_TYPE);
        assertThat(decision.message()).contains("sourceMaterialId", "STRING");
    }

    @Test
    void invalidEnumInputValueIsBlockedWithStructuredReasonCode() {
        var decision = policyService.evaluate(ToolPolicyEvaluationRequest.of(
            ToolNames.TESTCASE_GENERATE_DRAFTS,
            policy,
            Map.of("taskId", "task-1", "apiSpecId", "api-1", "generationMode", "RANDOM"),
            Set.of(
                ToolPrecondition.TASK_EXISTS,
                ToolPrecondition.API_SPEC_AVAILABLE,
                ToolPrecondition.CONTEXT_BUNDLE_AVAILABLE
            )
        ));

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.INVALID_INPUT_VALUE);
        assertThat(decision.message()).contains("generationMode", "RANDOM");
    }

    @Test
    void missingTaskPreconditionBlocksEvenWhenInputShapeIsValid() {
        var decision = policyService.evaluate(ToolPolicyEvaluationRequest.of(
            ToolNames.KNOWLEDGE_RETRIEVE_CONTEXT,
            policy,
            Map.of("taskId", "task-1", "query", "auth boundary"),
            Set.of()
        ));

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.MISSING_PRECONDITION);
        assertThat(decision.message()).contains("TASK_EXISTS");
    }

    @Test
    void missingApiSpecBlocksToolsThatDependOnApiAnalysisResult() {
        var decision = policyService.evaluate(ToolPolicyEvaluationRequest.of(
            ToolNames.TESTCASE_GENERATE_DRAFTS,
            policy,
            Map.of("taskId", "task-1", "apiSpecId", "api-1", "generationMode", "SINGLE"),
            Set.of(ToolPrecondition.TASK_EXISTS, ToolPrecondition.CONTEXT_BUNDLE_AVAILABLE)
        ));

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.MISSING_PRECONDITION);
        assertThat(decision.message()).contains("API_SPEC_AVAILABLE");
    }

    @Test
    void missingReviewedDraftGateBlocksHttpExecutionTool() {
        var decision = policyService.evaluate(ToolPolicyEvaluationRequest.of(
            ToolNames.HTTP_EXECUTE_APPROVED_CASE,
            policy,
            Map.of("taskId", "task-1", "testCaseId", "case-1", "targetEnvironment", "local"),
            Set.of(
                ToolPrecondition.TASK_EXISTS,
                ToolPrecondition.TEST_CASE_EXISTS,
                ToolPrecondition.EXECUTION_READINESS_CONFIRMED
            )
        ));

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.MISSING_PRECONDITION);
        assertThat(decision.message()).contains("TEST_CASE_DRAFT_REVIEWED");
    }

    @Test
    void missingExecutionRecordOrFailureSignalBlocksFailureAnalysis() {
        var missingRecord = policyService.evaluate(ToolPolicyEvaluationRequest.of(
            ToolNames.FAILURE_ANALYZE_EXECUTION,
            policy,
            Map.of("taskId", "task-1", "executionRecordId", "exec-1"),
            Set.of(ToolPrecondition.TASK_EXISTS, ToolPrecondition.FAILURE_SIGNAL_AVAILABLE)
        ));
        var missingFailureSignal = policyService.evaluate(ToolPolicyEvaluationRequest.of(
            ToolNames.FAILURE_ANALYZE_EXECUTION,
            policy,
            Map.of("taskId", "task-1", "executionRecordId", "exec-1"),
            Set.of(ToolPrecondition.TASK_EXISTS, ToolPrecondition.EXECUTION_RECORD_EXISTS)
        ));

        assertThat(missingRecord.reasonCode()).isEqualTo(ToolPolicyReasonCode.MISSING_PRECONDITION);
        assertThat(missingRecord.message()).contains("EXECUTION_RECORD_EXISTS");
        assertThat(missingFailureSignal.reasonCode()).isEqualTo(ToolPolicyReasonCode.MISSING_PRECONDITION);
        assertThat(missingFailureSignal.message()).contains("FAILURE_SIGNAL_AVAILABLE");
    }

    @Test
    void validInputAndSatisfiedPreconditionsAllowLowRiskTool() {
        var decision = policyService.evaluate(ToolPolicyEvaluationRequest.of(
            ToolNames.KNOWLEDGE_RETRIEVE_CONTEXT,
            policy,
            Map.of("taskId", "task-1", "query", "auth boundary"),
            Set.of(ToolPrecondition.TASK_EXISTS)
        ));

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.ALLOWED);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.ALLOWED_BY_POLICY);
    }

    @Test
    void validInputAndSatisfiedPreconditionsStillRequireHumanConfirmationForHighRiskTool() {
        var decision = policyService.evaluate(ToolPolicyEvaluationRequest.of(
            ToolNames.HTTP_EXECUTE_APPROVED_CASE,
            policy,
            Map.of("taskId", "task-1", "testCaseId", "case-1", "targetEnvironment", "local"),
            Set.of(
                ToolPrecondition.TASK_EXISTS,
                ToolPrecondition.TEST_CASE_EXISTS,
                ToolPrecondition.TEST_CASE_DRAFT_REVIEWED,
                ToolPrecondition.EXECUTION_READINESS_CONFIRMED
            )
        ));

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.REQUIRES_HUMAN_CONFIRMATION);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.HUMAN_CONFIRMATION_REQUIRED);
    }
}
