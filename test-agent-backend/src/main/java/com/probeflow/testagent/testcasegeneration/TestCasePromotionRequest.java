package com.probeflow.testagent.testcasegeneration;

import java.util.List;

public record TestCasePromotionRequest(
    List<String> draftIds,
    String promotedBy
) {
}
