package com.probeflow.testagent.httpexecution;

import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import java.util.List;
import java.util.Map;

public record HttpExecutionRequest(
    String taskId,
    List<String> selectedCaseIds,
    ExecutionMode executionMode,
    String environment,
    boolean dryRun,
    HttpExecutionOptions options,
    Map<String, Object> environmentVariables,
    Map<String, Object> authVariables
) {

    public HttpExecutionRequest(
        String taskId,
        List<String> selectedCaseIds,
        ExecutionMode executionMode,
        String environment,
        boolean dryRun,
        HttpExecutionOptions options
    ) {
        this(taskId, selectedCaseIds, executionMode, environment, dryRun, options, Map.of(), Map.of());
    }

    public HttpExecutionRequest {
        selectedCaseIds = selectedCaseIds == null ? List.of() : List.copyOf(selectedCaseIds);
        environment = environment == null ? "default" : environment;
        options = options == null ? HttpExecutionOptions.defaults() : options;
        environmentVariables = environmentVariables == null ? Map.of() : Map.copyOf(environmentVariables);
        authVariables = authVariables == null ? Map.of() : Map.copyOf(authVariables);
    }
}
