package com.probeflow.testagent.contractsmoke;

import java.util.Map;

public record OpenApiContractSmokeRunRequest(
    String apiSpecId,
    String taskId,
    String environment,
    String profile,
    Map<String, Object> environmentVariables,
    Map<String, Object> authVariables,
    String requestedBy
) {

    public static final String DEFAULT_PROFILE = "smoke-valid";

    public OpenApiContractSmokeRunRequest {
        environment = environment == null || environment.isBlank() ? "default" : environment;
        profile = profile == null || profile.isBlank() ? DEFAULT_PROFILE : profile;
        environmentVariables = environmentVariables == null ? Map.of() : Map.copyOf(environmentVariables);
        authVariables = authVariables == null ? Map.of() : Map.copyOf(authVariables);
    }
}
