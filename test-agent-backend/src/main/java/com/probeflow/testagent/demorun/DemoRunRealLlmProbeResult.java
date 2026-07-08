package com.probeflow.testagent.demorun;

import java.util.List;
import java.util.Map;

public record DemoRunRealLlmProbeResult(
    DemoRunRealLlmProbeStatus status,
    String message,
    List<DemoRunLlmCallSummary> callSummaries,
    Map<String, Object> metadata
) {

    public DemoRunRealLlmProbeResult {
        status = status == null ? DemoRunRealLlmProbeStatus.FAILED : status;
        message = message == null || message.isBlank() ? status.name() : message.trim();
        callSummaries = callSummaries == null ? List.of() : List.copyOf(callSummaries);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static DemoRunRealLlmProbeResult blocked(String message, Map<String, Object> metadata) {
        return new DemoRunRealLlmProbeResult(DemoRunRealLlmProbeStatus.BLOCKED, message, List.of(), metadata);
    }
}
