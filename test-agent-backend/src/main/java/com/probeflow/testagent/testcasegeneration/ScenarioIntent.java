package com.probeflow.testagent.testcasegeneration;

import java.util.List;
import java.util.Map;

record ScenarioIntent(
    ScenarioCategory category,
    String intentKey,
    String title,
    String description,
    int expectedStatus,
    Map<String, Object> requestShape,
    List<String> validationHints,
    List<String> tags,
    String priorityHint,
    String riskHint,
    String constraintSource,
    List<Map<String, Object>> contextCitations
) {
}
