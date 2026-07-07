package com.probeflow.testagent.manualsuiteagent;

import java.util.LinkedHashMap;
import java.util.Map;

public record ManualSuiteAgentSectionSummary(
    String sectionId,
    String title,
    ManualSuiteAgentSectionSource source,
    String status,
    Map<String, Object> summary
) {

    public ManualSuiteAgentSectionSummary {
        source = source == null ? ManualSuiteAgentSectionSource.STAGED : source;
        status = status == null || status.isBlank() ? "READY" : status;
        summary = summary == null ? Map.of() : new LinkedHashMap<>(summary);
    }
}
