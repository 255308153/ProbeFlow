package com.probeflow.testagent.failureanalysis;

import java.util.Map;

public record TaskFailureAnalysisCounts(
    int totalExecutions,
    int retryableExecutions,
    int nonRetryableExecutions,
    Map<String, Long> byClassification,
    Map<String, Long> byRiskLevel,
    Map<String, Long> byRetryability,
    Map<String, Long> byOverallStatus
) {
}
