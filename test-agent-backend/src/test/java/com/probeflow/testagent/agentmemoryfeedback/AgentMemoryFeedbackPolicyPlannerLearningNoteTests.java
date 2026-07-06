package com.probeflow.testagent.agentmemoryfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.ProposedPlanStep;
import com.probeflow.testagent.memory.LongTermMemoryQuery;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.memory.LongTermMemoryRetrievalService;
import com.probeflow.testagent.policyvalidator.PolicyValidationReasonCode;
import com.probeflow.testagent.policyvalidator.PolicyValidationResult;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentMemoryFeedbackPolicyPlannerLearningNoteTests {

    @Autowired
    private AgentMemoryFeedbackApplicationService memoryFeedback;

    @Autowired
    private MemoryCandidateRecordRepository candidates;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private LongTermMemoryRetrievalService retrievalService;

    @Autowired
    private TaskRepository tasks;

    @Test
    void blockedHighRiskPlannerDecisionCreatesPolicyLearningNoteThroughRefinery() {
        var taskId = "task-phase7-policy-note";
        tasks.save(newTask(taskId));
        var decision = highRiskPlannerDecision("secret-policy-token");
        var validation = PolicyValidationResult.blocked(
            decision,
            PolicyValidationReasonCode.TOOL_NOT_WHITELISTED,
            "Tool external.http is not whitelisted for this task phase.",
            List.of("Tool not whitelisted", "Use approved backend-only tool")
        );

        var result = memoryFeedback.refinePolicyLearningNote(taskId, validation, decision, Map.of(
            "stageProfile", "execution_preparation",
            "sourceTrigger", "PLAN_STEP_FAILED",
            "apiPath", "/api/payments/charge",
            "module", "payments",
            "systemName", "billing",
            "tags", List.of("payments", "policy"),
            "toolInput", Map.of("Authorization", "Bearer secret-policy-token")
        ));

        assertThat(result.status()).isEqualTo(MemoryCandidateProcessingStatus.ACCEPTED);
        assertThat(result.memoryId()).isNotBlank();
        var candidate = candidates.findBySourceTypeAndSourceRefAndTaskId(
            AgentMemoryCandidateSourceType.POLICY_VALIDATION_RESULT,
            "policy-validation:" + decision.decisionId() + ":TOOL_NOT_WHITELISTED",
            taskId
        ).orElseThrow();
        assertThat(candidate.getStatus()).isEqualTo(MemoryCandidateProcessingStatus.ACCEPTED);
        assertThat(candidate.getTags()).contains(
            "policy-learning",
            "policy-guardrail",
            "tool_not_whitelisted",
            "external.http",
            "high",
            "payments"
        );
        assertThat(candidate.getMetadata())
            .containsEntry("policyReason", "TOOL_NOT_WHITELISTED")
            .containsEntry("blockedAction", "INSERT_STEP")
            .containsEntry("toolName", "external.http")
            .containsEntry("riskLevel", "HIGH")
            .containsEntry("sourceTrigger", "PLAN_STEP_FAILED")
            .containsEntry("apiPath", "/api/payments/charge")
            .containsEntry("module", "payments")
            .containsEntry("errorCode", "TOOL_NOT_WHITELISTED");
        assertThat(candidate.getMetadata().get("recommendedSaferAlternative").toString())
            .contains("whitelisted");
        assertThat(candidate.getSanitizedEvidence()).contains(MemoryFeedbackSanitizer.MASKED_VALUE);

        var memory = longTermMemories.findById(result.memoryId()).orElseThrow();
        assertThat(memory.getMetadata())
            .containsEntry("policyReason", "TOOL_NOT_WHITELISTED")
            .containsEntry("apiPath", "/api/payments/charge")
            .containsEntry("module", "payments");
        assertThat(memory.getFullContent()).doesNotContain("secret-policy-token");
        assertThat(memory.getMetadata().toString()).doesNotContain("secret-policy-token");

        var recalled = retrievalService.retrieve(new LongTermMemoryQuery(
            "execution_preparation",
            "avoid external.http policy rejection for payments",
            "billing",
            "payments",
            "/api/payments/charge",
            "TOOL_NOT_WHITELISTED",
            List.of("policy-learning", "external.http"),
            List.of(),
            5,
            800
        ));
        assertThat(recalled.hits()).anySatisfy(hit -> assertThat(hit.memoryId()).isEqualTo(result.memoryId()));
    }

    @Test
    void repeatingSamePolicyRejectionIsIdempotentAndDoesNotCreateAnotherMemory() {
        var taskId = "task-phase7-policy-duplicate";
        tasks.save(newTask(taskId));
        var decision = highRiskPlannerDecision("duplicate-secret-token");
        var validation = PolicyValidationResult.blocked(
            decision,
            PolicyValidationReasonCode.TOOL_BLOCKED_BY_CONTRACT,
            "Tool contract blocks this operation.",
            List.of("contract blocks external write")
        );
        var metadata = Map.<String, Object>of(
            "stageProfile", "execution_preparation",
            "apiPath", "/api/payments/charge",
            "module", "payments",
            "tags", List.of("policy")
        );

        var first = memoryFeedback.refinePolicyLearningNote(taskId, validation, decision, metadata);
        var second = memoryFeedback.refinePolicyLearningNote(taskId, validation, decision, metadata);

        assertThat(first.status()).isEqualTo(MemoryCandidateProcessingStatus.ACCEPTED);
        assertThat(second.status()).isEqualTo(MemoryCandidateProcessingStatus.DUPLICATE);
        assertThat(second.memoryId()).isEqualTo(first.memoryId());
        assertThat(longTermMemories.findAll()).hasSize(1);
        assertThat(candidates.findAllByTaskIdOrderByCreatedAtAscCandidateIdAsc(taskId)).hasSize(1);
        assertThat(second.auditSummary()).containsEntry("idempotent", true);
    }

    @Test
    void lowValuePolicyRejectionIsAuditedButRejectedByRefinery() {
        var taskId = "task-phase7-policy-low-value";
        tasks.save(newTask(taskId));
        var decision = PlanDecision.insertStep(
            "Try a context read before the context bundle is loaded.",
            0.40d,
            ToolRiskLevel.LOW,
            "knowledge.retrieve-context",
            ProposedPlanStep.of("LOAD_CONTEXT", "Read context", "Read task-local context.", "knowledge.retrieve-context")
        );
        var validation = PolicyValidationResult.blocked(
            decision,
            PolicyValidationReasonCode.MISSING_CONTEXT_BUNDLE,
            "Context bundle is missing.",
            List.of("missing context bundle")
        );

        var result = memoryFeedback.refinePolicyLearningNote(taskId, validation, decision, Map.of(
            "stageProfile", "case_generation",
            "tags", List.of("low-value")
        ));

        assertThat(result.status()).isEqualTo(MemoryCandidateProcessingStatus.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo("low-confidence");
        assertThat(result.memoryId()).isNull();
        assertThat(longTermMemories.findAll()).isEmpty();
        var candidate = candidates.findBySourceTypeAndSourceRefAndTaskId(
            AgentMemoryCandidateSourceType.POLICY_VALIDATION_RESULT,
            "policy-validation:" + decision.decisionId() + ":MISSING_CONTEXT_BUNDLE",
            taskId
        ).orElseThrow();
        assertThat(candidate.getStatus()).isEqualTo(MemoryCandidateProcessingStatus.REJECTED);
        assertThat(candidate.getRejectionReason()).isEqualTo("low-confidence");
        assertThat(candidate.getMetadata())
            .containsEntry("policyReason", "MISSING_CONTEXT_BUNDLE")
            .containsEntry("riskLevel", "LOW")
            .containsEntry("toolName", "knowledge.retrieve-context");
    }

    private PlanDecision highRiskPlannerDecision(String secret) {
        return PlanDecision.insertStep(
            "Planner wants to call external.http with Authorization bearer " + secret,
            0.76d,
            ToolRiskLevel.HIGH,
            "external.http",
            ProposedPlanStep.of(
                "EXECUTE_TOOL",
                "Call external payment endpoint",
                "POST /api/payments/charge with token=" + secret,
                "external.http"
            )
        ).withSourceLlmCall("llm-phase7-policy", false);
    }

    private Task newTask(String taskId) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Phase 7 policy learning " + taskId);
        task.setStatus(TaskStatus.ANALYZING_RESULTS);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("manual");
        task.setTargetApiSpecIds(List.of());
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase7-test");
        task.setMetadata(Map.of());
        return task;
    }
}
