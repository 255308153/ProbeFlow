package com.probeflow.testagent.failureanalysis;

import com.probeflow.testagent.replanning.ReplanningTrigger;
import java.util.List;

public record FailureAnalysisReplanningHandoff(
    boolean available,
    ReplanningTrigger triggerHint,
    String sourceStepId,
    FailureClassification rootCauseClassification,
    List<String> affectedDownstreamStepIds,
    String contextSummary,
    List<String> constraints,
    List<String> policyNotes,
    RecoveryActionType recoveryActionType,
    String nextSuggestion
) {

    public FailureAnalysisReplanningHandoff {
        affectedDownstreamStepIds = affectedDownstreamStepIds == null ? List.of() : List.copyOf(affectedDownstreamStepIds);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        policyNotes = policyNotes == null ? List.of() : List.copyOf(policyNotes);
    }

    public static FailureAnalysisReplanningHandoff unavailable(String contextSummary) {
        return new FailureAnalysisReplanningHandoff(
            false,
            null,
            null,
            FailureClassification.NONE,
            List.of(),
            contextSummary,
            List.of(),
            List.of(),
            RecoveryActionType.NOOP,
            "No replanning handoff is required."
        );
    }
}
