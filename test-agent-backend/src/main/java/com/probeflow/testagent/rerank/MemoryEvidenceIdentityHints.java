package com.probeflow.testagent.rerank;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record MemoryEvidenceIdentityHints(
    String system,
    String module,
    String apiPath,
    String errorCode,
    String businessEntity,
    String suiteId,
    String variableKey,
    String policyReason
) {
    public Map<String, String> asMap() {
        var hints = new LinkedHashMap<String, String>();
        putIfHasText(hints, "system", system);
        putIfHasText(hints, "module", module);
        putIfHasText(hints, "apiPath", apiPath);
        putIfHasText(hints, "errorCode", errorCode);
        putIfHasText(hints, "businessEntity", businessEntity);
        putIfHasText(hints, "suiteId", suiteId);
        putIfHasText(hints, "variableKey", variableKey);
        putIfHasText(hints, "policyReason", policyReason);
        return Collections.unmodifiableMap(hints);
    }

    private void putIfHasText(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }
}
