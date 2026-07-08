package com.probeflow.testagent.failureanalysis;

import com.probeflow.testagent.humanintheloop.HumanRequestType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record FailureAnalysisHumanHandoff(
    boolean required,
    HumanRequestType requestType,
    List<Map<String, Object>> requiredInputFields,
    String reason,
    List<String> evidence,
    String suggestedAction,
    String riskLevel,
    List<String> policyNotes
) {

    public FailureAnalysisHumanHandoff {
        requiredInputFields = requiredInputFields == null ? List.of() : requiredInputFields.stream()
            .map(field -> field == null ? Map.<String, Object>of() : Map.copyOf(new LinkedHashMap<>(field)))
            .toList();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        policyNotes = policyNotes == null ? List.of() : List.copyOf(policyNotes);
    }

    public static FailureAnalysisHumanHandoff notRequired(String reason, String riskLevel) {
        return new FailureAnalysisHumanHandoff(
            false,
            null,
            List.of(),
            reason,
            List.of(),
            "No human-in-the-loop handoff is required.",
            riskLevel,
            List.of()
        );
    }
}
