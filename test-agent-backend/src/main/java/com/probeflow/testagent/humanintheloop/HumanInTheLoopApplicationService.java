package com.probeflow.testagent.humanintheloop;

import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HumanInTheLoopApplicationService {

    private final TaskRepository tasks;
    private final HumanReviewRequestRepository humanRequests;
    private final HumanDecisionRecordRepository decisions;

    public HumanInTheLoopApplicationService(
        TaskRepository tasks,
        HumanReviewRequestRepository humanRequests,
        HumanDecisionRecordRepository decisions
    ) {
        this.tasks = tasks;
        this.humanRequests = humanRequests;
        this.decisions = decisions;
    }

    @Transactional
    public HumanReviewRequestCreationResult createRequest(HumanReviewRequestCreateRequest request) {
        var task = tasks.findById(request.taskId());
        if (task.isEmpty()) {
            return HumanReviewRequestCreationResult.rejected(List.of("Task not found: " + request.taskId()));
        }
        if (task.get().getStatus() == TaskStatus.COMPLETED) {
            return HumanReviewRequestCreationResult.rejected(List.of("Completed task cannot accept new human review request"));
        }
        if (task.get().getStatus() == TaskStatus.CANCELLED) {
            return HumanReviewRequestCreationResult.rejected(List.of("Cancelled task cannot accept new human review request"));
        }

        var humanRequest = new HumanReviewRequest();
        humanRequest.setTaskId(request.taskId());
        humanRequest.setSourceStepId(request.sourceStepId());
        humanRequest.setRequestType(request.requestType());
        humanRequest.setStatus(HumanRequestStatus.PENDING);
        humanRequest.setWaitingReason(request.waitingReason());
        humanRequest.setRequiredInputSchema(request.requiredInputSchema());
        humanRequest.setRiskLevel(request.riskLevel());
        humanRequest.setSourceTrigger(request.sourceTrigger());
        humanRequest.setPlannerDecisionId(request.plannerDecisionId());
        humanRequest.setPolicyReason(request.policyReason());
        humanRequest.setMetadata(request.metadata());
        humanRequest.setExpiresAt(request.expiresAt());
        return HumanReviewRequestCreationResult.created(humanRequests.save(humanRequest));
    }

    @Transactional(readOnly = true)
    public List<HumanReviewRequest> requestsForTask(String taskId) {
        return humanRequests.findByTaskIdOrderByCreatedAtAsc(taskId);
    }

    @Transactional(readOnly = true)
    public List<HumanReviewRequest> pendingRequests() {
        return humanRequests.findByStatusOrderByCreatedAtAsc(HumanRequestStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<HumanReviewRequest> requestsByType(HumanRequestType requestType) {
        return humanRequests.findByRequestTypeOrderByCreatedAtAsc(requestType);
    }

    @Transactional(readOnly = true)
    public List<HumanDecisionRecord> decisionsForTask(String taskId) {
        return decisions.findByTaskIdOrderByCreatedAtAsc(taskId);
    }
}
