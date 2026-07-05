package com.probeflow.testagent.httpexecution;

import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import java.util.List;

public record HttpExecutionResult(
    String taskId,
    String environment,
    ExecutionMode executionMode,
    List<HttpExecutionCaseResult> caseResults,
    HttpExecutionCounts counts
) {
}
