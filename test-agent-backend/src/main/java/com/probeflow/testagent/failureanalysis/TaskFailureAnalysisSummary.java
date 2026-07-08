package com.probeflow.testagent.failureanalysis;

import java.util.List;

public record TaskFailureAnalysisSummary(
    String highestRiskLevel,
    FailureClassification priorityClassification,
    String priorityAction,
    String summary,
    List<String> priorityExecutionIds
) {

    public TaskFailureAnalysisSummary {
        priorityExecutionIds = priorityExecutionIds == null ? List.of() : List.copyOf(priorityExecutionIds);
    }

    public static TaskFailureAnalysisSummary empty() {
        return new TaskFailureAnalysisSummary(
            "LOW",
            FailureClassification.NONE,
            "No failure follow-up is required.",
            "No task-level failures were identified.",
            List.of()
        );
    }
}
