package com.probeflow.testagent.orchestration;

import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.DraftStatus;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import com.probeflow.testagent.testcasedraft.TestCaseDraft;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ManualReviewGate {

    private final TestCaseDraftRepository drafts;

    public ManualReviewGate(TestCaseDraftRepository drafts) {
        this.drafts = drafts;
    }

    public ManualReviewGateResult evaluate(Task task) {
        if (task.getTaskType() != TaskType.API_TEST || task.getPromotionMode() != PromotionMode.MANUAL) {
            return ManualReviewGateResult.notRequired();
        }

        var persistedDrafts = drafts.findAllByTaskIdOrderByCreatedAtAscDraftIdAsc(task.getTaskId());
        var promotedCaseIds = new LinkedHashSet<String>();
        var pendingDraftIds = new LinkedHashSet<String>();
        var discardedDraftIds = new LinkedHashSet<String>();
        var reviewedDraftIds = new LinkedHashSet<String>();

        for (var draft : persistedDrafts) {
            reviewedDraftIds.add(draft.getDraftId());
            if (draft.getStatus() == DraftStatus.PENDING_REVIEW) {
                pendingDraftIds.add(draft.getDraftId());
            } else if (draft.getStatus() == DraftStatus.PROMOTED && StringUtils.hasText(draft.getPromotedCaseId())) {
                promotedCaseIds.add(draft.getPromotedCaseId());
            } else if (draft.getStatus() == DraftStatus.DISCARDED) {
                discardedDraftIds.add(draft.getDraftId());
            }
        }

        metadataList(task, "createdDraftIds").stream()
            .filter(draftId -> !reviewedDraftIds.contains(draftId))
            .forEach(pendingDraftIds::add);
        metadataList(task, "promotedCaseIds").forEach(promotedCaseIds::add);
        metadataList(task, "discardedDraftIds").forEach(discardedDraftIds::add);

        if (!pendingDraftIds.isEmpty()) {
            return new ManualReviewGateResult(
                false,
                List.copyOf(promotedCaseIds),
                List.copyOf(pendingDraftIds),
                List.copyOf(discardedDraftIds),
                List.of("Waiting for manual review of " + pendingDraftIds.size() + " draft(s)")
            );
        }
        if (promotedCaseIds.isEmpty()) {
            return new ManualReviewGateResult(
                false,
                List.of(),
                List.of(),
                List.copyOf(discardedDraftIds),
                List.of("Waiting for manual review of generated drafts")
            );
        }
        return new ManualReviewGateResult(
            true,
            List.copyOf(promotedCaseIds),
            List.of(),
            List.copyOf(discardedDraftIds),
            List.of()
        );
    }

    private List<String> metadataList(Task task, String key) {
        var value = task.getMetadata() == null ? null : task.getMetadata().get(key);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .map(item -> item == null ? "" : item.toString())
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }
}
