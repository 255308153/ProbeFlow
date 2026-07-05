package com.probeflow.testagent.httpexecution;

import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import java.util.List;

public record HttpExecutionRequest(
    String taskId,
    List<String> selectedCaseIds,
    ExecutionMode executionMode,
    String environment,
    boolean dryRun,
    HttpExecutionOptions options
) {

    public HttpExecutionRequest {
        selectedCaseIds = selectedCaseIds == null ? List.of() : List.copyOf(selectedCaseIds);
        environment = environment == null ? "default" : environment;
        options = options == null ? HttpExecutionOptions.defaults() : options;
    }
}
