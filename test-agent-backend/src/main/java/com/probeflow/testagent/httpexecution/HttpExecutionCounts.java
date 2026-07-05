package com.probeflow.testagent.httpexecution;

import java.util.List;

public record HttpExecutionCounts(
    int total,
    int passed,
    int failed,
    int error,
    int skipped,
    int blocked
) {

    static HttpExecutionCounts from(List<HttpExecutionCaseResult> caseResults) {
        var passed = 0;
        var failed = 0;
        var error = 0;
        var skipped = 0;
        var blocked = 0;
        for (var caseResult : caseResults) {
            switch (caseResult.status()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case ERROR -> error++;
                case SKIPPED -> skipped++;
                case BLOCKED -> blocked++;
            }
        }
        return new HttpExecutionCounts(caseResults.size(), passed, failed, error, skipped, blocked);
    }
}
