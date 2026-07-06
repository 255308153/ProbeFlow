package com.probeflow.testagent.humanintheloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.orchestration.ManualReviewGate;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcase.TestCaseRepository;
import com.probeflow.testagent.testcasedraft.DraftStatus;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import com.probeflow.testagent.testcasedraft.TestCaseDraft;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
import jakarta.persistence.EntityManager;
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
class DraftReviewHumanWorkflowTests {

    @Autowired
    private HumanInTheLoopApplicationService humanInTheLoop;

    @Autowired
    private HumanReviewRequestRepository humanRequests;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TestCaseDraftRepository drafts;

    @Autowired
    private TestCaseRepository testCases;

    @Autowired
    private ManualReviewGate manualReviewGate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void createsDraftReviewRequestWithPendingDraftContextAndDeduplicatesActiveRequest() {
        var task = saveTask("task-hitl-draft-request", TaskStatus.WAITING_FOR_REVIEW);
        drafts.save(draft(task.getTaskId(), "draft-hitl-request-1", DraftStatus.PENDING_REVIEW, null));
        drafts.save(draft(task.getTaskId(), "draft-hitl-request-2", DraftStatus.PENDING_REVIEW, null));

        var first = humanInTheLoop.createDraftReviewRequest(new HumanDraftReviewRequest(
            task.getTaskId(),
            "step-review",
            "Please review generated API test drafts."
        ));
        var second = humanInTheLoop.createDraftReviewRequest(new HumanDraftReviewRequest(
            task.getTaskId(),
            "step-review",
            "Please review generated API test drafts."
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(first.status()).isEqualTo(HumanReviewRequestCreationStatus.CREATED);
        assertThat(second.status()).isEqualTo(HumanReviewRequestCreationStatus.EXISTING_PENDING);
        assertThat(second.request().getRequestId()).isEqualTo(first.request().getRequestId());
        assertThat(humanInTheLoop.requestsByType(HumanRequestType.DRAFT_REVIEW)).hasSize(1);
        var request = humanRequests.findById(first.request().getRequestId()).orElseThrow();
        assertThat(request.getWaitingReason()).contains("review generated");
        assertThat(request.getRequiredInputSchema())
            .extracting(field -> field.get("name"))
            .containsExactly("reviewDecision", "draftIds", "changeRequest");
        assertThat(metadataList(request.getMetadata().get("pendingDraftIds")))
            .containsExactly("draft-hitl-request-1", "draft-hitl-request-2");
        assertThat(request.getMetadata()).containsEntry("manualReviewCompatible", true);
    }

    @Test
    void doesNotCreateDraftReviewRequestWhenNoPendingDraftsRemain() {
        var task = saveTask("task-hitl-draft-none", TaskStatus.WAITING_FOR_REVIEW);
        drafts.save(draft(task.getTaskId(), "draft-hitl-none-discarded", DraftStatus.DISCARDED, null));

        var result = humanInTheLoop.createDraftReviewRequest(new HumanDraftReviewRequest(
            task.getTaskId(),
            "step-review",
            null
        ));

        assertThat(result.status()).isEqualTo(HumanReviewRequestCreationStatus.REJECTED);
        assertThat(result.blockers()).containsExactly("No pending test case drafts require review");
        assertThat(humanInTheLoop.requestsByType(HumanRequestType.DRAFT_REVIEW)).isEmpty();
    }

    @Test
    void promoteDraftDecisionCreatesTestCaseConsumesRequestAndManualReviewGateCanResume() {
        var task = saveTask("task-hitl-draft-promote", TaskStatus.WAITING_FOR_REVIEW);
        drafts.save(draft(task.getTaskId(), "draft-hitl-promote-1", DraftStatus.PENDING_REVIEW, null));
        var reviewRequest = createReviewRequest(task.getTaskId());

        var result = humanInTheLoop.applyDraftReviewDecision(new HumanDecisionSubmissionRequest(
            reviewRequest.getRequestId(),
            HumanDecisionType.PROMOTE_DRAFT,
            "reviewer-a",
            "Covers the payment success path.",
            Map.of("reviewDecision", "promote", "draftIds", List.of("draft-hitl-promote-1"))
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(HumanDraftReviewDecisionStatus.APPLIED);
        assertThat(result.promotedCaseIds()).hasSize(1);
        assertThat(result.request().getStatus()).isEqualTo(HumanRequestStatus.CONSUMED);
        var savedRequest = humanRequests.findById(reviewRequest.getRequestId()).orElseThrow();
        assertThat(savedRequest.getStatus()).isEqualTo(HumanRequestStatus.CONSUMED);
        var savedDraft = drafts.findById("draft-hitl-promote-1").orElseThrow();
        assertThat(savedDraft.getStatus()).isEqualTo(DraftStatus.PROMOTED);
        assertThat(savedDraft.getPromotedCaseId()).isEqualTo(result.promotedCaseIds().getFirst());
        assertThat(testCases.findById(result.promotedCaseIds().getFirst())).isPresent();
        assertThat(humanInTheLoop.decisionsForTask(task.getTaskId()))
            .first()
            .satisfies(decision -> {
                assertThat(decision.getDecisionType()).isEqualTo(HumanDecisionType.PROMOTE_DRAFT);
                assertThat(decision.getReason()).contains("payment success");
            });
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getMetadata())
            .containsEntry("promotedCaseIds", result.promotedCaseIds())
            .containsEntry("selectedCaseIds", result.promotedCaseIds());
        assertThat(manualReviewGate.evaluate(tasks.findById(task.getTaskId()).orElseThrow()).ready()).isTrue();
    }

    @Test
    void discardDraftDecisionMarksDraftDiscardedConsumesRequestAndKeepsGateCompatible() {
        var task = saveTask("task-hitl-draft-discard", TaskStatus.WAITING_FOR_REVIEW);
        drafts.save(draft(task.getTaskId(), "draft-hitl-discard-promoted", DraftStatus.PROMOTED, "case-existing"));
        drafts.save(draft(task.getTaskId(), "draft-hitl-discard-1", DraftStatus.PENDING_REVIEW, null));
        var reviewRequest = createReviewRequest(task.getTaskId());

        var result = humanInTheLoop.applyDraftReviewDecision(new HumanDecisionSubmissionRequest(
            reviewRequest.getRequestId(),
            HumanDecisionType.DISCARD_DRAFT,
            "reviewer-b",
            "Duplicate negative scenario.",
            Map.of("reviewDecision", "discard", "draftIds", List.of("draft-hitl-discard-1"))
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(HumanDraftReviewDecisionStatus.APPLIED);
        assertThat(result.discardedDraftIds()).containsExactly("draft-hitl-discard-1");
        assertThat(humanRequests.findById(reviewRequest.getRequestId()).orElseThrow().getStatus())
            .isEqualTo(HumanRequestStatus.CONSUMED);
        assertThat(drafts.findById("draft-hitl-discard-1").orElseThrow().getStatus()).isEqualTo(DraftStatus.DISCARDED);
        var gate = manualReviewGate.evaluate(tasks.findById(task.getTaskId()).orElseThrow());
        assertThat(gate.ready()).isTrue();
        assertThat(gate.promotedCaseIds()).containsExactly("case-existing");
        assertThat(gate.discardedDraftIds()).containsExactly("draft-hitl-discard-1");
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getMetadata())
            .containsEntry("discardedDraftIds", List.of("draft-hitl-discard-1"));
    }

    @Test
    void requestChangesDecisionRecordsModificationRequirementsAndKeepsTaskWaiting() {
        var task = saveTask("task-hitl-draft-changes", TaskStatus.WAITING_FOR_REVIEW);
        drafts.save(draft(task.getTaskId(), "draft-hitl-changes-1", DraftStatus.PENDING_REVIEW, null));
        var reviewRequest = createReviewRequest(task.getTaskId());

        var result = humanInTheLoop.applyDraftReviewDecision(new HumanDecisionSubmissionRequest(
            reviewRequest.getRequestId(),
            HumanDecisionType.REQUEST_CHANGES,
            "reviewer-c",
            "Needs an edge-case assertion.",
            Map.of(
                "reviewDecision", "request_changes",
                "draftIds", List.of("draft-hitl-changes-1"),
                "changeRequest", "Add timeout and retry edge cases before promotion."
            )
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(HumanDraftReviewDecisionStatus.APPLIED);
        assertThat(humanRequests.findById(reviewRequest.getRequestId()).orElseThrow().getStatus())
            .isEqualTo(HumanRequestStatus.CONSUMED);
        assertThat(drafts.findById("draft-hitl-changes-1").orElseThrow().getStatus()).isEqualTo(DraftStatus.PENDING_REVIEW);
        var savedTask = tasks.findById(task.getTaskId()).orElseThrow();
        assertThat(savedTask.getStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
        assertThat(metadataRecords(savedTask.getMetadata().get("draftReviewChangeRequests")))
            .singleElement()
            .satisfies(record -> {
                assertThat(record).containsEntry("actor", "reviewer-c");
                assertThat(record).containsEntry("reason", "Needs an edge-case assertion.");
                assertThat(record).containsEntry("changeRequest", "Add timeout and retry edge cases before promotion.");
                assertThat(metadataList(record.get("draftIds"))).containsExactly("draft-hitl-changes-1");
            });
        var gate = manualReviewGate.evaluate(savedTask);
        assertThat(gate.ready()).isFalse();
        assertThat(gate.pendingDraftIds()).containsExactly("draft-hitl-changes-1");
    }

    private HumanReviewRequest createReviewRequest(String taskId) {
        return humanInTheLoop.createDraftReviewRequest(new HumanDraftReviewRequest(
            taskId,
            "step-review",
            "Manual review is required before execution."
        )).request();
    }

    private Task saveTask(String taskId, TaskStatus status) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Draft review " + taskId);
        task.setStatus(status);
        task.setSourceType(TaskSourceType.OPENAPI);
        task.setSourceRef("source-" + taskId);
        task.setTargetApiSpecIds(List.of("api-" + taskId));
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase6");
        task.setMetadata(Map.of());
        return tasks.save(task);
    }

    private TestCaseDraft draft(String taskId, String draftId, DraftStatus status, String promotedCaseId) {
        var draft = new TestCaseDraft();
        draft.setDraftId(draftId);
        draft.setTaskId(taskId);
        draft.setSource(CaseSource.STRUCTURE);
        draft.setStage(CaseSource.STRUCTURE);
        draft.setStatus(status);
        draft.setPromotionMode(PromotionMode.MANUAL);
        draft.setTargetApiSpecId("api-" + taskId);
        draft.setDedupKey("dedup-" + draftId);
        draft.setExpectedStatusCode(200);
        draft.setDraftContent(Map.of(
            "title", "Generated " + draftId,
            "description", "Generated draft for review",
            "expectedResult", "HTTP 200",
            "steps", List.of(Map.of("name", "Call API", "expected", "OK")),
            "tags", List.of("phase6")
        ));
        draft.setPromotedCaseId(promotedCaseId);
        return draft;
    }

    private List<String> metadataList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> metadataRecords(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
