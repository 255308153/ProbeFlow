package com.probeflow.testagent.suiteruntime;

import java.util.List;
import java.util.Map;

public record VariableResolutionResult(
    Object resolvedValue,
    List<Map<String, Object>> diagnostics
) {

    public boolean successful() {
        return diagnostics.isEmpty();
    }
}
